# Prompt Codex — relecture des correctifs de ta propre relecture (SMS Tech v1.27.2)

> À coller tel quel dans Codex, à la racine de `j:\applications\sms_tech`,
> branche `fix/audit-codex-2026-08-04`.

---

## Ce que tu dois faire

Tu as relu le 2026-08-05 les commits `73d4caf` et `38fd272`, et rendu deux findings HAUTE
(rapport : `audit/ia-externe/rapport-relecture-codex-hydratation-2026-08-05.md`). Ils ont été
corrigés. **Relis les correctifs, pas le rapport.**

```bash
git diff 38fd272..HEAD
```

Trois commits, et uniquement ceux-ci :

```text
1631886  test(sauvegarde): aller-retour complet d'un .smsbk sur base Room reelle
16f52f0  test(envoi): figer le sens dans lequel l'envoi echoue quand les reglages sont illisibles
ebc8771  fix(safety call, reglages): deux findings HAUTE de la relecture Codex du 2026-08-05
```

Ce n'est pas un audit du dépôt. Tu cherches des défauts **introduits par ces trois commits**. Un
défaut préexistant qu'ils ne touchent pas n'est pas de ton ressort.

**Le motif à traquer en priorité : le correctif qui casse autre chose.** Dans l'historique de ce
dépôt, une relecture des correctifs de relecture a trouvé un vrai défaut neuf fois d'affilée. Pars
du principe qu'il y en a un ici, et trouve-le.

## Ce que je te demande de vérifier, par ordre de mon propre doute

### 1. La barrière d'hydratation — mon doute principal

`data/src/main/java/com/filestech/sms/data/local/datastore/SettingsRepository.kt`

`hydratedOrNull()` ne lance plus sa propre lecture : il attend un `CompletableDeferred<Boolean>`
complété par la collecte partagée. C'est ton finding 2, corrigé par ta seconde proposition
(« barrière unique »).

**Ce qui me préoccupe le plus, et que je ne sais pas tester :** j'ai troqué une lecture
indépendante, exécutée sur le contexte de l'appelant, contre une **dépendance à l'ordonnancement
de `Dispatchers.Default`** (`ApplicationScope`). Sur le chemin d'un SMS entrant, ça veut dire que
la notification attend qu'une coroutine d'un AUTRE dispatcher soit ordonnancée.

- Existe-t-il un scénario réaliste où `Dispatchers.Default` est saturé assez longtemps pour que
  `hydratedOrNull()` retarde sensiblement le traitement d'un SMS entrant ? Regarde ce que
  l'application lance dans `ApplicationScope` et sur `Dispatchers.Default` au démarrage —
  migrations, réparation de base, préchauffage — et dis-moi si l'un d'eux peut occuper le pool.
- Y a-t-il un **interblocage** possible : quelque chose exécuté DANS `appScope` qui attendrait
  `hydratedOrNull()`, directement ou indirectement ?
- `job.invokeOnCompletion { firstHydration.complete(false) }` couvre-t-il **tous** les cas où la
  collecte ne publiera jamais ? Notamment : portée déjà annulée à la construction du singleton,
  coroutine annulée avant d'avoir démarré son corps, exception non rattrapée en amont du `catch`.
- Deux appelants concurrents sur un processus froid : un seul réveil, ou une course ?
- `CancellationException` traverse-t-elle bien `await()` sans être avalée ?
- Le drapeau `@Volatile hydrated` et le `CompletableDeferred` peuvent-ils diverger — un lecteur
  voyant `hydrated == true` avec un `_state` encore aux défauts, ou l'inverse ?
- La branche chaude `if (hydrated) return _state.value` est-elle toujours sans suspension réelle ?

### 2. `MainActivity` — le câblage de cycle de vie

`app/src/main/java/com/filestech/sms/MainActivity.kt`

La remise à zéro du minuteur Safety Call passe d'un échantillonnage dans `onResume` à
`repeatOnLifecycle(RESUMED)` + `first { Unlocked || Disabled }`, armé **une fois** dans `onCreate`.
C'est ton finding 1.

- Le contrat « **une remise à zéro par session de premier plan** » tient-il vraiment ? Cherche un
  entrelacement produisant zéro remise à zéro, ou deux.
- Rotation d'écran / changement de configuration : l'activité est recréée, donc `onCreate` est
  rappelé. Y a-t-il accumulation de collecteurs, ou l'ancien `lifecycleScope` est-il bien annulé ?
- Reprise puis mise en arrière-plan **avant** que l'utilisateur ait saisi son code : le bloc est
  annulé au bon moment ? Rien ne fuit ?
- `PanicDecoy` : le `first { … }` attend indéfiniment dans cette session — c'est voulu. Confirme
  qu'aucun chemin ne remet quand même le minuteur à zéro sous contrainte.
