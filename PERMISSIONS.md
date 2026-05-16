# Permissions used by SMS Tech

Each permission is declared with a clear reason. SMS Tech never escalates privileges, never holds
permissions "just in case", and degrades gracefully if any of them is refused.

| Permission                                   | Why we need it                                                                 |
|----------------------------------------------|--------------------------------------------------------------------------------|
| `SEND_SMS`                                   | Send text messages.                                                             |
| `RECEIVE_SMS`                                | Receive text messages.                                                          |
| `READ_SMS`                                   | Read the system inbox (mandatory for any default SMS app).                      |
| `WRITE_SMS`                                  | Mark messages read, insert sent rows (mandatory).                               |
| `RECEIVE_MMS` / `RECEIVE_WAP_PUSH`           | Receive MMS notifications (WAP push).                                           |
| `BROADCAST_WAP_PUSH`                         | Required pairing for the WAP_PUSH_DELIVER receiver.                             |
| `READ_CONTACTS`                              | Show names instead of bare phone numbers in conversations.                      |
| `READ_PHONE_STATE` / `READ_PHONE_NUMBERS`    | Detect dual-SIM and let you choose which SIM to send from.                      |
| `POST_NOTIFICATIONS` (API 33+)               | Show new-message notifications (no notifications without your consent).         |
| `USE_BIOMETRIC`                              | Optional biometric unlock.                                                      |
| `INTERNET` + `ACCESS_NETWORK_STATE`          | MMS transport via your carrier MMSC, on demand. No analytics, no update check.  |
| `FOREGROUND_SERVICE` (+ `DATA_SYNC` type)    | Long-running migration and backup jobs.                                         |
| `RECEIVE_BOOT_COMPLETED`                     | Reschedule pending scheduled messages after a reboot.                           |
| `USE_FULL_SCREEN_INTENT`                     | Reserved for future incoming-call-style critical notifications. Off by default. |
| `VIBRATE`                                    | Optional vibration on new messages.                                             |
| `RECORD_AUDIO`                               | Record audio clips attached to outgoing MMS (mic only while the user is actively recording).|

## What we do **not** request

- No location, no camera, no Bluetooth, no nearby devices, no usage stats.
- No `QUERY_ALL_PACKAGES` (we use targeted `<queries>` blocks only).
- No Play Services dependency.
- No advertising identifier.
