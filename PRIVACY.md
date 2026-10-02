# Privacy Policy — SMS Tech

_Last updated: 2 October 2026_ · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Reference versions.** The English and [French](PRIVACY.fr.md) versions of this policy are both
> authoritative. The German, Italian and Spanish translations are provided for information; in case
> of discrepancy, the English and French versions prevail.

SMS Tech (`com.filestech.sms`) is part of the **Files Tech** suite, published by **Patrice
Haltaya**. See also the [terms of use](TERMS.md).

## What we collect

**Nothing.** SMS Tech does not collect, transmit or aggregate any personal data of any kind.

There is no analytics SDK, no crash reporter, no telemetry endpoint, no advertising identifier, no
fingerprinting library. The binary contains no third-party tracking code.

## What stays on your device

- SMS & MMS messages, stored in an encrypted Room database (SQLCipher) protected by a key wrapped
  by the AndroidKeyStore.
- Conversation metadata (drafts, pinning, archiving, vault flag, per-conversation overrides).
- Settings, in Android DataStore Preferences.
- A salted PBKDF2-HMAC-SHA512 hash of your app-lock PIN (the PIN itself is never stored).
- Optional MMS attachments, in `<files>/mms_attachments/`.
- Optional locally-generated PDF exports of conversations, in `<files>/exports/`.
- If you set them up, your emergency contacts and the settings of the emergency mode and of Safety
  call.

Like any default SMS app, SMS Tech also writes your messages to Android's own message store, which
keeps them independently of the app.

The Android system backup is **disabled** so this data does not get synced to Google Drive or to a
device transfer without your explicit consent.

## Network use

SMS Tech makes no network call. Since 1.28.13 it does not even hold the `INTERNET` permission, so
its process cannot open a network connection. MMS are carried by Android's own MMS service, which
talks to your carrier's MMSC when you send or receive one. No update check, no remote configuration,
no analytics ping.

The messages you send travel through your carrier's network, as with any SMS app. The developer
never receives them.

## Emergency features (off until you turn them on)

- **Emergency mode.** When you hold the emergency button for three seconds, SMS Tech sends an SMS
  to the emergency contacts **you** chose. If you granted the location permission, precise or
  approximate, and left "include my location" on (it is on by default in this mode), the app asks
  Android for **one** position at that moment: it reuses a position less than five minutes old if
  Android has one, otherwise it waits at most eight seconds for a new fix, and failing that reuses
  the last known position if it is less than thirty minutes old. No background or continuous
  tracking. The position is added to the SMS as a `https://maps.google.com/?q=…` link; when it is
  only approximate, Android shifts it by about two kilometres and the SMS says so with "(+/-N km)"
  after the link. If a contact opens that link, their browser contacts Google Maps; the app itself
  sends nothing to Google. If location is refused or unavailable, the SMS says so and leaves without
  coordinates.
- **Safety call.** If you do not use the app before the delay you set, SMS Tech sends a check-in
  SMS to the same contacts. It carries no location.
- **Emergency call.** The call tiles of the Emergency screen offer the emergency numbers of the
  country where your phone is registered on the network (112 is always there). When you tap one,
  the app asks for the call permission (`CALL_PHONE`) and, if you grant it, places the call itself;
  if you refuse, it opens the phone dialer pre-filled, which needs no permission. The lock-screen
  shortcut always opens the dialer.
- None of these run in the decoy session opened by the panic code.
- These messages are ordinary SMS: your carrier may charge them according to your plan.

## Permissions

See [PERMISSIONS.md](PERMISSIONS.md) for the justification of every permission used.

## Your rights

Since no personal data leaves your device, there is nothing for you to access, rectify, transfer or
delete from any remote system. To remove data from SMS Tech, either uninstall the app (Android
wipes its data automatically) or use **Settings → Delete all my data** which performs a panic-wipe
of the encrypted database, Keystore aliases, cached attachments and PDF exports. Messages kept by
Android's own message store are managed from Android or from any SMS app.

## Contact

For any privacy-related question: **contact@files-tech.com**.
