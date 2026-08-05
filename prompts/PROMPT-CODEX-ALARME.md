# Relecture Codex — ordonnancement et cycle de vie du Safety call

**Dépôt** : `j:\applications\sms_tech` · branche `fix/audit-codex-2026-08-04`
**Cible** : `git diff dbdd95f..HEAD` — 3 commits, 10 fichiers, +672 / −125.
**Axe imposé** : **ordonnancement, cycle de vie, concurrence.** Pas la sécurité applicative, pas
l'i18n, pas le style — un second relecteur couvre ces axes en parallèle.

## Contexte en six lignes

Le Safety call est un homme-mort : si l'utilisateur n'ouvre pas l'application pendant un délai
choisi (1 h à 30 jours), un SMS part vers 1 à 4 proches, suivi de 3 relances à 15 min, puis le
deadman se désarme. Ce sont de **vrais SMS vers de vraies personnes**.

Jusqu'à `cb74483`, rien n'était programmé : un `PeriodicWorkRequest` de 60 min **échantillonnait**.
Mesuré sur appareil le 2026-08-05 : échéance à 14:25, envoi à **14:48** — 23 min de retard sur un
délai d'une heure, qui est le minimum proposé par l'interface.

`cb74483` ajoute une alarme `AlarmManager.setAndAllowWhileIdle` posée à l'instant exact de
l'échéance, un `BroadcastReceiver` qui met en file un contrôle immédiat, et **un unique
observateur** de la configuration dans `MainApplication` qui (re)pose l'alarme.

## Les quatre questions que je ne sais pas trancher seul

Elles sont classées par **mon propre doute**, pas par l'ordre du code.

### 1. Trois ordonnanceurs pour un seul envoi — peuvent-ils se croiser ?

Trois chemins peuvent réveiller `SafetyCallWorker` :
- le `PeriodicWorkRequest` de 60 min (`WORK_NAME`) ;
- le travail ponctuel de relance (`RELANCE_WORK_NAME`, `ExistingWorkPolicy.REPLACE`) ;
- **nouveau** : le contrôle immédiat de l'alarme (`IMMEDIATE_WORK_NAME`, `REPLACE`).

Ce sont **trois noms de travail uniques distincts** : WorkManager ne les déduplique donc PAS entre
eux. Ils peuvent tourner en parallèle.

La défense est la **réservation atomique** du créneau dans `TriggerSafetyCallUseCase` : un
`settings.update` qui teste `cfg.messagesSent != current.messagesSent` et n'incrémente que si le
créneau est libre. Question directe : **cette réservation tient-elle si deux workers s'exécutent
réellement en parallèle ?** `SettingsRepository.update` s'appuie sur `DataStore.updateData`, dont
l'atomicité est garantie **par instance** — et l'instance est `@Singleton`, donc unique dans le
processus. Mais WorkManager peut-il exécuter deux workers dans **deux processus** ? Si oui, la
réservation ne protège plus rien et le doublon revient.

Cherche aussi l'entrelacement précis qui enverrait **deux fois le même message**, et celui qui
n'en enverrait **aucun**.

### 2. Qui re-pose l'alarme quand elle est consommée sans rien envoyer ?

L'alarme est à un coup. Elle est reposée par le collecteur de `MainApplication`, qui ne réagit
qu'à un **changement** de configuration (`distinctUntilChanged`).

Or plusieurs chemins consomment l'alarme **sans modifier la configuration** :
- `Result.PanicSuppressed` — session panic-decoy active, sortie avant tout ;
- `Result.NotExpired` — l'horloge murale a expiré mais **pas le compteur monotone** ;
- `Result.AlreadySent` — créneau déjà pris.

Dans ces cas, l'alarme est brûlée et rien ne la repose. Le filet est le tick horaire — donc on
retombe **exactement sur le défaut qu'on vient de corriger**, silencieusement.

