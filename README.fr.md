# SMS Tech

🇬🇧 [English](README.md) · Version française

Une application SMS &amp; MMS moderne et privée pour Android — écrite en Kotlin, avec Jetpack Compose
et Material 3. Sans publicité, sans traceur, sans analytique, **sans permission Internet**. Elle fait
partie de la suite **Files Tech**. Licence Apache 2.0.

Source : [github.com/gitubpatrice/SMS-TECH](https://github.com/gitubpatrice/SMS-TECH) ·
Site : [files-tech.com/sms-tech.php](https://files-tech.com/sms-tech.php) ·
Versions : [GitHub Releases](https://github.com/gitubpatrice/SMS-TECH/releases/latest)

## ✨ Fonctions

- Application SMS / MMS par défaut pour Android 8.0 et suivants (testée jusqu'à Android 16), avec
  import unique de vos messages existants.
- Interface Compose à activité unique, Material 3 avec couleurs dynamiques, noir AMOLED et thème
  « Dark Tech ».
- Base Room chiffrée (SQLCipher), clé maîtresse enrobée par l'AndroidKeyStore.
- Verrouillage de l'application : PIN dérivé en PBKDF2-HMAC-SHA512, temporisation exponentielle après
  des échecs ; déverrouillage biométrique facultatif, avec un PIN de secours obligatoire.
- **Coffre** pour les conversations sensibles : retiré de la liste principale, il s'ouvre avec son
  propre PIN, sa phrase de passe ou la biométrie. Il réside dans la même base chiffrée par SQLCipher,
  pas dans une enveloppe à clé distincte — voir [SECURITY.fr.md](SECURITY.fr.md) pour le modèle de
  menace et ses limites.
- **PIN leurre** facultatif : le saisir ouvre l'application dans une session où le coffre, le mode
  urgence et le Safety call sont invisibles et inaccessibles.
- **Mode urgence** : maintenir un bouton trois secondes envoie un SMS aux contacts que vous avez
  choisis, avec votre position si vous l'autorisez (exacte, ou approximative et signalée comme telle),
  et des tuiles d'appel vers les numéros d'urgence du pays où votre téléphone est enregistré. Le
  **Safety call** prévient les mêmes contacts si vous n'ouvrez pas l'application avant un délai que
  vous fixez. Ces fonctions sont une aide ; elles ne remplacent pas les services d'urgence — voir les
  [conditions d'utilisation](TERMS.fr.md).
- **Messages vocaux** enregistrés sur l'appareil et envoyés en MMS audio.
- Groupes nommés et MMS de groupe, réponses contextuelles avec citation, réponse directe depuis les
  notifications.
- **Détection d'arnaque** hors ligne (liens raccourcis, formules d'urgence, numéros surtaxés, domaines
  usurpés), fondée sur des règles : elle peut se tromper dans les deux sens.
- Blocage synchronisé avec la liste de blocage de Téléphone / Samsung Messages ; blocage facultatif
  des numéros inconnus.
- Recherche plein texte (FTS4), **export PDF** d'une conversation, envoi programmé (WorkManager).
- Sauvegarde et restauration manuelles au format `.smsbk` (AES-256-GCM + PBKDF2). Les pièces jointes
  ne font pas partie de la sauvegarde.
- Cinq langues : anglais, français, allemand, italien et espagnol — y compris chaque SMS que
  l'application envoie pour vous. Une langue par application sous Android 13 et suivants, ou celle du
  système.
- Compatible F-Droid : aucune bibliothèque Google, aucun binaire propriétaire, build reproductible.

## 🔐 Confidentialité

Nous ne collectons **rien**. Aucune analytique, aucun rapport de plantage, aucune journalisation à
distance. SMS Tech n'émet aucune requête réseau : elle ne détient pas la permission `INTERNET`, son
processus ne peut donc ouvrir aucune connexion. Les MMS sont acheminés par le service MMS d'Android,
qui contacte le MMSC de votre opérateur.

Le SMS n'est **pas** chiffré de bout en bout au niveau du protocole — c'est une limite du réseau des
opérateurs, pas un choix. SMS Tech protège ce qui est stocké sur votre appareil, avec SQLCipher et
l'AndroidKeyStore. Pour un vrai chiffrement de bout en bout, utilisez Signal ou Matrix.

Voir la [politique de confidentialité](PRIVACY.fr.md), les [conditions d'utilisation](TERMS.fr.md) et
[PERMISSIONS.md](PERMISSIONS.md) (en anglais). Modèle de sécurité : [SECURITY.fr.md](SECURITY.fr.md).
La politique de confidentialité et les conditions existent en anglais, français, allemand, italien et
espagnol ; les versions anglaise et française font foi.

## 📦 Construire

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/*.apk
./gradlew test detekt ktlintCheck lintDebug
```

JDK 17 requis. SDK minimal 26 (Android 8.0), SDK de compilation 37, SDK cible 35.

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

Voir [ARCHITECTURE.md](ARCHITECTURE.md) (en anglais) pour la vue d'ensemble et le parcours complet
d'un SMS reçu.

## 📃 Licence

Licence Apache 2.0 — voir [LICENSE](LICENSE). Bibliothèques tierces et leurs licences :
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
© 2026 Patrice Haltaya.
