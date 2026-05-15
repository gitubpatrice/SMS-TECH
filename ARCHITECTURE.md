# SMS Tech — Architecture

## Layers

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                            ui/                                               │
│  Compose screens, Material 3 theme, type-safe Navigation, per-screen ViewModels exposing     │
│  StateFlow<UiState> and SharedFlow<UiEvent>.                                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼ injected UseCases
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                          domain/                                             │
│  Immutable models, repository interfaces, UseCases (SendSmsUseCase, RetrySendUseCase,        │
│  ExportConversationPdfUseCase, ScheduleMessageUseCase, …).                                   │
│  No Android imports here — pure Kotlin.                                                      │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼ Hilt bindings
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                           data/                                              │
│  Room (entities + DAOs + FTS4) + SQLCipher key managed by DatabaseKeyManager.                │
│  DataStore (Settings + Security).                                                            │
│  ContentResolver wrappers: TelephonyReader (SMS provider), ContactsReader,                   │
│  BlockedNumberSystem (BlockedNumberContract).                                                │
│  Repositories implement the domain interfaces and convert entities to domain models.         │
│  ConversationMirror is the single point of insertion into Room for incoming/outgoing SMS.    │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                          system/                                             │
│  BroadcastReceivers + Services + WorkManager workers + NotificationChannels.                 │
│  Bridges Android system events to the data layer.                                            │
└──────────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                         security/                                            │
│  AppLockManager (PIN + biometric), AutoLockObserver, VaultManager, PanicService.             │
│  Acts as a cross-cutting concern.                                                            │
└──────────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                           core/                                              │
│  Result/Outcome, AppError sealed, AeadCipher + KeystoreManager + PasswordKdf, logging.       │
│  Zero dependencies on the rest of the app.                                                   │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Lifecycle of an incoming SMS

1. The carrier sends the SMS to the modem.
2. Android dispatches a `SMS_DELIVER` broadcast to SMS Tech (because it is the default SMS app
   declared in the manifest with the `BROADCAST_SMS` permission).
3. `SmsDeliverReceiver.onReceive` is invoked. It calls `goAsync()` to keep the process alive while
   the work runs on the application coroutine scope.
4. Messages are reconstructed from the PDU array via `Telephony.Sms.Intents.getMessagesFromIntent`.
5. The sender is checked against the blocklist (`BlockedNumberRepository.isBlocked`).
6. If allowed:
   - `TelephonyReader.insertInboxSms` writes the row into the **system SMS provider** (otherwise
     the inbox would stay empty — this is a duty of the default app since KitKat).
   - `ConversationMirror.upsertIncomingSms` writes a typed, mirrored row into our SQLCipher-backed
     Room database, ensures the conversation exists, updates `lastMessageAt`, `lastMessagePreview`
     and increments `unreadCount`.
   - `IncomingMessageNotifier.notifyIncomingSms` posts a `MessagingStyle` notification with inline
     reply (`RemoteInput`) and mark-as-read actions, respecting the user's preview-visibility
     setting.

## Lifecycle of an outgoing SMS

1. UI builds an outgoing message in `ThreadViewModel.send` and calls `SendSmsUseCase`.
2. The use case:
   - Verifies SMS Tech is still the default app.
   - Skips recipients in the blocklist (defense in depth).
   - Inserts the row in the system Sent box.
   - Mirrors a `MessageEntity` with `status = PENDING` into Room.
   - Dispatches the message through `SmsSender.send`, which calls
     `SmsManager.sendMultipartTextMessage` with `PendingIntent`s carrying the local Room id.
3. `SmsSentReceiver` and `SmsDeliveredReceiver` update the mirrored row to `SENT` / `DELIVERED` /
   `FAILED` as the modem reports back.

## Lifecycle of an outgoing MMS (voice clip)

1. `ThreadViewModel` enters Recording state when the user holds the mic button (push-to-talk).
   `VoiceRecorder` writes an AAC/M4A clip to `cache/voice_mms/` (60 s / 300 KB hard caps).
2. On release without drag-to-cancel, the composer switches to Reviewing and `VoicePlaybackController`
   plays the clip back through a single shared MediaPlayer instance.
3. Send button → `SendVoiceMmsUseCase`:
   - Default-app guard, blocklist guard, then for each non-blocked recipient:
     - `ConversationMirror.upsertOutgoingMms` writes an MMS row + `AttachmentEntity` referencing
       the audio file by absolute path.
     - `MmsSender.sendVoiceMms` builds a `SendReq` PDU through the in-tree
       `com.google.android.mms.pdu.*` classes (ported from AOSP under Apache-2.0), persists the
       encoded bytes to `cache/mms_outgoing/`, shares them via FileProvider, and hands the Uri to
       `SmsManager.sendMultimediaMessage` together with a result `PendingIntent`.
   - The OS routes the PDU to the carrier MMSC; SMS Tech does not touch HTTP / APN itself.
4. `MmsSentReceiver` flips the row to `SENT` or `FAILED` based on the broadcast resultCode and
   deletes the transient `.pdu` cache file.

## Lifecycle of an incoming MMS

1. The carrier pushes a `WAP_PUSH_DELIVER` intent. `MmsWapPushReceiver` parses the
   `m-notification.ind` PDU via `PduParser`, extracts the `contentLocation` URL + `transactionId`,
   and refuses anything larger than 1 MiB (defence-in-depth — SMS Tech only consumes ≤ 300 KB clips).
2. `MmsDownloader.download` creates an empty file in `cache/mms_incoming/`, shares it through
   FileProvider, and calls `SmsManager.downloadMultimediaMessage`. The OS performs the MMSC HTTP
   GET and writes the `RetrieveConf` PDU bytes into the file.
3. `MmsDownloadedReceiver` parses the result with `PduParser`. The first audio `PduPart` is
   persisted to `cache/mms_incoming_audio/` and `ConversationMirror.upsertIncomingMms` mirrors a
   typed Room row + `AttachmentEntity`. The conversation list, the thread view, and notifications
   pick up the change through the standard Flow pipeline.

## Encryption at rest

- **Database**: SQLCipher 4 with a 32-byte random key. The key file lives at
  `files/db/master.key` and is wrapped by an AES-256-GCM key in the AndroidKeyStore
  (`KeystoreManager.ALIAS_DB_MASTER`). The raw key is wiped from memory immediately after
  SQLCipher consumes it.
- **Backups** (`.smsbk`): PBKDF2-HMAC-SHA512 derives a 32-byte key from the user's passphrase,
  combined with a fresh 16-byte salt. Iterations are calibrated at first run (~250 ms target).
  The resulting key encrypts the JSON payload via AES-256-GCM with a 12-byte IV and a 128-bit tag.
- **App lock PIN**: PBKDF2-HMAC-SHA512 hash stored alongside the random salt and iteration count.
  Verification uses a constant-time comparison. After 5 consecutive failures, a monotonic
  exponential backoff blocks further attempts.

## Code quality gates

- **Ktlint** + **Detekt** + Android Lint must pass with zero warnings as errors on CI.
- Coverage target: ≥ 70 % on `domain/` and `data/`.
- Strict null safety, no `!!` outside test code.
- All I/O on `Dispatchers.IO`; structured concurrency only (no `GlobalScope`).
- All user-facing strings live in `res/values/strings.xml` (`values-fr` mirror is the same set).
