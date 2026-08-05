# Prompt Codex — relecture du lot « hydratation des réglages » (SMS Tech v1.27.2)

> À coller tel quel dans Codex, à la racine de `j:\applications\sms_tech`,
> branche `fix/audit-codex-2026-08-04`.

---

## Ce que tu dois faire

Relis **deux commits** et rien d'autre :

```
73d4caf  fix(reglages): ne plus servir les valeurs par defaut sur un processus reveille
38fd272  feat(safety call): afficher l'heure a laquelle le minuteur a demarre
```

```bash
git diff d3f4f92..HEAD
```

Tu ne fais **pas** un audit du dépôt. Tu cherches des défauts **introduits ou laissés ouverts par
ce diff**. Un défaut qui existait avant et que le diff ne touche pas n'est pas de ton ressort.

## Le contexte, en trois phrases

`SettingsRepository.state` est un `StateFlow` dont la valeur initiale est `AppSettings()`, donc les
réglages **par défaut** ; il ne rend les vrais réglages qu'après la première émission DataStore, qui
est asynchrone. Tout chemin réveillé par le système (receveur de SMS, worker WorkManager, réponse
rapide depuis une notification) s'exécute sur un processus qui **vient de naître**, donc avant cette
hydratation. Ce lot introduit `AppSettingsSource.hydratedOrNull()` et bascule cinq appelants dessus.

Un défaut de ce motif a déjà coûté cher : le Safety Call ne partait **jamais** parce que son worker
lisait `enabled = false` depuis cet instantané non hydraté.

## Ce que je te demande de vérifier, par ordre d'importance

### 1. La collecte explicite qui remplace `stateIn`

`SettingsRepository` n'utilise plus `flow.stateIn(appScope, Eagerly, AppSettings())` mais un
`MutableStateFlow` + `init { appScope.launch { flow.collect { … } } }`.

- Existe-t-il un cas où **`state` cesse d'être publié** alors qu'il l'était avant ? Le rayon
  d'explosion est l'application entière : tout le monde lirait des défauts en silence.
- L'ordre d'initialisation des propriétés (`flow`, `hydrated`, `_state`, `state`, `init`) est-il
  correct ? Un `init` qui lirait une propriété pas encore initialisée passerait sur `null`.
- Le drapeau `hydrated` est levé **après** `_state.value = snapshot`, et lu **avant** `_state.value`
  dans `hydratedOrNull()`. Cet ordre est-il réellement suffisant pour qu'un lecteur d'un autre
  thread ne puisse pas voir `hydrated == true` avec un `_state` encore aux défauts ? Les deux champs
  sont volatils. Si tu penses que non, donne l'entrelacement précis.
- `flow` porte un `retry(3)` puis un `catch {}` : sur un fichier corrompu il **termine à vide**.
  Vérifie que `hydratedOrNull()` ne peut ni **bloquer indéfiniment**, ni **lever**, dans ce cas.

### 2. Le sens dans lequel chaque repli échoue

Chaque appelant choisit son propre repli quand `hydratedOrNull()` rend `null`. Conteste chacun :

| Appelant | Repli retenu |
|---|---|
| `IncomingMessageNotifier` | notifie quand même, mais `previewMode = NEVER` + `inlineReply = false` |
| `MainActivity.onResume` | ne remet **pas** le minuteur Safety Call à zéro |
| `SendSmsUseCase`, `SendMediaMmsUseCase`, `SendVoiceMmsUseCase` | `AppSettings()`, donc les défauts |

Pour chacun : **quel est le pire scénario réel**, et le repli choisi le rend-il meilleur ou pire que
l'ancien comportement ? Je cherche un cas où j'ai fait *empirer* quelque chose, pas une préférence
de style.

Point précis à trancher : dans `IncomingMessageNotifier`, `previewMode = NEVER` conduit à
`VISIBILITY_SECRET`, donc à une notification **entièrement absente de l'écran de verrouillage**.
Est-ce que je fais disparaître une notification que l'ancien code affichait, et est-ce acceptable ?

### 3. Les appelants que je n'ai **pas** convertis

