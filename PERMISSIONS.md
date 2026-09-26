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
| `FOREGROUND_SERVICE_SPECIAL_USE`             | The optional "keep alive" setting (off by default): a permanent notification that stops aggressive ROMs from killing the app. |
| `RECEIVE_BOOT_COMPLETED`                     | Reschedule pending scheduled messages after a reboot.                           |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Emergency mode only, and only if you turn it on and grant the permission: **one** position, read when you trigger the alert, added to the emergency SMS as a map link. No background or continuous location. Refused: the SMS leaves without coordinates. See [PRIVACY.md](PRIVACY.md). |
| `CALL_PHONE`                                 | Emergency mode only, and only if you choose "call directly": the app itself calls 112 or 17. By default it opens the dialer instead, which needs no permission. |
| `VIBRATE`                                    | Optional vibration on new messages.                                             |
| `RECORD_AUDIO`                               | Record audio clips attached to outgoing MMS (mic only while the user is actively recording).|
| `HIDE_OVERLAY_WINDOWS` (API 31+)             | Hide other apps' overlays while you type a PIN or passphrase, so a malicious window cannot harvest your keystrokes. Grants **no** access to any data — it only asks that third-party overlays be hidden above **our own** windows. Protection level `normal`: granted at install, no prompt. |

Two more permissions are declared by AndroidX libraries, not by SMS Tech's own manifest: `WAKE_LOCK`
(WorkManager, to finish a background job) and `USE_FINGERPRINT` (the Biometric library, for
Android 8). Both are install-time permissions that give access to no data.

## What we do **not** request

- No background location, no camera, no Bluetooth, no nearby devices, no usage stats.
- No `QUERY_ALL_PACKAGES` (we use targeted `<queries>` blocks only).
- No Play Services dependency.
- No advertising identifier.
