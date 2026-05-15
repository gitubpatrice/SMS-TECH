# Privacy Policy — SMS Tech

_Last updated: 2026-05-14._

## What we collect

**Nothing.** SMS Tech does not collect, transmit or aggregate any personal data of any kind.

There is no analytics SDK, no crash reporter, no telemetry endpoint, no advertising identifier, no
fingerprinting library. The binary contains no third-party tracking code.

## What stays on your device

- SMS &amp; MMS messages, stored in an encrypted Room database (SQLCipher) protected by a key wrapped
  by the AndroidKeyStore.
- Conversation metadata (drafts, pinning, archiving, vault flag, per-conversation overrides).
- Settings, in Android DataStore Preferences.
- A salted PBKDF2-HMAC-SHA512 hash of your app-lock PIN (the PIN itself is never stored).
- Optional MMS attachments, in `<files>/mms_attachments/`.
- Optional locally-generated PDF exports of conversations, in `<files>/exports/`.

The Android system backup is **disabled** so this data does not get synced to Google Drive or to a
device transfer without your explicit consent.

## Network use

SMS Tech makes no network call by default. The `INTERNET` permission is declared exclusively for
MMS transport via your carrier's MMSC, and is only used when the user actually sends or receives an
MMS. No update check, no remote configuration, no analytics ping.

## Permissions

See [PERMISSIONS.md](PERMISSIONS.md) for the justification of every permission used.

## Your rights

Since no personal data leaves your device, there is nothing for you to access, rectify, transfer or
delete from any remote system. To remove data from SMS Tech, either uninstall the app (Android
wipes the data automatically) or use **Settings → Delete all my data** which performs a panic-wipe
of the encrypted database, Keystore aliases, cached attachments and PDF exports.

## Contact

For any privacy-related question: `contact@files-tech.com`.

---

# Politique de confidentialité — SMS Tech

_Dernière mise à jour : 14 mai 2026._

## Ce que nous collectons

**Rien.** SMS Tech ne collecte, ne transmet ni n'agrège aucune donnée personnelle.

Aucun SDK d'analytique, aucun rapporteur de crash, aucun endpoint de télémétrie, aucun identifiant
publicitaire, aucune bibliothèque de fingerprinting. Le binaire ne contient aucun code de pistage
tiers.

## Ce qui reste sur votre appareil

- Vos SMS et MMS, dans une base Room chiffrée (SQLCipher) protégée par une clé enrobée par
  l'AndroidKeyStore.
- Les métadonnées de conversation (brouillons, épinglage, archivage, coffre-fort, préférences par
  conversation).
- Les réglages, dans Android DataStore Preferences.
- Un hash salé PBKDF2-HMAC-SHA512 de votre code PIN (le PIN n'est jamais stocké en clair).
- Les pièces jointes MMS éventuelles, dans `<files>/mms_attachments/`.
- Les éventuels PDF de conversation générés localement, dans `<files>/exports/`.

La sauvegarde Android système est **désactivée** : ces données ne partent ni sur Google Drive ni
lors d'un transfert d'appareil sans votre accord explicite.

## Réseau

SMS Tech n'émet aucune requête réseau par défaut. La permission `INTERNET` n'est déclarée que pour
le transport MMS via le MMSC de votre opérateur, et n'est utilisée qu'au moment de l'envoi ou de la
réception effective d'un MMS. Aucune vérification de mise à jour, aucune configuration distante,
aucun ping analytique.

## Vos droits

Aucune donnée personnelle ne quittant votre appareil, il n'y a rien à consulter, rectifier ou
supprimer auprès d'un système distant. Pour effacer vos données dans SMS Tech : désinstallez
l'application (Android purge les données automatiquement) ou utilisez **Réglages → Supprimer
toutes mes données**, qui efface la base chiffrée, les alias Keystore, les pièces jointes en
cache et les PDF exportés.

## Contact

Pour toute question : `contact@files-tech.com`.
