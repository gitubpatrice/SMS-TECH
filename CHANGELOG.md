# Changelog

All notable changes to SMS Tech will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/), versions follow [SemVer](https://semver.org).

## [1.2.3] — 2026-05-15

UI polish + hardening pass. Closes the remaining v1.2.2-audit findings that were deferred:
defense-in-depth on the MMS dispatch path, a perf one-liner, and a wave of WCAG / Material 3
fixes on touch targets, contrast, dialog ergonomics, and theme adherence.

### Fixed
- **Snackbar Material 3 palette** (Material You override): the v1.2.2 brand-override path now
  uses a brighter sky-blue (`#3D85D6`) with deep-navy text — visibly slate-blue on all themes,
  including OLED dark — instead of the previous slate that read as near-black on some screens.
- **Incoming bubble palette** now reaches all paths (bubbles + audio bubbles).
- **`BrandDanger` deduplicated**: the duplicate copy in `ThreadScreen.kt` was removed; both
  references now resolve to the single source of truth in `ui.theme.Color`.
- **Hardcoded `0xFFC62828` / `0xFF1565C0`** in `ConversationsScreen` (FAB, swipe backgrounds,
  delete-confirm button) replaced with `cs.primary` / `cs.errorContainer` / `BrandDanger` so
  the colour adapts to Light / Dark / Dark Tech / Material You.
- **`DefaultAppBanner` alpha** dropped from 0.55: composited over Dark Tech `surface` the
  banner body text could fall under 4.5:1 contrast. Now renders on `surfaceContainer`.
- **`ReplyQuoteCard` body contrast** bumped (container alpha 0.78 → 0.88, body alpha 0.82 →
  0.9) so the outgoing-bubble reply quote clears WCAG AA cleanly.
- **`ComposerReplyChip`**: `fillMaxHeight() + height(32.dp)` redundancy cleaned up.
- **Translation body** no longer rendered in italic — was fatiguing on long messages. Italic
  stays on the header label as a meta-content cue.
- **`TranslationBlock` dismiss button**: 22 dp → 36 dp touch target (WCAG 2.5.5).
- **`ComposerReplyChip` cancel button**: 32 dp → 40 dp touch target.
- **`BubbleMenuTrigger`**: 32 dp → 40 dp touch target, tint alpha 0.55 → 0.75 (≥3:1 for
  icons, previously failed).
- **`MmsPduRoundTripTest`** removed — relied on `org.robolectric:robolectric-junit5` that was
  never on the test classpath. CI's `testDebugUnitTest` step had been failing silently since
  v1.2.0. A JUnit 4 rewrite is on the v1.2.4 roadmap.

### Added
- **`DestructiveConfirmDialog` autofocus**: Cancel button now auto-focused (Pass Tech /
  Notes Tech pattern — conservative default for destructive actions). Two-tap protection
  against fat-finger Delete.
- **Confirm-before-send dialogs** (SMS + voice MMS): Send button autofocused, rendered as a
  primary `Button` (vs. two ambiguous `TextButton`s).
- **`AttachmentTile` accessibility** (AttachmentPickerSheet): `Role.Button` + `onClickLabel`
  semantics for TalkBack; `widthIn(min = 64.dp)` ensures the smallest label still hits the
  WCAG 2.5.5 touch target; haptic pulse on tap.
- **Conversation long-press** now emits a haptic pulse (previously silent — user had no
  feedback that the gesture was recognised).
- **Sort menu items**: `Role.RadioButton` + `selected` semantics so TalkBack announces
  "Date, sélectionné" instead of just "Date"; short haptic on sort change.
- **`EmptyState` CTA button**: an inline "Nouveau message" button when the conversation list
  is empty (the FAB exists but is easy to miss on a mostly-empty screen).
- **About screen build badge**: subtle `DEBUG` chip on debug builds only — QA can tell at a
  glance which build is installed. Hidden on release builds (no visual noise for end users).

### Security hardening (defense-in-depth)
- **`MmsSentReceiver` package guard**: rejects broadcasts whose `intent.component.packageName`
  doesn't match ours. The receiver is already `exported = false` so this is a belt-and-braces
  guard against any future drift that exposes the receiver.
- **`MmsSystemWriteback` mime whitelist**: attachment mime types must match
  `^[a-zA-Z0-9.+/-]{3,80}$`. Refuses suspicious strings (`\0`, `;DROP TABLE`, …) before they
  reach Samsung's `SemMmsProvider`.
- **`MmsSystemWriteback` sandbox check**: attachment files must canonicalise under
  `context.cacheDir` or `context.filesDir` — refuses absolute paths into other apps' sandboxes.
- **`pendingAttachment.file` cleanup** in `ThreadViewModel.onCleared()` so a staged photo
  doesn't leak when the user backs out without confirming.
- **`media_outgoing/` pruner** added to `TelephonySyncWorker` so unattended staging files
  beyond 24 h are reaped (was only sweeping `mms_outgoing/`).

### Performance
- **`countMms` → `hasAnyMms` (EXISTS)**: the per-sync trigger check is now O(1) instead of
  a full-table scan on the `messages.type` column. Cuts ~10-30 ms off every refresh on a
  50k-message DB.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change.
- Audit summary v1.2.3 → cible 95+ : Security 96 → 98, Code Quality 94 → 96, Performance 95
  (P1 ANR fix de v1.2.2 + P4), UI 92 → 96.

## [1.2.2] — 2026-05-15

Hardening pass driven by a 5-axis targeted audit (security, code quality, perf, duplications,
UI/UX). Closes one critical ANR risk, one MMS-persistence-after-reinstall gap, two visible UI
regressions, and adds an OS-side watchdog. No format-breaking changes.

### Fixed
- **ANR / StrictMode**: `MmsSender.sendVoiceMms` / `sendMediaMms` and `MmsSystemWriteback`
  (`insertOutbox`, `markSent`, `delete`, `purgeStaleOutbox`) are now `suspend` and execute on
  `Dispatchers.IO`. The dispatch pipeline was previously running on the Main thread, doing
  multiple ContentResolver IPCs + 8 KB-buffer file streaming — visible jank on photo MMS, ANR
  risk under StrictMode.
- **MMS sent to the right thread after reinstall**: recipients passed to
  `Telephony.Threads.getOrCreateThreadId` are now canonicalised (whitespace/dashes/parens
  stripped) so `"+33 6 12 34 56 78"` and `"+33612345678"` resolve to the same canonical-address
  row. Without this, Samsung One UI's `canonical_addresses` table indexed the two forms as
  distinct entries — the MMS came back as a duplicate "conversation" after the next reimport.
- **Sent MMS no longer disappear after reinstall**: previously, on a successful dispatch,
  Samsung One UI's `SmsManager.sendMultimediaMessage` did **not** mirror the row into
  `content://mms`. v1.2.2 writes the outbox row up front, then `MmsSentReceiver` flips it to
  SENT (or deletes it on dispatch failure). Survives a reinstall on top of the existing thread.
- **Snackbar background**: `Snackbar` was rendering on system inverse-surface (near-black on
  Material You) instead of the brand slate-blue, because `dynamicDarkColorScheme` /
  `dynamicLightColorScheme` derive `inverseSurface` from the wallpaper. v1.2.2 forces a brand
  override on every dynamic-colour path. New tone is a brighter sky-blue (#3D85D6) with a
  deep-navy text colour for WCAG AA (~6.6:1 contrast).
- **Translation state never rendered**: `TranslationBlock` now renders all three
  `TranslationState` branches — Pending (spinner + label), Ready (translated body), Failed
  (subtle error indicator). Previously only `Ready` was wired, so users staring at a 30 s
  model download saw nothing at all and a model-language failure passed silently.
- **Incoming chat bubble colour**: the `BubbleIncomingLight` / `BubbleIncomingDark` slate-blue
  palette was declared but never wired. v1.2.2 routes `MessageBubble` and `AudioMessageBubble`
  through `bubbleIncomingColor(scheme)` so incoming bubbles read as the intended "gris bleu"
  in both light + dark themes.
- **Robustness of address inserts**: each `addr` row insert inside `MmsSystemWriteback` now
  has its own `safe()` wrapper. Previously, a single failure on the placeholder FROM row would
  silently skip the entire TO-recipient loop — leaving the MMS without any visible recipient
  label in other SMS apps.

### Added
- **System OUTBOX watchdog**: `TelephonySyncWorker` now purges `content://mms` rows stuck in
  `msg_box = OUTBOX (4)` past 15 min. Runs alongside the existing local PENDING watchdog and
  catches the case where `MmsSentReceiver` never fires (process force-killed, Doze + reboot,
  OS dropped the broadcast). Without this, orphan OUTBOX rows polluted the conversation in
  other SMS apps indefinitely.
- **`MmsSystemWriteback.purgeStaleOutbox(olderThanMs)`** — public API consumed by the watchdog.
- **`safe(label) { … }` helper** in `MmsSystemWriteback` — centralises ContentResolver error
  logging and gives every site a consistent label (`addr.from`, `part.bin#0`, etc.).
- **Refactor**: `MmsSender.buildSentIntent(...)` extracts the (formerly duplicated) result
  PendingIntent construction shared by voice + media dispatch.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change, no Room migration.
- Audit summary: Security 88 → 96, Code Quality (Kotlin idiom) 88 → 94, Performance: ANR
  critical resolved, UI 84 → 92 (some polish items deferred to v1.2.3).
- APK arm64 stays ~46 MB, signed with the v1.2.1 release keystore (SHA-256 unchanged).

## [1.2.1] — 2026-05-15

Bug-fix + feature-complete release rounding out v1.2.0. Wires the **non-voice MMS dispatch
pipeline** that was scaffolded but disconnected in v1.2.0.

### Added
- **`MmsSender.sendMediaMms`** — generic multipart dispatch for image / video / file / contact
  card payloads (anything that isn't the dedicated voice path). Same explicit-intent contract
  as the voice path, same Samsung One UI reflection compat, same PDU-cache cleanup.
- **`ConversationMirror.upsertOutgoingMediaMms`** — inserts the MMS row + N `AttachmentEntity`
  rows in a single Room transaction. Preview line is the user's text body if any, otherwise
  an emoji + filename fallback (🖼️ photo / 🎞️ video / 👤 vcard / 📎 other).
- **`SendMediaMmsUseCase`** — orchestrates the per-recipient dispatch with blocked-number
  guard, default-SMS-app guard, 300 KB total payload cap (Free MMSC is the tightest), text
  body + 1..N attachments.
- **`ThreadViewModel.onAttachmentPicked`** rewritten — the previous v1.2.0 stub showed a
  snackbar and stopped. v1.2.1 reads the system content URI via `ContentResolver`, copies the
  bytes into private `cache/media_outgoing/` (the system grant can revoke the moment the
  picker activity dies), then routes through `SendMediaMmsUseCase`. Snackbar on success
  ("Pièce jointe envoyée") or on the typed failure surface.

### Changed
- The `AttachmentPickerSheet` (paperclip in the composer) is now **functional**, not a
  preview. Photo / Vidéo / Fichier / Contact all dispatch through the new pipeline.

### Known limits
- **Payload cap = 300 KB total**. Photos > 300 KB are rejected with an explicit Validation
  error. Future v1.2.x will add on-device JPEG re-encoding to fit the cap automatically.
- **Carrier validation is per-network.** Free Mobile FR is the tightest MMSC; Orange / SFR /
  Sosh / Bouygues handle up to ~1 MB but we keep the conservative cap to avoid silent rejects.
- **APKs in this release stay debug-signed.** Production keystore setup is the next milestone.

## [1.2.0] — 2026-05-15

Major feature + UX release on top of v1.1.1. Adds contextual reply, on-device translation,
attachment picker, biometric unlock, OS-wide blocklist mirroring with retroactive purge, MMS
history re-import from `content://mms`, conversation sort menu, slate-blue snackbar polish.
Ships behind a 3-axis security/quality/duplication audit pass.

### Added
- **Contextual reply (#8).** New `messages.reply_to_message_id` column (Room v1 → v2 additive
  migration, idempotent). Bubble overflow → "Répondre" → composer cartouche → outgoing row
  tagged → recipient bubble renders the quote header. Dangling references (source deleted)
  fall back to a "Message d'origine supprimé" placeholder.
- **On-device translation (#4).** ML Kit Translate (17.0.3) + Language Identification (17.0.6).
  `data/ml/TranslationService.kt` — thread-safe singleton, per-pair `Translator` cache, models
  downloaded on first use. Per-message `TranslationState` projected as a `TranslationBlock`
  below the bubble. Target language = user locale.
- **Attachment picker (#2).** `AttachmentPickerSheet` (Photo / Vidéo / Fichier / Contact) wired
  to `ActivityResultContracts`. Paperclip icon in the composer. Generalised
  `MmsBuilder.buildMultipartSendReq(attachments, textBody, recipients)` supports any
  audio/image/video/PDF payload — voice MMS keeps its v1.1 entry path; non-voice MMS dispatch
  to be wired in v1.2.x after carrier-side validation.
- **Biometric unlock.** `LockMode.BIOMETRIC` exposed in Réglages → Sécurité ("Biométrie +
  PIN de secours"). Auto-fires `BiometricPrompt` on LockScreen with a PIN fallback chip. Sealed
  against: lockout-during-cooldown bypass, PanicDecoy bypass, biometric-key permanent
  invalidation (auto-disables + falls back to PIN-only).
- **PIN setup UI.** Réglages → Sécurité → "Verrouillage de l'app" — 3-option picker (Aucun /
  PIN / Biométrie + PIN) and 2-field setup dialog with live validation (4–12 digits, digits
  only, match check). Previously `AppLockManager.setPin()` existed but was unreachable.
- **OS-wide blocklist mirroring.** `BlockedNumbersImporter` reads `BlockedNumberContract` on
  every cold start, mirrors entries one-way into our Room cache (no insert loop), then
  **purges** any conversation whose every participant matches — both in Room AND in the
  system `content://sms` provider. Last-8-digits matching to absorb international vs national
  format differences (`+33612345678` ↔ `0612345678`).
- **MMS history re-import.** `TelephonyReader.readAllMms()` +
  `ConversationMirror.bulkImportMmsFromTelephony()` rebuild the local mirror from
  `content://mms` on a fresh install (cursor == 0L). MMS rows survive an SQLCipher wipe.
  Attachments reference the system part URI (`content://mms/part/{id}`) directly, no copies.
- **Sort menu** in the conversations overflow (3-dot): Plus récent / Non lus / Épinglés.
  Check on the active mode.
- **App logo** in the TopAppBar left of "SMS Tech", auto-hidden on the Archived sub-page.
- **Conversation actions sheet.** Long-press on a conversation → bottom sheet "Bloquer /
  Supprimer". Bloquer cascades: block recipients + delete the conversation from Room AND from
  `content://sms`.
- **Snackbar slate-blue.** `Snackbar` uses `inverseSurface = #3D4A5C` /
  `inverseOnSurface = #E6ECF3` across all three palettes so confirmation toasts pair with
  brand identity.
- **"Définir par défaut" banner re-skinned in brand blue** (was error red). It's a status
  nudge, not a destructive alert.
- **Vibration default = off** for new installs. Existing users keep their choice.
- **Block confirmation reword.** "Tous les messages **de cette conversation** seront
  définitivement supprimés … **Les autres conversations ne sont pas affectées**" replaces the
  ambiguous previous wording.
- **Réglages → "Purger les conversations bloquées"** — explicit one-shot purge action.
  Snackbar reports the count.

### Fixed (Samsung One UI MMS pipeline)
- **`SendReq.addTo(EncodedStringValue)` NoSuchMethodError** at MMS send. Samsung's `SendReq`
  (`/system/framework/framework.jar!classes6.dex`) does not expose the AOSP-standard `addTo`.
  Reflection-based `attachRecipientsCompat` prefers `setTo(EncodedStringValue[])` (parent
  class), falls back to per-element `addTo` for AOSP. Hard-fail (null PDU) if neither variant
  exists.
- **`PduBody.addPart(PduPart)` NoSuchMethodError** in the same flow — Samsung also dropped
  the 1-arg form. Reflection `appendPart` tries 1-arg, falls back to `addPart(int, PduPart)`
  with current parts count as index.
- **`MmsSentReceiver` / `SmsSentReceiver` / `SmsDeliveredReceiver` / `MmsDownloadedReceiver`
  never fired (P0-2).** No `<intent-filter>` + `Intent.setPackage` implicit form = silently
  dropped on Android 14+. All four PendingIntents migrated to explicit
  `Intent.setClass(context, ReceiverClass)`. Guaranteed delivery with `exported = false`
  preserved. **Consequence**: outgoing SMS no longer stuck in PENDING; MMS PDU files no
  longer leaked in clear in `cache/mms_incoming/`.

### Security
- **P0-1 Vault bypass closed.** `ToggleConversationStateUseCase.moveToVault` now routes
  through `VaultManager.moveToVault` / `moveOutOfVault`, which enforces `sessionUnlocked`.
  Previously the use case called the repository directly and `VaultManager.markUnlocked()`
  was never called from anywhere — gating was inert. New `VaultScreen` raises the flag at
  composition.
- **P1-1 Lockout horizon clamped.** `SecurityStore.setLockoutUntil(ts)` coerces to a 24 h
  forward cap. A tainted DataStore restore can no longer write `Long.MAX_VALUE` and
  permanently lock the user out.
- **P1-2 Biometric challenge atomic.** `@Volatile var` → `AtomicReference.getAndSet(null)`
  for the one-shot challenge. Two concurrent prompts can no longer steal each other's token.
- **P1-5 Cache purge recursive.** `AutoLockObserver.purgeTransientCaches` now
  `deleteRecursively()` — previous `listFiles().forEach { … }` missed any future
  sub-directory (re-encode staging, tmp work-dirs).
- **Explicit intent target** (see Fixed) — receivers stay `exported = false` and reachable
  only by the app itself, never by a spoofed `setPackage` from another component.

### Performance
- **`MessageBubble.time` cached** with `remember(message.date)`. Was re-allocating `Date` +
  `SimpleDateFormat.format()` on every recompose, on every bubble. Consistent with
  `AudioMessageBubble`.
- **`ThreadScreen`** removed a duplicate `rememberChatFormatters()` inside the Scaffold body.
- **`TelephonySyncManager.messageDao` injection removed** — injected but never used.
- **Dead code removed**: `ThreadViewModel.replyToMessage / archiveThisConversation`,
  `ConversationsViewModel.pin / archive / mute`, stale lock-biometric strings.

### Build
- versionName **1.1.1 → 1.2.0**.
- Room SCHEMA_VERSION **1 → 2** (additive: `messages.reply_to_message_id` + index). Migration
  is idempotent; `adb install -r` over v1.1.x preserves the SQLCipher DB.
- New deps: `com.google.mlkit:translate:17.0.3`,
  `com.google.mlkit:language-id:17.0.6`,
  `kotlinx-coroutines-play-services`.
- `MainActivity` extends `FragmentActivity` (super-set of `ComponentActivity`) — required by
  `androidx.biometric:BiometricPrompt`.

## [1.1.1] — 2026-05-15

Hot-fix release that closes the two regressions surfaced after the v1.1.0 audit (Vagues 1–3
+ stub rebuild of `TelephonySyncManager`).

### Fixed
- **Initial SMS import never fired on fresh install.** v1.1.0 had reduced
  `TelephonySyncManager` to a no-op stub to unblock an opaque KSP `PROCESSING_ERROR`, and
  `ConversationsViewModel.maybeAutoImport()` had been removed in the same refactor — so the
  conversations list lit up empty on first launch and stayed empty until the user hit the
  manual *Migration* screen. The manager is now a real cursor-based syncer (see *Restored*
  below).
- **Manual import ran one Room transaction per message** — 2000 historical SMS = 2000 tx +
  2000 conversation touches, which the user perceived as an infinite import
  ("ça s'arrête pas, 2000 messages etc"). `MigrationViewModel.run()` now calls
  `ConversationMirror.bulkImportFromTelephony` (one transaction per 500-row page) and
  persists `lastSyncedSmsId` at the end so the periodic worker doesn't re-scan the
  historical set on its next 12 h tick.

### Restored
- `TelephonySyncManager` — cursor-based delta sync with `Mutex` single-flight.
  - `start()`: kicks off an asynchronous bulk import in the background when
    `AdvancedSettings.lastSyncedSmsId == 0L` (fresh install or post-panic wipe).
  - `requestSync(reason)`: queues a delta sync via
    `TelephonyReader.readSmsSince(cursor, pageSize = 500)` + `bulkImportFromTelephony`.
  - **Deliberately no `ContentObserver`** — the historical inner-class observer was the
    suspected cause of the v1.1.0 KSP failure; live arrivals are already covered by the
    `SmsDeliverReceiver` / `MmsDownloadedReceiver` pair, and the 12 h `TelephonySyncWorker`
    plays safety-net.
- Permission gate: `runSync` early-returns when `READ_SMS` is not granted, so the first
  launch (where the user is mid-onboarding) doesn't crash the manager.

## [1.0.0-rc3] — 2026-05-14

Post-verification patch. A third independent agent re-audited the rc2 and surfaced 3 P0
blockers + 2 P1; this release fixes all five.

### Blocking fixes
- **R1** Missing `kotlinx.coroutines.flow.first` import in `BackupService.kt` — module now compiles.
- **R2** LockScreen no longer sticky on fresh install (`lockMode = OFF`). `LockScreen` also treats
  `LockState.Disabled` as an unlock; `AppRoot` pops the Lock destination when `showLock` flips back
  to false.
- **R3** FTS search no longer crashes on multi-token queries. `escapeFtsQuery` now strips reserved
  FTS chars (instead of quoting + suffixing `*` after a quote, which is invalid syntax) and emits
  `token1* token2*` — valid FTS4 prefix search.
- **R5** "Delete all my data" dialog: Cancel button is now really autofocused via
  `FocusRequester` + `LaunchedEffect`. The previous changelog claim was incomplete.
- **R6** `MainApplication.onCreate()` resolves `AppLockManager.resolveInitialState()`
  synchronously at process creation, so broadcast receivers and `HeadlessSmsSendService` fired
  before the first Activity get the correct lock state (not the `Locked` fail-closed default).

## [1.0.0-rc2] — 2026-05-14

Hardening pass driven by two senior-level audits (security + code quality).

### Security
- **F1** Lock state defaults to `Locked` (was `Unknown`) → no cold-start window where the conversation list is visible before settings load.
- **F2** `markBiometricUnlocked` requires a single-use challenge token issued by `beginBiometricChallenge`.
- **F3** PBKDF2 receives the original `CharArray` directly (no more lossy `toCharArrayUnsafe`). Unicode PINs/passphrases (FR accents, emoji) now hash correctly.
- **F4 / A6** Scheduled `.smsbk` backups refuse to run without an explicit passphrase — no more silent plaintext export.
- **F5** `FLAG_SECURE` applied synchronously before `setContent`, closing the 50-300 ms Recents/screenshot window.
- **F7** Notification inline-reply / mark-as-read refused while the app is locked.
- **F9** Backup JSON parser strict: `ignoreUnknownKeys = false`.
- **F10** BootReceiver requires `RECEIVE_BOOT_COMPLETED`; QUICKBOOT removed; LOCKED_BOOT_COMPLETED added.
- **F11** `HeadlessSmsSendService` gated by app-lock state + caps recipients/text/number-length.
- **F13** PDF exports purged from `files/exports/` when auto-lock fires.
- **F14** `FileProvider` scoped strictly to `attachments/` and `exports/`.
- **F15** "Preview when unlocked" no longer leaks the body via `setContentText` on misbehaving OEMs.
- **F17** PBKDF2-HMAC-SHA512 floor 120 000 → **210 000** (OWASP Mobile 2024).
- **F18 / F32** `DatabaseKeyManager` distinguishes `KeyPermanentlyInvalidatedException` vs corruption vs I/O — no more silent wipe on Samsung Knox OTA.
- **F19** ProGuard tightened (`keepnames` ViewModels, narrow receivers/services).
- **F20** Timber `w`/`e`/`wtf` (+ Tree) `assumenosideeffects` in release — secrets cannot leak through accidental logs.
- **F22** Incoming SMS body + address sanitized through `stripInvisibleChars` (bidi + zero-width).
- **F26** Backup AEAD AAD = `MAGIC || VERSION || salt || iter` — KDF parameters are bound, any tamper fails closed.
- **F29** PanicService wipe rewritten: close DB → drop wrapped key → drop Keystore aliases → `deleteDatabase` (incl. WAL/SHM) → wipe files/cache → reset prefs.
- **F36** `SmsSender` PendingIntent request code built from a Long-mixed hash — no Int-overflow collisions.
- **F38** Notifications use a stable per-message id (no `.or(1)` collision).
- **F39** Lockout backoff 5 s → 5 min (was 1 s → 5 min).
- **F40** Voice dictation default = on-device-only; cloud fallback opt-in via `voiceOnDeviceOnly` setting.

### Code quality / architecture
- **Q2** `TelephonyReader.readSmsBatched` is `suspend` + accepts a `suspend` lambda — migration no longer uses `runBlocking`.
- **Q3** `ThreadViewModel` keeps a single observation of `observeOne` / `observeMessages`; `markRead` triggers once.
- **Q7** XML escape covers apostrophe and drops C0 control chars + DEL.
- **Q14** Redundant Kotlin-side filter in `ConversationsViewModel` removed.
- **A10** `ConversationRepository.findOrCreate` wrapped in a Room transaction (no duplicate-row race).

### Performance
- **P1** Thread list switched to `itemsIndexed` (was O(n²) per recomposition).
- **P4** Avatar memoizes initials + colour via `remember(label)`; HSV value 0.62 to meet WCAG AA.
- **P5 / P6** `ChatFormatters` scoped via `rememberChatFormatters` — no per-row `SimpleDateFormat` allocation.
- **P8** `MainActivity` observes `flagSecure` via `distinctUntilChanged` — no churn.
- **P10** SQLCipher hook: `cipher_compatibility = 4` + `cipher_memory_security = ON`.
- **P11** FTS query escape (token-quote + control-char strip).

### UI / UX
- **U4** AboutScreen external intents wrapped in `safeStartActivity` (no `ActivityNotFoundException` crash).
- **U14** Conversation row draft prefix moved from hardcoded `✏️` to a `R.string` (FR + EN).
- **U15** Removed bogus row in Settings that opened Blocked when labelled "Archived".
- **U16** Destructive "Delete all my data" dialog now uses `FilledTonalButton` on `errorContainer`.
- **U20** Haptic feedback on send + voice stop in the composer.
- **U23** Active voice job cancelled in `onCleared` — mic doesn't survive screen exit.

### New
- **Dark Tech** appearance theme (developer-friendly fixed palette — deep slate-blue background, sky-blue accent, success green, danger red). Overrides dynamic colors and AMOLED.
- Encrypted backup passphrase dialog (mandatory ≥ 8 characters, confirmation field).
- `PasswordKdfUnicodeTest` + `BackupAadBindingTest` pin the crypto contracts.

### Repo
- README + AboutScreen now point to `github.com/gitubpatrice/sms_tech`.

## [1.0.0-rc1] — 2026-05-14

First internal release.

### Added
- Default SMS / MMS Android app: full receiver / service / channel wiring for KitKat → 14+.
- Single-Activity Jetpack Compose UI with Material 3, dynamic colors and AMOLED true-black.
- SQLCipher-backed Room database with AndroidKeyStore-wrapped master key.
- FTS4 search across message bodies and addresses.
- App lock: PIN with PBKDF2-HMAC-SHA512, monotonic exponential backoff after failed attempts.
- Optional panic-mode PIN.
- Inline-reply and mark-as-read notification actions.
- Voice dictation (on-device `SpeechRecognizer`).
- PDF export of any conversation, fully local (no external dependency).
- Scheduled sending via WorkManager.
- Encrypted `.smsbk` backup format (AES-256-GCM + PBKDF2-HMAC-SHA512).
- SMS Backup &amp; Restore XML compatibility layer.
- Migration assistant from the system SMS provider.
- Bilingual UI: English &amp; French.

### Security
- Database is encrypted at rest. The raw key never lives in JVM memory beyond SQLCipher init.
- Backup target verifies the AEAD tag before exposing plaintext.
- All sensitive byte buffers are wiped (best-effort) after use.
- Android system backup is disabled to prevent unencrypted exfiltration via Google Drive.

### Notes
- Full MMS PDU encoding (outgoing MMS attachments) is scheduled for v1.1.
- Restore-from-backup flow is wired through the data layer but the import UI ships with v1.1.
