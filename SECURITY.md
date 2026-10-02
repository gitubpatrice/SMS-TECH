# SMS Tech — Security model

English · 🇫🇷 [Version française](SECURITY.fr.md)

Current release : **v1.28.13** (2026-10-02)

This document describes the threat model SMS Tech protects against, the cryptographic
primitives it uses, the architectural choices that make those primitives meaningful, and the
known limits beyond which the app cannot defend (because no app can).

If you find a vulnerability, please disclose it to **contact@files-tech.com** with the subject
`SMS Tech security report`. We respond within 5 working days.

---

## Threat model

> 📎 **Operational companion: [`THREAT-MODEL.md`](THREAT-MODEL.md).** The table below
> answers "what does the app defend against". `THREAT-MODEL.md` answers "**where is the guard that
> is authoritative, and which paths must go through it**": invariants, enforcement layer, twin
> paths to inventory, and **acknowledged non-guarantees** (screen overlay, clipboard).
> Any change touching a security guard must be checked against its invariants.

| Adversary | What we protect against | How |
|---|---|---|
| Device-level adb pull / forensic image | Plaintext SMS / MMS body, vault content, draft messages, recordings | Whole Room DB encrypted at rest by SQLCipher; key derived from a passphrase wrapped in the Android Keystore. Transient caches (PDU staging, voice drafts, exports) wiped on auto-lock + on panic. |
| Lost / stolen phone (no PIN known) | Read access to the conversation list and the vault | App lock (PIN / passphrase / biometric) with exponential backoff. `FLAG_SECURE` on every sensitive surface so the lock screen + recent-apps preview never leaks content. |
| Coerced unlock ("show me your phone") | Disclosure of the hidden vault under duress | Panic-code unlock (`PanicDecoy` state) exposes the standard conversation list while gating every vault entry point — UI hides the vault icon, navigation refuses the route, the data layer returns empty lists. |
| Replay of a successful biometric scan | Bypass of the lock state via a stolen "auth event" | Single-use challenge token issued by `AppLockManager.beginBiometricChallenge()`, consumed atomically (`AtomicReference.getAndSet(null)`) by `markBiometricUnlocked(token)`. A second call with a stale token is a no-op. |
| Brute-force PIN | Online guessing of a short PIN | PBKDF2-HMAC-SHA512 with calibrated iterations (>= 210 k) + salted hash. Exponential lockout (5 s, 10 s, 30 s, 1 min, 2 min, 5 min) starting at 5 failures. `setLockoutUntil` clamped to a 24 h forward horizon so a tainted backup restore cannot brick the app. |
| Malicious component on the same device sending an Intent | Forge a `MmsSent` / `SmsSent` broadcast to manipulate row status | Every result-callback `PendingIntent` uses **explicit** `Intent.setClass(context, ReceiverClass)` rather than implicit `setPackage`. Receivers stay `exported = false`. |
| Carrier-side / MITM | Intercept message bytes in flight | **Not in scope.** SMS / MMS is unencrypted by protocol; we cannot fix that. Users who need transport encryption should use Signal or similar. |
| Unwanted automatic Safety call SMS during coercion (v1.9.0) | The deadman feature firing while the victim is forced into the `PanicDecoy` session — which would reveal her emergency contacts to the attacker | `SafetyCallTriggerService` and `SafetyCallWorker` both check `AppLockManager.LockState.PanicDecoy` and short-circuit before any send. Worker tick retries automatically once decoy state is left. |
| Spoofed Safety call reset intent (v1.9.0) | A third-party app on the device crafting `ACTION_SAFETY_CALL_RESET` (the activity is `exported=true` because of the SMS role) to neutralise the deadman remotely | `SafetyCallIntentToken` rotates a `SecureRandom` 63-bit nonce on every warning notification; `MainActivity` validates the extra against the in-process token and burns it on consume. A forged intent with no/wrong token is logged and ignored. |
| Coerced reset of Safety call timer via app opening (v1.9.0) | An attacker who knows the app icon could open SMS Tech repeatedly (without PIN) to neutralise the deadman | `MainActivity.onResume` only resets `lastActivityAt` when `AppLockManager.LockState` is `Unlocked` or `Disabled`. `Locked` / `PanicDecoy` sessions never reset the timer. |
| User-triggered emergency SMS during coercion (v1.10.0) | The new Emergency mode (hold-3s button) could reveal the victim's emergency contacts if the attacker forces an unlock to `PanicDecoy` and sees the URGENCE button | Three layers of defence : (a) `AppRoot` navigation guard pops both `Emergency` and `EmergencySetup` routes the moment `PanicDecoy` becomes active, (b) `SettingsScreen` hides both "Mode urgence" and "Safety call" sections when `isPanicDecoy = true` (the attacker doesn't learn the feature exists), (c) `TriggerEmergencyUseCase` checks the same lock state and short-circuits before any SMS is sent. |
| Wall-clock manipulation to bypass anti-spam cooldown (v1.10.0) | A rooted attacker advances `Settings.Global.AUTO_TIME=0; date <future>` to skip the 60 s emergency-mode cooldown and re-trigger the SMS to harass the contacts | `EmergencyConfig.isInAntiSpamWindow()` checks both wall-clock AND `SystemClock.elapsedRealtime()`. Cooldown is active if EITHER clock is still in window. A negative monotonic delta (post-reboot before drift recovery) is also treated as "still in cooldown" — fail-safe against root + reboot + clock-forward. Same defence on `SafetyCallConfig.isExpired()` (SEC-11) — required both clocks to expire before the deadman fires. |
| Wall-clock manipulation to fire Safety call early (v1.10.0 SEC-11) | A rooted attacker advances the wall-clock to make `lastActivityAt + timeoutMs < now()` evaluate `true` immediately and trigger the SMS to expose the support network | `SafetyCallConfig.isExpired()` now requires BOTH wall-clock AND monotonic clock to have crossed `timeoutMs`. `SystemClock.elapsedRealtime()` is not manipulable via Settings → Date/time. The drift between the two clocks defeats the attack. Drift recovery in `MainApplication.onCreate` realigns monotonic post-reboot if the stored value exceeds current uptime. |
| Double-trigger of emergency SMS via UI race (v1.10.0) | A panicked user holds the URGENCE button, releases, and immediately holds again before the DataStore write completes; without protection a second SMS could be sent within ~50–300 ms | `EmergencyViewModel.trigger()` uses `AtomicBoolean compareAndSet(false, true)` as an in-flight guard. A second `trigger()` call while the first is still running returns immediately. The flag is reset in `finally` so any exception in the UseCase still releases it. |
| Listener leak in `LocationResolver` (v1.10.0) | Theoretical leak of GPS listener if `SecurityException` thrown on NETWORK provider after GPS listener was registered | `awaitFirstFix` uses `AtomicBoolean resumed` to enforce single-resume on `suspendCancellableCoroutine`, and `cleanup()` is always called in the `catch (SecurityException)` block regardless of `resumed` state. Same `cleanup()` is wired in `invokeOnCancellation`. |

---

## Outgoing address normalization (v1.21.0)

Before an SMS / MMS is handed to the telephony stack, the **destination** is normalized to E.164
(`PhoneNumberUtils.formatNumberToE164`) using the SIM country — or a user-set default region
(Settings → Sending → "Default country") when set. This fixes silent non-delivery when sending
from a foreign SIM (a national `06…` is not routable abroad; the network needs `+33…`).

Security-relevant invariants:

- **Only the on-wire address changes.** The `content://sms` / `content://mms` provider rows and
  the SQLCipher-encrypted Room mirror keep the user's original raw string — threading, display and
  history are byte-for-byte unchanged. Normalization happens exclusively inside `SmsSender` /
  `MmsSender` on the wire destination.
- **Fail-open to raw.** If the region is unknown, the value is a short code / alphanumeric sender,
  or it is not a valid number for the region, the raw string is sent verbatim — no regression, and
  never a wrong-country number (an ambiguous foreign national number yields `null` → raw).
- **No PII logging.** `WireAddress` and `PhoneNumberWireFormatter` never log a phone number.
- **No new attack surface.** `formatNumberToE164` is a platform API; the pre-existing MMS
  email-target guard (`MmsBuilder.formatAddressForMms` rejecting `@`) is unaffected.

---

## Cryptographic primitives

| Concern | Primitive | Key source | Notes |
|---|---|---|---|
| Room database at rest | SQLCipher v4 | 32-byte random passphrase wrapped by Keystore alias `db_master` | The unwrapped passphrase stays resident for the process lifetime — SQLCipher holds it by reference and needs it to reopen the database after a `close()`. Wiping it earlier (as versions up to 1.23.4 did) meant SQLCipher received an all-zero key; `LegacyZeroKeyRekey` re-encrypts affected databases on first launch of 1.24.0. |
| Vault wrap key (reserved) | AES-256-GCM | Keystore alias `vault_kek` (Android 9+) | Currently used as a structural anchor; the actual vault gating is enforced at the data layer via `in_vault = 1` + session checks. v1.3.x is planned to add a separate envelope. |
| App-lock PIN / passphrase | PBKDF2-HMAC-SHA512 | User secret + 16-byte random salt | Iterations calibrated on the device (>= 210 k); stored in DataStore alongside the salt. We never store the secret itself. |
| Panic code | PBKDF2-HMAC-SHA512 | Same parameters as the PIN | Comparison is constant-time. A panic match resets the fail counter to 0 to avoid leaving fingerprint of attempts in the persisted state. |
| Settings AEAD blobs | AES-256-GCM | Keystore alias `settings_aead` | Used for low-volume sensitive prefs (cached display name, future heir-recovery hooks). IV is 12-byte random per encryption. |
| Biometric challenge | 32 bytes from `SecureRandom` | — | Single-use, single-thread atomic consume (`AtomicReference.getAndSet(null)`). Comparison uses `MessageDigest.isEqual` to avoid timing side-channels. |

All Keystore keys are non-exportable, hardware-backed when available, and pinned to the
biometric authenticator on the BiometricPrompt path (`setUserAuthenticationRequired = true`
will be wired in v1.3 once the strong-bio gating decision is finalised — v1.2.0 ships with
the BIOMETRIC_WEAK class for fingerprint **OR** face).

---

## Audit history

### v1.28.13 — What refusing each permission, one at a time, revealed

**Affected versions: see each item.** Reported on the F-Droid merge request (fdroid/fdroiddata!38458)
by a tester, by-architect, who ran 1.28.12 on Android 16 with optional permissions refused, as the
F-Droid test protocol does — and confirmed from the source by a second reviewer, mezinster. The crash
they found led to a sweep of every runtime permission, refused one at a time; the three items below
came out of that sweep and none crashes. They concern the safety of a person, which is why they are
recorded here.

#### Emergency mode: with the call permission refused, tapping 112 did nothing

**Affected versions: 1.14.1 to 1.28.12.** Availability of a person-safety feature, on the screen
reached in a crisis.

Without `CALL_PHONE`, an emergency tile only requested the permission again, and the answer was
read by nobody: refused, the tile did nothing — no call, no dialer, no message. After two refusals
Android no longer shows the dialog, and the tile stayed inert for good. The comments had promised a
"fallback to the dialer" since v1.14.1; it did not exist. The permission was also read at
composition, so a grant could stay without effect until something recomposed the screen.

Measured on an API 34 emulator with `CALL_PHONE` refused: 1.28.12 stays on the screen after 112 is
tapped; 1.28.13 opens the dialer with 112 filled in.

**Fix.** The permission is read when the tile is tapped, and the answer to the request places the
call or opens the dialer, which needs no permission. A trusted contact gets the same fallback through
`EmergencyCallHelper.openTrustedContactDialer`, since `openDialer` rejects anything off the emergency
list. The pre-release audit then found the twin: after two refusals, the "Allow location" and "Allow
precise location" buttons did nothing either. A refusal that comes back faster than a person can
read a dialog now opens the app's Android settings page, where the permission can still be granted —
measured both ways on the emulator.

#### An "approximate" location was treated as no location

**Affected versions: 1.10.0 to 1.28.12, on Android 12 and later.** The emergency SMS left without a
position although the user had agreed to share one.

Since Android 12 the location dialog offers "Approximate", which grants `ACCESS_COARSE_LOCATION`
only. Every check in the app tested `ACCESS_FINE_LOCATION`: the screens said "permission not
granted", and `LocationResolver` returned nothing. Past that check, the GPS request throws
`SecurityException` without the precise permission — inside the same `try` as the network request,
which was therefore never made.

**Fix.** Either permission is enough, GPS is used only with the precise one, and the accuracy of the
fix is carried to the message: beyond one kilometre the map link is followed by `(+/-N km)`. Android
shifts an approximate position by about two kilometres while the link keeps five decimals; without
the margin it would point at the wrong street — the "sent but wrong" variant already refused for
stale positions in v1.26.1. The margin is written in ASCII: `±` would push the whole SMS into UCS-2.

#### With notifications off, Safety call and the lock-screen shortcut failed silently

**Affected versions: from their introduction to 1.28.12.**

Without `POST_NOTIFICATIONS`, or with the app's notifications or one of its channels turned off, the
notifiers skip posting, by design: `notify()` does not throw. But nothing said so. Safety call lost
its pre-send warning, the notification that stops a sequence, and the v1.28.12 "armed but nothing
will go out" signal; the lock-screen shortcut stayed "on" in Settings while existing nowhere.

**Fix.** A banner on the Safety call screen, under the shortcut switch and in Settings →
Notifications, re-read on every resume. It checks the app-wide switch **and** the feature's own
channels (`CanauxVisibles.kt`), since Android lets the user turn channels off one by one.

#### Two hardening changes that are not vulnerabilities

The crash itself: with `READ_CONTACTS` refused, opening New message, or any conversation, threw an
uncaught `SecurityException`. The refusal is caught at those two callers and deliberately **not** in
the contacts repository: `IncomingBlockPolicy` needs the exception to tell "could not check" from
"unknown sender", or "block unknown senders" would block every incoming message. A test locks that
invariant.

And the app no longer holds `INTERNET`. It never opened a connection: MMS go through `SmsManager`,
and Android's own MMS service talks to the carrier. Measured on a Galaxy S9: the app process is
outside the kernel's `inet` group (no gid 3003), with Fennec measured in the same session as a
positive control; MMS were confirmed in both directions between two phones.
`.github/scripts/permissions-manifeste.py` fails CI if `INTERNET` ever reaches the merged release
manifest, and first proves it can fail.

---

### v1.28.12 — What browsing the app in another language revealed

**Affected versions: see each item.** No report received. The three defects below were found
while translating the app, then through five audit waves and two external reviews; all were
present in the published 1.28.11.

#### The "I'm OK" banner could appear in a decoy session

**Affected versions: 1.26.1 to 1.28.11.** What is at stake is the app's threat model, not a
convenience: the decoy session exists so that a person under duress can give up a code without
handing over anything.

`showIAmOkChip` does carry `!isPanic`, but it is computed in the **aggregated** flow, served by
`stateIn(WhileSubscribed(5 s))`. When the lock screen is laid over it, this screen leaves
composition; five seconds later the upstream stops and `state.value` **freezes**. On return, the
cached value is served — computed before the lock, hence with `isPanicDecoy = false` — until the
`combine` re-emits, which costs an encrypted query and a Binder call on IO.

The scenario: the victim triggers the emergency, the messages go out, someone takes her phone
and forces the panic code out of her. The decoy screen opens — and the banner is there. **The
attacker learns that a call for help has just gone out.** The vault icon revealed that a vault
exists; this reveals the alert itself.

This is exactly the mechanism that audit H18 had fixed in v1.26.1, and the KDoc it left describes
it word for word. That fix derived `isPanicDecoy` directly from the lock, with `Eagerly` — and it
was applied to **that single field**. Six places on this screen already kept the fresh value; the
banner was the seventh, and the only one left on the cache.

**Fix.** The block becomes its own composable, with the guard in **one** place instead of in the
middle of forty branches.

#### Safety call and emergency mode could arm and then stay silent

**Affected versions: 1.10.0 to 1.28.11**, whenever the app does not hold the default-SMS role.
Availability of a personal-safety feature — the worst way to fail, because the user believes they
are covered.

`SendSmsUseCase` refuses any send without the role, and both features go through it. At the
deadline, the send failed, the slot was released — which resets `triggeredAt`, hence closes the
`isTriggered` gate that would have posted a notice — and **nothing was displayed**. The next tick
retried, failed the same way, indefinitely, while the screen showed "armed" and a countdown.

Neither of the two arming screens mentioned the role: `isDefault` appeared nowhere in them. Worse,
the screen from which the emergency is **triggered** announced "ready" while nothing would be sent.

**Fix.** A shared banner reports the missing role and offers to request it, on the three screens
concerned — Safety call arming, emergency setup, and the emergency screen itself. The button is
**not** disabled: in a crisis, a dead button is worse than a button that tries. A wiring test reads
the three sources and requires the banner to be present in each one.

#### Three retired keys survived "Delete all my data"

**Affected versions: all those in which these keys existed, up to 1.28.11.** Completeness of the
erasure, not confidentiality of the encryption.

`PanicService.nukeEverything` does not **clear** the preferences store: it writes a fresh
`AppSettings()` over it. A key that the writer does not name is therefore never touched.
`locale.tag`, `locale.firstDay` and `advanced.isDefault` had stopped being read, which had not
erased them: on any earlier installation, the chosen language survived a purge **that declared
itself complete** — and it is this very version that started counting leftovers and saying when it
had not erased everything.

**Fix.** The writer removes these keys on **every** write, from a single named list. Any key
retired later goes there and nowhere else: it is the only place that guarantees its disappearance
from existing installations. The test writes the keys into a real DataStore and **first checks
that they are there** — otherwise an empty store would make the test pass without measuring
anything.

#### Two safety fixes that are not vulnerabilities

The bodies of the emergency and Safety call SMS had been **hard-coded in French** since
v1.10.0, outside `strings.xml` — hence invisible to any parity check. A German-speaking
recipient received a call for help they could not read. And emergency calls dialed the numbers
**of France** wherever the phone was: the country now comes from the **network** the phone is
registered on, never from the app's language.

---

### v1.28.11 — An optimization marker cannot carry access to the data

**Affected versions: 1.25.0 to 1.28.10.** Data availability, not confidentiality: encryption at
rest, the algorithm and the threat model are unchanged. No report received.

Since 1.25.0, the database has been opened with a **raw key** (see the v1.25.0 entry below). Yet
the zero-key repair that runs just before, `LegacyZeroKeyRekey.rekeyIfNeeded`, probed the file
with the **plaintext passphrase** — a probe wrong by construction on any database written since.
The probe with the legacy zero key failed as well, and the code then concluded that the database
"decrypts with nothing": `Failure` thrown, repair screen, **messages and vault refused on every
launch**, with no way out other than a reinstall — on an app whose `allowBackup=false` leaves no
other copy.

The only thing that prevented this conclusion was the `shared_prefs/db_repair.xml` marker, written
by `apply()`, that is to say **asynchronously**. A process killed before that write reached the
disk lost the marker while leaving the database intact. `adb install -r` produces exactly this
sequence; so does the system, when it reclaims a process shortly after its first launch.

**A/B measurement**, same device, same files, same APKs: **5 checks out of 5 pass with the
marker, 0 out of 5 with that single file removed.**

**Fix.** The probe accepts **both forms of the same key**, the raw one first — as its twin
`ensureRawKeyed` always has. The marker goes back to being a work-saver and not the sole gateway
to the data. The markers switch to `.commit()`: a repair marker whose write does not survive the
death of the process does not do its job.

**What the defect says about the rest.** It is, for the third time, the pattern of a fix applied
to only one of two twin paths (see v1.28.3). It was found not by review but by a new
continuous-integration check, which installs the previous version, seeds a test dataset into the
real encrypted database, installs the new one over it and requires everything to read back —
negative controls included. Regression pinned by
`RawKeyMigrationTest.rawKeyedDb_withoutRepairFlag_isNotDeclaredUnreadable`, written RED before the
fix.

### v1.28.9 — What resists is kept and reported, never announced as erased

Andrew Pozdnakov's seventh note on F-Droid MR !38458: five findings deduced from the 1.28.8 source, all
confirmed in the code and fixed. What they have in common: an erasure that failed, or that did not know,
was treated as a successful erasure.

**Shared attachment files.** The same file is referenced by several rows (a send to several
recipients, the echo of a group, a scheduled send). The first erasure won and left the other rows
pointing to a missing file. A file now goes only with its last reference; a read of the references
that fails keeps it and counts the failure. Same rule for the retention purge, whose files stayed on
the phone with nothing left leading to them.

**Vault conversation whose system copy resists.** Without the SMS role, or on a refusal from the
provider, it disappeared from the app and the resync recreated it **outside the vault**, in
plaintext. It is now kept in the vault, and the user is warned about it. Outside the vault, the
contract remains the previous one: the conversation disappears, its system copy may come back.

**"Delete all my data".** An unreadable conversation list passed for an empty list, and the dialog
said "erased"; in a decoy session, nothing was erased. What resists — system copies, local
failures, unreadable list — is counted and reported. The Keystore aliases are re-read after their
deletion (`deleteKey` swallows its errors), and the key of the biometric second factor, missing from
the list, is added to it.

**Received MMS (F17).** No incoming MMS is written to `content://mms`: the downloaded PDU is the
only copy. When a media item could not be written, it was kept but never reopened, then swept at
24 h. It is now recovered: a transaction key — SHA-256 of the `transactionId`, of the download
address and of the SIM, each field prefixed with its length — is carried by the file name and by
the database (schema 14, unique index; excluded from backups). Neither the MMSC address, which may
carry a token, nor the carrier identifier appear in plaintext. The recovery is idempotent; the
presence of the PDU is re-read **inside** the write transaction, so that a message deleted during
the recovery is not resurrected; the PDU goes with its message, and a PDU arriving during a
deletion keeps the conversation. A media item that fails in a vault conversation posts no failure
notification, which would name the correspondent — measured on device.

**Documented limits.** A PDU written before this version (without a key) or without a sender is
not recovered; a scheduled send already handed to the radio is not recalled.

**Verification.** Each guard has its test (real Room and provider for the data paths) and its
negative control read in the XML report: 53 mutations, all killed. Tests of the four scenarios on
Galaxy S9 (Android 10) and S24 (Android 16), SMS role removed and restored. Reviews: Gemini 3.1 Pro
(F17 design), GPT 5.2 (code), data-room, coherence and 3-axis audits — no critical or high finding.

### v1.28.8 — Search goes through the history, never through the vault

