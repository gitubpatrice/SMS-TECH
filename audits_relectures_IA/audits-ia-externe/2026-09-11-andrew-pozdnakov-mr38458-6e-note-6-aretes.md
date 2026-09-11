# Relecture externe Andrew Pozdnakov — MR F-Droid !38458, 6ᵉ note (6 arêtes)

**Date** : 2026-09-11. **Notes** : `note_3821759254` (clôture R01/R02/R03 sur source) et
`note_3821779452` (cinq candidats + une arête mineure). **Réponse courte postée** :
`note_3822795933`. **Version cible** : 1.28.5 (294).

## 1. Ce que les notes disent

La première clôt **explicitement** R01/R02/R03 sur revue du source de `aafe607d`. La seconde
liste cinq candidats **déduits du code, non mesurés sur Android**, chacun avec son chemin d'appel
et un test de régression suggéré, plus une arête mineure dans la copie bornée. Andrew les dit
« à traiter comme des candidats tant que je ne les confirme pas ».

## 2. Vérification indépendante — les six sont réels

| # | Constat | Verdict | Preuve dans `aafe607d` |
|---|---|---|---|
| 1 | Sous `force`, un échec de dépendant passe à la suppression du parent avec `localeComplete = true` | **Confirmé, le plus grave** | `ConversationEraser.kt:113` — `if (echecs > 0 && preserveOnSystemFailure)`, faux sous `force` ; même garde ligne 124 pour l'arrivée tardive |
| 2 | Barrière test-puis-agir ; barrière abaissée avant le retrait du PIN | **Confirmé** | `VaultManager.kt:111/213/267` lisent `enCours` puis écrivent ; `purgeVault` rend, puis `SettingsViewModel` retire le PIN hors barrière |
| 3 | Miroir du MMS de groupe avec les bloqués, PDU sans eux | **Confirmé** | `SendMediaMmsUseCase.kt:167` (`recipients`) vs `:177` (`cibles`) ; idem `SendVoiceMmsUseCase.kt:133/146` |
| 4 | Garde d'abaissement fail-open sur lecture en échec | **Confirmé** | `AppLockManager.kt:96` `getOrDefault(0)`, `:98` `getOrNull()` |
| 5 | Geste Safety Call retenu sur `LockedOut` / `PanicDecoy` | **Confirmé comme mauvaise politique** | `MainActivity.kt:445-449` : l'ouverture réelle ne désarme PAS (`withActivityReset()` sans `disarmIfTriggered`) ; le geste retenu ajoutait donc un désarmement |
| 6 | Fichier partiel après exception dans la copie bornée | **Confirmé** | `LectureBornee.kt:33-51` : `use` ferme, le `delete()` de la ligne 49 n'est jamais atteint |

**Ce qu'Andrew n'a pas dit et qui compte plus que les six** : ma note du 10 septembre affirmait
« sous `force`, les enfants locaux survivants sont comptés dans `localFailures`, jamais comme
résidu système ». Le code disait le contraire. C'est le motif de mémoire « décrire un code à
partir d'une observation à l'exécution » — j'ai décrit l'intention, pas la ligne. Dit à Andrew
dans la note courte, avant qu'il le mesure.

## 3. Ce qui change en 1.28.5

