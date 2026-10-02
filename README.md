# SMS Tech

English · 🇫🇷 [Version française](README.fr.md)

A modern, private SMS &amp; MMS app for Android — built with Kotlin, Jetpack Compose and Material 3.
No ads, no trackers, no analytics, **no Internet permission**. Part of the **Files Tech** suite.
Apache-2.0 licensed.

Source: [github.com/gitubpatrice/SMS-TECH](https://github.com/gitubpatrice/SMS-TECH) ·
Website: [files-tech.com/sms-tech.php](https://files-tech.com/sms-tech.php) ·
Releases: [GitHub Releases](https://github.com/gitubpatrice/SMS-TECH/releases/latest)

## ✨ Features

- Default SMS / MMS app for Android 8.0 and later (tested up to Android 16), with a one-time import
  of your existing messages.
- Single-Activity Compose UI, Material 3 with dynamic colors, AMOLED true-black and a "Dark Tech"
  theme.
- Encrypted Room database (SQLCipher), master key wrapped by the AndroidKeyStore.
- App lock: PIN with PBKDF2-HMAC-SHA512 and exponential backoff after failed attempts; optional
  biometric unlock with a mandatory fallback PIN.
- **Vault** for sensitive conversations: hidden from the main list and opened with its own PIN,
  passphrase or biometrics. It lives in the same SQLCipher-encrypted database, not in a separately
  keyed envelope — see [SECURITY.md](SECURITY.md) for the threat model and its limits.
- Optional **decoy PIN**: entering it opens the app in a session where the vault, emergency mode and
  Safety call are invisible and unreachable.
- **Emergency mode**: hold a button for three seconds to text your chosen contacts, with your location
  if you allow it (precise, or approximate and marked as such), and call tiles for the emergency
  numbers of the country your phone is registered in. **Safety call** texts the same contacts if you
  do not open the app within a delay you set. These features help; they do not replace emergency
  services — see the [terms of use](TERMS.md).
- **Voice messages** recorded on the device and sent as audio MMS.
- Named groups and group MMS, contextual replies with quotes, inline reply from notifications.
- Offline **scam detection** (shortened links, urgency wording, premium numbers, spoofed domains), on
  rules: it can be wrong both ways.
- Blocking synchronized with the Phone / Samsung Messages blocklist; optional blocking of unknown
  numbers.
- Full-text search (FTS4), **PDF export** of a conversation, scheduled sending (WorkManager).
- Manual backup &amp; restore in `.smsbk` (AES-256-GCM + PBKDF2). Attachments are not part of the
  backup.
- Five languages: English, French, German, Italian and Spanish — including every SMS the app sends
  on your behalf. Pick one per app on Android 13 and later, or follow the system.
- F-Droid friendly: no Google libraries, no proprietary blobs, reproducible build.

## 🔐 Privacy

We collect **nothing**. No analytics, no crash reporting, no remote logging.
SMS Tech makes no network call: it does not hold the `INTERNET` permission, so its process cannot
open a connection. MMS are carried by Android's own MMS service, which talks to your carrier's MMSC.

SMS is **not** end-to-end encrypted at the protocol level — this is a limitation of the carrier
network, not a choice. SMS Tech protects what's stored on your device, with SQLCipher + the
AndroidKeyStore. For true end-to-end encryption, use Signal or Matrix.

See the [privacy policy](PRIVACY.md), the [terms of use](TERMS.md) and
[PERMISSIONS.md](PERMISSIONS.md). Security model: [SECURITY.md](SECURITY.md)
([français](SECURITY.fr.md)). The privacy policy and the terms exist in English, French, German,
Italian and Spanish; English and French are authoritative.

## 📦 Build

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/*.apk
./gradlew test detekt ktlintCheck lintDebug
```

Requires JDK 17. Min SDK 26 (Android 8.0), compile SDK 37, target SDK 35.

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

## 📃 License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party libraries and their licences:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
© 2026 Patrice Haltaya.