**Search in the text of messages is wired up** (GitHub issue #17). It existed on the data side
with no caller; the field only searched the name, the number and the last preview. Security scope:
the results are bounded **in the SQL** — never a message from a vault conversation (`in_vault = 0`),
hence nothing more in a decoy session, which sees the same non-vault list; never a service row
(`hidden = 0`); the text only, never the numbers. The conversations of the results are re-read
with the same requirement in the SQL, against a move to the vault between two reads, and the flow
re-emits when a conversation moves to the vault while a search is displayed — measured on device.
Each guard has its instrumented test and its negative control. The full-text index covers the whole
database, vault included, in the same encrypted database: only the results are bounded, as before
this version.

**Pinned conversations first in every sort order**, and **isolation of the DataStore tests**: no
security scope. The second touches `SecurityStore` (primary constructor receiving the `DataStore`,
injected constructor unchanged): Hilt graph, file, keys and behaviours identical — reviewed by
GPT 5.2 and by a dedicated security audit.

### v1.28.7 — What is deleted is no longer displayed, on every path

**Three deletions left their notifications behind**, recorded without a fix in 1.28.6 by the
security review of the final delta: the retention purge (a bulk `DELETE`), the sync reconciliation
when a message disappears from the provider through another app, and the merging of duplicate
conversations. An erased message remained readable in the notification shade, sender and text
included. The first two now collect the messages they are about to erase — in the same transaction
and with the same SQL clause as the `DELETE`, vault excluded — then cancel their notifications after
the commit, and only if rows were removed; the merge cancels those of the victim conversations
actually deleted. The grouped cancellation reads the active notifications only once and cancels
only the targeted tag + identifier pairs, never a notification without a tag. The inventory of all
the app's deletions is closed: each one cancels its notifications, or cannot have any.

A GPT 5.2 review found an error in the fix itself before release: the merge cancelled the victims
of every plan, including a plan skipped without deleting anything — hence the notifications of
conversations still present. Fixed.

**⋮ button squashed on a narrow screen** (no security scope; accessibility): the bubble, capped at
a fixed width, was measured before the button — 16 dp instead of 40 on a 360 dp screen with a long
message, 0 dp on 320 dp, the menu then being unreachable. Only received bubbles were affected.

### v1.28.6 — A second copy path, aligned with the first

*Two user reports, unrelated to the F-Droid review.*

**Free selection of a message excerpt copies through the system menu**, hence through Compose's
`LocalClipboard` and not through `copyToClipboardSensitive`. Left as it was, an excerpt of a vault
message came out as a preview thumbnail on Android 13+ — exactly defect N4, reopened by a new
path. `SensitiveClipboard` wraps the Compose clipboard under the selection container and sets the
same mark; `ClipData.markSensitive()` is the single writer of this mark, now set on every version
(the system ignores it before Android 13), which makes it **verifiable** on the measurement device.
Instrumented test: long press, copy from the toolbar, reading of the system clip's description;
negative control: without the wrapper, the mark is absent.

**"Delete all my data" ran in the decoy session, in full.** Found by a pattern audit launched after
the splash fix, to look for its neighbours: the button is deliberately visible in the decoy
(v1.27.11, same reason as "Reset all settings": an ordinary SMS app knows how to wipe itself), but
its effect destroyed the real vault, the PIN and the panic code from a session whose very purpose
is to preserve them. No test covered `nukeEverything`. In the decoy, the purge now erases only what
the decoy shows: every non-vault conversation through `ConversationEraser` in ordinary mode (system
copy, scheduled sends, files, like a manual deletion), the transient files, and the settings while
preserving the security block — the splash replays, the screen is that of a real purge. The
database, its key, the Keystore, the PIN, the panic code and the counters stay untouched. The
branch is taken in `PanicService`, the single entry point, and not in the screen. Three unit tests,
negative control done. Severity assessed: medium — the decoy protects the vault's existence, not
its availability, and whoever holds the phone can uninstall; but losing the vault AND the panic
code in three taps from the decoy was not acceptable.

**"Delete all my data" deleted no message from the phone.** Raised by Patrice, with a question
worth more than an audit: if you delete everything, it is to delete the messages. The purge
destroyed the database **file** without ever going through `ConversationEraser`, the only path that
propagates to the system provider. The copy of each message therefore stayed in `content://sms`,
and the resynchronisation at the next launch — cursor reset to zero by that same purge — brought
them **all** back. Including those of the VAULT, in the main list and **in clear text**: their
system copy was never deleted (limit N2, accepted) and the `in_vault` flag lived only in the
database that had just been destroyed. It was the only deletion path in the app that bypassed the
phone. Now: a sweep of all conversations by the eraser, under the purge barrier, before the
database is destroyed; whatever resists is counted and **stated** before the restart, instead of
being promised erased. Measured end to end on S9 with the SMS role held by the app: 10 messages in
the phone before, 0 after, 0 system copies remaining.

**The purge could block the app permanently**, on the already published 1.28.5. It destroys the
database, the wrapped-key file and the Keystore aliases, but the process SURVIVES while keeping the
SQLCipher passphrase in memory — `DatabaseFactory` documents why it cannot be erased. The slightest
reopening of Room rewrote an encrypted database with a key whose wrapping no longer existed, and the
next launch displayed "cannot open its database", forever. Two layers: the process restarts after
the purge, and at startup a database that no existing key opens — wrapped key absent — is set aside
instead of blocking, which repairs the installations already blocked. **The F18 doctrine remains
intact**: wrapped key PRESENT and database unreadable still throws, without deleting anything, and
an instrumented test holds this positive control.

**The secure store was cleared only through eight named keys out of eighteen.** What survived the
purge: the **vault** PIN hash (v1.13.0), its throttling (v1.27.10), the timestamp of the last
unlock and the persistent notification token — in an **unencrypted** preferences DataStore. A
PBKDF2 hash of a four-digit code is cracked offline, and its mere presence proves that a vault
existed, which is exactly what the decoy exists to keep quiet. No behavioural defect, on the other
hand, verified: the vault gate requires the hash AND the `vaultPinEnabled` flag, which the purge
resets to false, and setting a PIN again rewrites the hash while purging the throttling. It is this
repository's usual asymmetry pattern — `pin.*` and `panic.*` erased, their twin `vault.*` never
added to the list. Fixed by a single `clearAll()`: a list of keys to keep up to date is a missed
appointment with every new key, and it was missed three times. The decoy session never calls it,
and a test holds this.

**Notifications survived every deletion.** The repository's only cancellation call lived in "mark
as read": the eraser, the single deletion rule since v1.28.1, did not even have the dependency.
Deleting an unread conversation left its sender and its text in the system shade; "Delete all my
data" left them all there, after a dialog that promises the irreversible, and at the worst moment —
you purge because someone is about to take the phone. The emergency shortcut's notification is
`ongoing`, so it cannot even be swiped away by hand. Bound verified: a vault conversation never
notifies, so the vault purge left nothing behind. The cancellation is placed in the eraser —
ordinary deletion and the deletion of a single message are repaired in the same stroke — and the
full purge cancels everything, in both sessions with the same visible effect (a difference between
the decoy and the real session would be the leak that I1 forbids).

**The clipboard too.** This version adds copying a message excerpt: you select, you copy, you purge
— and the text stayed in the phone's clipboard, readable by any app. The clipboard is outside the
vault's scope (I7/N4), an accepted and written limit; what was not accepted is that a purge calling
itself irreversible leaves it filled. It is cleared in both sessions, at the same point as the
notifications. `clearPrimaryClip` requires Android 9 whereas `minSdk` is 26: a fallback with an
empty clip covers Android 8, without which the line would have done nothing there without saying
so. Android 10 and later allow this write only to the foreground app — which it is at the moment of
the tap —, and the code says so rather than promising it.

**What the notification cancellation does not cover yet.** The security review of the final delta
found three paths that delete without going through the eraser, and therefore leave their
notifications behind: the retention purge, the sync reconciliation when a message disappears from
the provider through another app, and the merging of duplicates. Pre-existing and of bounded impact
— a notification does not survive a phone restart, retention targets old messages, merging deletes
no message —, they are recorded for a later version rather than fixed at the last minute. Likewise,
`cancelAll()` does not remove the notification of an active foreground service: the one of the
"resistant" mode goes away when the settings reset stops the service. The same review found the
only step of the purge without a safety net — the clipboard, where an exception would have crashed
the app after the data was destroyed and before the confirmation dialog —; it is fixed.

**The purge was non-cancellable only at its end**, and it was the pre-release audit that found it,
on code written in this very version. It lives in a `viewModelScope`; v1.28.5 had put its final
writes under `NonCancellable` for that precise reason, and the conversation sweep added here went
**above** that block, even though it is suspending and long. Leaving Settings during the purge
cancelled the sweep, then the synchronous steps destroyed the database: the messages not
propagated came back at the next sync — the very defect this version closes. The guarantee is now
carried by the whole function. The comment that justified `exitProcess` claimed "`NonCancellable`
end to end": it was false when it was written; it is corrected and names the place where the
invariant is held. *A justification that relies on an invariant must say where it is held,
otherwise it outlives its own truth.*

**The welcome screen stayed stuck after a reset** (idempotence guard never re-armed). No security
impact, but the path is that of "Delete all my data": a user who empties the app must be able to
start using it again without killing it.

### v1.28.5 — Six edges deduced from the source, all real

*Andrew Pozdnakov's sixth note on F-Droid MR !38458 (2026-09-11): he closes R01/R02/R03 after
reviewing the 1.28.4 source, then lists five candidates deduced from the code and not measured,
plus a minor edge. Checked one by one in the source before replying: **all six are real.**
Register: `audits_relectures_IA/audits-ia-externe/2026-09-11-andrew-pozdnakov-mr38458-6e-note-6-aretes.md`.*

**1. The deliberate exit lifted more than the "system copy" condition.** `purgeVault(force = true)`
called `erase(preserveOnSystemFailure = false)`, and that same boolean also guarded the count of
dependants and the re-read for late arrivals. Under `force`, a refused `delete()` or a `cancel()`
that throws therefore went on to the deletion of the parent with `localeComplete = true`: the resume
log disappeared, the purge called itself locally complete, and `residuSystemeSeul` allowed the
removal of the PIN. **My note of 10 September claimed the opposite of the code** — I described the
intent, not the line. Three contracts, three names: `ConversationEraser.Mode` (`ORDINAIRE`,
`COFFRE`, `COFFRE_FORCE`). Under `COFFRE_FORCE`, only the "system copy" condition is lifted; a
dependant that resists keeps the parent; a message that arrived late goes, counted as a system
residue. Four tests on real Room, with the real failure producer (folder `0500`, `cancel()` that
throws, message inserted during the sweep), and the positive control of what `force` still lifts.

**2. The purge barrier was a test-then-act.** An entry read `enCours`, then wrote to the database
later; a purge raised between the two did not see it and could re-read `remaining = 0` before its
commit. And the barrier came down **before** the removal of the PIN. `VaultPurgeBarrier` is
linearised: an entry registers under the same lock as the one that raises the purge, the purge waits
for the entries registered before it, an entry arriving after it is refused. The removal of the PIN
runs **under the barrier**, through an `apresPurge` callback that `deleteAllInVault` invokes with
the result before lowering it; the ViewModel makes its decision there, once, and its events follow
from what was done. Tests: in-flight entry awaited then committed, new entry refused during the
wait, throwing entry deregistered anyway, PIN removed while the barrier is raised (measured, not
assumed).

**3. Group MMS: mirror with the blocked members, PDU without them.** The local thread was `A+B+C`,
the MMS transmitted `A+B`; since matching on reception requires the same set of members, A's reply
came back into a second group. The mirror now receives the same targets as the PDU, on both paths
(photo, voice). Test: three members including one blocked, the mirror carries `A;B`.

**4. The downgrade guard failed open.** A failing `countInVault()` became `0` and the unreadable
factor `null` — exactly the combination that authorises the downgrade, obtained without reading
anything. Both reads now return `VaultStateUnknown`, a refusal stated on screen. Fault-injection
tests on each read, positive control on a vault read as empty.

**5. The "I am OK" gesture coming from a notification was held back in the decoy session.** The
"wait for authentication" policy was intended, but wrong for `LockedOut` and `PanicDecoy`: a real
opening only resets the timer, it never disarms; a gesture held back during the decoy therefore
added, at the first real unlock, a disarm the user had not made. The gesture is **discarded** in
these two states, held back in `Locked` only, and the notification is republished so that a
legitimate gesture remains possible. The reset twin, which disarms nothing, keeps waiting.

**6. Bounded copy: a partial file after an exception.** `LectureBornee.recopier` only cleaned up
on overflow; a source that throws midway left what had been written. The file is now deleted
before the exception propagates. Test: a source that gives out after 200 bytes.

**7. Found while verifying point 2, not reported.** While looking for "any other writer of
`in_vault` that bypasses the barrier": **restoring a backup** inserts conversations with the
backup's `in_vault` (`BackupService.importPayload`), outside the barrier. A restore launched during
a purge could therefore fill the vault between the re-read of `remaining` and the removal of the
PIN. The restore now registers as an entry (`barriere.enEntrant`); a raised purge refuses it before
it reads a single byte, passphrase erased. Test with strict test doubles, existing positive control
kept. In the same stroke, `ConversationRepository.moveToVault(id, inVault)` — a bare `setInVault`,
with neither barrier nor second factor, with no caller at all since `VaultManager` carries the
guards — is **removed** from the interface: an unguarded path left public next to its guarded twin
is a trap for the next caller, and the 2026-08-03 audit (F11) had already asked for it.

**8. Two consistency drifts, found by the whole-app audit launched right after.** (a) **Deleting
ONE message left its files in clear text**: `deleteMessage` lived in the repository, outside
`ConversationEraser`; the `attachments` row went by cascade, the file in `filesDir` stayed — the
defect class closed in 1.28.3 (F04) for the whole conversation, reopened on the most frequent path,
deleting an embarrassing MMS. `eraseMessage` now lives in the eraser, with the same sandbox; the
repository delegates. Test on real Room, file actually created then measured absent. (b) The
**quick reply from a call** (`HeadlessSmsSendService`) called `SendSmsUseCase` directly: a third
sending point, the one the single dispatch of 2026-09-10 had missed. It now goes through
`EnvoyerMessageUseCase`.

**9. Targeted reading of the vault's blind spots in the decoy session** (agent, whole app). Fifteen
read surfaces checked one by one — full-text search, incoming notification, contact identity,
unread badge, mark as read, scheduled sends, PDF export, deep link, incoming share: **all
guarded**, by `in_vault = 0` in SQL or by a session guard. Two medium points fixed: (a) the "Turn
back on" path of the Safety Call notification (`ACTION_SAFETY_CALL_REARM`) lacked its twin's guard
— a persisted write of security settings without proof of unlock; it now applies
`attendreOuvertureOuJeter`. (b) Restoring a backup containing vault conversations on a device
**without a second factor** made them readable in two taps without anything saying so. This is not
a hole in the restore (writing to the vault requires no secret, `VaultSecondFactor.NONE` is an
accepted configuration), it is a silence: `RestoreResult.vaultRestoredWithoutSecondFactor` states
it on screen and points to Settings.

**10. Sweep of the repository's 245 `runCatching`** (agent, read-only): which ones wrap a `suspend`
call without rethrowing `CancellationException`. One **security** finding: the four final writes
of "Delete all my data" (`PanicService.nukeEverything` — removal of the PIN, of the panic code, of
the lock counters, of the settings) lived in a `viewModelScope`; if the Settings screen left the
stack at that instant, each `suspend` raised a cancellation that `runCatching` swallowed **without
a word**, and the function returned leaving the PIN and the Safety call contacts in place. The four
writes are now under `NonCancellable`, failures logged. Five data sites fixed by a common helper,
`runCatchingCancellable`, which lets the cancellation propagate: negative cache of contact names
(poisoned by a cancellation), files of a cancelled scheduled send (never erased), snapshot of the
dialling region (F-01 reopened), previous system row of an MMS (orphaned in `content://mms`), and
`runCatchingOutcome` itself (an interrupted export or restore displayed "storage failure"). The
~30 other sites identified are log noise with a fallback on the safe side; listed in the register,
not modified.

**11. External review (Gemini) of the second wave**, two findings retained: (a) a direct
consequence of point 10, the restore passphrase was no longer erased when the cancellation
propagated — `restaurer` is now in `try/finally`; (b) pre-existing since 1.28.3, the wait of the
"I am OK" gesture lived in a `lifecycleScope.launch` that survives going to the background:
notification tapped, then app left locked, and the gesture ran at whatever unlock came next, hours
later. `lancerGesteDeNotification` bounds both twins to the foreground: when the activity stops,
the pending gesture is cancelled and the notification republished.

### v1.28.4 — An incomplete cleanup reported as complete

*Andrew Pozdnakov's fifth note on F-Droid MR !38458 (2026-09-10): he narrowed in on the vault purge
(F03/F04/F09/F13) and measured, on an Android 14 emulator with the real SQLCipher database and the
production Hilt graph, **three cases where the purge calls itself complete while something
remains**. Register: same file as 1.28.3, section "Cinquième note" ("Fifth note").*

**What he found, and why it is serious.** 1.28.3 had made the purge resumable by keeping the parent
when the system copy resists. But its **dependants** — scheduled sends, attachment files — were
cleaned up by helpers that returned nothing: an exception swallowed, a `delete()` returning `false`
logged and forgotten. The ordinary success branch was therefore reached **without `force`**, the
parent went, the PIN was removed, `VaultPurged` emitted — and an orphaned scheduled send **became
visible again outside the vault** (`COALESCE(c.in_vault, 0)`), with no way for the resume ever to
find it again, its parent being gone. Third case: a message imported by the production sync that
**commits between the second re-read and the `DELETE` of the parent** is swept away by the cascade,
system copy intact, purge "complete".

**What changes.** `ConversationEraser.erase` returns `Issue(systemCopyGone, localeComplete)`. Both
helpers return a failure count (failed enumeration = failure, `delete()` returning `false` =
failure); a single failure **keeps the parent** for the vault and counts in `localFailures`, so
never "complete". The re-read and the deletion of the parent live in **a single Room transaction**:
SQLite having only one writer, an import that commits during the purge waits for the lock and can
no longer slip in between the two. The scheduled rows are deleted in that same transaction, no
longer by the helper.

**What the measurement added.** Three tests on real Room reproduce R01 (`TRIGGER` refusing the
`DELETE`), R02 (folder `0500`, `delete()` measured at `false`), R03 (message inserted at the
scheduler's injection point), each with its resume. The **negative control of R01 did not fail**:
the refused `DELETE` now lives in the final transaction and propagates through another path — the
failure count of the cancellation itself was covered by nothing. A fourth test makes the
scheduler's `cancel()` throw and fails on its own when that count is neutralised.

**Two vault branches never tested since v1.26.1**, now closed: the refusal of export **and of
restore** in the decoy session, reached through the real path (main PIN, panic code, unlock with
the panic code); and the **biometric** second factor, which the policy must return for a vault
without a vault PIN and which export must respect. Negative controls on the policy, on the export
guard, on the restore guard.

**Two limits, stated rather than kept quiet.** (1) Cancelling a scheduled send is a request to
WorkManager: a radio operation already handed over by an attempt in progress is not recalled; the
row is removed and, if the radio had already accepted the send, the message goes out. (2) A message
that commits **after** the final transaction targets a parent that is gone: Room enforces the
foreign key, the insertion fails instead of creating a protected orphan, and the provider's row is
imported later into a new ordinary conversation — like any message from that contact arriving after
the purge. The vault protects what the app knows about.

**Outside security, in the same version**: group MMS (setting off by default), a `hidden` column
(schema 13) so that a reaction sentinel is no longer recognised by its shape, the send loop and the
dispatch written only once after a fourth divergence between twin paths.

### v1.28.3 — A fix applied to only one of the paths that needed it

*Fourth pass of Andrew Pozdnakov's external review on F-Droid MR !38458 — 33 findings, all
verified in the source before fixing, 32 fixed — followed by a global audit and a measurement
session on two phones. Register:
`audits_relectures_IA/audits-ia-externe/2026-09-09-andrew-pozdnakov-mr38458-4e-passe-33-findings.md`.*

**The dominant pattern, worth more than the list.** The vast majority of these defects were fixes
**already written**, but applied to only one of the paths that needed them. The repository knew,
and often said so in a comment: the backup described the F01 mechanism word for word and bypassed
it for itself alone; the incoming-message notifier applied the three redaction guards that the
failure notifier lacked (F08); the outgoing path handled several attachments where the incoming
path kept only one (F16). And the pattern recurred **during the fix**: F21 applied to the SMS path
alone while the same silent `continue` lived on both MMS paths; then to their background caller,
the scheduled send.

**Second pattern: claims of exhaustiveness age badly.** "The ONLY unguarded read path", "three
dialogs, one setting, consistent", "every migration is additive" — three false comments, one of
which hid F02 for two versions.

**What touches security, in substance.**
- **F01** — two local conversations without a system thread shared the sentinel `thread_id = 0`
  under a UNIQUE index: the second erased the first, along with its favourites, reactions and vault
  membership. `thread_id` is nullable (migration 8 → 9), insertion no longer replaces.
- **F02, F03, F04, F09, F10, F11** — the vault: its scheduled messages could be read without the
  PIN, its purge left scheduled sends and files behind, and believed it had deleted system rows it
  never reached (no outgoing MMS ever had a `telephony_uri`).
- **F06, F07** — disarming the lock did not require authenticating; downgrading the mode removed
  the vault's second factor.
- **F26** — "Block unknown numbers" was a displayed promise that not a single line kept. Wired,
  with the rule that matters: a refused contacts permission **blocks nothing**, because ignorance is
  not knowledge.
- **X-01** (found while verifying a finding of the global audit that was false) — replying from a
  vault group wrote outside the vault, and a member's reply arrived there likewise. SMS has no
  groups: putting a group in the vault now puts its members there, and taking it out takes them
  out.
- **A-03** — restore did not refuse in the decoy session, unlike export.

**What on-device measurement taught, and reading could not provide.** Nine defects found in one
evening on a Galaxy S9 and an S24, four of them in advertised features that had **never** worked:
attaching a contact (the contact card is not a file), sending two photos (each image fit under the
cap on its own, never together), creating a group (the "touch = open" shortcut had killed the path),
seeing all the attachments of an MMS (the F16 data fix lacked its display twin). A green unit test
says nothing about a path that no one takes.

**What the negative control taught.** Two fixes delivered together can mask each other: F12 stayed
green for the wrong reason as long as F14 was neutralised along with it. And a negative control
whose report is stale measured nothing — seen twice in the session, once on a refused compilation,
once on a Windows file lock.

Instrumented campaign: **131 cases on Galaxy S9 / Android 10, 0 failed, 0 skipped**; 557 unit
tests; migration 11 → 12 run on device.

### v1.28.2 — A guard that always refuses the same way is no longer a protection

*Direct follow-up to v1.28.1, decided after it and not reported by the external review: it is
the v1.28.1 fix itself that created the case.*

v1.28.1 was right to **keep** the vault conversation when its system copy resists. That is
what makes the purge resumable, and that is what closed the leak on the second attempt. But it
assumed the failure was **transient** — the SMS role momentarily lost, a provider unavailable —
and that assumption is false in one specific and lasting case.

A backup restored from **before v1.27.10** copied the `telephony_uri` values of the **source**
phone. A message can therefore carry, permanently, a link that here designates **another**
message. The identity guard added in v1.28.1 then does exactly what it is asked to do: it
refuses to delete the system row, because that row is not proven to be the right one. It refuses
on the first attempt, the second, the hundredth — identically. And the user who has forgotten
their vault PIN has **no** way out at all.

**An exit door that never opens is not one.** The guard ended up protecting the application
against its owner.

**What changes, and what does not.** On the **second** failure only, the application offers to
empty the vault and remove the PIN anyway, after stating what will **remain** on the phone — and
says it again once the operation is done. The first failure still invites the user to retry: a
transient fault clears by itself, and offering the degraded option straight away would push
toward destroying more than necessary.

`SystemCopyEraser` does **not change by a single line**: the system copy is still not deleted
without proof of identity, so this is not a loosening of the guard. What changes is the
**local** trade-off — whether or not to keep the Room row when propagation has failed — and it
now belongs to the user, informed, on the second failure.

**The flag that tells the two failures apart is in the database** (`vaultPurgeFailedOnce`), not
in memory. An in-memory counter does not survive what it is asked to survive: closing the
application between two attempts would bring back the dead end, and that is precisely what
someone who is stuck would do.

**The general rule, to apply to future guards.** Distinguish the **transient** failure from the
**repeated** failure. The first invites a retry. The second opens a deliberate exit, under three
conditions: the guard itself does not move, the user decides after reading what will remain,
and the state that tells the two failures apart is persistent.

Covered by 5 JVM tests (`SettingsResetGuardsTest`) and 2 instrumented ones
(`VaultPurgeRetryTest`), negative control performed: putting both regressions back makes the
tests that target them fail.

### v1.28.1 — The vault's exit door opened on the second attempt

*Third pass of Andrew Pozdnakov's review of F-Droid MR !38458, 2026-09-08, which he reproduced
on an Android 14 emulator. Direct follow-up to v1.27.11, which had fixed the same path without
closing it.*

v1.27.11 had made the vault purge **honest**: it said what had failed, and the PIN was only
removed from a demonstrably empty vault. It had not made it **resumable**, and that is what was
missing: the Room row went away anyway, including when the system copy resisted.

The sequence is more serious than the isolated finding. First attempt: the PIN is correctly
kept, but the conversation is already erased. Second attempt: `idsInVault()` returns an empty
list, `VaultPurgeResult` is `0/0/0`, and `isComplete` is true **vacuously**. The PIN goes, the
system copy survives, and the next resync resurrects it **in cleartext** — precisely the state
v1.27.11 claimed to prevent.

**The lesson, more useful than the defect.** Reporting a failure serves no purpose if the state
that resumption needs is destroyed along the way. The fix adds no purge log: **the vault row IS
the log**. Kept, it is reread on the next attempt just as after a restart, it counts in
`remaining`, and the system deletion is retried.

**Two guards hardened along the way.** The identity of a provider row rested only on its date,
to within one minute — that is, on nothing, one minute being the ordinary length of an exchange.
It now compares date, body, direction and address on the `content://sms` side, and an
unverifiable identity fails on the safe side instead of triggering the deletion. On the
`content://mms` side, the table designated by the URI carries neither body nor address: identity
there came down to date + direction, and the test written to verify it **deleted another
correspondent's MMS** on a Galaxy S9 before the fix existed. The address is now reread from
`content://mms/<id>/addr`.

**Automatic history deletion did not propagate to the system provider**, unlike the three other
deletion paths, and nothing documented it. For a privacy setting, this is the costliest kind of
defect: it cannot be seen. The messages the user believed erased remained in `content://sms`,
readable by any application holding `READ_SMS`, and "Resync" brought them all back — even though
the confirmation already promised "This cannot be undone". Found in a coherence audit, not by
the external review.

⚠ **Destructive behaviour change.** On a device where automatic retention is enabled, the next
purge deletes the messages **from the phone** and no longer only from the application. The
confirmation now says so explicitly, and the description of "Resync" no longer promises to
recover an erased history.

**The rule that unifies the four destructive paths**, and that was missing: *the local row only
survives a propagation failure if a **security** decision depends on that propagation.* Removing
the vault PIN is one — proof is required. An ordinary deletion or a retention purge raises none:
keeping the row there would lock the user into a history they asked to see disappear, without
protecting anything more.

**Why three defects got through on the same path in three versions.** It lived as `private` in
`ConversationRepositoryImpl`, among eleven dependencies, nine of which had nothing to do with it:
no test could reach it without building the whole repository, and nobody did. It now stands on
its own (`SystemCopyEraser`, `ConversationEraser`), the deletion policy is a table tested in its
own right, and every fix went through a negative control — the defect put back makes the tests
that target it fail.

### v1.27.10 — The vault's second factor could be replaced without being asked for

*External review by Andrew Pozdnakov on F-Droid MR !38458, 2026-09-07. Direct follow-up to the
v1.27.2 entry below: that one had shown that the vault guard held the screen and not the data;
this one shows that the secret itself was not guarded.*

The vault PIN could be **replaced** (Settings → "Change the vault PIN") or **removed** (toggle
OFF) without the current one ever being asked for. The second factor therefore did not withstand
its declared adversary: whoever knows the app PIN. Reproduced on an Android 14 emulator by the
reviewer.

**It was not an oversight, and that is what makes it a lesson.** The KDoc of `VaultPinManager`
documented this disabling as the way out in case of a forgotten PIN, twenty lines below the
"threat model" it contradicted word for word. Both paragraphs had been reread dozens of times
without the contradiction standing out, because each was right on its own. **An exit door that
requires nothing more than the factor one is protecting against is not a compromise: it is the
bypass.**

Since then:

- `changeVaultPin` and `disableVaultPin` require the current PIN, verified by the same PBKDF2 and
  under the same backoff as entering the vault;
- `configureVaultPin` refuses to overwrite a genuinely guarded vault (hash set **and** flag ON) —
  a last-resort guard in case a future screen wired up the wrong dialog;
- the exit door remains, because an unrecoverable secret with no way out is a trap, but it is
  **destructive**: it empties the vault before removing its PIN. Destroying is a power the holder
  of the app PIN already had; reading is the one denied to them;
- `verifyVaultPin` finally has a **dedicated** exponential backoff. The original reasoning —
  "the vault can only be reached after the app lock, which is already rate-limited" — was true as
  long as the vault was just one door behind another, and false as soon as the second factor has
  to resist someone who has **already** passed the first: their attempts produce no failure on
  the app side. A `vault.*` key set separate from `auth.*`, without which a simple lock/unlock
  would have reset the backoff at will.

Locked in by 12 tests in `VaultPinGuardsTest`, placed on `VaultPinManager` and not on the
screen — the faulty guard lived in the UI, which is precisely why it guarded nothing.

### v1.27.2 — The vault's second factor guarded the screen, not the data

**Affected versions: all, up to and including 1.27.1.**

Four paths opened or exposed the vault without its separate code being asked for:

- **Moving a conversation into the vault armed the session.** The auto-unlock dated from
  v1.11.0, earlier than the vault code (v1.13.0). Since then, anyone who got past the main lock
  could move any conversation and then open the vault — code and biometrics skipped, the screen
  initialising from that session.
- **Moving out required nothing.** Moving a conversation out of the vault reveals protected
  content; only the non-grouped variant carried the guard.
- **The encrypted backup exported the locked vault.** `buildPayload` reads all conversations,
  vault included. The file could be decrypted off the device with the passphrase chosen by
  whoever exported it.
- **`observeVault` was the last unguarded read flow**, while its three siblings had been checking
  the session since v1.26.1.

**Fix.** The guard now applies to **access** and not to display: no more auto-unlock, moving out
and export made conditional on the second factor, and all read flows aligned. When biometrics is
the only second factor and becomes unavailable, the vault is no longer opened by default — the
application explains why and points to setting up a separate code, reachable from outside the
vault.

### v1.27.2 — Loss of incoming messages on a database error

**Affected versions: all, up to and including 1.27.1.**

A momentary unavailability of Room/SQLCipher could make an incoming message disappear without a
trace:

- **SMS.** The blocklist lookup came before the write. Its failure ended the message broadcast by
  the system without `insertInboxSms` having been called: the SMS existed neither in the
  telephony provider nor in the application.
- **MMS.** The PDU file — the only copy of the message and its attachment — was deleted as soon
  as the download had succeeded, including when the database write then failed.

**Fix.** Decoding comes before any dependency resolution; the blocklist lookup fails **open** (an
error lets the message through, only an explicit block discards it); a last-resort safety net
writes the SMS into the system inbox even with the database dead; and the PDU is only deleted
once its fate is settled. `CancellationException` passes through these guards instead of being
converted into "not blocked".

⚠️ **Accepted limitation.** If the system provider commits the insertion and then fails before
the call returns, the safety net reinserts: "at least once" semantics. A duplicate is visible and
can be deleted; a loss is permanent.

### v1.27.2 — Personal safety alert defeatable by rebooting

**Affected versions: 1.10.0 to 1.27.1.**

Triggering requires **both** clocks to have expired — wall and monotonic — so that moving the
clock forward cannot cause a premature alert (SEC-11). But `elapsedRealtime()` restarts from zero
at every reboot, and the drift recovery re-aligned the counter on that value: rebooting more
often than the configured delay prevented the alert from going off, indefinitely. A theft
followed by regular reboots defeated it; in honest use, a system update pushed the deadline back
by as much.

**Fix.** Elapsed monotonic time is accumulated in a persisted field, checkpointed at every hourly
tick of the worker. A reboot now costs only the segment not yet checkpointed, bounded to one
tick. No wall clock enters this calculation: the SEC-11 protection remains fully intact.

### v1.25.0 — Opening SQLCipher with a raw key (performance, no change to security)

Since 1.25.0, the database is opened with the **raw key** (`SupportOpenHelperFactory` receives
`x'<64 hex>'`) instead of letting SQLCipher derive the key through **PBKDF2** (256 000
iterations).

**Why it is neutral for security.** PBKDF2 is used to *stretch* a low-entropy secret (a human
password) to make it costly to brute-force. But SMS Tech's passphrase is already **32 random
bytes (256 bits) sealed by the Keystore** (since the null-key fix in v1.24.0): on a key already at
full entropy, PBKDF2 iterations add **no** resistance — they only slow down opening (~490 ms → a
few ms). Encryption at rest, the algorithm (AES-256) and the threat model are **unchanged**; only
the derivation step, useless here, is skipped.

**Conversion.** A single, crash-safe switchover (`LegacyZeroKeyRekey.ensureRawKeyed`, same
copy → `cipher_integrity_check` validation + row count → swap pattern as the v1.24.0 repair)
runs on the first launch of 1.25.0, after the null-key repair. No message loss: the original is
never destroyed before the replacement has been proven sound.

### v1.24.0 — SEC-CRIT: the database was encrypted with a null key

**Affected versions: all, up to and including 1.23.4.**

`DatabaseFactory` handed the SQLCipher passphrase to `SupportOpenHelperFactory` and then zeroed it
immediately (`raw.wipe()`, i.e. `Arrays.fill(this, 0)` — an **in-place** mutation). But no link
in SQLCipher copies that array: verified by disassembling `sqlcipher-android-4.16.0`,
`SupportOpenHelperFactory` stores its reference, `SupportHelper` passes it on, and
`SQLiteOpenHelper` keeps it in `mPassword`, which it only reads in `getDatabaseLocked()`, that
is, **when the database is opened**. Since Room opens lazily, on the first DAO access, SQLCipher
received 32 zero bytes.

**Consequence: `smstech.db` was encrypted with a constant and public key**, and not with the
32-random-byte passphrase sealed by the Keystore. The defect was invisible because a null key is
perfectly stable from one launch to the next. It had been present since the first commit of the
file — the claim "32-byte random passphrase wrapped by Keystore" in the primitives table above
was therefore never true before 1.24.0.

**Actual scope of the risk.** It must be stated honestly in both directions:
- as the default SMS application, SMS Tech is **required** to mirror all messages into
  `content://sms`, in cleartext, including those of vault conversations (`in_vault` is a simple
  Room flag). The SQLCipher database is defence in depth, never the only copy;
- what the null key exposed **in addition**: SMS Tech's own metadata — vault membership, drafts,
  scheduled messages, reactions, anti-smishing verdicts — and any message deleted by the user in
  SMS Tech.

**Fix (`LegacyZeroKeyRekey`).** On the first launch of 1.24.0, before Room opens the file: WAL
checkpoint, copy to a temporary file, re-encryption of the **copy** with the real passphrase,
end-to-end validation (`PRAGMA cipher_integrity_check` + row-by-row count per table), then
switchover. The original is never modified or deleted before the replacement has been proven
sound: a process kill at any step leaves a usable database. A database unreadable with either
key is reported, never erased.

**What the fix cannot do.** `File.delete()` does not overwrite blocks: the sectors that held the
old database remain physically readable until the file system reuses them. An application-level
overwrite would be illusory on f2fs (copy-on-write) and with the wear-levelling of flash memory.
The only action that truly eliminates this residue is a **factory reset**, which destroys the
device's FBE encryption key.


### v1.14.7 (this release) — Received-MMS cache protection + sync safety nets + transparent splash + audit fixes

User report 2026-05-23: on S24 (after uninstall/reinstall cycles to test v1.14.5/6), the audio attachments of received MMS had disappeared and sync seemed frozen. Root cause identified by logcat diagnosis: (a) `cacheDir/mms_incoming/` (where the audio files of received MMS lived) is volatile — the Android Storage Manager purges it under memory pressure, and "Clear cache" via Settings → Apps empties it too, leaving the Room `AttachmentEntity.localUri` values pointing at missing files; (b) on Samsung S24 Android 15 the system ContentObserver sometimes misses emissions after a long sleep + Freecess freezes background workers.

**Three changes + 3 audit fixes**:

1. **Cache → filesDir protection for received MMS attachments**. `MmsDownloadedReceiver.persistAttachment` now writes to `filesDir/mms_attachments/` (persistent, only disappears with `PanicService.nukeEverything` or `clearData`) instead of `cacheDir/mms_incoming/`. `FileProvider` paths already OK (existing `files-path "attachments" path="mms_attachments/"`). `AutoLockObserver.purgeTransientCaches` doc updated: does NOT purge the new filesDir/mms_attachments (by design, otherwise attachments would disappear at every auto-lock). `PanicService.nukeEverything` still wipes filesDir/mms_attachments correctly (already in its list, verified).

2. **One-shot cold-start migration** `MainApplication.migrateAttachmentsToFilesDirIfNeeded()`. Idempotent DataStore flag `AdvancedSettings.attachmentsMovedToFilesDirV147`. For each `AttachmentEntity.local_uri` starting with `cacheDir/mms_incoming/`, physically moves the file to `filesDir/mms_attachments/` (atomic rename if same partition, copy+delete fallback) then `attachmentDao.updateLocalUri(id, newPath)`. Edge cases handled: (a) source file missing (cache already cleared) → flips the Room path anyway for consistency, (b) destination already exists → keeps dest, deletes source. **Audit S1 fix**: `canonicalFile` + `startsWith(newDir.canonicalFile)` anti-path-traversal check before writing, defence in depth against a future regression of the name generator. **Audit P2 fix**: explicit `withContext(Dispatchers.IO)` around the migration (the `renameTo` / `copyTo` IO no longer saturates the Default thread pool). Async in `appScope.launch` — no main-thread blocking.

3. **onResume sync safety net**. `MainActivity.onResume()` calls `telephonySyncManager.requestSync("MainActivity.onResume")` (idempotent via Mutex on the manager side) + `TelephonySyncWorker.enqueueOneShot(this)` (belt-and-braces if the manager is in an unexpected state). **Audit P1 fix**: 30s monotonic throttle on the WorkManager `enqueueOneShot` (without the throttle, quickly switching between apps spammed WorkManager's internal SQLite → risk of Freecess throttling). `requestSync` itself needs no cooldown (the single-flight Mutex absorbs it).

**Polish**: `splash_logo.xml` from v1.14.5 removed (user report "prefer the old splash, the Android 12+ circle hides my square logo") → new `drawable/splash_transparent.xml` = `<shape rectangle solid transparent />`. `windowSplashScreenAnimatedIcon = @drawable/splash_transparent` (light + night themes) → Android 12+ just shows a flash of plain background, no visible circle. The Compose `SplashScreen.kt` fade-in intro on first launch remains intact. Orphan string `emergency_topbar_warning_cd` removed (FR+EN).

**Threat model** unchanged. No crypto / Keystore / Room / SQLCipher change. Cert SHA-256 stable. No Room migration. `lintVitalRelease` clean, `testReleaseUnitTest` green. Final 3-axis audit — 1 MEDIUM (P1) + 3 LOW ALL FIXED, zero Critical/High.

### v1.14.6 — `(défaut)` label in the reactions picker

The `Réglages → Format des réactions` picker showed `(défaut)` on "Readable French" whereas the actual default value had been switched to `EMOJI_WITH_QUOTE` in v1.14.4 (see section below). FR+EN strings fixed (`settings_reaction_format_fr` drops `(défaut)`, `settings_reaction_format_emoji_quote` adds `(défaut)`). No behavioural change, interface label only. No crypto / Room / Keystore / threat-model change.

### v1.14.5 — Emergency mode UX polish: ⚠️ emoji + direct GPS toggle + full reset on disable + dry-run cleanup + square splash

UX polish after v1.14.4 on 6 axes:

1. **⚠️ emoji at the start of the emergency SMS body** (NEED_HELP + DANGER templates). When the recipient receives the SMS, the heads-up notification immediately shows the alert triangle in the preview → the urgency is visually recognisable even before the SMS is opened. **Trade-off accepted**: the ⚠️ (U+26A0 + U+FE0F variation selector) forces UCS-2 encoding → 70 chars/segment instead of 160 GSM-7 → potentially multi-segment. Audit SEC-5 v1.10.0 kept 1-segment GSM-7 by default for reliability in areas with weak radio coverage. **v1.14.5 decision**: the visibility of the urgency takes precedence over marginal robustness (French carriers in 2026 are reliable on multi-segment). `AuditV1100Test` tests updated (`startsWith("⚠️ URGENCE")`). **DISCREET NOT modified**: the neutral, non-alarming variant keeps its purpose (signal distress without alarming, avoid revealing the situation to an aggressor looking at the screen). Stays 1-segment GSM-7.

2. **"Include GPS position in the SMS" toggle directly in Settings → Emergency mode**. Before: only `EmergencySetupScreen` exposed this toggle. v1.14.5: direct `ToggleRow` accessible in the main `SettingsScreen`. On switching OFF→ON, requests `ACCESS_FINE_LOCATION` at runtime immediately via `rememberLauncherForActivityResult`. **Audit SEC-1 fix**: if the user denies the permission, the callback automatically does a `revert` to `includeLocation = false` in DataStore + error snackbar "Permission denied — GPS inclusion disabled". Mirror pattern of `revertCallBehaviorIfPermissionRevoked` (v1.10/v1.14.1) — no dirty "toggle ON but SMS without coords" state.

3. **Hotfix for the orphan "I am OK" banner** (user report 2026-05-22). `EmergencyViewModel.disableEmergencyMode()` now ALSO clears `lastTriggeredAt = 0L` + `monotonicLastTriggeredAt = 0L` in addition to `enabled = false` + `emergencyShortcutEnabled = false` + `emergencyCallPoliceEnabled = false`. Full reset in a single atomic DataStore transaction. **Cold-start repair migration extended**: `MainApplication.onCreate` detects `hasOrphanShortcut || hasOrphanTrigger` (cooldown active while the mode is disabled) and repairs automatically → existing users with a dirty state after v1.14.x are cleaned up at the next startup. Idempotent.

4. **"Test without sending" cleanup**: removed from the emergency page at the user's request (it cluttered the UI, the active mode is already visible via the recap section). Removed: button + composable `EmergencyDryRunDialog` + `EmergencyViewModel.previewTrigger/dismissPreview/_previewState/_isPreviewLoading/DryRunPreview/redactPhoneNumber` + constructor param `locationResolver` + 12 strings `emergency_dry_run_*` (FR+EN). Zero orphan references.

5. **Square splash logo** (user report: "the logo is in a circle, it's not nice, my logo is square"). New `drawable/splash_logo.xml` (20% inset wrapping `sms_tech_icon.png`). `windowSplashScreenAnimatedIcon` → `@drawable/splash_logo`. Added `windowSplashScreenIconBackgroundColor` matching `windowSplashScreenBackground` (light + dark) → the Android 12+ circular mask becomes visually invisible, and the branded square logo appears as is.

6. **AboutScreen + files-tech.com site sms-tech.php**: new `Feature` entry "Emergency mode" + new `HelpRecipe` "Use Emergency mode" (4 concise steps). Website: new ⚠️ feature card + permissions row `ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION` (optional, emergency mode). Site version bumped to v1.14.5.

Final 3-axis audit (security / perf+quality / UI+wiring) — 1 MEDIUM finding fixed (SEC-1 revert of the GPS toggle on permission denied), no Critical/High. No crypto / Room / Keystore / threat-model change. No Room migration. `lintVitalRelease` clean, `testReleaseUnitTest` green.

### v1.14.4 — Reaction default `EMOJI_WITH_QUOTE` (user request)

Small UX release: the default format of emoji reaction SMS changes from `READABLE_FR` ("J'ai réagi par ❤️ à : «…»") to `EMOJI_WITH_QUOTE` ("❤️ «…»"). User request 2026-05-22.

**Why**: `EMOJI_WITH_QUOTE` is compact, keeps the context (quote of the original message), and contains no stray explanatory sentence. More natural for today's conversations, where reactions are numerous and the recipient does not need to read "j'ai réagi par" again.

**Scope**: DataStore default for NEW installs only. Existing users keep their choice (persistent DataStore). The Settings → Sending → "Reaction format" picker exposes the 4 options, unchanged.

No crypto / Room / Keystore / threat-model change. No migration.

### v1.14.3 — One-shot migration hotfix: repair dirty `emergencyShortcutEnabled` flag

Urgent PATCH after v1.14.2 — a user who had disabled emergency mode in v1.14.0 or v1.14.1 saw the persistent lock-screen notification reappear at every app launch despite the deactivation. Root cause: before the cascade-disable fix of v1.14.2, the "Disable emergency mode" button flipped ONLY `emergency.enabled = false`, leaving `emergencyShortcutEnabled = true` orphaned in DataStore. The v1.14.2 fix corrects FUTURE deactivations but does not clean up the existing dirty state.

**v1.14.3 fix**: one-shot migration at app cold start in `MainApplication.onCreate`. If `emergency.enabled == false` AND (`emergencyShortcutEnabled == true` OR `emergencyCallPoliceEnabled == true`), force-clears the 2 flags in the same `settings.update` transaction. Idempotent: if the invariant already holds, the `update` does not rewrite. Executed ONCE per cold start, negligible cost (`first()` DataStore snapshot + possibly 1 write).

Timber log on repair for the audit trail. No Room migration, no crypto change.

### v1.14.2 — CRITICAL hotfix: 3 paths of accidental emergency SMS triggering closed

**URGENT HOTFIX** after v1.14.1 for a critical bug reported by a user on 2026-05-22: "lots of MMS sent without doing anything, emergency mode disabled". The investigation identified **3 independent paths** of accidental triggering of the emergency SMS to SafetyCall contacts. All closed in v1.14.2.

**3 critical fixes**:

1. **`EmergencyHoldButton` interpreted scroll gestures as a hold-3s**. v1.14.1 added `Modifier.verticalScroll(rememberScrollState())` on the EmergencyScreen page. When the user scrolled vertically across the big EMERGENCY button, the pointerInput intercepted the DOWN event, set `isHolding = true`, and the `LaunchedEffect(isHolding) { delay(3000) }` fired the SMS trigger. The parent scroll modifier then took visual control (scrolled the page), but the logical hold had already started. A slow scroll ≥ 3 s triggered the SMS send to the contacts.

   **Fix**: added drag detection via `viewConfiguration.touchSlop`. If the pointer moves more than the slop (~24 dp), `isHolding = false` immediately + drains the remaining pointer events until UP so the hold does not re-fire on the same gesture. Code in `EmergencyHoldButton.kt:121-167`.

2. **EMERGENCY quick action removed from the persistent lock-screen notification**. The notification posted by `EmergencyShortcutNotifier` had 3 quick actions: EMERGENCY + 112 + 17 (police, opt-in). Tapping EMERGENCY = `ACTION_TRIGGER_EMERGENCY` received by `EmergencyShortcutReceiver.handleTrigger`, which called `TriggerEmergencyUseCase` → SMS to the contacts. A mistap (pocket tap, dismiss mistaken for an action, confusion with a body tap) = SMS broadcast. Android notification actions are single-tap by design — it is impossible to put an anti-pocket-dial hold-3s on them.

   **Fix**: the EMERGENCY quick action is **removed** from the lock-screen notification. To trigger EMERGENCY from the lock screen, the user now taps the **body** of the notification → `setContentIntent` (added in v1.14.1) opens the in-app Emergency page → hold 3 s on the big EMERGENCY button (itself protected by fix #1 above). Three deliberate gestures instead of one mistap. The 112 and 17 (French Police, opt-in) quick actions remain — they use `ACTION_DIAL` (dialer: the user confirms in the dialer, no auto-call).

3. **`disableEmergencyMode()` did not clear the notification shortcut**. v1.14.1 added the "Disable emergency mode" button on EmergencyScreen. It flipped `emergency.enabled = false` BUT left `emergencyShortcutEnabled = true`. Consequence: the persistent lock-screen notification reappeared at every app launch (MainApplication combine flow). Confused user, taps the notification (often the EMERGENCY quick action BEFORE fix #2), SMS sent.

   **Fix**: `disableEmergencyMode()` now sets `emergency.enabled = false`, `emergencyShortcutEnabled = false`, AND `emergencyCallPoliceEnabled = false` in the same DataStore transaction. Complete deactivation in a single click, with no residual active setting. `EmergencyViewModel.kt:188-203`.

**Threat model corrected**:

- The hold-3s of `EmergencyHoldButton` is now a real anti-pocket-dial guard (not only against an accidental tap — also against drag/scroll, which was an open path in v1.14.1).
- The lock-screen notification no longer allows triggering the SMS send to the contacts in 1 tap. EMERGENCY triggering is gated behind navigation to the in-app page + hold-3s = 3 deliberate gestures.
- The "Disable" function is atomic: 1 confirm dialog + tap → all emergency flags + shortcut off. No residual setting can make the notification pop up again.

**Verdict**: no crypto / DB / extended threat-model change. Purely defensive hotfix on 3 unintentional triggering paths.

### v1.14.1 — Emergency screen full-page redesign + 15 SAMU + 18 Pompiers + "Call a relative" + Disable mode + tap-notif-opens-page + 5 audit fixes

PATCH release after v1.14.0 responding to user feedback asking for the emergency mode page to be "clearer, with all actions visible, without any fiddling". All emergency actions are now grouped on a single screen with big colored buttons.

**4 topics delivered**:

1. **EmergencyScreen full-page redesign** — 3 clear sections: "Call directly" (5 tiles), "Send an SOS to relatives" (preview + hold-3s SMS), "Other actions" (Test / Disable). Vertical scroll for small screens. All emergency actions on the same page, no more hidden navigation.

2. **Complete French numbers — 4 direct-call tiles + 1 relatives tile**:
   - **112** (European SOS, BrandDanger red)
   - **15** (SAMU, teal `#00796B`)
   - **17** (Police, navy `#1565C0`)
   - **18** (Fire brigade, orange `#E65100`)
   - **★ Call a relative** (primary brand-blue, if ≥1 SafetyCall contact) — if 1 contact, direct call; if ≥2, picker dialog
   - All colors WCAG AA ≥ 4.5:1 vs white text.
   - **Tap = direct call** (`ACTION_CALL`) without going through the dialer. Automatic fallback to the dialer if CALL_PHONE is denied.

3. **"Disable emergency mode" button** — on the page itself, with a confirmation dialog. Sets `emergency.enabled = false` in DataStore. Immediate effect: EMERGENCY button greyed out, lock-screen notification cancelled, Settings sections show "disabled". Can be re-enabled from Settings or by re-running setup. PanicDecoy already gated upstream (see v1.10.0 SEC-1).

4. **Tap on the persistent notification → opens the in-app page** — `setContentIntent` added on the NotificationCompat.Builder with a `getActivity` PendingIntent to MainActivity, action `ACTION_OPEN_EMERGENCY`. `MainActivity.handleSharedIntent` routes → `pendingNav.set(Pending(openEmergency = true))`. `AppRoot.LaunchedEffect` consumes → `nav.navigate(Emergency)`. Preserves the PanicDecoy guard: if decoy is active, the pending is held for 30s without a push (TTL `PENDING_TTL_MS`). Normal resumption if the user leaves the decoy before expiry.

#### Security — `EmergencyCallHelper` whitelist widened

- `ALLOWED_NUMBERS = setOf("15", "17", "18", "112")` (extended from 2 to 4).
- New method `placeTrustedContactCall(context, phoneNumber)` WITHOUT a whitelist by design (the number comes from the user-configured SafetyCall DataStore, not from an intent-extra source). Internal refactor: private `executeCall(...)` shared between `placeCall` (strict whitelist) and `placeTrustedContactCall` (trusted contact).
- The UI routes SafetyCall contacts via `viewModel.safetyCallContacts` (StateFlow → private DataStore); no Intent extra path → `placeTrustedContactCall`. Verified by audit.

**Final multi-axis audit** (security + perf + quality + wiring + coherence + vulnerabilities) — 2 MEDIUM + 5 LOW findings, all fixed:

- **MEDIUM SEC-1** — `safetyCallContacts.collectAsStateWithLifecycle()` hoisted to the top of the EmergencyScreen Composable (vs the conditional Scaffold body) to respect the Compose hook position rules. Removed the dead double `.let { _ -> }` that shadowed the previous `state`.
- **MEDIUM COH-1** — Setting `emergencyCallBehavior` (DIALER_ONLY / HOLD_3S_DIRECT_CALL added in v1.14.0) became orphaned with the v1.14.1 redesign (the page always uses direct call with fallback). Cleanup: removed `EmergencyCallBehaviorPickerDialog` + `EmergencyBehaviorRadioRow` from SettingsScreen + removed the `EmergencyViewModel.callBehavior` StateFlow + removed `revertCallBehaviorIfPermissionRevoked()` + removed the `DryRunPreview.callBehavior` field. The DataStore key `emergencyCallBehavior` is PRESERVED for backward compat (safe downgrade) but is no longer consumed by the UI.
- **LOW SEC-2** — Explanatory comment added on the `ACTION_OPEN_EMERGENCY` separation (handled by MainActivity, not by EmergencyShortcutReceiver) for future maintenance.
- **LOW COH-2** — Strings `settings_emergency_call_police_title/desc` FR+EN updated to clarify that the toggle now only controls the 3rd action of the lock-screen notification (the in-app 17 button is always visible in v1.14.1).
- **LOW PERF-1+PERF-2** — `callPhonePermLauncher` hoisted to the top of the Composable. `callPhoneGranted` read on every recomposition (cheap), the capturer recomposes on return ON_RESUME → status up to date.
- **LOW UI-1** — Orphan string `emergency_call_close_no_contacts` removed FR + EN (the "Call a relative" button is conditioned by `if (safetyContacts.isNotEmpty())`, no "disabled" state shown).

#### Threat model — clarifications

- Direct call via the CALL_PHONE permission: pocket-dial risk mitigated by (a) explicit navigation to EmergencyScreen + (b) tiles laid out in a column (not a single accidental tap on a forgotten tap target) + (c) the Disable-mode confirm to cancel. Acceptable for the requested feature.
- The "Call a relative" picker only shows SafetyCall contacts (already configured by the user, authorized by default). No access to native Android Contacts → no `READ_CONTACTS` leak.
- The "Disable emergency mode" button: reversible action (re-enable in Settings). No destructive effect on the contacts or the template. PanicDecoy already gated by `AppRoot` upstream.
- `ACTION_OPEN_EMERGENCY`: constant action, FLAG_IMMUTABLE PendingIntent, explicitly targeting `MainActivity::class.java`. Not exposed in a manifest intent-filter → no exfiltration possible by another app.

### v1.14.0 — Vault auto-lock + Emergency hold-3s call (CALL_PHONE) + kill-switch "I am OK" + dry-run preview + 4 audit fixes

MINOR release capping SMS Tech's emergency mode before the release of a dedicated app, **SOS Tech** (Files Tech no. 8), for the extended features (voice, siren, live GPS). Four topics delivered:

1. **Vault auto-lock on explicit exit from VaultScreen**. Tap back arrow / system back / cancel PIN dialog / biometric refused → immediate `VaultManager.lock()`. Preserves the v1.13.1 fix on ThreadScreen ↔ VaultScreen navigation: the Singleton `sessionUnlocked` AtomicBoolean persists while a vault conversation is opened and closed again (the VaultScreen composable stays in the back stack), but locks as soon as one really leaves. Coexists with the existing `lockVaultOnLeave` (lock when the process goes to background) — both are idempotent and orthogonal. New helper `VaultViewModel.lockVaultSession()`.

2. **112 / 17 buttons — 2 behavior levels**:
   - **DIALER_ONLY** (default, v1.12–v1.13 behavior): `ACTION_DIAL`, the user confirms in the pre-filled dialer. Zero permission required.
   - **HOLD_3S_DIRECT_CALL** (opt-in): holding the button for 3 seconds → direct call via `ACTION_CALL` + runtime permission `CALL_PHONE`. Anti-pocket-dial through a mandatory hold (visible progress ring). No LEVEL 2 (single tap → direct call), on purpose: pocket-dial risk too high for a marginal gain.

   The whole call path goes through `EmergencyCallHelper` (new) with a **strict number whitelist**: only `"112"` and `"17"` are accepted, any other number returns `INVALID_NUMBER` without any Intent being emitted. Eliminates any possibility of redirection to a premium number via a forged Intent extra. `EmergencyShortcutReceiver.handleDial` (lock-screen actions) delegated to the same helper for consistency; on the lock screen we keep `openDialer` (never `placeCall`) because an accidental tap is probabilistically more likely on a locked screen.

3. **"I am OK" kill-switch**. New `IAmOkUseCase` which resets `lastTriggeredAt = 0L` + (opt-in `sendIAmOkSmsOnReset`, default `true`) sends a short SMS "I am OK, false alarm" to the SafetyCall contacts. `PanicDecoy` guard (anti-tampering: an attacker cannot erase the UI trace of the emergency trigger). On `ConversationsScreen`, an `IAmOkBanner` banner appears for 30 minutes after the trigger and offers a confirmation dialog. Differentiated snackbar on partial success (`sent=0, failed=N` → explicit error message, the user knows the contacts were NOT informed).

4. **"Test without sending"**. Button in `EmergencyScreen` that runs a dry run: GPS resolution, SMS body rendering, contact count, number masking (`+33 … 78` style). **No side effect** — no SMS sent, no DataStore write, no `lastTriggeredAt` mutation. Loader spinner during the ~8s of GPS resolution, double-tap guard. Shows the active call behavior + a red warning if emergency mode is disabled.

A HIGH-PRECISION final audit surfaced **3 blocking MEDIUM** findings, all fixed before tag:

- **MEDIUM SEC-1** — On `ON_RESUME` of `EmergencyScreen`, re-check of the `CALL_PHONE` permission. If the user revoked the permission via Android Settings in the meantime, `emergencyCallBehavior` is auto-reverted to `DIALER_ONLY` in DataStore. Without this check, the setting became orphaned (placeCall returned `PERMISSION_DENIED` on every tap, silent error snackbar; in an emergency the user believed the app was broken).
- **MEDIUM SEC-2** — Snackbar `IAmOkDoneWithSms(sent, failed)` now distinguishes `sent > 0` (success) from `sent == 0 && failed > 0` (error, contacts not informed despite the reset). New string `emergency_i_am_ok_send_failed` FR+EN. The user clearly sees when the reassurance SMS could not go out.
- **MEDIUM PERF-1** — Double-tap guard + UI spinner during the dry-run GPS resolution (up to 8s). `_isPreviewLoading: StateFlow<Boolean>` exposed to `EmergencyScreen`, which disables the `TextButton` and shows `CircularProgressIndicator` + the label "Resolving GPS…". Without it, the button seemed unresponsive and the user could tap again, creating N parallel coroutines.

A **LOW ARCH-1** was also fixed: redundant nested double `if (emergency.enabled)` in `SettingsScreen` (cosmetic, removed).

#### Security — checks verified with no finding

- `EmergencyCallHelper` strict whitelist on `openDialer` AND `placeCall`. No extra-intent path that would inject an arbitrary number.
- `EmergencyShortcutReceiver` (`exported=false`) only passes the hardcoded constants `EMERGENCY_NUMBER_EU = "112"` and `EMERGENCY_NUMBER_POLICE_FR = "17"` to the helper.
- Vault auto-lock covers all explicit exit paths (top-bar back, system back, PIN cancel, biometric refused). No `DisposableEffect` locks on destruction (preserves the v1.13.1 fix).
- Anti-pocket-dial hold-3s: no-op `Button(onClick = {})` + `pointerInput` that only triggers on a complete hold. Clean cancellation on Activity rotation via the `LaunchedEffect(isHolding)` key.
- `IAmOkUseCase`: PanicDecoy guard first, opt-in `sendIAmOkSmsOnReset` strictly honored.
- Dry run: zero side effect confirmed by audit (no SendSms, no DataStore write, no Timber log of the body in clear text).

#### Manifest

New permission `<uses-permission android:name="android.permission.CALL_PHONE" />`. Requested at RUNTIME only when the user opts in to `HOLD_3S_DIRECT_CALL` in Settings. Denial → automatic fallback to `DIALER_ONLY`. No automatic call: hold-3s is the anti-pocket-dial guard.

#### Strategic note — emergency mode cap in SMS Tech

v1.14.0 is deliberately the **upper cap** of emergency mode in SMS Tech. The extended features (Vosk voice mode, siren + flash, live GPS sharing, encrypted audio recording, webhook broadcast) are delegated to a new app, **SOS Tech** (Files Tech no. 8), which will be scaffolded separately. The shared code (`LocationResolver`, `EmergencyConfig`, `SafetyCallContact`, `PasswordKdf`, `Outcome`) will be progressively factored into an AAR module `files-tech-emergency-core` consumed by SMS Tech and SOS Tech. Rationale: for 95 % of SMS users, these features would mean useless weight (permanent foreground service, Vosk model ~50 MB, aggressive permissions BACKGROUND_LOCATION / continuous RECORD_AUDIO).

### v1.13.1 — Hotfix UX on top of v1.13.0

PATCH release fixing three user-reported regressions after v1.13.0:

- **Long-press → ActionsSheet legacy** restored on both `ConversationsScreen` and `VaultScreen`. v1.13.0 had collapsed the long-press behaviour into "enter multi-selection mode" — discoverability of the legacy quick actions (Move to vault / Move out of vault / Block / Delete) was lost. v1.13.1 restores the ModalBottomSheet on long-press AND adds a new item "Sélectionner plusieurs" (Select multiple) which enters multi-selection mode for users who want batch ops.
- **Vault PIN re-prompt bug** on return from `ThreadScreen` to `VaultScreen`. The `vaultPinPassed` Compose `remember` local state was reset on re-composition, causing the PIN dialog to briefly re-flash. Fix: initialise `vaultPinPassed` (and `unlocked`) from `VaultManager.sessionUnlocked` (Singleton AtomicBoolean) which persists for the app session. PIN re-entry now only happens after auto-lock / panic / process kill — the expected behaviour.
- **Avatar palette: slate + gunmetal removed**. v1.13.0 kept these two blue-grey shades but on some displays they could appear greenish (G ≈ B in RGB). v1.13.1 ships **9 strictly-blue stops** : 4 royal/electric/cobalt/brand-blue + 3 sky/periwinkle/azure + navy + indigo-deep (Material Indigo 600→900). All WCAG AA ≥ 4.5:1 vs white confirmed.

No threat-model change, no DB / SQLCipher / Keystore change, no schema change. `adb install -r` non-destructive.

### v1.13.0 — Multi-selection bulk vault + distinct vault PIN/password (second-factor) + biometric vault unlock + avatar palette strict-blue + 6 audit fixes

MINOR release adding two requested features: **multi-selection bulk move into / out of the vault** (both lists), and a **dedicated PIN-or-password second-factor for the vault** (separate from the app PIN, with biometric fallback). Plus a palette cleanup removing the last three green-tinted avatar shades. No DB / SQLCipher / Keystore schema changes — `adb install -r` non-destructive.

A pre-release final audit run twice (consolidation pass) surfaced **2 HIGH + 4 MEDIUM + 2 LOW** findings, all fixed before tag :

- **HIGH SEC-1** — `PinEntryDialog` now sets `pin = ""` BEFORE the `scope.launch { onVerify(...) }` coroutine. Without this, the `String pin` lingered in JVM heap during the ~100 ms PBKDF2 derivation, exposed to heap-dump forensics (a vector documented as "out of scope" in the threat model, but the fix is one line). The `snapshot: CharArray` is always wiped in `finally`, including under `CancellationException` (rotation Activity).
- **HIGH SEC-2** — `VaultViewModel.vaultPinRequired` flow now calls `vaultPin.isVaultPinConfigured()` inside `withContext(io) { ... }`. The function performs a `DataStore.first()` which is technically I/O; on cold-start with a sluggish DataStore it could have blocked the Main thread for tens of milliseconds. Routed through `@IoDispatcher` injected via Hilt.
- **MEDIUM SEC-4** — The `selectedIds: MutableStateFlow<Set<Long>>` purge on PanicDecoy entry was moved out of the `combine { ... }` lambda (anti-pattern — mutating a flow from inside its own transform) into a dedicated `init { viewModelScope.launch { appLock.state.collect { ... } } }`. Cleaner separation of concerns ; the `combine` keeps a defensive `effectiveSelection = emptySet()` fallback regardless.
- **MEDIUM UX-2** — The "Distinct vault PIN" toggle and its "Change vault PIN" row in `SettingsScreen` are now wrapped in `if (!isPanicDecoy) { ... }`. Without this, a coerced PanicDecoy session would still see the toggle in Settings — leaking the existence of a configured vault (the top-bar lock icon and navigation to Vault are already hidden in decoy; this completes the cross-screen consistency).
- **MEDIUM NEW-5** — `VaultPinManager.setVaultPin` now writes the `settings.vaultPinEnabled = true` flag **inside** the `try { hash; storeHash; flag }` block, immediately after `securityStore.setVaultPinHash()`. Symmetrically, `clearVaultPin` flips the flag BEFORE removing the hash. Without this ordering, a rare DataStore IOException between hash-write and flag-write would leave the vault in an "orphan hash, flag=false" state where `isVaultPinConfigured()` would detect the inconsistency and gracefully treat as disabled — but the inverse (flag=true with no hash) would lock the user out.
- **LOW NEW-1** — Removed orphan string `settings_vault_pin_confirm_subtitle` (declared FR + EN, used nowhere). APK cleanup.

#### Multi-selection bulk vault (Topic A)

`ConversationsScreen` and `VaultScreen` both expose a Gmail-style multi-selection mode: long-press a row → enters selection mode, tap toggles inclusion, top-bar swaps to a contextual title (count) + bulk action (`Move to vault` / `Move out of vault`) + a Cancel (X) icon. System back exits selection mode (BackHandler). The bulk action loops through `requestMoveToVault(id, intoVault)` per ID — the existing PanicDecoy + Locked guards are re-evaluated on each call (defensive, no batch transaction bypass). A single snackbar is emitted with the success count (plurals FR + EN). The `selectedIds` is purged on PanicDecoy entry (audit SEC-4).

#### Distinct vault PIN/password + biometric (Topic B)

New `VaultPinManager` Singleton:
- **Crypto**: PBKDF2-HMAC-SHA512, 16-byte salt + ≥ 210 000 iterations (calibrated). Hash stored in `SecurityStore` under `vault.salt` / `vault.hash` / `vault.iters` — totally separated from `pin.*` (app) and `panic.*` (decoy). Comparison via `MessageDigest.isEqual` (constant-time).
- **Threat model**: defends against "I shoulder-surfed your app PIN, now I'll open your vault" and "I lent you my app PIN to retrieve a SMS, but my vault is private". The second-factor is a UI / domain gate — at-rest crypto is still the single SQLCipher master key from v1.0.
- **Out of scope**: forensics with Keystore + decrypted SQLCipher key. The vault PIN does NOT add a second envelope.
- **Fallback**: if the device has biometrics, the entry dialog also exposes a "Use biometrics" button. Either path (PIN/pass OR biometric) unlocks the vault. When the PIN-or-biometric succeeds, the app's regular biometric prompt (gated on `lockMode = BIOMETRIC`) is skipped — no double second-factor.
- **Reset**: from Settings → Security toggle (requires app already unlocked, so a user who forgot the vault PIN but knows the app PIN can disable & reconfigure). No recovery if both are forgotten — panic-code unlock remains the escape hatch into the decoy session (vault stays sealed but rest of app usable).

New `PinEntryDialog` reusable composable (kept under `ui/components/`) with `PasswordVisualTransformation` + `KeyboardType.Password` (alphanumeric — user picks PIN or passphrase), optional biometric button slot, suspend `(CharArray) -> Boolean` callback contract, single error string `pin_error_invalid` (no leak between "no PIN set" and "wrong PIN").

#### Avatar palette strict-blue (Topic 0)

The 14-shade palette of v1.12.0 was reduced to **11 strict-blue stops** by removing the 3 green-leaning entries (`teal`, `dark teal`, `cyan`). The remaining 11 are pure blue / cobalt / sky / periwinkle / azure / navy / cool-steel / slate / gunmetal — all WCAG AA ≥ 4.5:1 against white initials. Deterministic hash distribution unchanged ; existing users will see some contacts shift to a new slot (size 14 → 11), which is acceptable for a UX refinement.

### v1.12.0 — Avatar palette (blue family) + ComposeScreen contact name fix + ThreadScreen vault overflow + Emergency lock-screen shortcut (112 / 17) + 3 audit fixes

MINOR release with UX-focused polish on the Emergency mode (accessibility on lock screen + voice-grade emergency call buttons) and on the conversation list (all-blue avatar palette WCAG AA, contact name now resolved at compose time). No DB / vault / Keystore changes — `adb install -r` non-destructive.

A pre-release final audit (3-axes + coherence) surfaced **2 HIGH and 1 MEDIUM blockers**, all fixed before tag :

- **HIGH S1** — `MainApplication.kt` emergency-shortcut observation now `combine(settings.flow, appLock.state)` and cancels the persistent lock-screen notification whenever `LockState.PanicDecoy` becomes active. Without this guard, an attacker who coerces a panic-PIN unlock would still see the "URGENCE / 112 / 17" notification on the lock screen, learning that SMS Tech has an Emergency mode configured (info leak + lateral attack vector — the URGENCE action itself is already gated by `TriggerEmergencyUseCase`'s PanicDecoy check, but the *presence* of the shortcut was leaking).
- **HIGH S2** — `SettingsScreen` Toggle "Appel police FR (17)" is now gated behind `if (state.security.emergencyShortcutEnabled)` so it cannot be configured as an orphan. Without the shortcut enabled, the toggle had no observable effect (the in-app EmergencyScreen 17 button reads the same flag, so it stayed visible, but the lock-screen notification — the only consumer that visibly differs — wasn't posted) — confusing UX + spurious DataStore writes.
- **MEDIUM U2** — Emergency 112 and 17 buttons in `EmergencyScreen` now catch `ActivityNotFoundException` (no dialer installed — rare but possible on stripped AOSP builds and corporate MDM profiles) and surface a snackbar `emergency_shortcut_no_app_to_dial`. Without feedback, the user would believe the call is in progress while nothing happens — a silent failure in an emergency context. Also added `FLAG_ACTIVITY_NEW_TASK` defensively (currently invoked from Activity context, so non-blocking, but matches the BroadcastReceiver path which strictly requires it).

#### Avatar palette refactor (Topic 1)

The v1.11.0 palette mixed 5 reds + 1 plum with blues and greens. Red is visually anxiogenic in a messaging context and reserved by Files Tech for destructive/danger states (BrandDanger). v1.12.0 ships a 14-shade pure blue / teal / navy / cyan palette, every stop verified ≥ 4.5:1 against `Color.White` for initials legibility (WCAG AA). The light teals and cyans that didn't meet contrast were darkened; the hue family is uniform but the spread across royal/electric/navy/teal/cyan keeps avatars distinguishable in long conversation lists.

#### ComposeScreen contact name fix (Topic 7)

`ConversationRepositoryImpl.findOrCreate(addresses)` used to insert new conversations with `displayName = null`, leaving the conversation labelled by raw phone number until the next system contact sync. With single-recipient compose, we now :

1. Look up `ContactRepository.lookupByPhone(addresses[0].raw)` **outside the transaction** (hot lookup, no DB write).
2. Sanitise the result through `stripInvisibleChars()` + `trim()` to defeat homoglyph / bidi / RLO smuggling in the contact's name field (a malicious vCard import could otherwise inject a `‮` override).
3. Pass the resolved name to `insertOrIgnoreConversation` so the new row is labelled correctly on the first frame.
4. **Back-fill an existing conversation** whose `displayName` is null/blank — covers the legacy data created by v1.11.x before this fix.

Single-recipient only (group MMS keeps `null` and lets the UI compose participants).

#### ThreadScreen "Move to vault" overflow (Topic 2)

Overflow menu in `ThreadActionsMenu` now exposes "Move to vault" / "Move out of vault" with `Lock` / `LockOpen` icons. The action :

- Hidden in PanicDecoy (UI layer guard).
- Refused by `VaultManager.requestMoveToVault` in PanicDecoy + Locked (domain layer guard).
- Snackbar distinguishes Locked (`error_session_locked`) vs generic failure (`snack_generic_error`).
- No data-layer-only feature flag — the row simply doesn't render in PanicDecoy, defeating the snoop-the-menu sidechannel.

#### Emergency lock-screen shortcut (Topic "voice vigilance")

The Emergency mode in v1.10.0/v1.11.0 required unlock + nav into Settings to reach. In a real emergency that's too many taps. v1.12.0 adds :

1. **`EmergencyShortcutReceiver`** (`exported = false`) — BroadcastReceiver with 3 actions :
   - `ACTION_TRIGGER_EMERGENCY` → delegates to `TriggerEmergencyUseCase` (PanicDecoy-guarded). Uses `goAsync()` + `ApplicationScope`, so the SMS send + location resolve continues even if the notif is dismissed.
   - `ACTION_DIAL_112` and `ACTION_DIAL_POLICE` → `ACTION_DIAL` intents pre-filled with 112 (EU) or 17 (FR). `ACTION_DIAL` opens the dialer with the number pre-typed but DOES NOT call automatically — the user confirms by tapping the green button. This avoids requiring `CALL_PHONE` runtime permission AND prevents pocket-dial of emergency services.
2. **`EmergencyShortcutNotifier`** — persistent ongoing notification on `CHANNEL_EMERGENCY_SHORTCUT` (`IMPORTANCE_LOW` to avoid heads-up / sound / vibration), `VISIBILITY_PUBLIC` so the actions are tappable from the lock screen. Up to 3 actions (URGENCE + 112 + 17 if police opt-in enabled).
3. **`MainApplication`** observes `(emergencyShortcutEnabled, emergencyCallPoliceEnabled)` paired with `appLock.state` (audit fix S1) — posts the notification only when the shortcut is enabled AND the session is not PanicDecoy.
4. **`BootReceiver`** re-posts the notification after device reboot (with a 3 s `withTimeoutOrNull` cap on the DataStore read to keep the boot path bounded).
5. **`EmergencyScreen`** also exposes the 112 and 17 buttons (in-app counterpart). Both surface a snackbar on `ActivityNotFoundException` (audit fix U2).

The numbers `112` and `17` are hard-coded in companion objects — they cannot be hijacked by intent extras to dial an arbitrary number. The "URGENCE" action piggybacks on the existing PanicDecoy / wall-clock-monotonic / single-flight defences from v1.10.0 SEC-11.

### v1.11.0 — Vault polish + Anti-smishing + Appearance + 7 audit fixes

MINOR release strengthening the Vault feature (3 gaps closed), introducing a 100% offline anti-smishing detector, and per-conversation custom appearance (WCAG-safe bubble color + custom avatar).

A pre-release audit (3 axes + deep-dive security final + architecture coherence + i18n) surfaced **7 HIGH and 14 MEDIUM** findings, all fixed before tag :

- **HIGH SEC-V1** — `MessageDao.search` joins `conversations` with an `in_vault = 0` filter; `ConversationRepositoryImpl.findMessageById` guards on `inVault`. Without these 2 fixes, FTS search exposed the body of vault messages (IDOR: searching for `1mpots scam` returned the vault messages).
- **HIGH SEC-V2** — `VaultManager.sessionUnlocked` migrated from `@Volatile Boolean` → `AtomicBoolean`. Correct semantics for a flag shared between IO/UI coroutines (no tearing possible). `PanicDecoy` double-check after suspension in `requestMoveToVault` (race window closed).
- **HIGH SEC-V3** — `AppearanceDialog` makes `pickedAvatarUri` conditional on the success of `takePersistableUriPermission`. Without it, a URI revoked between pick and take silently polluted Room (Coil failed at render).
- **HIGH P1** — `SmishingDetector.analyze()` moved to the IO dispatcher in `ThreadViewModel.recomputeSmishingVerdicts`, exposed via `Map<Long, List<SmishingReason>>` in state. No more 600 ms to 3 s jank on a 200-message thread on low-end hardware (Cortex-A53).
- **HIGH U1** — `ColorChip` TalkBack accessibility: `contentDescription` + `role = RadioButton` + `selected` semantics + 9 color names FR/EN. `FlowRow` to adapt to small 320 dp screens.
- **HIGH C4** — `ForwardMessageSheet` passes `customUri = conv.avatarUri` to the `Avatar` composable (consistency with ConversationRow — otherwise the custom avatar was invisible in the share sheet).
- **HIGH S1** — `VaultScreen.LaunchedEffect(Unit)` (instead of `lockMode` as key): prevents a double stacked `BiometricPrompt` on some OEMs if lockMode changes while the prompt is in flight.

#### Vault polish (3 gaps closed)

1. **Notifications gated on `inVault`** — `IncomingMessageNotifier.notifyIncoming` injects `ConversationDao` and returns early if the conversation is in the vault. SMS + MMS covered (a single point). No notification, no sound, no system badge leaks for vault conversations.
2. **UI move-in/move-out** — Long-press on a conversation in `ConversationsScreen` → ActionsSheet with "Move to vault" (hidden in PanicDecoy). Long-press on a conversation in `VaultScreen` → "Move out of vault". Strings `vault_move_in/out` (orphaned until then) wired up. Snackbar feedback (brand blue for success / red for error).
3. **BiometricPrompt on entry** — If `lockMode = BIOMETRIC`, prompt on entering VaultScreen as a second factor. If refused/cancelled → `onBack()`. If biometrics unavailable → graceful fallback to direct entry.
4. **New `VaultManager.requestMoveToVault(id, intoVault)`** — wrapper for calls outside VaultScreen (list long-press, future Thread overflow). Refuses `PanicDecoy` + `Locked`, auto-`markUnlocked` otherwise. `PanicDecoy` double-check after suspension (SEC-V2).

#### Local anti-smishing (Topic 3)

100% offline detector, no model, no cloud. 4 composable heuristics:
- **URL shortener** (17 hosts: bit.ly, t.co, tinyurl, rebrand.ly…)
- **Urgency words** (~40 FR + EN patterns: urgent, compte bloqué, colis bloqué, click here, impots impayés…)
- **FR premium-rate numbers** (regex with non-digit lookaround: `32xx`-`36xx`, `0899xxxxxx`, `081x/088x/089x`)
- **Typosquatting of official FR domains** (Levenshtein bounded ≤ 2 over 28 official hosts: impots.gouv.fr, ameli.fr, banks, carriers, paypal…)

Default threshold = 2 positive heuristics (against false positives). Cap 1000c on the inspected body. Cap of 20 URLs + 30 domains inspected per body (anti-DoS on Levenshtein × matches). Clickable red banner in the incoming SMS bubble → "Why" dialog listing the localized reasons. Settings toggle, opt-in by default, can be disabled.

20 regression-guard tests: genuine cases (colissimo phishing, fake impots, Amazon scam EN) + official FR false positives (bank, impots, ameli) + edges (empty, body > 1000c, symmetric Levenshtein).

#### Per-conversation appearance (Topic 5)

Strictly additive Room migration v6→v7: `conversations.bubble_color_argb INTEGER?` + `avatar_uri TEXT?`. Downgrade safe. `ALTER TABLE ADD COLUMN` × 2 wrapped atomically by Room (SQLCipher WAL rollback if the process is killed).

UI: "Appearance" dialog from the ThreadScreen overflow. `BubbleColorPalette` palette of 8 WCAG-safe colors against white text (default BRAND_BLUE = reset to null). Avatar picker via `PickVisualMedia` Android 13+ → `content://` URI persisted via `takePersistableUriPermission` (the old URI is released before taking the new one, against grant accumulation). `content://` scheme whitelisted on the repository side (defense in depth against path traversal).

Auto-generated avatar palette extended 7 → 14 shades (blue/teal core + plum transition + 5 red/garnet/burgundy shades), all WCAG AA against white, deterministic hash per contact.

#### Refactors + MEDIUM audit fixes (14)

- `IncomingMessageNotifier`: removed the `Timber.d` "conv vault suppressed" (anti-correlation on beta builds)
- `SmishingDetector`: cap `MAX_URL_MATCHES=20` + `MAX_DOMAIN_MATCHES=30` on `findAll`
- `ConversationRepositoryImpl.setAppearance`: `content://` scheme whitelist
- `AppearanceDialog`: old URI released before taking the new one (anti-accumulation)
- `ThreadViewModel.recomputeSmishingVerdicts`: `smishingJob?.cancel()` before re-launch (anti-race on rapid toggling)
- `Migrations.MIGRATION_6_7`: explicit KDoc on the non-idempotence of `ALTER TABLE ADD COLUMN` (Room WAL transactionality)
- `EmergencyArmedRecap` added to `SettingsScreen` (mirror of `SafetyCallArmedRecap`, "Armed" chip + 3 lines + 2 buttons)
- `AboutScreen` cleaned up: ML Kit + Google Messages references removed (post-v1.7.0 FLOSS compliance + editorial consistency)
- FR tone: 4 leftover tutoiement strings from v1.9.0 → vouvoiement (project i18n consistency)
- `smishing_reason_typosquatting`: HTML `<i>` tags removed (not rendered by Compose Text) → typographic quotation marks

**Deferred to v1.12.0**: Thread overflow "Move to vault", separate PIN/passphrase for the vault (second crypto hash), multi-selection of conversations for the vault, sharing options from the vault, replying from the vault.

Cert SHA-256 stable `b09a9511…687d`. No NonFreeDep dependency added.

### v1.10.0 — Emergency mode + clock-monotonic hardening + refactors

MINOR release introducing the **Emergency mode** feature (opt-in active hold-3s SMS button that sends a personalised template + GPS location URL to the user's safety-call contacts) plus a monotonic-clock complementary check (SEC-11) on the existing Safety call deadman.

A pre-release audit (3 axes + deep dive security + architecture coherence + i18n) surfaced 3 HIGH, 8 MEDIUM and 2 LOW findings, all fixed before tag :

- **HIGH SEC-1** — `AppRoot` navigation guard now pops both `Emergency` and `EmergencySetup` routes when `PanicDecoy` activates ; `SettingsScreen` hides both "Mode urgence" and "Safety call" sections when `isPanicDecoy = true`. Without this, an attacker in a forced-decoy session would see the URGENCE button and learn the feature exists, breaking the "ordinary SMS app" illusion.
- **HIGH SEC-2** — `EmergencyViewModel.trigger()` protected by `AtomicBoolean compareAndSet` in-flight guard. Without it, a panicked double-hold during the ~50–300 ms DataStore-write window could fire two SMS to every contact, confusing the recipients in a stressful moment.
- **HIGH P1** — `LocationResolver.awaitFirstFix` uses `AtomicBoolean resumed` to guarantee single-resume on `suspendCancellableCoroutine`. Without it, near-simultaneous GPS + NETWORK fixes could call `cont.resume` twice → `IllegalStateException: Already resumed` swallowed silently → SMS sent without coordinates despite a valid fix being available.
- **HIGH S1+U1+U2** — `EmergencySetupScreen` now uses `rememberPermissionState(ACCESS_FINE_LOCATION)`, prompts at toggle ON, and shows a persistent red warning if the permission is denied. `EmergencyScreen.MessagePreviewCard` reflects the REAL permission state (not just the user preference) so the previewed SMS body matches what will be sent. Without these, a user activating the switch without granting the permission was building false trust in a security feature.
- **MEDIUM SEC-4** — `EmergencyConfig.isInAntiSpamWindow()` treats a negative monotonic delta (post-reboot before async drift recovery in `MainApplication.onCreate` completes) as "still in cooldown" — fail-safe against a root + reboot + clock-forward attack.
- **MEDIUM SEC-5** — `EmergencyTemplate` body strings switched from `—` (U+2014, em dash) to `-` (ASCII hyphen) and removed `Ù` from `DISCREET`. All three templates now fit in a single GSM-7 segment with the URL Maps appended → no multi-segment risk in weak-radio emergency zones. Guarded by two unit tests in `AuditV1100Test`.
- **MEDIUM SEC-6** — `LocationResolver.awaitFirstFix`'s `SecurityException` catch always calls `cleanup()` before checking the `resumed` flag, ensuring the GPS listener is removed even if NETWORK provider registration failed after GPS already registered.
- **MEDIUM S3** — `EmergencyViewModel.save()` preserves the LIVE `lastTriggeredAt` and `monotonicLastTriggeredAt` from DataStore at the moment of save, not the stale value captured at setup-open. Without this, modifying any setup parameter after a recent trigger would clear the anti-spam cooldown.
- **MEDIUM C1** — `EmergencySetupScreen.SetupCard` aligned on `surfaceContainer` (was `surface`), matching the `SafetyCallSetupScreen.SectionCard` convention.
- **MEDIUM C2** — `EmergencySetupScreen` event collector uses exhaustive `when (event)` instead of `if (event is …)`, so a new `Event` case added later is signalled at compile time.
- **MEDIUM i18n** — 6 FR strings in the Emergency block converted from tutoiement to vouvoiement, aligning with the project-wide FR tone (the templates themselves keep the user's first-person voice).
- **LOW C5** — `K.safetyCallCustomMessage` declaration moved back into the `safetyCall*` block in `SettingsRepository` (was visually orphaned after the `emergency*` block).

#### SEC-11 — clock-monotonic complementary check on Safety call

Independent hardening of the v1.9.0 Safety call : `SafetyCallConfig.lastActivityAt` (wall-clock) is now complemented by `SafetyCallConfig.monotonicLastActivityAt` (snapshot of `SystemClock.elapsedRealtime()` at every reset). `isExpired()` and `isInWarningWindow()` require BOTH clocks to cross `timeoutMs` before triggering. A rooted attacker who advances `Settings.Global.AUTO_TIME=0; date <future>` to force the deadman to fire immediately is now defeated — the monotonic clock continues to track real elapsed time since boot, regardless of wall-clock manipulation.

Drift recovery: on every cold-start, `MainApplication.onCreate` detects if any stored monotonic value exceeds the current `SystemClock.elapsedRealtime()` (consequence of a reboot) and realigns it to the current monotonic. The deadman is effectively extended by the post-reboot uptime — acceptable trade-off given the alternative would be a permanently-locked deadman after every reboot.

Migration v1.9.0 → v1.10.0: configs persisted before this release have `monotonicLastActivityAt = 0L` ; `isExpired()` returns `false` in that case (safety net) until the first reset (`MainActivity.onResume`, "Je vais bien", warning-notif tap, setup save) populates the new field. The first app open after upgrade implicitly re-arms the deadman.

#### Refactors C1 + C4 (cosmetic, no behaviour change)

- C1 — `SafetyCallTriggerService` (data layer) → `TriggerSafetyCallUseCase` (domain/usecase) with `operator invoke()`, aligning with the project's dominant UseCase pattern. Callers updated: `SafetyCallWorker`, `AuditV190Test`.
- C4 — `SafetyCallContactJsonCodec` → `SafetyCallContactCodec` (the format was never JSON, was pipe-separated from day one). Renamed object + file ; DataStore key (`security.safetyCall.contactsJson`) kept unchanged for storage backward-compat.

#### Performance P2

`SettingsScreen.SafetyCallArmedRecap` no longer calls `System.currentTimeMillis()` at every recomposition ; `SettingsViewModel` exposes `safetyCallRemainingMs: StateFlow<Long>` recomputed every 60 s (or whenever `state` changes via `combine`). Granularity sufficient for an hour-level countdown displayed to the user.

Pre-release audit: 17 regression-guard tests in `AuditV1100Test` (clock-forward attack on safety call + emergency, post-reboot drift, v1.9.0 migration fallback, emergency anti-spam, GSM-7 single-segment guarantee, template defaults).

### v1.9.0 — Safety call + compact reaction format + audit hardening

MINOR release introducing the **Safety call** feature (opt-in automatic SMS to 1–4 emergency contacts after a user-configured inactivity timeout, 1 h to 30 days) and a fourth compact reaction format `EMOJI_WITH_QUOTE` (`❤️ «excerpt»`). Disabled by default.

A pre-release audit (3 axes in parallel + architecture coherence + i18n + deep dive security) surfaced 1 CRITICAL, 4 HIGH and 6 MEDIUM findings, all fixed before tag :

- **CRITICAL** — `SafetyCallTriggerService` + `SafetyCallWorker` now check `AppLockManager.LockState.PanicDecoy` and short-circuit before any send. Without that guard, the deadman would have fired SMS to the victim's emergency contacts under coercion, revealing her support network to the attacker. The worker tick (60 min) retries automatically once decoy state is left.
- **HIGH** — `BootReceiver` now reschedules `SafetyCallWorker.schedulePeriodic` on `BOOT_COMPLETED` so a force-stop OEM (Xiaomi / Huawei) doesn't lose the deadman. KEEP policy keeps the call idempotent.
- **HIGH** — `IncomingReactionDecoder.EMOJI_WITH_QUOTE_REGEX` reformulated with negative class `[^»"]{1,200}` (was `.+?` + DOT_MATCHES_ALL) — eliminates catastrophic backtracking on pathological input `❤️ «aaaa...` (no closing guillemet, capped at 400 chars).
- **HIGH** — Strict emoji guard `isLikelyEmojiChar(c)` requires high-surrogate OR `U+2300..U+27BF` OR ZWJ / VS-16 (was `code < 128` which silently swallowed FR messages starting with an accented word — `"été «aperçu»"` was being misinterpreted as a reaction).
- **MEDIUM** — Anti-spoofing nonce (`SafetyCallIntentToken`) on `ACTION_SAFETY_CALL_RESET`. The intent extra `EXTRA_RESET_TOKEN` is validated and consumed mono-shot by `MainActivity` ; a third-party app cannot neutralise the deadman by forging the action.
- **MEDIUM** — `MainActivity.onResume` reset gated on `LockState.Unlocked || Disabled`. `Locked` / `PanicDecoy` no longer reset the timer.
- **MEDIUM** — `SafetyCallTriggerService.disableSafetyCall()` is now called **before** the send loop (preemptive disable). A crash mid-loop no longer causes a double-trigger 60 min later.
- **MEDIUM** — `SafetyCallContactJsonCodec.decode()` filters via `SafetyCallContact.isValid()` (defense in depth against tampered DataStore restore). Encoding strips full C0/C1 range + `|` separator (was only `\n` + `|`).
- **MEDIUM** — Dedicated notification channel `CHANNEL_SAFETY_CALL_WARNING` (was sharing `CHANNEL_INCOMING` with regular SMS) so the user can tune sound / vibration independently.
- **MEDIUM** — `SafetyCallSetupViewModel` snapshot-once from DataStore (`first()` instead of `collect`) — fixes data loss when a concurrent write (`onResume` reset) overrode the in-progress draft.

Logs no longer leak `phoneNumber` to Timber (replaced by index-only identifiers). `SafetyCallTemplate.CUSTOM` re-caps at render to `MAX_CUSTOM_MESSAGE_LENGTH=140` to defend against tampered DataStore values that would otherwise produce a multi-segment surprise.

### v1.8.1 — Reaction wording hybrid (named / anonymous) + decoder dual

PATCH following v1.8.0 field testing — the FR readable reaction format `"J'ai réagi par ❤️ à : «…»"` was ambiguous when read out of context. v1.8.1 reformulates to a hybrid : with sender name → `"<Name> a réagi par ❤️ à votre message : «…»"`, anonymous → `"Réagi par ❤️ à votre message : «…»"`. Sender name resolved via 3-tier fallback : (1) Settings override (sanitized, cap 40c, anti-C0/C1/bidi/BOM), (2) `ContactsContract.Profile` auto-detection, (3) anonymous.

Decoder accepts 4 new regex (named/anonymous × with-preview/no-preview) + legacy v1.8.0 + legacy Tapback EN. `MAX_DECODE_INPUT_LENGTH = 400` neutralises ReDoS on the non-greedy quantifiers. No schema migration, DataStore-additive downgrade-safe.

### v1.8.0 — Conversation badge fixes + reaction format picker + tap-notif nav

12 fixes confirmed on Galaxy S9 Android 10 + Galaxy S24 Android 15. Notable security-adjacent : `markRead` now propagates to the system `content://sms` + `content://mms` providers so uninstall + reinstall preserves read state ; one-shot migration `unreadResetV180` resets historical incoming messages to read=1 (aligned Google Messages / Samsung Messages) ; `TelephonySyncManager.runSync` forces `read=true` on all historical messages at first sync.

### v1.7.1 — FLOSS translation via system delegation (ACTION_PROCESS_TEXT)

Restores translation by delegating to the user's installed translation app via `Intent.ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT_READONLY=true` (anti-spoofing : the called app cannot modify the original). `<queries>` in `AndroidManifest` declares targeted package visibility (no `QUERY_ALL_PACKAGES`). System chooser obligatory. No bundled ML model.

### v1.7.0 — F-Droid FLOSS compliance (Google ML Kit removed)

Suppression of `com.google.mlkit:translate` + `com.google.mlkit:language-id` + `kotlinx-coroutines-play-services` (was a bridge `Task→suspend` for ML Kit). `TranslationService.kt` rewritten as a stub returning `Outcome.Failure(Validation("translation_unavailable_v17"))`. Cert SHA-256 stable. APK arm64 -2 MB.

### v1.6.2 — Critical settings regression fix + Tapback fold improvements

PATCH bundling 5 user-visible fixes uncovered during v1.6.1 in-field testing.

**B1 — CRITICAL: all user settings ignored by ThreadViewModel.** My
PERF-01 v1.6.1 introduced a `cachedSettings: StateFlow<AppSettings>` initialized
with `stateIn(viewModelScope, WhileSubscribed(5_000), AppSettings())` — but
NO consumer ever collected this flow (read only through `.value`
from 5 sites). With no collector, the upstream flow was never subscribed and
`.value` always returned the **default initial value** `AppSettings()`. All
user settings were therefore **silently ignored** in
`ThreadViewModel`: `confirmBeforeBroadcast`, `reactionConfirmDismissed`,
`reactionEmojiOnly`, `sendReactionsToRecipient`. The confirmation dialog kept showing
up even after ticking "Don't ask again", the emoji-only mode remained unreachable,
etc. Fix: `cachedSettings` now delegates to `settings.state` (the `Eagerly`
StateFlow hydrated by `appScope` on the [SettingsRepository] side, which IS always
collected). Check added: `WhileSubscribed` is only valid for
StateFlows exposed to and collected by Compose; private caches must use
`Eagerly` or another active mechanism.

**B2 — Tapback fold failed on multi-line bodies.** The encoder
[SendReactionUseCase.buildTapbackBody] normalizes whitespace (newlines, tabs →
single space) in the preview before sending, but the receiver matcher
[ConversationMirror.applyIncomingReaction] used `body LIKE 'prefix%'` on the
SQL side — and SQLite LIKE has no whitespace equivalence. An OUTGOING stored as
`"Hello\nworld"` did not match the prefix `"Hello world"`, so the reaction
showed up as a text bubble instead of a badge. Fix: new DAO
`findRecentOutgoingForConversation(convId, 50)` + Kotlin fallback that normalizes
whitespace on both sides (`collapseWhitespace()` private extension). The SQL LIKE
fast path is kept for single-line cases (the majority).

**B3 — Fold ambiguity on short messages sharing a prefix.** When
several short OUTGOING messages share a prefix ("Hello" vs "Hello world"),
the old matcher always picked the MOST RECENT one — so a reaction to the older
"Hello" was folded onto "Hello world" (wrong message). Fix: new field
[DecodedReaction.wasTruncated] (true if the wire text contained `…`). In the matcher:
- `wasTruncated == false` (short, non-truncated body, preview = full body) →
  **EXACT** match after whitespace normalization. "Hello" only matches
  "Hello", not "Hello world".
- `wasTruncated == true` (long body, only the prefix is known) → fallback prefix
  match (with the ambiguity inherent to the SMS-based Tapback protocol, which has no
  solution that does not break iMessage/Google Messages compatibility).

**B4 — Reaction confirm dialog reopened despite "Don't ask again".** Sub-100 ms
race between `settings.update { reactionConfirmDismissed = true }` (async DataStore
write) and the next read of `cachedSettings.value.sending`
(Eagerly StateFlow with a propagation delay). If the user reacted twice
in quick succession, the 2nd read still found the old value
`false` and reopened the dialog. Fix: **fresh** read via `settings.flow.first()`
ONLY at this site (read after a potential write). The 4 other
PERF-01 sites (SMS/MMS send hot path) keep reading `cachedSettings.value` because
they have no prior write to wait for.

**B5 — Misleading "Compact format (emoji only)" label.** The option actually
controls the wire format of reactions (verbose Tapback, which enables the fold on the
recipient side, vs bare emoji, which forces the recipient to see a text SMS with no
context). Users turned the option on thinking "compact = better", and ended up
with badges that no longer appeared on the recipient's side.
Label renamed to **"Send the bare emoji (no context)"** + description
rewritten to spell out the trade-off OFF (recommended, badge on the message) vs
ON (text SMS, loses the merge).

No security surface changed. The Tapback fold is strictly local to the
receiver; it creates no new sensitive entry. The exact matcher
(B3) does not lower security — it only improves the precision of
the message↔reaction association.

### v1.6.1 — Reaction notif fix + deep audit hardening (30 fixes)

**1. Reaction notification regression fix** (root cause of this PATCH).
Since v1.4.1 (Tapback fold path), `SmsDeliverReceiver` correctly attached an incoming
reaction to the original outgoing message but did `return@launch` immediately after the
SEC-01 sentinel insert — skipping the entire `notifier.notifyIncoming(...)` branch. The
recipient saw the badge change silently, no system notification, breaking parity with
iMessage / Google Messages Tapback.
- `ConversationMirror.ReactionApplied` gains a `targetMessageId: Long` (stable notif id
  + deep-link to the precise message ; no collisions, no DB schema change).
- `SmsDeliverReceiver` posts a localized body (`reaction_notif_body_with_preview` /
  `_no_preview`) via `notifyIncoming(...)`, preserving `previewMode`, `enabled`,
  `POST_NOTIFICATIONS`, active-conv auto-dismiss and tag-based cleanup contracts.

**2. Post-release deep audit — 30 fixes landed across 3 axes** (score 84/83/88 → 96+).

*Security (7)*
- **SEC-01**: `MessagingStyle.Message(visiblePreview)` instead of the raw `body`. Previously,
  some OEMs (Xiaomi MIUI/HyperOS, Samsung One UI < 5) ignored
  `VISIBILITY_SECRET` for `MessagingStyle` and leaked the content on the lockscreen.
- **SEC-05**: `addrSuffixes` (PII: 8-digit phone number suffixes, GDPR
  quasi-identifiers) removed from the Timber logs in `BlockedNumbersImporter`.
- **SEC-06**: full MMSC URL (potential carrier session tokens in path/query)
  removed from the debug log in `MmsWapPushReceiver`.
- **SEC-07**: `applied.targetBody` now passed through `stripInvisibleChars()` in
  `SmsDeliverReceiver` before injection into the reaction notification (anti BiDi/RLO on
  OUTGOING bodies, which were not stripped at write time).
- **SEC-08**: MMS sender + caption + subject passed through `stripInvisibleChars()` in
  `MmsDownloadedReceiver` (parity with the SMS path, defense in depth).
- **SEC-09**: `Attachment.toShareableUri` adds `canonicalFile` + a
  `[filesDir, cacheDir]` whitelist before FileProvider (defense in depth against path traversal).
- **SEC-11**: `AndroidManifest.xml` clarified on the actual protection of the
  `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `USER_UNLOCKED` actions (AOSP protected
  broadcasts, not the `RECEIVE_BOOT_COMPLETED` permission, which is "normal").

*Performance (8)*
- **PERF-01 (HIGH)**: `cachedSettings: StateFlow<AppSettings>` in `ThreadViewModel`
  replaces 5 `settings.flow.first()` calls on the send path (saves ~25-50 ms of
  cumulative latency on fast typing). Same for **PERF-08** in `TelephonySyncWorker`
  (single snapshot) and **PERF-11** in `IncomingMessageNotifier` (synchronous
  read via `SettingsRepository.state`, hydrated Eagerly at boot).
- **PERF-02**: `distinctUntilChanged` on the contact lookup flow in
  `ThreadViewModel` (the ContentProvider query no longer re-fires on every
  keystroke).
- **PERF-03**: `remember(conversation.lastMessageAt)` around `relativeRowLabel`
  in `ConversationRow` (~100 Calendar allocations avoided per list
  recomposition).
- **PERF-04**: precomputed `daySeparatorLabels: List<String?>` in `ThreadScreen`
  via `remember(state.messages, todayLabel, yesterdayLabel)` (was ~600 Calendar
  allocations per initial render on a 200-message thread).
- **PERF-05**: `appLock.state` isolated from the main combine in
  `ConversationRepositoryImpl.observeMessages` (an unlock no longer triggers
  an attachments rebuild).
- **PERF-06**: `debounce(200 ms)` on the search query in `ConversationsViewModel`
  (a single LazyColumn recomposition per stabilization instead of one per keystroke).
- **PERF-07**: `ContactsReader` moves from an unbounded `ConcurrentHashMap` to
  `LruCache(500)` + `LruCache(1000)` (prevents a progressive memory leak on spam SMS
  / 2FA / delivery messages).

*Quality (15)*
- **QUAL-01 + QUAL-16**: `SAFETY_NET_DAYS`, `MS_PER_DAY`, `purgeCutoffMs(days, now)`
  centralized in `domain/purge/PurgePolicy.kt` (single source of truth — the
  duplicates in `ConversationRepositoryImpl` and `TelephonySyncWorker` are
  removed).
- **QUAL-02**: `flowOn(io)` before `stateIn` in `ConversationsViewModel`
  (the synchronous Binder IPC `defaultAppManager.isDefault()` no longer blocks the Main thread).
- **QUAL-03**: `defaultAppManager` made `private` in `ConversationsViewModel` —
  the screen now goes through `buildChangeDefaultIntent()` (ViewModel encapsulation
  respected).
- **QUAL-04**: `openOutputStream(uri)!!` replaced by `?: error("...")` in
  `BackupService` (explicit diagnostic if the URI is revoked / the disk is full).
- **QUAL-05**: redundant `!!` after a smart cast removed in `ContactsReader`.
- **QUAL-06**: `conv!!.draft` replaced by `conv?.draft.orEmpty()` in
  `ThreadViewModel` (an invariant not captured by the compiler; robust to a future
  refactor of `seededDraft`).
- **QUAL-07**: `"PDF export failed"` (hardcoded English) replaced by
  `R.string.snack_pdf_export_failed` FR+EN (i18n regression fixed).
- **QUAL-10**: `SmsDeliverReceiver` goes through `ConversationRepository.findMessageById`
  instead of accessing `MessageDao` directly (Repository pattern respected).
- **QUAL-11**: `VoicePlaybackController` receives an injected `@MainDispatcher` instead
  of the hardcoded `Dispatchers.Main.immediate` (testability).
- **QUAL-13**: `splitGraphemeClusters` moved from `ui.components` to
  `core/ext/StringExt.kt` (a utility String extension, which must not live in a
  UI module).
- **QUAL-14**: `SortMode.DATE` no longer sorts by `pinned` first (it was
  indistinguishable from `SortMode.PINNED_FIRST`).
- **QUAL-15**: KDoc drift `v1.3.11` → `v1.4.0 (F5 forward feature)` in
  `ThreadViewModel` and `AppRoot`.
- **QUAL-17**: `@androidx.compose.runtime.Stable` on the 5 `UiState` data classes
  (Compose recomposition skipping).
- **QUAL-18**: `escapeFtsQuery` extracted as a pure top-level function in
  `data/repository/EscapeFtsQuery.kt` + new test file
  `EscapeFtsQueryTest.kt` (15 cases: empty, whitespace, FTS reserved chars, BiDi,
  zero-width, BOM, control chars, Unicode letters).

**Deferred to v1.6.2+** (format change / migration / infrastructure): SEC-04
(PBKDF2 salt 16→32 B breaks `.smsbk`), SEC-12 (PendingIntent hash), SEC-14 (carrier
MMSC whitelist), PERF-09 (Baseline Profile setup), PERF-10 (FTS4→FTS5),
PERF-12 (WAL + SQLCipher page_size), QUAL-08/09/12 (FQN imports / injectable
Dispatchers.Default in VoiceRecorder).

**Tests**: 14 pre-existing `IncomingReactionDecoderTest` tests + 15 new
`EscapeFtsQueryTest` tests (29 JUnit5 tests on the 2 most sensitive files) + full
suite green. **Lint**: no new error (baseline regenerated for 4 pre-existing errors
+ 177 pre-existing warnings).

### v1.6.0 — Post-v1.5.0 audit hardening (security / perf / a11y)

Patch+minor follow-up to v1.5.0. The 3-axis audit run after publication surfaced 2 HIGH and 6 MEDIUM/LOW findings ; this release lands them all.

**HIGH (closed)**

- **S1 — ReDoS guard on `IncomingReactionDecoder`.** The Tapback regex
  `^Reacted\s+(.+?)\s+to\s+[«"](.+?)[»"]$` is non-greedy and accepts `DOT_MATCHES_ALL` ;
  a malicious multi-part SMS like `Reacted ❤️ to «aaa…` (no closing guillemet, 3 kB)
  would otherwise force catastrophic backtracking. We now reject any input
  `> MAX_DECODE_INPUT_LENGTH = 400` chars before the regex sees it. A real Tapback
  always fits in a single UCS-2 segment (≤ 70 chars). Three new JUnit5 tests lock the
  guard (oversize body rejected fast, body just above the cap rejected, realistic
  body close to the cap accepted).
- **U1 — Custom emoji dialog respects `MAX_REACTION_EMOJIS`.** The "Other emoji"
  shortcut routes through the system emoji keyboard, which could produce a string of
  arbitrarily many clusters. The dispatch site in `ThreadScreen` now splits the input
  via `splitGraphemeClusters().take(MAX_REACTION_EMOJIS).joinToString("")`, atomically
  preserving ZWJ family / skin-tone / VS-16 sequences.

**MEDIUM / LOW (closed)**

- **Q1** — `SendReactionUseCase` KDoc updated : the "Changed transitions stay
  network-silent" line was a v1.3.1 artifact ; since v1.4.1 the ViewModel dispatches
  Changed too so the recipient sees the new emoji. KDoc now reflects reality.
- **Q2** — Dead branch `Modifier.then(Modifier)` in `EmojiReactionPickerSheet`
  replaced by `Modifier.alpha(0.38f)` (Material 3 disabled state), so emojis blocked
  by the capacity cap are visually muted instead of just non-clickable.
- **Q3** — `when (result)` on the `SetReactionResult` sealed interface is now
  exhaustive with explicit `Removed` and `Noop` branches. A future variant of the
  sealed type will fail to compile here instead of being silently swallowed.
- **P2** — `atCapacity` in the picker wrapped in `derivedStateOf`, so the 22 emojis
  that did not change selection state skip a recomposition on every tap.
- **U2** — `EmojiReactionBadge` now carries `semantics { role = Role.Button ;
  contentDescription = "Réaction <emoji>" }` and `clickable(onClickLabel = "Retirer la
  réaction")`. TalkBack used to announce a mute "double-tap to activate" with no
  context ; it now describes both the badge state and the remove action.
- **S2** — `MessageDao.listAll()` (the backup snapshot read) excludes Tapback
  sentinels (`body = '' AND attachments_count = 0 AND reaction_emoji IS NULL`). These
  rows are internal artifacts of the anti-reimport mechanism ; including them in a
  backup would surface empty bubbles on restore.

**Not changed**

- The composite `(conversation_id, date)` index on `messages` was flagged as missing
  but is in fact already present since schema v2 (and the `date` standalone index since
  v4). No migration required.
- The "abandon multi-select on Other emoji" UX is intentional and documented in code
  ; we left it as-is for v1.6.0.

### v1.5.0 — Multi-emoji reactions + Tapback bidirectional fidelity

Minor SemVer bump. Two themes shipped together :

1. **Multi-emoji reactions** (v1.5.0 feature) :
   - `EmojiReactionPickerSheet` rewritten as a multi-select grid. Tap toggles an
     emoji in/out of the current selection (highlighted with a `cs.primary` ring),
     "Envoyer la réaction" commits the joined string. Cap at `MAX_REACTION_EMOJIS = 3`
     to keep the badge readable.
   - `EmojiReactionBadge` switched from fixed-size circle to an auto-growing pill
     (min 28 dp height, min 28 dp width, rounded-rect with 14 dp corner = visual
     circle for the single-emoji case ; widens horizontally for 2-3 emojis).
   - `splitGraphemeClusters()` helper handles the multi-codepoint emojis (ZWJ
     families, skin tones, variation selectors) so the picker's pre-selection
     state and the badge rendering keep clusters atomic.
   - The picker opens with the message's existing reaction pre-selected, allowing
     additive edits without re-typing the full combination.
   - The wire format (Tapback or emoji-only per `SendingSettings.reactionEmojiOnly`)
     ships the joined string transparently — the receiving decoder accepts arbitrary
     emoji sequences without code changes.

2. **Tapback bidirectional fidelity** (folded the v1.4.1 backlog) :
   - **Multi-react dispatch** : `ThreadViewModel.dispatchReactionSms` dedup map
     switched from `Map<Long, Long>` (messageId → timestamp) to `Map<Long,
     ReactionDispatch>` (messageId → emoji + timestamp). The 60 s dedup now only
     fires when the SAME emoji is sent twice ; legitimate changes (❤️ → 👍) bypass
     the window so the correspondent sees the update.
   - **Send-on-change** : `setReaction` dispatches an outgoing SMS on both `First`
     (null → emoji) AND `Changed` (A → B) transitions (was First-only since v1.3.1).
     `Removed` stays local-only — Tapback has no "remove reaction" wire format.
   - **Hide reactor's own outgoing Tapback bubble** : `upsertOutgoingSms` accepts a
     new `localMirrorBody: String?` parameter ; `SendReactionUseCase` passes `""`
     so the Tapback SMS still ships on-wire + lands in `content://sms` (legal duty
     as default SMS app) but the reactor's own thread no longer paints a redundant
     `"Reacted ❤️ to «…»"` text bubble. The empty Room row is filtered out by
     `MessageDao.observeForConversation` (`body='' AND attach=0 AND reaction IS NULL`
     regardless of direction).
   - `touchConversation` is skipped when `mirrorBody.isEmpty()` so the conversation
     list does not show a blank preview / wrong sort order from the sentinel row.

**Carryover from v1.4.0 / v1.4.1 backlog** (already in v1.4.0 but kept here for
trace) : MMS reception unblocked on Android 10+, multi-MIME attachment extraction,
caption ↔ previewLabel decoupling, KeepAliveService opt-in, AttachmentPicker `*/*`,
voice bubble waveform, file picker open to all MIME types.

**Defensive audit fixes shipped with this release**
- **SEC-01** : `upsertReactionSentinel` drops a poison-pill Room row carrying the
  same `telephonyUri` as the system inbox row after a Tapback fold, so
  `TelephonySyncManager` cannot re-import the body as a phantom text bubble. The
  sentinel is filtered out at the DAO level (body='' + 0 attach + 0 reaction).
- **SEC-02** : `stripInvisibleChars` widened to also strip U+00AD (soft hyphen),
  U+034F (combining grapheme joiner), U+061C, U+180E, U+2060–2064, U+FFFC, U+FFFD.
  Closes an attack path where a forged SMS like `­❤` could pass the
  pure-emoji heuristic and forcibly pin a reaction badge.
- **SEC-03** : `AppRoot` share-target route now clears `IncomingShareHolder.pending`
  when the user is already inside a Thread or Compose — prevents the pending
  payload from being silently stuffed into a different conversation later.
- **SEC-04** : phone number address removed from a `Timber.i` log line for
  consistency with the project-wide PII-out-of-logs policy.
- **KQ1** : FQN repetition in `ConversationMirror.applyIncomingReaction` replaced
  by a top-of-file import alias `Kind`.
- **KQ2** : `AppRoot` switched from `collectAsState()` to
  `collectAsStateWithLifecycle()` for the incoming-share flow.
- **KQ3** : `TAPBACK_WITH_PREVIEW_REGEX` KDoc fixed to describe the actual fallback
  ASCII quotes `"..."` (was misleadingly stating `<<>>`).
- **KQ4** : `EMOJI_ONLY_REACT_WINDOW_MS` visibility narrowed from `public` to
  `internal`.

Same cert SHA-256 `b09a9511…687d`. No new permission. No schema change. 17 JUnit
tests green (3 new for the grapheme cluster splitter, 14 existing for the reaction
decoder).

### v1.4.0 — Ergonomics pack + voice bubble waveform

Minor SemVer bump driven by 5 user-facing features. No new permission, no schema
change, no signing key change. 6 defensive fixes applied from a parallel 3-axis
audit (security / performance / UI-coherence) before tag.

**F1 — Keyboard retract after send** (`ThreadScreen.kt`)
- After every send (text, voice, or media MMS), the soft keyboard is hidden via
  `LocalSoftwareKeyboardController.hide()` and IME focus is dropped via
  `LocalFocusManager.clearFocus()`. Lets the sender see the freshly-sent message
  at the bottom of the thread without manually collapsing the keyboard.

**F2 — Instant-validation contact picker** (`ComposeViewModel.kt`, `ComposeScreen.kt`)
- New `pickRecipient(rawNumber: String): Boolean` checks if `recipients.isEmpty()`
  BEFORE the append; when true it appends + immediately calls `createConversation`
  (single-recipient flow, ~99 % of cases). When false (user is building a group)
  it only appends — the explicit "Continuer" button stays the validation step.
  The free-entry row supporting text adapts via a new `compose_use_this_number` /
  `compose_add_to_group` string pair to mirror the current state.

**F3 — Copy message** (`BubbleMenuTrigger.kt`, `MessageBubble.kt`)
- New `onCopy: (() -> Unit)?` parameter on `BubbleMenuTrigger` surfaces a
  Material 3 `DropdownMenuItem` "Copier" with `Icons.Outlined.ContentCopy`.
  `MessageBubble` wraps its body Box in `combinedClickable(onClick, onLongClick)`
  so a long-press triggers the same copy action without going through the 3-dots
  menu (iMessage convention). `MediaAttachmentBubble` exposes copy only when a
  caption is present (placeholder bodies are filtered out at the call site).
  `ThreadScreen` injects `LocalClipboardManager` and routes through a single
  `copyMessageBody(msg)` helper that emits a `LongPress` haptic + a "Message
  copié" snackbar for tactile + visual confirmation.

**F4 — Phone number actions** (`MessageTextWithLinks.kt`, `PhoneActionsDialog.kt`)
- `buildLinkifiedText` is extended to also run `Patterns.PHONE` over the body
  alongside `Patterns.WEB_URL`. Phone hits are filtered by a strict digit-count
  band `[PHONE_DIGITS_MIN=7, PHONE_DIGITS_MAX=15]` to reject promo codes (too
  short) and IBANs / credit-card numbers (too long). Overlapping hits prioritise
  URL over phone (priority `0 < 1` in `compareBy`) so an URL containing digits
  is never fragmented.
- Phone hits emit `LinkAnnotation.Clickable` (NOT `Url`) so the tap routes
  through a custom listener instead of an implicit Intent. The listener
  surfaces `PhoneActionsDialog` with 3 actions :
  - **Call** → `Intent.ACTION_DIAL` with `tel:$number` URI. Intentionally NOT
    `ACTION_CALL` which would require `CALL_PHONE` runtime permission.
  - **Copy** → push to clipboard + snackbar.
  - **Add to contacts** → `ContactsContract.Intents.Insert.ACTION` pre-filled
    with the number. No permission required.
- Each `startActivity` is wrapped in `runCatching` + fallback snackbar so a
  stripped ROM without a default dialer / contacts app cannot crash the thread.
- The WAP "any-charset" sentinel (MIBenum 0 → literal `*`) was already handled
  by `resolveCharset` in v1.3.10 — same fallback to UTF-8.

**F5 — Forward message** (`ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`ThreadViewModel.stageForward`)
- New `Modal Bottom Sheet` lists recent conversations (with search) + a top
  "Nouveau destinataire" CTA. The source conversation is hidden from the list
  (impossible to forward to oneself) via a new
  `ForwardPickerViewModel.setExcludedConversation(id)` API.
- The forward payload reuses the existing share-target plumbing
  (`IncomingShareHolder.Pending`) :
  - text → `Pending.text` → consumer's draft
  - first attachment → `Pending.uris[0]` (wrapped via FileProvider for local
    files — see SEC-01 below), `Pending.mimeType` drives the `AttachmentKind`
    selection
- Destination ThreadViewModel picks up the payload via the existing
  `consumeIncomingShareIfAny()` path. No new ViewModel-to-ViewModel coupling.

**Voice bubble waveform** (`AudioMessageBubble.kt`)
- At rest (`!isPlaying`), the standard Material 3 inactive slider track is
  overlaid by a `Canvas` drawing `WAVE_BAR_COUNT = 28` vertical bars with
  pseudo-random heights seeded by `audio.id`. Same audio clip always renders
  the same silhouette across recompositions (deterministic `Random(seed)`).
  During playback the slider takes over for clean progress animation.
- Incoming voice bubbles now carry a 1-dp border at
  `lerp(bgColor, Color.Black, 0.18f)` (18 % darker than the fill) for better
  contrast against the thread surface, symmetric with the outgoing border-only
  design.

**Defensive audit fixes shipped with this release**
- **SEC-01** (`ThreadViewModel.stageForward`) : local file paths in the forward
  payload are wrapped through `FileProvider.getUriForFile(...)` instead of
  `Uri.fromFile(...)`. The latter was technically safe today (intra-process
  `openInputStream` consumer, no `Intent` crossing), but the `file://` pattern
  is a known `FileUriExposedException` landmine and the rest of the app already
  uses `FileProvider` throughout — alignment.
- **P1** (`ForwardPickerViewModel`) : the filtered conversation list is now
  cached in `UiState.filtered` and recomputed only on `setQuery`,
  `setExcludedConversation`, or `observeAll` emissions. The composable reads
  `state.filtered` instead of calling the filter inside the recomposition.
  Eliminates O(n·m) jank on Android Go appliances when typing into the picker
  search field with ~150 conversations loaded.
- **P3** (`MessageTextWithLinksTest.kt`) : 3 new JUnit 5 tests pin the
  `countDigits` helper and the `PHONE_DIGITS_MIN/MAX` band so a future widen
  cannot silently let through promo codes or IBANs.
- **U2** (`ForwardMessageSheet`) : the "Nouveau destinataire" `ListItem` carries
  `semantics { role = Role.Button }` so TalkBack announces "Bouton" in addition
  to the headline text.
- **U3** (`ForwardMessageSheet`) : the sheet's `dismissAndReset` lambda clears
  `viewModel.setQuery("")` before bubbling the dismiss up — prevents a stale
  query from reappearing when the user reopens the sheet.
- **Hook removed** : the `Annuler` button on `PhoneActionsDialog` was orphaned
  by Material 3's `dismissButton` slot (rendered below the 3-action stack).
  Removed — tap-outside and back-press already invoke `onDismissRequest`.

Same cert SHA-256 `b09a9511…687d`. ~12 files modified, 2 new files
(`PhoneActionsDialog.kt`, `ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`OemKeepAliveOnboarding.kt` was v1.3.10 — no new manifest entry).

### v1.3.1 (this release) — Reaction-as-SMS feature

v1.3.1 adds an opt-in capability : when the user posts an emoji reaction on a
**received** message, an SMS containing only that emoji is sent to the
**single sender** of the message (never to other group participants, never
when the message is outgoing). The preference is **on by default** ; a one-shot
confirmation dialog (with "don't ask again" checkbox) protects the user from
silent billing surprises on the first send.

Audited along three axes ; two CRITICAL findings caught and fixed before tag :

- **F1 (CRITICAL)** : `setReaction` could trigger a SMS for outgoing messages
  (i.e. reacting to your own sent message). Now blocked at the use case AND
  hidden from the UI menu (`onReact` is `null` on outgoing bubbles).
- **F2 (CRITICAL)** : in a group conversation, the reaction SMS would be
  broadcast to *every* participant. Now targets *only* the sender of the
  reacted message (`message.address`).
- **F3 (HIGH)** : user signature would be appended to the reaction body
  (`❤️\n--\nPat` → multi-part SMS billed x2/x3 + sender thread pollution).
  `SendSmsUseCase` got an `appendSignature: Boolean = true` parameter ;
  `SendReactionUseCase` passes `false`.
- **F4 (HIGH)** : race between the confirm dialog and a subsequent reaction
  tap could dispatch a stale emoji. `Event.RequestReactionConfirm` now carries
  the `messageId` and `ThreadViewModel.confirmReactionSend` re-checks the
  current `reactionEmoji` before dispatching.
- **P1 (HIGH)** : a `DataStore.first()` failure in the post-First block of
  `ThreadViewModel.setReaction` would crash the ViewModel scope. Wrapped in
  `runCatching` with a Timber warning.
- **P5 (MEDIUM)** : empty recipient label in the confirm dialog if the
  conversation was not yet hydrated. Falls back to a localised "this contact"
  string.

The 24 quick-pick emojis are hard-coded standard codepoints (no ZWJ-only,
no BiDi controls). The "+ Other emoji" path runs `EmojiCustomDialog
.isLikelyEmoji()` which rejects `<>&"'\`, BiDi overrides, BOMs and ZWJ-only
fakes (see v1.3.0 audit Q4/F2).

A second-pass UI/branchements audit caught 6 additional issues, all fixed
before tag :

- **X1 (CRITICAL)** : refuse alphanumeric senders (`Free`, `INFO`, bank,
  delivery, 2FA) and short codes <4 digits. Without this guard, reacting to
  a bank SMS would attempt to send `❤️` to a non-dialable address or to a
  premium short code (1,50 €+/SMS in France). `SendReactionUseCase
  .isDialablePhoneNumber()` enforces `^[+0-9 .()-]+$` + ≥4 digits + no ASCII
  letter.
- **X2 (HIGH)** : RAM dedup window (60 s) on `messageId` to prevent billing
  spam when the user toggles `null → ❤️ → null → ❤️` quickly. Each cycle is
  legitimately a `SetReactionResult.First` but only the first one in the
  window sends a SMS.
- **X3 (HIGH)** : `reactionConfirmDismissed = true` is now persisted **only
  after** a successful `DispatchOutcome.Sent`. Previously, a permanently
  failing dispatch (NotDefaultSmsApp, blocklist) would still set the pref
  silently, leaving the user with no future confirmation despite no SMS ever
  having been sent.
- **X4 (MEDIUM)** : confirm dialog autofocuses the "Cancel" button (pattern
  used by all destructive dialogs : DestructiveConfirmDialog,
  PurgeNowConfirmDialog, nuke data).
- **X5 (MEDIUM)** : a second `RequestReactionConfirm` event arriving while
  the first dialog is still open is now silently dropped (no overwrite). The
  local reaction badge of the 2nd tap remains posted ; only the SMS dispatch
  for that second tap is skipped — consistent with "one confirm = one send".
- **X6 (MEDIUM)** : "Don't ask again" row uses `Modifier.toggleable(role =
  Role.Checkbox)` instead of `Row.clickable` + `Checkbox.onCheckedChange` to
  expose a single a11y node to TalkBack / Switch Access.

Tests `AppSettingsTest.v1_3_1_reaction_send_defaults_are_explicit` +
`SetReactionResultTest` lock the new defaults and sealed-class semantics so
any future refactor that drops `messageId` from `First` or flips the
default toggle to `false` fails CI.

### v1.3.2 (this release) — Apple/Google Tapback format + clickable URLs

Two UX refinements building on v1.3.1 :

- **Tapback format** : the reaction SMS body is now
  `"Reacted <emoji> to «<preview>»"` (e.g. `"Reacted ❤️ to «See you tomorrow?»"`).
  This **exact** ASCII wrapping is what iMessage (iPhone) and recent Google
  Messages parse to display a **native attached reaction bubble** on the
  original message — instead of a free-standing `❤️` text bubble. Other apps
  show the raw text, which remains contextually clear.
  - `SendReactionUseCase.buildTapbackBody` is extracted as a top-level
    `internal` function for direct JUnit testing without instantiating the
    use case.
  - Body sanitization : control characters (CR/LF/NUL/BEL, U+0000–U+001F,
    U+007F–U+009F) are replaced by a single space, then whitespace runs are
    collapsed. Prevents a malicious incoming SMS from injecting line breaks
    that would split our outgoing tapback into multiple PDUs or fake a
    different sender prefix on the receiver's parser.
  - Preview truncated at 50 chars + "…" to fit comfortably inside one UCS-2
    SMS segment (70 chars cap) with the wrapping + emoji ; avoids the silent
    billing surprise of a 2-segment SMS.
  - Empty body (MMS image-only) → `"Reacted <emoji>"` fallback (still parsed
    by Apple/Google).
- **Clickable URLs in message bubbles** : `MessageTextWithLinks` Composable
  uses Compose 1.7+ `LinkAnnotation.Url` with `Patterns.WEB_URL` (Android's
  battle-tested regex used by every system app). Detected URLs are styled
  underlined + medium weight, inherit the parent text color (legible in
  both light/dark themes), and open via the system `UriHandler` →
  `Intent.ACTION_VIEW`.
  - Scheme normalization : bare domains (`google.com`) are wrapped as
    `https://google.com` before opening. Existing `http(s)://` URLs are
    preserved as-is. **No other scheme is ever generated** (no `tel:`,
    `file:`, `content:`, etc.) — eliminates the entire class of "click a
    URL, open a weird intent" exploits.
  - Trailing punctuation strip (`.`, `,`, `;`, `:`, `!`, `?`, `)`, `]`, `}`,
    `»`, `"`, `'`) so `Hello google.com.` opens `https://google.com` and
    leaves the trailing period outside the link.
  - `remember(text)` caches the `AnnotatedString` so the WEB_URL regex
    doesn't re-run on every recomposition of the bubble list.

New test `SendReactionUseCaseTest` locks the exact Apple/Google Tapback
wording, the control-character sanitization, the truncation boundary, and
the empty-body fallback. Any future refactor that changes
`"Reacted X to «Y»"` wording would fail CI immediately.

A second-pass audit found 7 issues, all fixed before tag :

- **Y1 (HIGH)** : extend the body-sanitization regex to strip U+2028/U+2029
  (Line/Paragraph separators), U+200E/U+200F + U+202A–U+202E + U+2066–U+2069
  (Bidi controls — a `‮` RLO in a received malicious SMS would
  visually flip the rendered Tapback on the recipient's screen and break
  the iMessage parser), and U+FEFF (BOM).
- **Y2 (HIGH)** : `String.take(n)` operates on UTF-16 code units and can
  cut in the middle of a surrogate pair (emoji 4-byte) or a ZWJ cluster
  (family emoji). A new `safeTake()` walks back to a clean boundary so the
  outgoing SMS never carries an orphan surrogate / corrupted glyph.
- **Y3 (MEDIUM)** : preview budget is now computed dynamically from
  `SMS_UCS2_SEGMENT_CAP (70) - TAPBACK_WRAP_LENGTH (15) - emoji.length`,
  guaranteeing the whole tapback fits in **one** UCS-2 SMS segment even
  with a 4-UTF-16-unit flag emoji or an 11-UTF-16-unit family emoji.
- **Y4 (MEDIUM)** : URL whitelist via `toSafeHttpsTargetOrNull()`. Only
  `http://` and `https://` are turned into `LinkAnnotation.Url`. Any other
  scheme (`javascript:`, `data:`, `intent:`, `file:`, `content:`, `tel:`,
  custom app schemes) is rendered as plain text — the system `UriHandler`
  is never asked to resolve a hostile intent.
- **Y5 (MEDIUM)** : `buildLinkifiedText` short-circuits when the input
  exceeds `LINKIFY_INPUT_CAP = 2000` chars. Anti-ReDoS on the
  `Patterns.WEB_URL` regex (java.util.regex NFA can degrade on
  pathological inputs).
- **Y6 (MEDIUM)** : `MessageBubble` skips the linkified renderer on
  messages whose status is `FAILED` — the bubble's outer `clickable`
  retry-tap stays unambiguous, no gesture conflict with the link.
- **Y7 (MEDIUM)** : new `MessageTextWithLinksTest` (11 cases) locks the
  scheme whitelist behavior (case-insensitive http/https accepted, every
  other scheme rejected, bare domain normalized to `https://`, path-colon
  edge case correctly classified as non-scheme). Patterns-dependent cases
  deferred to v1.3.3 with Robolectric.

Tests added in v1.3.2 : `SendReactionUseCaseTest` (10 cases including the
new Y1/Y2/Y3 anti-regression guards), `MessageTextWithLinksTest` (11
cases). All pass.

### v1.3.3 (this release) — Critical bug fixes + share-target IPC surface

Triggered by user-reported regressions after v1.3.2. Seven fixes (5
critical, 2 high) plus a global audit that caught 2 additional issues
fixed before tag.

User-facing fixes :

- **Bug #5 (CRITICAL)** : duplicate empty conversation on SMS receive.
  `ConversationMirror.ensureConversation` now falls back to **8-digit
  suffix matching** for 1-to-1 conversations when the exact CSV match
  fails. Prevents a system-imported conv in international format
  (`+33612345678`) from spawning a duplicate when a broadcast SMS arrives
  in national format (`0612345678`).
- **Bug #6 (CRITICAL)** : notifications kept stacking. Notifier now posts
  with a **tag** (`com.filestech.sms.conv.<id>`) ; `markRead` calls
  `cancelAllForConversation` which iterates `activeNotifications` filtered
  by tag. Effective even when the app is opened directly (no longer
  requires tapping the notification).
- **Bug #1 (HIGH)** : voice send failed on SFR. `BITRATE_BPS` 24 → 16
  kbps + `MAX_SIZE_BYTES` 450 → 280 KB. 120 s now fits in one MMS segment
  for all French carriers.
- **Bug #4 (MEDIUM)** : SMS Tech absent from system Share chooser. Added
  `<intent-filter ACTION_SEND>` to MainActivity with **strict MIME
  whitelist** (`image/*`, `video/*`, `audio/*`, `text/plain`,
  `text/x-vcard`, `text/vcard`, `application/pdf`). New `IncomingShareHolder`
  singleton + parsing in MainActivity. `ThreadViewModel` consumes the holder
  after `state.conversation` hydrates (Z3 audit fix).
- **Bug #2 (MEDIUM)** : received images / files not openable. New
  `MediaAttachmentBubble` Composable with tap → `Intent.ACTION_VIEW` via
  scheme-aware URI resolution (Z1 audit fix : detects `content://mms/part/N`
  for system-imported MMS vs file paths for app-cache attachments —
  blanket `File(localUri)` would have crashed on legacy MMS).
- **Feature #3** : image thumbnail preview in the attachment confirmation
  dialog (Coil with same scheme-aware URI handling).
- **UI #7** : `senderLabel` ("You" / contact name) bold above the first
  bubble of each burst ; vertical spacing 3 dp → 8 dp at boundaries.
  Snackbar custom in `inverseSurface` (per user spec : red reserved for
  destructive actions only).

Audit Z (post-fix) — 7 findings, all fixed before tag :

- **Z1 (CRITICAL)** : `MediaAttachmentBubble` was opening every attachment
  with `File(localUri)` regardless of scheme. `content://mms/part/N`
  attachments (all system-imported MMS) would silently fail. Fixed with
  `toShareableUri(context)` helper that branches on scheme.
- **Z3 (HIGH)** : `consumeIncomingShareIfAny` was racing with the
  conversation hydration flow ; `onAttachmentPicked` returned early if
  `state.conversation == null` and the holder was already consumed —
  losing the share. Fixed by suspending on `_state.first { it.conversation
  != null }` with a 5 s timeout.
- **Z4 (HIGH)** : `setGroup(...)` without a posted `groupSummary` creates
  phantom group headers on OneUI/MIUI that are not cancelled by per-notif
  cancel. Switched to **tag-based** notify/cancel (`nm.notify(tag, id,
  notif)` / `nm.cancel(tag, id)`).
- **Z5 (MEDIUM)** : `senderLabel` color on outgoing AudioMessageBubble used
  `cs.onSurfaceVariant` over `cs.primary` background = ~2:1 contrast (WCAG
  AA fail). Switched to `cs.onPrimary` for outgoing.
- **Z6 (MEDIUM)** : intent-filter MIME whitelist tightened (removed
  `text/*` and `application/*` catch-alls).

Global audit — 2 high findings caught :

- **G1 (HIGH, privacy)** : after `purgeOlderThan`, conversations whose every
  message was deleted retained their original `last_message_preview` in
  clear on the list screen. New `refreshAllConversationPreviewsAfterPurge`
  DAO query recomputes preview + `last_message_at` from the surviving
  rows. Called by both `purgeHistoryNow` (manual) and `TelephonySyncWorker`
  (auto monthly).
- **G2 (HIGH, UX)** : an `IncomingShareHolder` left posted from an
  abandoned Share could attach the file to the wrong conversation when
  the user later opened a thread via notification tap.
  `IncomingShareHolder.Pending` now carries a `postedAt` timestamp ;
  `consume()` returns `null` if past `PENDING_TTL_MS = 60 s`. MainActivity
  also `clear()`s the holder on any non-SEND intent (launcher, deep-link,
  notification-open).

Other audit findings (G3–G10) deferred to v1.3.4 (dead code cleanup, doc
sync, settings polish — non-blocking).

### v1.3.4 (this release) — Multi-attach in composer

Refactor of the attachment staging UX following user feedback ("immediate
send is not practical, I want to stack several images / files in one
message"). Replaces the modal confirmation dialog (v1.2.1) by an
in-composer staging bar. Single MMS multipart dispatch for the whole
batch + optional text body.

Architecture :

- `UiState.pendingAttachment: PendingAttachment?` → `pendingAttachments:
  List<PendingAttachment>` ; cleanup of all staged files in `onCleared`.
- `onAttachmentPicked` **appends** to the list with a live cap check
  (`sum(file sizes) + draft.length > CARRIER_PAYLOAD_CAP_BYTES` =
  280 KB) — refuses the new pick + snack + deletes the tmp cache copy.
- `removePendingAttachment(fileAbsolutePath: String)` removes by stable
  path id (not by index, anti-race) + deletes cache file.
- `clearAllPendingAttachments()` flush + delete-all (used by future
  routes if needed).
- `dispatchPendingAttachments()` is the new MMS multipart path : single
  `SendMediaMmsUseCase.invoke(recipients, payloads, textBody)` call.
- `send()` routes : if `pendingAttachments.isNotEmpty()` →
  `dispatchPendingAttachments` ; else → text-only SMS via `doSend`.
- Modal `AlertDialog(pendingAttachment)` removed from `ThreadScreen`.
- New `PendingAttachmentsBar` Composable : `LazyRow` of 72 dp chips,
  thumbnail Coil for images / icon for video/file, small red X
  (16 dp circle + `cs.error` background + `cs.onError` cross) in top-right
  to remove (destructive color reserved per the v1.3.3 user rule).
- `ComposerBar` / `ComposingRow` get `hasPendingAttachments: Boolean`
  param that flips the mic button to the Send button when the user has
  staged attachments but no text yet (M1 audit fix — without this, the
  feature was unusable for "just send a photo" cases).

Audit M (post-fix) — 4 findings, all fixed before tag :

- **M1 (HIGH)** : Send button unreachable when draft empty + attachments
  staged. Fixed by propagating `hasPendingAttachments` flag.
- **M2 (MEDIUM)** : race between dispatch (1-5 s) and concurrent
  attachment add wipes the late add. Switched to snapshot/diff pattern
  (`val dispatched = pending` then `filterNot { it in dispatched }`).
- **M3 (MEDIUM)** : the cap check at `onAttachmentPicked` didn't cover
  the case where the user added text *after* staging — could submit a
  payload > 280 KB to MMSC. Added re-check at `dispatchPendingAttachments`
  entry.
- **M4 (MEDIUM)** : `LazyRow` key derived from `absolutePath.hashCode().toLong()`
  risks Int collision crash on edge cases, and `onRemove(index: Int)` was
  vulnerable to index shift on add/remove race. Switched to stable
  `String` id (= absolute path) + remove by id.

### v1.3.10 — MMS reception unblocked on Android 10+ OEM ROMs

Cross-device testing on Samsung Galaxy S9 (Android 10 One UI), Samsung S24 FE,
Redmi 9A (MIUI 12 Go), and Xiaomi Poco F5 (HyperOS 2024) exposed three independent
silent failures in the MMS reception pipeline. Each was a "no log, no crash, no
bubble, no notification" failure mode — and the fixes are layered:

**Fix 1 — Hidden API blacklist on Android 10+** (PDU package rename) :
- The embedded AOSP MMS PDU codec (`PduParser`, `PduComposer`, `PduBody`,
  `PduPart`, `EncodedStringValue`, `PduHeaders`, `CharacterSets`,
  `NotificationInd`, `SendReq`, `RetrieveConf`, `MultimediaMessagePdu`,
  `GenericPdu`, `PduContentTypes`, `MmsException`, `InvalidHeaderValueException`)
  previously lived under `com.google.android.mms.pdu.*`. Android 10+ enforces a
  hidden API blacklist on that namespace — the system ClassLoader prefers the
  framework-bundled (hidden) class over our embedded copy and denies access at
  link time ("Accessing hidden method PduParser.<init>([B)V (blacklist, linking,
  denied)", observed on S9 Android 10 logcat 2026-05-18).
- The whole codec was renamed to `com.filestech.sms.pdu.*` (15 .java files moved,
  5 .kt files updated, ProGuard `-keep` rule updated). The ClassLoader now always
  picks our embedded copy. Same pattern Signal / Google Messages use.

**Fix 2 — Silent Hilt-injection crash on Android 10 BroadcastReceivers** :
- `@AndroidEntryPoint` on `MmsWapPushReceiver` + `MmsDownloadedReceiver` triggered
  a silent crash in the Hilt-generated wrapper BEFORE `onReceive` was called when
  Android dispatched WAP_PUSH at cold-start (the normal state for an SMS app
  killed in the background by aggressive OEMs). Result: every incoming MMS was
  dropped without a single log line.
- Both receivers now expose a `@EntryPoint @InstallIn(SingletonComponent::class)`
  interface and resolve `MmsDownloader`, `ConversationMirror`, `MessageDao`,
  `IncomingMessageNotifier`, and the `@ApplicationScope CoroutineScope` on-demand
  inside `onReceive` via `EntryPointAccessors.fromApplication(...)`. The
  `Application` is guaranteed initialized before any broadcast dispatch, so the
  Singleton component is always ready, and any resolution failure now logs
  loudly instead of crashing silently.

**Fix 3 — `PduParser` Message-Class header devoured Content-Location** :
- Per OMA-WAP-MMS-ENC, the `Message-Class` header can be either a `class-identifier`
  octet (0x80–0x83 = Personal/Advertisement/Informational/Auto) OR a `text-string`.
  Our parser was forcing the `text-string` branch unconditionally; when the carrier
  sent a class-identifier (0x8A 0x80…), the parser consumed the 0x80 as the first
  byte of a "text-string" and looped until the next 0x00 — devouring `Message-Size`,
  `Expiry`, AND the trailing `Content-Location` URL. The receiver then bailed with
  "NotificationInd has no contentLocation", silently dropping the MMS.
- Added a dual-path decoder: high-bit-set first byte → class-identifier lookup
  (mapped to "personal" / "advertisement" / "informational" / "auto"); otherwise
  the existing `text-string` path.

**Reception attachment pipeline modernized** (`MmsDownloadedReceiver`,
`ConversationMirror.upsertIncomingMms`) :
- SMS Tech v1.3.9 only kept `audio/*` parts; every incoming image / video / file
  MMS landed as a `[MMS]` placeholder bubble with no attachment, the file silently
  discarded. The receiver now extracts the first non-text non-`application/smil`
  part of ANY MIME (image, video, audio, application) and persists it to
  `cache/mms_incoming/`.
- A separate `text/plain` extractor handles the user caption — but the
  `CharacterSets.getMimeName(0)` call returns the literal `*` (WAP "any-charset"
  MIBenum sentinel) which is not a valid JVM charset; `charset("*")` then threw
  and the caption was silently dropped. Now treated as UTF-8 fallback.
- Decoupled storage: `messages.body` stores the caption verbatim (empty when
  absent); the conversation-list preview label is computed separately and passed
  to `touchConversation`. Stops the placeholder emoji from rendering as a fake
  inline caption under the attachment bubble.

**MMS notifications wired** (`IncomingMessageNotifier`, `MmsDownloadedReceiver`) :
- Renamed `notifyIncomingSms` → `notifyIncoming` (the `MessagingStyle`
  notification is type-agnostic — same heads-up, same lockscreen redaction, same
  inline-reply action, same per-conversation tag cancellation). `MmsDownloadedReceiver`
  now calls it after `upsertIncomingMms` returns, using the preview label as the
  notification body. Before, every incoming MMS landed silently in the DB.

**Duplicate-conversation guard** (`ConversationMirror.ensureConversationByThread`) :
- `MmsDownloadedReceiver` creates the conversation with no system thread id
  (we don't query `content://mms-sms/threadID` from the receiver). The subsequent
  `TelephonySyncManager.bulkImportMmsFromTelephony` was importing the same MMS
  with its real system thread id and inserting a SECOND conversation row when the
  thread-id lookup missed. Added exact-CSV + suffix-8 fallback inside
  `ensureConversationByThread` symmetric with `ensureConversation`: a matching
  one-to-one conv now gets its `thread_id` UPDATED in place instead of being
  shadowed by a duplicate.

**File picker accepts all MIME types** (`AttachmentPickerSheet`) :
- Replaced `{pdf, image/*, audio/*, video/*, text/*}` whitelist by `arrayOf("*/*")`.
  Office formats (.docx, .xlsx, .pptx, .odt, .zip, .epub, .json…) no longer appear
  greyed out in the system file picker. The MMS size cap (~280 KB on most French
  MMSCs) still gates oversized files at send time.

**KeepAliveService — opt-in foreground service ("Resistant mode")** :
- For aggressive OEMs (Xiaomi/Redmi/Poco, Huawei/Honor, Oppo/Realme/OnePlus,
  Vivo/iQOO, Meizu, Asus — detected by `OemRomDetector` via `Build.MANUFACTURER`
  / `Build.BRAND`) that kill background SMS apps within minutes. Disabled by
  default; enabled via Settings → Advanced → "Resistant mode". `START_STICKY`,
  `stopWithTask="false"`, `foregroundServiceType="dataSync"`. Auto-restart at
  device boot via `BootReceiver` (reads the DataStore flag). Defensive try/catch
  covers POST_NOTIFICATIONS revocation (Android 13+) and
  `ForegroundServiceStartNotAllowedException` (Android 12+).

**Documented compatibility limits (recent HyperOS)** :
- **Xiaomi Poco F5 + HyperOS 2024+** : HyperOS whitelists Google Messages + Mi
  Messages at the system level and demands a Mi Account login to disable MIUI
  Optimization — a step many users won't take. The "Resistant mode" foreground
  service mitigates background kills but does not bypass the system whitelist.
  Recommended fallback for these devices: use Google Messages. Documented in
  the [Compatibility](https://files-tech.com/sms-tech.php) section of the
  product site.

No new dependency. No schema change. No signing-key change. Same cert SHA-256
`b09a9511…687d`. ~10 files modified, 15 .java files renamed.

### v1.3.9 — Foreground-active conversation : auto-dismiss notification

User-driven UX fix : when the user is **currently looking at conversation X** in the
foreground (Thread screen open) and a new SMS for X arrives, the notification was
persisting in the shade — forcing the user to manually swipe it away even though the
message had already appeared in real-time inside the open conversation.

**New behaviour** (aligns with Google Messages, iMessage, Mi Messages) :
- Conv X **open foreground** + SMS arrives on X → notif is **posted briefly** (sound
  + heads-up play normally, so the user "hears" the new message arrive) then **auto-
  cancelled by Android after 1500 ms** via `Notification.setTimeoutAfter(1500L)`
  (API 26+, in our `minSdk`). No persistence in the shade.
- Conv X open + SMS arrives on **a different conv Y** → notif on Y persists normally
  (the user has no visibility on Y).
- App in background → notifs persist normally (unchanged).

**Architecture** :
- New `ActiveConversationTracker` singleton (`@Singleton @Inject constructor()` Hilt,
  `AtomicLong` for the active id, sentinel `NONE = -1L`).
  - `setActive(conversationId)` : called from `ThreadViewModel.init` (before
    observers / `markRead`).
  - `clearActive(conversationId)` : called from `ThreadViewModel.onCleared` (before
    the rest of the cleanup). Uses `AtomicLong.compareAndSet(conversationId, NONE)`
    so a stale `onCleared` from a previous ViewModel cannot wipe the state set by
    a freshly-init'd new ViewModel during a configChange race.
  - `isActive(conversationId): Boolean` : lock-free read by the notifier.
- `IncomingMessageNotifier.notifyIncoming` reads `isActiveConversation` ONCE
  before building the notification, then conditionally applies
  `.setTimeoutAfter(ACTIVE_CONV_TIMEOUT_MS)` in the builder `.also { ... }` block.

**Why this design is robust** (pre-release audit mobile-quality-auditor, level 2
STRICT, verdict APPROVED) :
- `AtomicLong` lock-free read costs ~1 ns per SMS, negligible vs the already-present
  IO suspends (`settings.flow.first()`, `contacts.lookupByPhone`).
- `setTimeoutAfter` is Android's official API for time-bounded notifications — no
  custom coroutine timer, no Handler.postDelayed, no WorkManager. Zero overhead.
- The metadata held in memory (the active Room conversation id, a Long) carries no
  PII, no message content. Lock-screen redaction (`VISIBILITY_PRIVATE` / `_SECRET`)
  is unaffected.
- `cancelAllForConversation` (v1.3.3 Z4 audit) keeps working : it iterates
  `activeNotifications` filtered by tag, and a notif already auto-cancelled via
  `setTimeoutAfter` is simply absent from the iteration — no double-cancel.
- `markRead` flow stays intact : `init → setActive → observers launch → first batch
  → markRead → cancelAllForConversation`. Active-conv timeout and read-cancel are
  orthogonal.

**Limits accepted** :
- On aggressive OEM ROMs (some MIUI / ColorOS variants) `setTimeoutAfter` may be
  ignored at the system level — fallback behaviour is the pre-v1.3.9 persistence,
  no functional regression.
- If the process is hard-killed without `ThreadViewModel.onCleared` running (rare :
  OOM killer, force-stop), the AtomicLong stays "active" for the rest of the process
  session. Next SMS on that conv would then be auto-dismissed instead of persisted —
  cosmetic UX glitch, no security risk. Reset on next app cold start (AtomicLong
  has no persistence).

No new dependency. No format / schema / signing key / permission change. Same cert
SHA-256 `b09a9511…687d`. 3 files modified (1 new `ActiveConversationTracker.kt`, 2
edited `ThreadViewModel.kt` + `IncomingMessageNotifier.kt`).

### v1.3.8 — CRITICAL hotfix : R8 keep rule for embedded AOSP MMS PDU classes

**Severity** : CRITICAL. All users on v1.3.7 had their **MMS sending silently broken**
(voice messages, multi-attach, single-image MMS). Text SMS were unaffected — the bug was
specific to the MMS dispatch path through `MmsBuilder.attachRecipientsCompat` reflection.

**Symptom** :
- User taps Send on a voice clip → bubble appears with status "Sending" → instantly
  flips to FAILED with "Échec d'envoi" red snackbar.
- "Tap to retry" under the bubble does nothing (pre-existing limitation : `RetrySend
  UseCase` only knows how to re-dispatch text SMS, not MMS — to be addressed in a
  later release).
- Logcat shows `TP/MmsProvider: insert outbox row 10XX` followed 24 ms later by
  `TP/MmsProvider: delete row 10XX, caller: com.filestech.sms` — the system row is
  rolled back the moment `MmsBuilder.buildMultipartSendReq` returns null.

**Root cause** :
`app/src/main/java/com/google/android/mms/pdu/*.java` embeds the AOSP MMS PDU encoder
classes (`SendReq`, `PduComposer`, `PduBody`, `PduPart`, `EncodedStringValue`,
`PduHeaders`, `CharacterSets`, `PduParser`…). `MmsBuilder.attachRecipientsCompat` +
`MmsBuilder.appendPart` invoke methods on these classes **exclusively via reflection**
(`Class.getMethod("setTo", arr.javaClass)`, `Class.getMethod("addTo", …)`,
`Class.getMethod("addPart", …)`) to cross OEM signature divergences (Samsung One UI
removed `addTo`, certain AOSP versions don't expose `addPart(PduPart)`, etc.).

Because no Kotlin/Java source code calls these methods directly, R8's global static
call-graph analyzer concluded they were dead code and **stripped them from the release
APK** when minifying. Subsequent `Class.getMethod("setTo", …)` then throws
`NoSuchMethodException` → `attachRecipientsCompat` returns `false` →
`buildMultipartSendReq` returns `null` → `dispatchMms` enters the "BRANCH 2 :
encodePdu null" rollback path → user sees the failure.

The bug existed latently in every prior release ; v1.3.6 happened to compile with R8
keeping the methods (call-graph analysis decided to preserve them for unrelated
reasons), v1.3.7's adjacent changes (G4 migration / G5 string purge / F5 LruCache)
shifted the analysis output enough to trigger the strip.

**Fix** (`proguard-rules.pro`) :
```
-keep class com.filestech.sms.pdu.** { *; }
```
(Updated in v1.3.10 — the embedded PDU package was renamed from
`com.google.android.mms.*` to `com.filestech.sms.pdu.*` to bypass the Android 10+
Hidden API blacklist; the keep rule was bumped accordingly. Pre-v1.3.10 history:
the rule originally targeted `com.google.android.mms.**`.)

A single keep rule covering all members of the embedded PDU package. Verified
sufficient by user test 2026-05-17 (vocal sent successfully, received by recipient).
No security risk added — the PDU classes are read-only utility code (no credential,
no state, no network access, no persistence).

**Verification trail** :
1. `MMS_DEBUG` `android.util.Log.e` calls were temporarily added to all 5 failure
   branches of `MmsSender.dispatchMms` + every `return null` of
   `MmsBuilder.buildMultipartSendReq` to bypass the `-assumenosideeffects`
   Timber stripping in release. Logcat capture pinpointed
   "`attachRecipientsCompat returned false`" as the root path.
2. After applying the keep rule + cleaning up the debug logs, user retested ;
   voice MMS dispatched successfully and was confirmed received.
3. No diff vs v1.3.7 in `MmsSender.kt` / `MmsBuilder.kt` net of the `MMS_DEBUG`
   removals — only `proguard-rules.pro` changed functionally.

**Deferred to v1.3.9+** :
- `RetrySendUseCase` should learn to re-dispatch MMS (voice / multi-attach) via the
  appropriate use case, not always through `SmsSender.send()` (which silently
  ignores attachments). The "tap to retry" affordance is currently dead for MMS.
  → **v1.28.3 (global audit of 2026-09-09, B-1)** : worse than dead, it *did* something — it
  re-sent the caption as a plain SMS and the row could flip to SENT under a thumbnail that
  never left the device. `RetrySendUseCase` now refuses MMS rows with a typed
  `AppError.MmsRetryUnsupported` **before** `resetOutgoingForRetry`, and the thread shows a
  message. A real MMS re-dispatch (via `MmsDispatcher`, with an attempt-bearing `requestCode`
  as F23 did for SMS) is still deferred — it needs two phones to verify.
- Consider tightening the AOSP PDU keep rule from `** { *; }` to per-method `-keep
  class … { method-name; }` once we have an exhaustive list of reflected methods —
  saves ~20-50 KB in the APK but requires audit-trail discipline.

No new feature in this patch. No change to file formats, database schema, signing
key, permissions, or i18n. Same cert SHA-256 `b09a9511…687d`.

### v1.3.7 — First-launch splash + snackbar bi-color + audit backlog

User-driven feature release that also clears the entire audit backlog reported during
v1.3.5 and v1.3.6 pre-release audits.

**Feature 1 — First-launch splash** (`SplashScreen.kt` + `SplashViewModel.kt` + DataStore
`AdvancedSettings.splashShown`) :
- 100% Compose native animation (no Lottie dependency, no APK weight added).
- Animations : logo scale (0.5 → 1.0) + alpha (0 → 1) over 900 ms, ease-out cubic ;
  tagline fade-in after 700 ms ; skip hint fade-in after 1500 ms. Total ≈ 5.5 s,
  skippable via tap, back hardware, or auto-dismiss.
- Tagline : "L'appli qui ne lit pas vos messages et qui respecte votre vie privée."
  (FR) / "The app that doesn't read your messages and respects your privacy." (EN).
- Single-fire guard via `AtomicBoolean` shared across all dismiss paths (tap, back,
  auto-dismiss, cold-start "already seen" branch). `LaunchedEffect(Unit)` never
  re-fires → no double-navigation risk on `markShown()` flag flip.
- `StateFlow` with `SharingStarted.Eagerly` + initial value `true` → no flash splash
  on second-launch+ users ; redirect to home before any frame is drawn.
- Branched into `AppRoot` as `startDestination = Splash` with `popUpTo(Splash)
  { inclusive = true }` on completion → splash is unreachable via back stack.

**Feature 2 — Bi-color snackbar** (success vs error) :
- `ThreadViewModel.Event.ShowSnackbar` gains `isError: Boolean = false` flag.
- `SmsTechSnackbarVisuals` custom `SnackbarVisuals` carries the flag through the
  `SnackbarHostState` (official Material 3 pattern, no external state).
- `SnackbarHost { data -> ... }` branches `containerColor` / `contentColor` /
  `actionColor` on `(data.visuals as? SmsTechSnackbarVisuals)?.isError`.
- Success → slate-blue brand (`BrandBlue #2460AB`, `Color.kt:SnackbarBg`). Error →
  strong red (`BrandDanger #C62828`, identical to delete buttons + destructive
  dialogs). White text on both, WCAG AA contrast verified (5.8:1 / 5.5:1).
- All `_FAILED` snackbar emissions in `ThreadViewModel` pass `isError = true`.
- `SnackbarHostState.showError(message)` helper for direct-call sites
  (`onMicPermissionDenied`, etc.).

**Cleanup release — audit backlog cleared** :

- **G4 (MEDIUM)** : `MIGRATION_5_6 { DROP TABLE IF EXISTS conversation_overrides }` —
  dead table (entity + DAO existed but zero business consumer ; verified by
  transversal grep). `IF EXISTS` makes the migration idempotent. `ConversationOverride
  Entity` + DAO files deleted, removed from `AppDatabase.entities[]` + Hilt
  `DatabaseModule.@Provides`. Schema version bumped 5 → 6.
- **G5 (MEDIUM)** : 64 orphan i18n strings removed from both `values/strings.xml`
  (EN) and `values-fr/strings.xml` (FR). FR↔EN parity preserved at 291/291.
  ~10-15 KB APK reduction. 43 orphans evocative of planned features
  (`action_archive`, `action_pin`, `settings_signature`, etc.) kept as-is per
  v1.3.5 retention rule.
- **F5 (MEDIUM)** : `displayNameCache` migrated `ConcurrentHashMap` (unbounded) →
  `android.util.LruCache<String, String>(1000)`. Long-tenure accounts (50k+ SMS
  history with bank/delivery/2FA alphanumeric senders) no longer grow the cache
  unbounded across the singleton lifetime. LRU eviction drops oldest senders
  first ; active conversations stay hot. `LruCache` is thread-safe (synchronized
  internally) — same atomicity guarantee as the previous `ConcurrentHashMap`.
- **U1 (MEDIUM)** : `ToggleRow.clickable + Switch.onCheckedChange` → `Modifier
  .toggleable(role = Role.Switch) + Switch.onCheckedChange = null`. Single
  semantic node for TalkBack / Switch Access (previously announced two distinct
  interactive elements per toggle). Material 3 official recommendation for
  selectable list items.
- **P2 (MEDIUM)** : conditional "Compact reaction format" toggle wrapped in
  `AnimatedVisibility(fadeIn + expandVertically / fadeOut + shrinkVertically)`
  for smooth layout transition when the parent `sendReactionsToRecipient` toggle
  flips.

**Deferred to v1.3.8** :
- **U2** : `stateDescription` on parent toggles that gate a child toggle — TalkBack
  doesn't currently announce why the child appeared/disappeared. Low priority,
  affects only TalkBack power-users.
- 43 orphan i18n strings flagged as "evocative of planned features" — to be
  reassessed individually when the corresponding features land (or get cancelled).

Compile clean (`assembleRelease` green). All 60+ unit tests still pass. No new
dependency. APK size delta ≈ -10 KB (G5 strings purge minus a few bytes from the
splash classes). Cert SHA-256 `b09a9511…687d` unchanged. No file format change /
no user data destroyed (G4 drop empty table).

### v1.3.6 — Voice MMS universal codec + reaction format toggle

Two user-driven fixes after field reports on Xiaomi Redmi 9A (Android 10 Go
+ Orange/SFR). No new feature surface ; minimal touch, audited delta.

**Fix 1 — Voice MMS codec switched to AMR-NB / 3GP** (`VoiceRecorder.kt`
only) :
- `audio/mp4` (AAC encoder, MP4 container) → `audio/3gpp` (AMR-NB encoder,
  3GP container). AAC was being silently rejected by the carrier MMSC on
  certain ROM × carrier combinations (Redmi 9A Android 10 Go + Orange and
  SFR observed 2026-05-16) — the local bubble appeared but the PDU upload
  came back `RESULT_ERROR_GENERIC_FAILURE` and the message stayed at "send
  failed". AMR-NB is the historical universal MMS audio codec (RFC 3267,
  OMA-MMS since 2002) accepted without exception by every MMSC and every
  Android ROM. It is the format used by Mi Messages, legacy Google
  Messages, Samsung Messages. Slight bitrate reduction (12.2 kbps fixed,
  mono 8 kHz) compensated by universal compatibility ; voice intelligibility
  remains identical to the GSM-FR 2G phone codec.
- Renamed constant `MIME_AUDIO_M4A` → `MIME_AUDIO_3GPP`.
- File extension `.m4a` → `.3gp`. `MmsDownloadedReceiver.mimeExtension`
  already handled `audio/3gpp` for the receive path, no consumer-side
  change needed.
- `MAX_SIZE_BYTES` cap (280 KB) kept unchanged : AMR-NB at 12.2 kbps for
  120 s = ~183 KB, comfortable margin for future unknown MMSCs.

**Fix 2 — User-facing toggle for reaction SMS format** (5 files +
i18n FR/EN) :
- New `SendingSettings.reactionEmojiOnly: Boolean = false`. When `true`,
  the reaction SMS contains just the bare emoji (e.g. `"❤️"`). When `false`
  (default), the Apple/Google Tapback wrapping introduced in v1.3.2 stays
  in effect (`"Reacted ❤️ to «preview»"`).
- Rationale : on legacy SMS apps (Mi Messages, older Samsung) that do not
  parse Tapback, the wrapping shows as raw text — visually noisy. The new
  option lets users targeting legacy recipients send the cleaner bare
  emoji. Default is left at `false` to preserve the native reaction-bubble
  rendering on iPhone iMessage and recent Google Messages, where the
  Tapback parsing is what produces the merged bubble under the original
  message.
- UI : new `ToggleRow` in `SettingsScreen` → Sending section, shown only
  when `sendReactionsToRecipient` is enabled (consistency : you can only
  set the format if you are actually sending).
- Use-case path : `SendReactionUseCase.invoke()` gains an `emojiOnly:
  Boolean = false` parameter (defaulted for backward compat with existing
  callers / tests). The guards F1/F2/F3/X1 remain unchanged and execute
  before the branching `body = if (emojiOnly) emoji else buildTapbackBody
  (...)`.
- DataStore key : `send.reactions.emojiOnly` (boolean). No migration, no
  schema bump — first read returns `false` on installs that pre-date the
  release.

**Pre-tag audit found 1 HIGH + 5 MEDIUM ; HIGH + 1 MEDIUM (P1) fixed
inline, 4 MEDIUM deferred to v1.3.7 (TalkBack semantic merges that affect
all existing `ToggleRow`s, not just this delta) :**

- **S1 (HIGH)** : `dispatchReactionSms` originally added a 2nd
  `settings.flow.first()` read on every reaction send (in addition to the
  one already in `setReaction`). Refactored to pass `emojiOnly: Boolean`
  as a parameter from the caller's existing snapshot — zero extra
  DataStore reads, no suspend-point introduced where the in-flight emoji
  value could drift. Eliminates the original concern (no-timeout flow
  collection on a critical send path) entirely.
- **S2 (MEDIUM)** : `versionName` bumped `"1.3.5"` → `"1.3.6"` in
  `app/build.gradle.kts` (line 38) plus the related comment on line 71.
  Required for manifest / F-Droid yml coherence.
- **P1 (MEDIUM)** : same as S1 — refactored `dispatchReactionSms(messageId,
  emoji, emojiOnly)` and updated both call sites (`setReaction` post-
  observer path + `confirmReactionSend` post-dialog path) to read the
  `sending` snapshot once and pass `emojiOnly` down.

Deferred to v1.3.7 (not specific to this delta, applies to **all**
existing `ToggleRow`s) :
- **P2** : wrap conditional `ToggleRow`s in `AnimatedVisibility` for
  smoother layout transitions.
- **U1** : `Modifier.semantics(mergeDescendants = true) {}` on `ToggleRow`
  Row + `onCheckedChange = null` on the inner `Switch` to fuse TalkBack
  into a single a11y node. Affects every toggle in `SettingsScreen`, not
  just the new one.
- **U2** : add a `stateDescription` to parent toggles whose value gates
  the visibility of a child toggle, so TalkBack users know the child is
  hidden because the parent is OFF (rather than navigation skipping
  silently).

No regression in the existing 60+ unit tests (compileRelease + tests
green). No file format change. Voice clips recorded by v1.3.5 (AAC `.m4a`)
remain readable — `MmsDownloadedReceiver.mimeExtension` handles both
`audio/mp4` (legacy received) and `audio/3gpp` (new sent + received).

### v1.3.5 — Architecture cleanup + UI polish

Polish release closing 5 findings from the v1.3.3 global audit (G3, G6,
G7, G8, G9) plus a user UI tweak. No new feature. Aims to keep the
codebase tidy heading into v1.4.x.

User-facing :
- X attachment-remove button : 20 → 22 dp (slightly bigger, easier tap).

Cleanup :
- **G3 (MEDIUM)** : dropped 3 orphan permissions from manifest
  (`SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `CHANGE_NETWORK_STATE`) —
  unused by the code, generated Play Console warnings + could be revoked
  on Android 14+. Scheduled message dispatch goes via `WorkManager
  .enqueueUniqueWork`, no need for exact alarms ; MMS transport uses
  `INTERNET + ACCESS_NETWORK_STATE`, no `CHANGE_NETWORK_STATE` calls.
- **G6 (MEDIUM)** : removed phantom field `BlockingSettings
  .blockShortCodes` — never read anywhere, never exposed in UI. If
  short-code filtering is ever re-requested, re-implement via a
  `BlockedNumberEntity.scope` column rather than a global boolean.
- **G7 (MEDIUM)** : `ThreadViewModel.recentlySentReactionFor` now purges
  expired entries (> 60 s window) at each access. Was a slow growth
  during ViewModel lifetime (negligible but unnecessary).
- **G8 (MEDIUM)** : `HeadlessSmsSendService` dropped `Intent.ACTION_SEND`
  from its action whitelist — the body extractor relied on
  `intent.data.schemeSpecificPart` which is empty for true `ACTION_SEND`
  intents (`EXTRA_TEXT`-based). Dead branch + needlessly opened IPC
  surface.
- **G9 (MEDIUM)** : `AppLockManager.disableBiometric` switched from
  read-then-update to atomic `settings.update { transform }`. DataStore
  guarantees atomicity ; the previous pattern was anti-idiomatic and
  bordered on race-conditional under concurrent callers.

Deferred to v1.3.6+ (need more careful work) :
- G4 : `ConversationOverrideDao` + entity + table : entirely dead. To be
  dropped via a `MIGRATION_5_6 { DROP TABLE conversation_overrides }`.
- G5 : ~103 i18n strings unused. To be cleaned via the new
  `android-i18n-strings-cleaner` agent.

### v1.2.0 — 3-axis audit

Three independent agents reviewed the v1.1.x → v1.2.0 delta along three axes :

- **Security** : scored 78/100. Two P0 fixed before release :
  - **P0-1** Vault bypass via `ToggleConversationStateUseCase` short-circuiting the
    `VaultManager` guard, combined with `VaultManager.markUnlocked()` never being called.
  - **P0-2** PendingIntent implicit form silently dropped on Android 14+, leading to the MMS
    PDU file (raw audio + sender headers) being left in clear in `cache/mms_incoming/`
    forever — receiver never fired.
  - P1 fixes : lockout horizon clamp, biometric challenge atomicity, recursive cache purge,
    explicit `setClass` on every internal receiver target.
- **Code quality / performance** : scored 84/100. Dead code removed
  (`ThreadViewModel.replyToMessage`, `archiveThisConversation`, `ConversationsViewModel.pin /
  archive / mute`, `TelephonySyncManager.messageDao` injection), `MessageBubble.time` cached
  via `remember`, duplicate `rememberChatFormatters()` in `ThreadScreen` removed.
- **Duplications** : scored 72/100. Dead strings removed
  (`lock_biometric_prompt_title`, `lock_biometric_use_pin` — never referenced). `BrandDanger`
  centralised in `ui/theme/Color.kt`. The `AsyncCoroutineReceiver` factoring is **deferred to
  v1.3** as it touches every receiver and we wanted v1.2.0 to ship without that risk.

Full audit reports archived as comments inside the code (search "Audit P0-1", "Audit P1-5"
etc. for the inline justification of each fix).

### v1.1.x — Waves 1–3 (internal audit)

Internal pre-release audit applied 23 corrections (F1–F14 sec, P1–P5 perf, U1–U11 UX).
See [`CHANGELOG.md`](CHANGELOG.md#1-1-0--2026-05-14) for the exhaustive list.

---

## Out of scope

- **Transport encryption.** SMS and MMS are clear by protocol. Use Signal / WhatsApp / Matrix
  for content that must not be carrier-visible.
- **Rooted devices.** If `/data/data/com.filestech.sms.debug/` is readable as root, the
  SQLCipher key is still wrapped in the Keystore, but a sufficiently privileged attacker can
  hijack the same `KeyStore.getInstance("AndroidKeyStore")` API the app uses. We do not
  detect or refuse to run on root.
- **Side-channel attacks on the Keystore hardware** (TEE / StrongBox firmware bugs). Out of
  our hands.
- **Carrier-level MMS gateway compromise.** A malicious MMSC could deliver an arbitrary PDU
  and we'd display it — we do not sign / verify content. Mitigation : caps on attachment
  sizes, MIME whitelist for incoming parts, SMIL XML escape on filenames.

---

## Permissions inventory

See [`PERMISSIONS.md`](PERMISSIONS.md) for the full table. Every permission is justified there
with a one-sentence rationale.

---

## Reporting

`contact@files-tech.com` — subject `SMS Tech security report`. PGP key available on request.
Please give us 90 days before public disclosure for any unpatched issue.
