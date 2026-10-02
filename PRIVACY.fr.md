# Politique de confidentialité — SMS Tech

_Dernière mise à jour : 2 octobre 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Versions de référence.** Les versions française et anglaise de cette politique font foi. Les
> traductions allemande, italienne et espagnole sont fournies à titre d'information ; en cas de
> divergence, ce sont les versions française et anglaise qui prévalent.

SMS Tech (`com.filestech.sms`) fait partie de la suite **Files Tech**, éditée par **Patrice
Haltaya**. Voir aussi les [conditions d'utilisation](TERMS.fr.md).

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

Comme toute application de SMS par défaut, SMS Tech écrit aussi vos messages dans la messagerie
système d'Android, qui les conserve indépendamment de l'application.

La sauvegarde Android système est **désactivée** : ces données ne partent ni sur Google Drive ni
lors d'un transfert d'appareil sans votre accord explicite.

## Réseau

SMS Tech n'émet aucune requête réseau. Depuis la 1.28.13, elle ne détient même plus la permission
`INTERNET` : son processus ne peut ouvrir aucune connexion réseau. Les MMS sont acheminés par le
service MMS d'Android, qui contacte le MMSC de votre opérateur lorsque vous en envoyez ou en recevez
un. Aucune vérification de mise à jour, aucune configuration distante, aucun ping analytique.

Les messages que vous envoyez passent par le réseau de votre opérateur, comme avec toute application
de SMS. Le développeur ne les reçoit jamais.

## Fonctions d'urgence (inactives tant que vous ne les activez pas)

- **Mode urgence.** Quand vous maintenez le bouton d'urgence trois secondes, SMS Tech envoie un SMS
  aux contacts d'urgence que **vous** avez choisis. Si vous avez accordé la permission de
  localisation, exacte ou approximative, et laissé « inclure ma position » (activé par défaut dans
  ce mode), l'application demande à Android **une** position à cet instant : elle reprend une
  position datant de moins de cinq minutes si Android en a une, sinon elle attend un nouveau relevé
  pendant huit secondes au plus, et à défaut reprend la dernière position connue si elle date de
  moins de trente minutes. Aucun suivi en arrière-plan ni continu. La position est ajoutée au SMS
  sous forme de lien `https://maps.google.com/?q=…` ; quand elle n'est qu'approximative, Android la
  décale d'environ deux kilomètres et le SMS l'indique par « (+/-N km) » après le lien. Si un contact
  ouvre ce lien, c'est son navigateur qui contacte Google Maps ; l'application, elle, n'envoie rien
  à Google. Si la position est refusée ou indisponible, le SMS le dit et part sans coordonnées.
- **Safety call.** Si vous n'utilisez pas l'application avant le délai que vous avez fixé, SMS Tech
  envoie un SMS de vérification aux mêmes contacts. Il ne contient aucune position.
- **Appel d'urgence.** Les tuiles d'appel de l'écran Urgence proposent les numéros d'urgence du pays
  où votre téléphone est enregistré sur le réseau (le 112 figure toujours). Quand vous en touchez une,
  l'application demande la permission d'appel (`CALL_PHONE`) et, si vous l'accordez, passe l'appel
  elle-même ; si vous la refusez, elle ouvre le composeur pré-rempli, qui ne demande aucune
  permission. Le raccourci de l'écran verrouillé ouvre toujours le composeur.
- Aucune de ces fonctions ne s'exécute dans la session leurre ouverte par le code panique.
- Ces messages sont des SMS ordinaires : votre opérateur peut les facturer selon votre forfait.

## Permissions

Voir [PERMISSIONS.md](PERMISSIONS.md) pour la justification de chaque permission.

## Vos droits

Aucune donnée personnelle ne quittant votre appareil, il n'y a rien à consulter, rectifier ou
supprimer auprès d'un système distant. Pour effacer vos données dans SMS Tech : désinstallez
l'application (Android purge ses données automatiquement) ou utilisez **Réglages → Supprimer
toutes mes données**, qui efface la base chiffrée, les alias Keystore, les pièces jointes en
cache et les PDF exportés. Les messages conservés par la messagerie système d'Android se gèrent
depuis Android ou depuis toute application de SMS.

## Contact

Pour toute question : **contact@files-tech.com**.