- `LockedOut` : même question.
- Le `try/catch(CancellationException)/catch(Throwable)` qui remplace `runCatching` est-il correct
  vis-à-vis de `repeatOnLifecycle` ?
- **Régression fonctionnelle** : le cas nominal — application déjà déverrouillée au premier plan —
  se comporte-t-il exactement comme avant ce commit ?

### 3. Les tests réécrits — sont-ils déterministes ?

`app/src/test/java/com/filestech/sms/settings/SettingsHydrationTest.kt`

`apresUneLectureFroide_stateConnaitLaMemeValeur` simule le processus froid en occupant l'unique
thread de la portée par un `Thread.sleep(400)` posté sur un `SingleThreadExecutor`.

- Est-ce **réellement** déterministe, ou existe-t-il un chemin par lequel la collecte démarre quand
  même avant l'assertion ? Une machine chargée peut-elle inverser l'ordre ?
- `uneCollecteQuiNeDemarreJamais_rendNullSansSeSuspendreIndefiniment` construit le dépôt avec une
  portée **déjà annulée**. Est-ce que ça exerce bien le filet `invokeOnCompletion`, ou est-ce que
  ça passerait aussi sans lui ?
- Les trois tests partagent le `Context` Robolectric et donc le fichier DataStore. Un ordre
  d'exécution différent peut-il en faire échouer un ?
- Fuites : `executor.shutdownNow()`, portées annulées — reste-t-il un thread ou une coroutine vivante
  après chaque test ?

### 4. Les deux fichiers de tests jamais relus

- `app/src/androidTest/java/com/filestech/sms/data/backup/BackupRoundTripTest.kt` — aller-retour
  `.smsbk` sur base Room réelle, deux bases distinctes. Les assertions prouvent-elles ce qu'elles
  prétendent ? En particulier : le test « le coffre reste dans le coffre » et le test « mauvais mot
  de passe n'importe rien » sont-ils **non vacants** ? Le montage à la main de `BackupService`
  reproduit-il fidèlement le graphe de production, ou masque-t-il une garde ?
- `domain/src/test/java/com/filestech/sms/domain/usecase/SendSmsSettingsFallbackTest.kt` — les faux
  écrits à la main respectent-ils les contrats des interfaces ? Un faux trop complaisant ferait
  passer un use-case défaillant.

### 5. Ce que ces trois commits laissent ouvert

Tu avais listé les tests de régression nécessaires. Lesquels manquent encore, et lesquels ne sont
**pas** couverts malgré ce que les nouveaux tests laissent croire ? Je sais déjà qu'il n'existe
aucun test de la transition de verrou du finding 1 ; dis-moi ce qui m'a échappé en plus.

## Format de réponse attendu

Un tableau, une ligne par finding :

| # | Fichier:ligne | Sévérité | Statut | Défaut | Scénario d'échec concret |
|---|---|---|---|---|---|

- **Statut** : `CONFIRMÉ` (tu as remonté le chemin jusqu'à un appelant réel) ou `À VÉRIFIER` (tu as
  un doute et tu dis lequel). N'écris pas `CONFIRMÉ` sans avoir ouvert le fichier appelant.
- **Scénario d'échec concret** : entrée ou état de départ → ce qui se passe → pourquoi c'est faux.
  Pas de « pourrait poser problème ».
- Si un point de la liste ci-dessus ne donne rien, écris-le explicitement. Un « rien à signaler »
  motivé m'est plus utile qu'un finding inventé pour remplir le tableau.
- Si tu estimes qu'un de tes deux findings précédents est **mal corrigé** plutôt que non corrigé,
  dis-le franchement — c'est le cas qui m'intéresse le plus.

## Ce que tu ne dois pas proposer

- Toucher au keystore ou à la signature — la chaîne de mise à jour des installations existantes en
  dépend.
- Régénérer une baseline detekt ou lint pour faire passer le gate.
- Déclarer `USE_EXACT_ALARM` ou `SCHEDULE_EXACT_ALARM` : décision produit déjà arbitrée, hors
  périmètre de cette relecture.
- Renommer, reformater ou « moderniser » du code que ce diff ne touche pas.
- Des suggestions de style. Je cherche des défauts de logique, de sécurité et de cycle de vie.

## Note d'environnement

Ta relecture précédente n'a pas pu exécuter les tests : `:app:compileDebugJavaWithJavac` échouait
avec des `cannot find symbol` dans les sources générées par KSP, avec `--no-daemon`. Le gate passe
ici sans cette option :

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :data:testDebugUnitTest \
          :domain:test :core:test :app:lintDebug \
          :app:detekt :data:detekt :domain:detekt :core:detekt
```

Si tu ne parviens toujours pas à les exécuter, dis-le et raisonne sur le code — mais ne conclus
alors ni au succès ni à l'échec des tests.