1. **`ConversationEraser.Mode`** — `ORDINAIRE` / `COFFRE` / `COFFRE_FORCE` remplace le booléen.
   `COFFRE_FORCE` ne lève que la condition « copie système » ; un dépendant qui résiste garde le
   parent (`localeComplete = false`) ; un message arrivé tard part avec le parent mais compte en
   résidu système (il n'a jamais été présenté au fournisseur).
2. **`VaultPurgeBarrier` linéarisée** — `Mutex` + compteur d'entrées en vol + `CompletableDeferred`.
   `enEntrant` s'inscrit sous le verrou (refus si purge levée), se désinscrit en `NonCancellable`.
   `pendant` lève, attend `entreesEnVol == 0`, exécute, abaisse en `NonCancellable`.
   `deleteAllInVault(force, apresPurge)` invoque le rappel **sous la barrière** ; le ViewModel y
   retire le PIN, une seule décision, événements dérivés de `pinRetire`.
3. **`enGroupe(miroir: (cibles) -> Long)`** — le miroir reçoit les cibles ; les deux voies MMS
   passent `addresses = cibles`. Compromis assumé : avec un membre bloqué, la ligne locale va dans
   le groupe des membres servis, pas dans le fil tapé ; `blocked` le dit à l'appelant.
4. **`LockDowngradeOutcome.VaultStateUnknown`** — les deux `runCatching` rendent ce refus ;
   `SettingsViewModel.signalerRefus` en `when` exhaustif ; chaîne FR/EN
   `settings_lock_downgrade_vault_unverifiable`.
5. **`SafetyCallResetGate.attendreOuvertureOuJeter`** — `Locked` traversé, `LockedOut` et
   `PanicDecoy` jettent (rend `false`). `MainActivity` republie la notification (nonce déjà
   consommé) et sort. `attendreOuverture` conservé pour la remise à zéro à l'ouverture.
6. **`LectureBornee.recopier`** — `runCatching { … }.onFailure { cible.delete() }.getOrThrow()`.
7. **Septième, non signalé — restauration sous la barrière.** GPT a laissé « à vérifier : aucun
   autre chemin d'écriture `in_vault = true` ne contourne `enEntrant` ». Vérifié par grep : un
   seul, `BackupService.importPayload` (`conversationDao.insert(backupConv.copy(...))` avec le
   `inVault` de la sauvegarde). `readSmsbk` passe désormais par `barriere.enEntrant` ; refus
   `VaultPurging` avant toute lecture, passphrase effacée. Test `pendant une purge la
   restauration est refusee et la passphrase effacee` (doublures strictes). L'auditeur de motifs
   (agent, application entière, lecture seule) l'a trouvé indépendamment au même moment, classé
   CRITIQUE, et a relevé en plus `ConversationRepository.moveToVault(id, inVault)` : `setInVault`
   nu, sans garde, zéro appelant de production (F11 de l'audit du 2026-08-03, jamais appliqué).
   **Retirée** de l'interface et de l'implémentation.
8. **Audit de cohérence (agent, application entière)** — deux dérives, corrigées : (a)
   `deleteMessage` hors de `ConversationEraser`, fichiers de pièces jointes laissés en clair
   (classe F04) → `ConversationEraser.eraseMessage`, corps commun `supprimerFichiers`, test Room
   réel `laSuppressionDUnMessageEffaceSonFichier` ; (b) `HeadlessSmsSendService` appelait
   `SendSmsUseCase` en direct, troisième point d'envoi hors aiguillage → `EnvoyerMessageUseCase`.
   Non couverts par cet agent, à sa propre demande : les 266 `runCatching` (spot-check seul), le
   câblage Hilt.
9. **Lecture ciblée « angles morts » (demandée par Patrice)** — Q2 oracle du coffre en leurre :
   15 surfaces, toutes gardées (tableau dans le rapport de l'agent, rien à corriger). Q3 :
   `ACTION_SAFETY_CALL_REARM` sans le garde de son jumeau → `attendreOuvertureOuJeter` appliqué,
   republication sur geste jeté. Q1 : restauration de lignes `in_vault` sur appareil sans second
   facteur → `RestoreResult.vaultRestoredWithoutSecondFactor`, chaîne FR/EN, message à l'écran.
   Une seconde lecture (266 `runCatching` / annulation, rapprochement des groupes E.164) a
   rendu le point 10 et les points ouverts ci-dessous.
10. **Balayage `runCatching` + annulation** (245 sites lus) — SÉCURITÉ : `PanicService`
    « supprimer toutes mes données », quatre écritures DataStore annulables et avalées en silence
    → `withContext(NonCancellable)` + journaux. DONNÉES : `CancelScheduledMessageUseCase:72`,
    `ConversationMirror:79`, `PhoneNumberWireFormatter:77`, `MmsSender:182`, et
    `runCatchingOutcome` → helper `runCatchingCancellable` (core), test unitaire avec contrôle
    positif. Bruit (≈30 sites, repli sûr, journal trompeur) : non modifié, liste dans le rapport
    de l'agent (`tasks/a3244f6cb25a2d9cf`).

### Relecture externe Gemini — seconde vague (restauration, `eraseMessage`, REARM)

| Constat | Verdict | Suite |
|---|---|---|
| A1 : passphrase de restauration non effacée sur annulation, `runCatchingOutcome` relançant désormais | **Confirmé, conséquence directe du point 10** | `restaurer` en `try/finally`, wipe sur tous les chemins |
| A3 : second facteur lu avant l'attente du verrou SQLite | Probable, marginal | lu APRÈS la transaction, sur `importe.copy(...)` |
| B4 : `local_uri` partagé par deux messages effacé avec le premier | À vérifier (dépend de `AttachmentStore` : copie ou réutilisation au transfert) ; préexistant pour la suppression de conversation (F04) | **ouvert**, §7 |
| B5 : pièce jointe insérée entre `findForMessage` et le `delete` → fichier orphelin | Réel mais fenêtre étroite, même structure que F04 | **ouvert**, §7 |
| B6 : message unitaire du coffre dont la copie système résiste « réapparaîtrait en clair » | **Douteux** : la réimportation rattache par fil, et la conversation du coffre existe encore ; préexistant depuis toujours | à mesurer, §7 |
| C7 : republication après geste jeté | D'accord, rien | — |
| C8 : `lifecycleScope.launch` sans borne au premier plan → geste exécuté des heures plus tard | **Confirmé** (préexistant depuis 1.28.3 sur RESET) | `lancerGesteDeNotification` : annulé à `ON_STOP`, notification republiée ; les deux jumeaux |

## 7. Points ouverts, notés pour la 1.28.6

- **Pièces jointes** : `local_uri` partagé entre deux messages (transfert ?) et fenêtre entre
  lecture des pièces et suppression transactionnelle — pour `eraseMessage` ET `erase` (F04).
- **Message unitaire du coffre supprimé, copie système résistante** : mesurer où il revient à la
  resynchronisation complète.

- **Groupes MMS, « Mon numéro » absent** : `groupMms` activable sans `userMsisdn` → les réponses
  retombent en 1-à-1, la fonctionnalité paraît cassée. Avertir ou refuser dans les Réglages.
- **Groupes MMS, région inconnue** (`PhoneIdentity.Snapshot.canonical == null`) : « soi-même »
  non retiré → groupe reçu de N+1 membres, jamais rapproché, répondre s'envoie à soi-même.
  Faible probabilité (région connue dès qu'une SIM est présente). Tests proposés par l'agent.
- **Groupes MMS, même membre sous deux notations côté composeur** → second groupe. Test proposé.
- **`BlockedNumbersImporter`** : une annulation en boucle consomme le créneau de l'étrangleur.
- **Import système après resynchronisation complète** : un MMS de groupe sortant revient en
  ligne 1-à-1 sous le premier destinataire (à mesurer, pas propre aux groupes).

## 4. Tests, et ce que chacun mesure

| Test | Module | Ce qui tombe si le défaut revient |
|---|---|---|
| `force_unFichierQueDeleteRefuse_gardeLeParentEtCompteLEchec` | androidTest, Room réel, dossier `0500` | parent supprimé sous `force`, `localFailures = 0` |
| `force_uneAnnulationQuiLeve_gardeLeParentEtCompteLEchec` | androidTest, `cancel()` lève | idem |
| `force_unMessageArriveTard_partMaisCompteEnResiduSysteme` | androidTest | `deleted = 1` au lieu de `systemResidue = 1` |
| `force_leveToujoursLaSeuleConditionCopieSysteme` | androidTest — contrôle positif | `force` ne lève plus rien |
| `laDecisionDApresPurgeSExecuteSousLaBarriere` | androidTest | `enCours` faux au moment du rappel |
| `une entree inscrite avant la purge est attendue par elle` | data/test | la purge balaie avant le commit de l'entrée |
| `une entree qui leve ne bloque pas la purge suivante` | data/test | purge bloquée pour toujours |
| `the PIN is removed while the purge barrier is still raised` | app/test | `forgetVaultPin` hors rappel |
| `trois membres dont un bloque, le miroir ne porte que les deux servis` | domain/test | miroir `A;B;C` |
| `abaisser le verrou echoue ferme quand le coffre ne se lit pas` (+ facteur, + contrôle positif) | app/test Robolectric, DAO mocké qui lève | `Ok` sur lecture ratée |
| `un geste recu en session leurre est jete, meme si l'on deverrouille ensuite` (+ 4) | data/test | `true` après `Unlocked` |
| `une exception au milieu de la copie ne laisse rien derriere et remonte` | core/test | fichier partiel présent |

Contrôles négatifs : voir §6, remplis pendant le gate.

## 5. Décisions prises

- **Point 3, sémantique** : le miroir suit le PDU (ce qui est parti), pas la saisie. L'alternative
  (garder `A+B+C` en local) laissait la réponse dans un second groupe — c'est le défaut. Le membre
  bloqué reste visible dans `blocked`.
- **Point 5, politique** : jeter sur `LockedOut` et `PanicDecoy`, retenir sur `Locked`. Retenir sur
  `Locked` est le cas légitime (téléphone déverrouillé, application qui demande son code). Le
  jumeau de remise à zéro continue d'attendre : il ne désarme rien, et jeter là aurait supprimé
  la remise à zéro légitime au déverrouillage.
- **Point 2, second trou** : fermé par un rappel sous barrière plutôt qu'en exposant la barrière au
  ViewModel — `pendant` n'est pas réentrant, et la décision reste à l'appelant.

## 6. Gate et contrôles négatifs

| Étape | Résultat |
|---|---|
| Compilation (4 modules) | OK |
| Tests unitaires `:core:test :domain:test :data:test :app:testDebugUnitTest` | **1096 cas, 0 échec, 0 ignoré** ; les 15 nouveaux cas vérifiés présents dans les XML |
| Contrôles négatifs unitaires (6) | **6/6** : chaque défaut remis fait tomber le(s) test(s) visé(s), et eux seuls — barrière (1 test), PIN sous barrière (2), miroir de groupe (2), abaissement (1), porte Safety Call (3), copie bornée (1) |
| detekt (4 modules) / lint / assembleDebug — première vague | OK (deux points de formatage dans les tests corrigés) |
| Relecture Gemini (gratuite) puis GPT 5.2 (effort moyen) sur barrière + modes | Gemini : 2 constats réfutés, 2 préexistants notés ; GPT : rien, un « à vérifier » qui a mené au point 7 |
| Audit 3 axes du delta (agent) | rien trouvé ; « code prêt pour tag » sous réserve S9 |
| Audit motifs de défaut, application entière (agent) | restauration hors barrière (déjà corrigée, point 7) + `moveToVault` non gardée (retirée) |
| Audit de cohérence, application entière (agent) | C1 `deleteMessage` hors effaceur (corrigé, point 8a), C2 `HeadlessSmsSendService` hors aiguillage (corrigé, 8b) |
| Seconde vague : unitaires 4 modules, detekt, lint, compile androidTest | **OK** — 1098 cas, 0 échec ; un plantage lint transitoire (résolveur FIR sur `BackupRoundTripTest.kt`) disparu à la relance |
| Campagne S9 n° 1 | **arrêtée à 74 cas** : `SystemJobService` réveillé par le système dans le processus de test sans WorkManager initialisé → processus tué. Pas une régression. `HiltTestRunner` initialise désormais WorkManager, jobs annulés avant la campagne |
| Campagne S9 n° 2 (avant les correctifs de la seconde lecture) | **146 cas, 0 échec, 0 ignoré** ; les 6 nouveaux cas force/barrière/eraseMessage présents |
| Gate après seconde lecture + Gemini vague 2 (points 9 à 11) | **OK** — 1104 tests unitaires, detekt, lint, androidTest compilés |
| Contrôles négatifs seconde vague | **2/2** : restauration hors barrière → son test tombe seul ; `eraseMessage` sans suppression de fichiers → `laSuppressionDUnMessageEffaceSonFichier` tombe seule sur le S9 (104 s, 20 cas de la classe) |
| Campagne S9 n° 3 (finale, code complet) | **148 cas, 0 échec, 0 ignoré** — dont les deux cas du drapeau de restauration, posés et non supposés (réglage écrit par le test) |
| Mesure manuelle Safety Call sur le S9 (build final, délai 1 h, contact = S24) | **OK de bout en bout** : tap de la notification + code → remise à zéro écrite (21:13:46) ; avertissement à H+45 (22:01:08) ; échéance à H+60 (22:16:00) ; SMS d'alerte reçu sur le S24 à 22:16:02 avec le texte attendu. L'avertissement « immédiat » de 21:12 venait d'un armement antérieur encore présent sur le téléphone d'essai (réveil déjà posé), pas du code |
| Parité FR/EN (agent) | OK : 764 clés de chaque côté, 0 argument divergent, 0 apostrophe nue, changelogs 486 / 489 caractères |

### Relecture externe Gemini (gratuite) — barrière et modes, 7 questions ciblées

| Constat Gemini | Verdict après lecture | Suite |
|---|---|---|
| Q1 : plus d'entrelacement entre relecture de `remaining` et retrait du PIN | **D'accord** | — |
| Q2 : une seconde purge refusée abaisserait la barrière de la première via son `finally` | **Réfuté** : `check(!purgeEnCours)` lève DANS `withLock`, AVANT le `try` ; le `finally` n'est jamais atteint | assertion ajoutée dans `deux purges ne s'entrelacent pas` : `enCours` reste vrai après le refus |
| Q3 : annulation entre la sortie de `withLock` et le `try` laisserait `entreesEnVol` incrémenté | **Réfuté** : aucun point de suspension entre les deux, une annulation ne s'observe qu'à une suspension | — |
| Q4 : `enCours` lu hors verrou | D'accord, sans conséquence (tests et journaux) | — |
| Q5 : sous `COFFRE_FORCE`, un `findByConversation` initial qui lève finit en `systemResidue`, pas `localFailures` | **Probable, préexistant** (identique en 1.28.4) : les copies système n'ont pas été présentées, le résidu est vrai ; sous `COFFRE` le parent est gardé et la reprise les présente | noté, pas corrigé |
| Q6 : `systemCopyGone` muté dans `withTransaction` | D'accord, sans problème | — |
| Q7 : `force`, tout à 0 sauf `remaining > 0` → `VaultPurgeStuck` rouvre le dialogue « forcer » | **Préexistant en 1.28.4, et devenu inatteignable** : les entrées au coffre sont refusées pendant la purge, `remaining` ne peut plus dépasser les parents gardés, qui comptent déjà en résidu ou en échec local | noté |

**Piège rencontré, à ne pas réapprendre.** La première exécution des contrôles négatifs a conclu
« rien ne tombe » pour les six en deux minutes : Python sous Windows ne lançait pas `./gradlew`
(`WinError 193`), puis `cmd /c gradlew.bat` avec un `cwd` en barres obliques ne le trouvait pas,
et le script relisait les XML **périmés** de la campagne verte. Correctifs : chemin absolu
normalisé du wrapper, suppression des XML de la classe **avant** le run, impression de `rc` et
de la durée. Un contrôle négatif qui ne mesure pas la durée de Gradle n'a rien mesuré.

## 8. Publication — 2026-09-11, 22:18 → 2026-09-12, 00:05

| Étape | Résultat |
|---|---|
| Commit de release | `0ca8c9d`, tag `v1.28.5` ; rectification `590518c` (deux listes de clés commises par erreur à la racine) |
| Release GitHub | 4 fichiers, certificat `b09a9511…c687d` inchangé |
| Site files-tech.com | `ab1dc8a`, 6 pages FR/EN vérifiées en ligne |
| Recette F-Droid | `fe335040c4` sur `add-sms-tech`, entrée 1.28.5 (294) |
| Pipeline MR `!38458` | `2842001900` — `fdroid build` **rouge** à 23:22, puis **vert** au second essai (00:01), pipeline entièrement verte |
| Note à Andrew | `note_3823827918` |

**Ce qui a rougi, et pourquoi.** L'agent de release a été tué par un `/compact` involontaire après
`assembleRelease`. Reprise à la main. Le job `fdroid build` a rebâti les neuf entrées de la recette :
les huit précédentes se reproduisaient, **la 294 seule divergeait** — `classes.dex` +16 octets,
`classes2.dex` +128, `baseline.prof` +1, même R8 8.13.19, mêmes classes, mêmes chaînes, `pg-map-id`
différent. L'APK avait pourtant été construit `--no-build-cache clean`. Après `gradlew --stop`, la
même commande a donné un APK identique à l'artefact du job sur ses 365 entrées : **le daemon Gradle
portait un état que ni `clean` ni `--no-build-cache` ne purgent**. Quatre fichiers remplacés sur la
release (`--clobber`), job relancé, vert. Consigné dans `NOTE-release-reproductible.md` (`0f6b0a0`).

Leçon : huit versions vertes dans le même job ne prouvent rien sur la neuvième ; comparer les dex
de l'APK publié à l'artefact du job avant de commenter la MR.