Je les ai laissés délibérément. Dis-moi si l'un d'eux est en fait atteignable depuis un processus
réveillé à froid — auquel cas j'ai laissé un **jumeau asymétrique**, qui est le motif de défaut
dominant de ce dépôt :

- `PhoneNumberWireFormatter.resolveRegion` (`data/…/sms/`) — non suspendable, retombe déjà sur la
  région de la SIM.
- `SenderNameProviderImpl` (`data/…/sender/`) — je prétends qu'il ne sert que les réactions.
  **Vérifie tous ses appelants.**
- `SafetyCallSetupViewModel:234` — écran de réglages ouvert à la main.
- `ThreadViewModel` — collecte réactive, se corrige d'elle-même. Confirme-le.

Vérifie aussi qu'il ne reste **aucun** autre `settings.state.value` sur un chemin déclenché par le
système. Cherche large : `BroadcastReceiver`, `CoroutineWorker`, `JobIntentService`, tout ce
qu'appelle `NotificationActionReceiver`.

### 4. `SendSmsUseCase` et consorts — régression de performance ?

`hydratedOrNull()` est `suspend`. Sur un processus chaud elle ne suspend pas et ne fait aucune
E/S — vérifie-le dans le code plutôt que de me croire. Sur un processus froid, elle ouvre DataStore.
Y a-t-il un chemin où cette lecture s'exécute **par destinataire** au lieu d'une fois, ou dans une
boucle serrée ?

### 5. La ligne d'interface ajoutée (`SafetyCallArmedRecap`)

- `stringResource` est appelé **conditionnellement**, à l'intérieur d'un `takeIf{}?.let{}`. Est-ce
  correct vis-à-vis des groupes de composition Compose ?
- `formatters.time` est un `SimpleDateFormat`, non thread-safe. La composition est mono-thread —
  confirme qu'aucun accès n'a lieu ailleurs.
- Le libellé affiche `lastActivityAt`, c'est-à-dire le **dernier reset**, pas une date d'activation
  figée. Vu que `MainActivity.onResume` remet ce champ à `now()` à chaque ouverture de
  l'application, l'utilisateur verra presque toujours « il y a quelques secondes ». Est-ce que la
  formulation « Minuteur lancé » reste honnête, ou est-ce que j'affiche une information qui n'apprend
  rien ?

### 6. Les tests

`app/src/test/java/com/filestech/sms/settings/SettingsHydrationTest.kt`.

- Le processus froid est simulé par un `StandardTestDispatcher` **jamais avancé**. Est-ce
  réellement déterministe, ou existe-t-il un chemin par lequel la collecte s'exécute quand même ?
- Les deux tests partagent le même `Context` Robolectric et donc le même fichier DataStore.
  Peuvent-ils s'influencer selon l'ordre d'exécution ?
- Que **ne** testent-ils pas parmi ce que ce diff change ? Réponds précisément : c'est la question
  qui m'intéresse le plus.

## Format de réponse attendu

Un tableau, une ligne par finding :

| # | Fichier:ligne | Sévérité | Statut | Défaut | Scénario d'échec concret |
|---|---|---|---|---|---|

- **Statut** : `CONFIRMÉ` (tu as remonté le chemin jusqu'à un appelant réel) ou `À VÉRIFIER` (tu as
  un doute et tu dis lequel). N'écris pas `CONFIRMÉ` sans avoir ouvert le fichier appelant.
- **Scénario d'échec concret** : entrée ou état de départ → ce qui se passe → pourquoi c'est faux.
  Pas de « pourrait poser problème ».
- Si tu ne trouves rien sur un point, écris-le explicitement. Un « rien à signaler » motivé m'est
  plus utile qu'un finding inventé pour remplir le tableau.

## Ce que tu ne dois pas proposer

- Toucher au keystore ou à la signature — la chaîne de mise à jour des installations existantes en
  dépend.
- Régénérer une baseline detekt ou lint pour faire passer le gate.
- Renommer, reformater ou « moderniser » du code que ce diff ne touche pas.
- Des suggestions de style. Je cherche des défauts de logique, de sécurité et de cycle de vie.