**Est-ce que je dois re-poser l'alarme à la fin de chaque exécution du worker ?** Si oui, comment
éviter qu'un `enqueueNow` déclenché par l'alarme ne repose une alarme qui se redéclenche
immédiatement — une boucle de réveil qui viderait la batterie ?

### 3. Le collecteur : une alarme reposée toutes les heures pour rien ?

Le tick horaire écrit `monotonicAccumulatedMs` et `monotonicLastActivityAt` à **chaque passage**.
La configuration change donc toutes les heures, `distinctUntilChanged` laisse passer, et le
collecteur re-pose l'alarme — au **même instant** qu'avant.

Bénin ou pas ? Est-ce que ré-armer une alarme identique toutes les heures a un coût, ou déclenche
un bridage OEM (Samsung One UI est agressif) ? Faut-il filtrer sur `nextWakeUpAt(cfg)` plutôt que
sur `cfg` entier ?

### 4. Redémarrage, fermeture forcée, mise à jour

Les alarmes ne survivent pas à un redémarrage. Le `BootReceiver` appelle `schedulePeriodic` mais
**pas** le collecteur — celui-ci repart parce que `Application.onCreate` s'exécute quand le
processus renaît. **Est-ce vrai dans tous les cas** — direct-boot (`LOCKED_BOOT_COMPLETED`,
stockage chiffré encore verrouillé, DataStore illisible), fermeture forcée par l'utilisateur, mise
à jour de l'application ?

Après une **fermeture forcée**, Android annule alarmes *et* travaux WorkManager, et rien ne
redémarre tant que l'utilisateur n'ouvre pas l'application. Le deadman est alors mort en silence.
Est-ce que quelque chose peut être fait, ou est-ce une limite à documenter ?

## Fichiers à lire en priorité

```
app/src/main/java/com/filestech/sms/system/scheduler/SafetyCallAlarmScheduler.kt   (nouveau)
app/src/main/java/com/filestech/sms/system/receiver/SafetyCallAlarmReceiver.kt     (nouveau)
app/src/main/java/com/filestech/sms/system/scheduler/SafetyCallWorker.kt
app/src/main/java/com/filestech/sms/MainApplication.kt        (le collecteur, ~ligne 143)
domain/src/main/java/com/filestech/sms/domain/usecase/TriggerSafetyCallUseCase.kt
domain/src/main/java/com/filestech/sms/domain/safetycall/SafetyCallConfig.kt
app/src/main/AndroidManifest.xml                              (le receveur ajouté)
```

## Hors périmètre — ne pas le proposer

- **`USE_EXACT_ALARM` et `SCHEDULE_EXACT_ALARM`** : arbitré, écarté. La première est réservée par
  politique aux réveils et agendas ; la seconde ajoute une permission inquiétante sur une
  application qui promet la sobriété, et F-Droid publie la liste en clair. `setAlarmClock` est
  écartée aussi : elle **affiche l'icône réveil**, donc rend le deadman visible à un agresseur.
- **Toucher au keystore ou à la signature** : deux audits l'ont déjà recommandé à tort. Appliqué,
  cela casserait la chaîne de mise à jour de toutes les installations existantes.
- **Régénérer une baseline detekt ou lint.**
- Le style, l'i18n, les commentaires.

## Ce que j'attends en retour

Pour chaque constat : **fichier:ligne**, le **scénario concret** (quel entrelacement, quel état de
l'appareil), la **conséquence pour l'utilisateur**, et un statut **CONFIRMÉ / PROBABLE / À
VÉRIFIER**. Pas de findings génériques.

Et une consigne explicite : **si tu estimes qu'un de ces quatre points est mal corrigé plutôt que
non corrigé, dis-le franchement** — c'est le cas qui m'intéresse le plus.

## Gate à faire passer avant de proposer un correctif

```
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt
```

Quatre outils, `detekt` sur `:app :data :domain :core` inclus. Il est vert sur `HEAD`.
