# Privacy Policy — SMS Tech

_Last updated: 2026-09-26._

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
- If you set them up, your emergency contacts and the settings of the emergency mode and of Safety
  call.

The Android system backup is **disabled** so this data does not get synced to Google Drive or to a
device transfer without your explicit consent.

## Network use

SMS Tech makes no network call by default. The `INTERNET` permission is declared exclusively for
MMS transport via your carrier's MMSC, and is only used when the user actually sends or receives an
MMS. No update check, no remote configuration, no analytics ping.

The messages you send travel through your carrier's network, as with any SMS app. The developer
never receives them.

## Emergency features (off until you turn them on)

- **Emergency mode.** When you hold the emergency button for three seconds, SMS Tech sends an SMS
  to the emergency contacts **you** chose. If you granted the location permission and left
  "include my location" on (it is on by default in this mode), the app asks Android for **one**
  position at that moment, or reuses one less than five minutes old — no background or continuous
  tracking — and adds it to the SMS as a `https://maps.google.com/?q=…` link. If a contact opens that link, their
  browser contacts Google Maps; the app itself sends nothing to Google. If location is refused or
  unavailable, the SMS says so and leaves without coordinates.
- **Safety call.** If you do not use the app before the delay you set, SMS Tech sends a check-in
  SMS to the same contacts. It carries no location.
- **Emergency call.** By default, the emergency button opens the phone dialer pre-filled. Only if
  you choose "call directly" in the settings does the app place the call itself (112 or 17), which
  is what the `CALL_PHONE` permission is for.
- None of these run in the decoy session opened by the panic code.
- These messages are ordinary SMS: your carrier may charge them according to your plan.

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

_Dernière mise à jour : 26 septembre 2026._

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
- Si vous les configurez, vos contacts d'urgence et les réglages du mode urgence et du Safety call.

La sauvegarde Android système est **désactivée** : ces données ne partent ni sur Google Drive ni
lors d'un transfert d'appareil sans votre accord explicite.

## Réseau

SMS Tech n'émet aucune requête réseau par défaut. La permission `INTERNET` n'est déclarée que pour
le transport MMS via le MMSC de votre opérateur, et n'est utilisée qu'au moment de l'envoi ou de la
réception effective d'un MMS. Aucune vérification de mise à jour, aucune configuration distante,
aucun ping analytique.

Les messages que vous envoyez passent par le réseau de votre opérateur, comme avec toute application
de SMS. Le développeur ne les reçoit jamais.

## Fonctions d'urgence (inactives tant que vous ne les activez pas)

- **Mode urgence.** Quand vous maintenez le bouton d'urgence trois secondes, SMS Tech envoie un SMS
  aux contacts d'urgence que **vous** avez choisis. Si vous avez accordé la permission de
  localisation et laissé « inclure ma position » (activé par défaut dans ce mode), l'application
  demande à Android **une** position à cet instant, ou en reprend une datant de moins de cinq
  minutes — aucun suivi en arrière-plan ni continu — et l'ajoute au SMS sous forme de lien
  `https://maps.google.com/?q=…`. Si un contact
  ouvre ce lien, c'est son navigateur qui contacte Google Maps ; l'application, elle, n'envoie rien
  à Google. Si la position est refusée ou indisponible, le SMS le dit et part sans coordonnées.
- **Safety call.** Si vous n'utilisez pas l'application avant le délai que vous avez fixé, SMS Tech
  envoie un SMS de vérification aux mêmes contacts. Il ne contient aucune position.
- **Appel d'urgence.** Par défaut, le bouton d'urgence ouvre le composeur pré-rempli. C'est
  seulement si vous choisissez « appeler directement » dans les réglages que l'application passe
  l'appel elle-même (112 ou 17) : c'est l'usage de la permission `CALL_PHONE`.
- Aucune de ces fonctions ne s'exécute dans la session leurre ouverte par le code panique.
- Ces messages sont des SMS ordinaires : votre opérateur peut les facturer selon votre forfait.

## Permissions

Voir [PERMISSIONS.md](PERMISSIONS.md) pour la justification de chaque permission.

## Vos droits

Aucune donnée personnelle ne quittant votre appareil, il n'y a rien à consulter, rectifier ou
supprimer auprès d'un système distant. Pour effacer vos données dans SMS Tech : désinstallez
l'application (Android purge les données automatiquement) ou utilisez **Réglages → Supprimer
toutes mes données**, qui efface la base chiffrée, les alias Keystore, les pièces jointes en
cache et les PDF exportés.

## Contact

Pour toute question : `contact@files-tech.com`.
