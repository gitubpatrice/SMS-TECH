# Prompt d'arbitrage — SMS Tech (pour Claude Code, autre session)

> À coller dans une nouvelle session Claude Code ouverte sur `j:\applications\sms_tech`.
> Le rapport Codex se colle à la suite, ou se dépose dans `audit/rapport-codex-<date>.md`.

---

Tu reprends l'audit de **SMS Tech** (`j:\applications\sms_tech`, Kotlin natif, 4 modules Gradle, branche `main`, v1.27.1).

Un audit externe **Codex** vient de rendre son rapport. Ton rôle n'est **pas** de produire un nouvel audit. Il est d'**arbitrer** celui-ci, puis d'appliquer ce qui survit.

## Règle numéro un — rien n'est vrai avant vérification

Pour **chaque** finding du rapport, avant toute conclusion :

1. Ouvre le fichier cité, à la ligne citée. Le code est-il celui que le rapport décrit ?
2. Remonte **tous** les appelants du code fautif. Le chemin est-il réellement atteignable ?
3. Alors seulement, tranche : **CONFIRMÉ**, **FAUX**, ou **À VÉRIFIER** (avec ce qui manque pour trancher).

Ne prends aucun rapport pour argent comptant. Historique factuel de ce dépôt :

- un audit externe a rendu **7 findings faux**, dont **3 décrivaient du code Flutter** — SMS Tech est en Kotlin ;
- **deux rapports** ont recommandé de « révoquer la clé de signature ». Appliqué, cela aurait **cassé définitivement la chaîne de mise à jour** de toutes les installations. **Ne touche JAMAIS au keystore.** Si un finding le suggère, marque-le FAUX et explique pourquoi.

Inversement, ne rejette pas trop vite : la dernière relecture Codex sur une app sœur a rendu **3 findings, tous confirmés**, dont le plus grave de tout l'audit — et il se trouvait dans un correctif écrit *après* quatre passes Gemini et une relecture ChatGPT.

## Ce que SMS Tech est

L'**application SMS par défaut** du téléphone. Elle détient la totalité des messages de l'utilisateur. Un défaut d'intégrité y coûte des données **réelles et irremplaçables** — c'est déjà arrivé : deux conversations perdues via un chemin d'import.

Priorité d'arbitrage, dans cet ordre : **intégrité des données** > sécurité > cycle de vie / concurrence > cohérence. Un finding « perte ou écrasement de messages » passe avant tout le reste, même s'il est moins spectaculaire qu'une faille crypto.

## Les motifs qui ont produit tous les vrais défauts

Utilise-les pour juger la plausibilité d'un finding, et pour repérer ce que Codex aurait manqué **autour** d'un finding confirmé :

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS** — écran masqué, fonction toujours appelable.
2. **Le repli échoue du mauvais côté** — ce qui compte n'est pas la probabilité du repli, c'est le sens dans lequel il échoue.
3. **Le correctif est asymétrique entre jumeaux** — quand tu confirmes une garde manquante quelque part, **cherche systématiquement les autres chemins qui devraient la porter**. C'est ce motif qui a produit la majorité des défauts.
4. **Chemin mort / ressource orpheline** — fonctionnalité annoncée et inatteignable. Vécu ici : un « mode panique » annoncé au changelog sans aucun écran pour l'activer ; un blocage anti-force-brute qui ne se levait jamais.

⚠️ **Un commentaire n'est pas une preuve.** Plusieurs commentaires de ce dépôt énonçaient une règle que leur propre fichier n'appliquait pas. Le code fait autorité. Un commentaire faux est lui-même un défaut à corriger.

## Comment corriger

- **Ajout plutôt que modification.** Ne réécris pas du code existant sans raison forte.
- **Aucun changement cosmétique non demandé.** Pas de renommage, pas de reformatage, pas de refactor « tant qu'on y est ».
- **Un lot = un thème.** Ne mélange pas un correctif d'intégrité et un correctif d'UI dans le même commit.
- **Gate à chaque palier**, avant de passer au suivant :

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest        # ~30 fichiers de tests
./gradlew :app:lintDebug           # baseline app/lint-baseline.xml — 249 entrées
```

⚠️ La baseline lint **absorbe** les avertissements existants. Un défaut réel peut y être déjà enterré : si un finding porte sur une zone couverte par la baseline, vérifie dans le fichier, pas dans la sortie de lint. C'est exactement ce qui a laissé passer le mode panique inatteignable.

## Après avoir corrigé — la passe qui compte

**Relance une revue SUR tes propres correctifs.** Sur les dernières sessions, cette passe a trouvé un vrai défaut **huit fois d'affilée**, dont une régression déjà installée sur le téléphone de Patrice.

Cherche en priorité, dans ce que tu viens d'écrire :

- une garde que tu as posée **à un seul endroit** alors que le fichier en compte plusieurs équivalents ;
- un instantané d'état que tu restaures **partiellement** — as-tu remis *tout* ce que l'opération avait modifié ?
- un `runCatching` autour d'un appel `suspend` : il avale `CancellationException` et transforme une annulation normale en échec ;
- un commentaire que tu viens d'écrire et qui promet plus que ce que ton code fait.

## Discipline Git

- **Stage explicitement**, fichier par fichier. **Jamais `git add -A`** — un incident passé a emporté des fichiers non voulus.
- Message de commit **via `-F fichier`**, jamais `-m` : les accents graves et les backticks font disparaître des fragments.
- Ne pousse ni ne tague sans demande explicite.

## Appareils

- Le **S24 FE** est le téléphone **réel** de Patrice. **Annonce avant d'installer.** Installation non destructive : `adb install -r <apk>`.
- ⚠️ **Jamais `connectedAndroidTest` sur le S24 FE** — cela efface les données de l'app.
- Le **Galaxy S9** est l'appareil de test, sans données réelles : tests instrumentés autorisés. Son GPS est HS, il n'est pas toujours branché. Toujours `adb -s <serial>`.
- Si `adb devices` rend une liste vide : c'est **Auto Blocker** de Samsung. **Ne fais pas `adb kill-server`.**

## Ce que tu dois rendre

**Étape 1 — arbitrage, avant toute modification.** Un tableau : numéro, titre, verdict (CONFIRMÉ / FAUX / À VÉRIFIER), fichier:ligne, et **une phrase de justification appuyée sur le code lu**. Pour les FAUX, dis précisément ce que le rapport a mal lu.

**Étape 2 — plan de correction**, par ordre de gravité, avec ce que tu comptes changer. Attends la validation de Patrice avant d'écrire dans le code si un correctif touche à la suppression, à l'import ou à la migration de messages.

**Étape 3 — application**, lot par lot, gate vert à chaque palier.

**Étape 4 — revue de tes propres correctifs**, avec ses conclusions écrites noir sur blanc, même si elle ne trouve rien.

**Étape 5 — ce que tu n'as pas pu vérifier.** Les zones non lues, les hypothèses non confirmées. Cette section dit où le travail ne protège pas ; elle vaut autant que le reste.

## Style attendu

Court, dense, factuel. Cite `fichier:ligne`. Distingue ce qui est confirmé de ce qui est probable. Pas de complaisance : si le rapport Codex est faible, dis-le. Si un de tes propres correctifs est douteux, dis-le avant que Patrice le découvre sur son téléphone.

Livre le lot, puis rends la main.
