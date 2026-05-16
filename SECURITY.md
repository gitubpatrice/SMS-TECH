# SMS Tech — Security model

Current release : **v1.3.3** (2026-05-16)

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
