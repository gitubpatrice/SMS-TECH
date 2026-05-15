# SMS Tech &nbsp; [`github.com/gitubpatrice/sms_tech`](https://github.com/gitubpatrice/sms_tech)

A modern, private SMS &amp; MMS app for Android — built with Kotlin, Jetpack Compose and Material 3.
No ads, no trackers, no analytics. Apache-2.0 licensed.

> **Status**: v1.0.0 — first public release.

## ✨ Features

- Default SMS / MMS app for Android (KitKat → Android 14+).
- Single-Activity Compose UI, Material 3 with dynamic colors + AMOLED true-black.
- Encrypted Room database (SQLCipher), AndroidKeyStore-wrapped master key.
- App lock: PIN with PBKDF2-HMAC-SHA512, monotonic exponential backoff after failed attempts.
- Optional **panic-decoy** PIN: when entered, the app opens in a session where the **vault**
  (a UI-level folder of hidden conversations) is fully invisible — neither its top-bar entry
  point nor its rows / messages are reachable, even via saved nav state or share-target deep
  links. Note: the vault is currently a logical UX boundary inside the (already-encrypted by
  SQLCipher) Room database, not a separately-keyed cryptographic envelope. A second-layer
  vault crypto keyed by an `setUserAuthenticationRequired = true` Keystore alias is planned
  for v1.1.1 — see `SECURITY.md` for the threat model and the explicit limit.
- Inline reply &amp; mark-as-read actions on notifications, per-conversation overrides.
- **Voice SMS** — on-device dictation via `SpeechRecognizer` (no network).
- **PDF export** of any conversation, generated locally via `PdfDocument` (no external lib).
- Scheduled sending via `WorkManager` (exact alarms when granted).
- Backup &amp; restore in `.smsbk` (AES-256-GCM + PBKDF2) and XML SMS-Backup-Restore compat.
- Migration assistant: read the system SMS provider once SMS Tech is the default app.
- Bilingual UI: English &amp; French, switchable at runtime.
- F-Droid friendly: no Google libraries, no proprietary blobs.

## 📦 Build

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/*.apk
./gradlew test detekt ktlintCheck lintDebug
```

Requires JDK 17. Min SDK 26 (Android 8.0). Compile/Target SDK 35.

## 🏗️ Architecture

```
core/      Result, AppError, crypto (AES-GCM + Keystore + PBKDF2), Timber wrapper
data/      Room (entities, DAOs, FTS4), DataStore, ContentResolver wrappers, repositories
domain/    Immutable models, repository interfaces, UseCases
system/    Receivers (SMS_DELIVER, WAP_PUSH, sent/delivered, boot), Services
           (HeadlessSmsSendService), Notifications (channels, MessagingStyle, inline reply),
           Schedulers (WorkManager)
security/  AppLockManager, AutoLockObserver (ProcessLifecycleOwner), VaultManager, PanicService
ui/        Theme (M3, dynamic, AMOLED), Navigation (type-safe), Screens (Compose), ViewModels
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full picture and the end-to-end flow of a received SMS.

## 🔐 Privacy

We collect **nothing**. No analytics, no crash reporting, no remote logging.
The only network calls SMS Tech can make are MMS transport via your carrier MMSC (`INTERNET`
permission), and only when you actually send / receive an MMS.

SMS is **not** end-to-end encrypted at the protocol level — this is a limitation of the carrier
network, not a choice. SMS Tech protects what's stored on your device, with SQLCipher + the
AndroidKeyStore. For true end-to-end encryption, use Signal or Matrix.

See [PRIVACY.md](PRIVACY.md) and [PERMISSIONS.md](PERMISSIONS.md).

## 📃 License

Apache License 2.0 — see [LICENSE](LICENSE).
© 2026 Patrice Haltaya.
