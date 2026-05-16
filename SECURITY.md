# SMS Tech — Security model

Current release : **v1.3.1** (2026-05-16)

This document describes the threat model SMS Tech protects against, the cryptographic
primitives it uses, the architectural choices that make those primitives meaningful, and the
known limits beyond which the app cannot defend (because no app can).

If you find a vulnerability, please disclose it to **contact@files-tech.com** with the subject
`SMS Tech security report`. We respond within 5 working days.

---

## Threat model

| Adversary | What we protect against | How |
|---|---|---|
| Device-level adb pull / forensic image | Plaintext SMS / MMS body, vault content, draft messages, recordings | Whole Room DB encrypted at rest by SQLCipher; key derived from a passphrase wrapped in the Android Keystore. Transient caches (PDU staging, voice drafts, exports) wiped on auto-lock + on panic. |
| Lost / stolen phone (no PIN known) | Read access to the conversation list and the vault | App lock (PIN / passphrase / biometric) with exponential backoff. `FLAG_SECURE` on every sensitive surface so the lock screen + recent-apps preview never leaks content. |
| Coerced unlock ("show me your phone") | Disclosure of the hidden vault under duress | Panic-code unlock (`PanicDecoy` state) exposes the standard conversation list while gating every vault entry point — UI hides the vault icon, navigation refuses the route, the data layer returns empty lists. |
| Replay of a successful biometric scan | Bypass of the lock state via a stolen "auth event" | Single-use challenge token issued by `AppLockManager.beginBiometricChallenge()`, consumed atomically (`AtomicReference.getAndSet(null)`) by `markBiometricUnlocked(token)`. A second call with a stale token is a no-op. |
| Brute-force PIN | Online guessing of a short PIN | PBKDF2-HMAC-SHA512 with calibrated iterations (>= 210 k) + salted hash. Exponential lockout (5 s, 10 s, 30 s, 1 min, 2 min, 5 min) starting at 5 failures. `setLockoutUntil` clamped to a 24 h forward horizon so a tainted backup restore cannot brick the app. |
| Malicious component on the same device sending an Intent | Forge a `MmsSent` / `SmsSent` broadcast to manipulate row status | Every result-callback `PendingIntent` uses **explicit** `Intent.setClass(context, ReceiverClass)` rather than implicit `setPackage`. Receivers stay `exported = false`. |
| Carrier-side / MITM | Intercept message bytes in flight | **Not in scope.** SMS / MMS is unencrypted by protocol; we cannot fix that. Users who need transport encryption should use Signal or similar. |

---

## Cryptographic primitives

| Concern | Primitive | Key source | Notes |
|---|---|---|---|
| Room database at rest | SQLCipher v4 | 32-byte random passphrase wrapped by Keystore alias `db_master` | The unwrapped passphrase is wiped from the JVM heap immediately after Room consumes it (`SecretBytes.wipe()`). |
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

### v1.1.x — Vagues 1–3 (audit interne)

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
