# Prompt de reprise — tests appareil SMS Tech (à coller dans un nouveau chat)

---

Tu reprends le travail sur **SMS Tech** (`j:\applications\sms_tech`), mon application SMS/MMS
chiffrée. Objectif de cette session : **me faire tester sur mon téléphone** les correctifs d'un
audit complet, puis préparer la **release v1.27.0**.

## État exact du dépôt

- Branche : `fix/audit-haut-niveau-2026-08`, commit `2de33c4`, **non poussée**.
- Base : `6e0fd31` = tag `v1.26.0`, versionCode 175.
- Ce commit corrige **53 findings** d'un audit de haut niveau : 69 fichiers, +4753/−427.
- **Deux documents de référence, à lire avant toute chose** :
  - `_audit_sms_tech_results/correctifs-appliques-2026-08-03.txt` — ce qui a été corrigé,
    comment, et les **douze défauts trouvés dans les correctifs eux-mêmes** ;
  - `_audit_sms_tech_results/audit-haut-niveau-sms-tech-2026-08-03.txt` — le raisonnement de
    l'audit (le verdict « bloquant » en tête est historique).

## ⚠️ Règles impératives sur mon téléphone

Mon **Galaxy S24 FE** (`SM-S721B`) est mon téléphone **principal**, avec mes **vrais SMS** depuis
mai 2026. SMS Tech y est l'application SMS **par défaut**.

- **JAMAIS de `connectedAndroidTest`** dessus : ça efface les données de l'app.
- **Installation non destructive uniquement** : `adb install -r` avec un APK **release** signé
  du même keystore (`b09a9511…687d`). Un APK debug a une signature différente et n'est
  installable qu'après désinstallation, donc avec perte.
- `run-as` et `adb root` sont **refusés** sur cet appareil.
- Si `adb devices` est vide : c'est l'**Auto Blocker** de Samsung. Ne surtout pas faire
  `adb kill-server`, me demander de le désactiver.
- Le **Galaxy S9** est mon appareil de test sans données réelles — tests instrumentés permis
  là-bas, mais il n'est pas branché en permanence et son **GPS est hors service**.

## Première chose à faire

⚠️ **Mon téléphone tourne une build ANTÉRIEURE à ces correctifs.** Avant tout test :

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Puis **vérifie et dis-moi** : versionCode installé, `firstInstallTime` préservé (doit rester au
2026-05-26), rôle SMS par défaut conservé, et absence de crash au démarrage (`adb logcat`).

## Ce que je dois tester, par ordre de risque

Guide-moi pas à pas, un point à la fois, et attends mon retour entre chaque.

**1. Le Coffre — le changement le plus visible.** Le second facteur garde maintenant la DONNÉE
et plus seulement l'écran. Conséquence voulue : ouvrir un fil du Coffre sans passer par l'écran
Coffre affiche un fil **vide**, et l'application **ressort du fil** quand le Coffre se referme.
Or `lockVaultOnLeave` est actif par défaut et referme le Coffre à chaque passage en
arrière-plan. À éprouver : ouvrir le Coffre normalement, puis basculer vers une autre app et
revenir ; puis « Nouveau message » vers un contact dont la conversation est au coffre.

**2. Le mode panique.** Essayer de changer mon PIN pour la valeur de mon code panique → doit
être **refusé** avec un message. Puis ouvrir avec le code panique et vérifier que Réglages ne
montre plus : Verrouillage de l'app, Code panique, Verrouiller le coffre en quittant, PIN
coffre, Sauvegarde. Et qu'À propos ne cite plus le coffre, le code panique ni le mode urgence.

**3. Les envois.** Double appui rapide sur Envoyer → **un seul** SMS. Envoyer puis revenir en
arrière immédiatement → le SMS part quand même. Un SMS long (plusieurs segments) → statut
cohérent.

**4. Les nouveaux gestes.** Appui long sur une conversation → épingler / archiver / mettre en
sourdine. Vérifier que le tri « Épinglés d'abord » fonctionne enfin, que la page Archivés se
remplit, et surtout que la **sourdine coupe réellement** les notifications. Appui long sur un
message → **Favori**.

**5. Les réglages nouvellement accessibles.** Signature automatique (Réglages → Conversations)
et délai de verrouillage automatique (Réglages → Sécurité).

**6. Le blocage.** Bloquer un numéro, puis vérifier qu'il ne peut plus m'envoyer **ni SMS ni
MMS** — le filtrage des MMS entrants est nouveau.

**7. Le verrouillage automatique.** Aller-retour rapide vers une autre app → **ne doit pas**
verrouiller ni perdre un brouillon vocal.

## Rollback si quelque chose va mal

```
git checkout main
adb install -r <APK de la release v1.27.0 précédente, cf. GitHub releases>
```
Même signature, aucune perte de données.

## Ensuite : release v1.27.0

Une fois mes tests concluants, tu prépares la release. Points d'attention propres à ce projet :

- **versionName** à passer à `1.27.0` dans `app/build.gradle.kts`. Le **versionCode est
  dynamique** (nombre de commits) — ne pas le figer.
- SMS Tech n'a **pas** de constante de version statique à bumper (contrairement à PDF/Notes Tech).
- **Changelogs fastlane FR + EN** obligatoires pour le nouveau versionCode, sous
  `fastlane/metadata/android/{fr-FR,en-US}/changelogs/<versionCode>.txt`, **cap 500 caractères**.
- Release GitHub avec les **3 APK splits + l'APK universel**, et vérifier que le certificat reste
  `b09a9511…687d` sur les quatre.
- Bump du site **files-tech.com** (URL de release + versionName + date).
- **F-Droid** : le yml local, puis la MR sur `gitubpatrice/fdroiddata`. ⚠️ Le versionCode étant
  dynamique, `AutoUpdateMode` doit rester à `None`. ⚠️ La v1.26.0 n'a **jamais été soumise à
  F-Droid** — à traiter aussi.
- Mettre à jour `CHANGELOG.md` et `SECURITY.md`.

## Comment je veux que tu travailles

Court, dense, factuel, en français. Pas de complaisance. Cite `fichier:ligne`. Distingue ce qui
est **confirmé** de ce qui est **probable**.

Et surtout, trois règles que cette session a payées cher :
- **Prudence** : annonce avant toute action sur mon téléphone, et livre un lot cohérent puis
  rends la main — pas de cascade.
- **Vigilance** : recense **tous** les appelants avant de corriger, et relance une revue **sur**
  tes propres correctifs. Sur l'audit précédent, cette règle a trouvé un vrai défaut à chaque
  passe, douze fois au total.
- **Pertinence** : aucun changement cosmétique non demandé, aucun agent en cascade.
