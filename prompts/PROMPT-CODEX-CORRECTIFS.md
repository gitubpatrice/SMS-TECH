# Relecture Codex — les correctifs de tes propres constats, et la concurrence non testée

**Dépôt** : `j:\applications\sms_tech` · branche `fix/audit-codex-2026-08-04`
**Cible** : `git diff cb74483..HEAD` — 4 commits, jamais relus par personne.
**Axe** : vérifier que ces correctifs **corrigent vraiment**, et fermer SC-07.

## Pourquoi cette relecture précisément

Ton rapport précédent concluait : « la correction de ponctualité est **mal corrigée**, et non
simplement incomplète ». Tu avais raison. Les quatre commits ci-dessous répondent à SC-01 à SC-06,
plus deux relectures Gemini menées en parallèle.

**Aucun n'a été relu.** Or c'est exactement là que ce dépôt casse : relire les correctifs d'une
relecture y a trouvé un vrai défaut **neuf fois d'affilée**. Aujourd'hui même, en relisant mon
propre travail à quelques heures d'intervalle, j'ai trouvé :

- une **boucle de réveil** que j'avais introduite en corrigeant la ponctualité ;
- une garde qui retirait la notification de séquence **alors que la séquence courait encore** ;
- cette même notification **visible en session leurre**, révélant qu'une alerte était partie.

Pars du principe qu'il en reste.

## Les quatre commits

| Commit | Ce qu'il prétend fermer |
|---|---|
| `4d38362` | SC-01, SC-04, moitié de SC-06 + 5 constats Gemini (machine à états) |
| `9b71731` | Étanchéité du Coffre : second facteur contournable, évasion du leurre |
| `b4cf690` | SC-02, SC-03, reste de SC-06 |
| `bef014c` | Notification pendant la séquence de relances |

## Ce que je te demande de vérifier, dans l'ordre de mon doute

### 1. SC-01 est-il vraiment fermé, ou déplacé ?

`nextWakeUpAt(cfg, nowMs, nowMonoMs)` rend désormais `max(échéance murale, nowMs + monotone
restant)`, et `apply()` **refuse de programmer une échéance déjà passée** — on retombe alors sur le
tick horaire. L'observateur de `MainApplication` déduplique sur **l'instant calculé**, plus sur la
configuration.

- Reste-t-il un état où l'alarme se reprogramme en boucle ?
- La déduplication tient-elle vraiment ? Elle repose sur un invariant : le jalon horaire déplace du
  temps de l'ancre vers le capital **sans changer la somme**, donc sans déplacer l'instant. Un test
  le fige (`SafetyCallAlarmSchedulerTest`). **Cet invariant est-il exact dans tous les cas ?**
- Les deux horloges sont lues sur deux lignes différentes. La gigue entre les deux peut-elle
  produire des instants distincts à chaque émission, donc une reprogrammation permanente ?

### 2. Le bail (`claimedAt`) est-il correct, ou ai-je créé un nouveau trou ?

Nouveau champ persisté. La réservation le pose, la restitution et le succès le lèvent. Passé
`CLAIM_LEASE_MS` (2 min), un créneau non conclu est repris et l'envoi retenté.

- Peut-on **perdre** un message avec ce protocole, ou en envoyer **deux** ?
- Que se passe-t-il si le processus meurt **pendant la reprise** ?
- `KEEP` au lieu de `REPLACE` sur le contrôle immédiat : est-ce que ça ne fige pas un worker mort ?
- Le bail bloque tout envoi pendant 2 minutes. Une relance due dans cet intervalle est-elle
  **perdue** ou seulement retardée ?

### 3. SC-03 : la comparaison de `lastActivityAt` suffit-elle ?

La transaction de réservation compare maintenant `enabled`, `messagesSent`, le bail **et**
`lastActivityAt`. Tu recommandais « une génération de timer comparée atomiquement ».

- `lastActivityAt` fait-il office de génération, ou existe-t-il un entrelacement où il ne bouge pas
  alors que l'utilisateur a bien confirmé ?
- `withActivityReset()` est censé être le **seul** moyen d'enregistrer une activité. Vérifie qu'il
  n'existe plus aucun autre site qui écrive `lastActivityAt` à la main.
- L'asymétrie `disarmIfTriggered` est-elle au bon endroit ? Le geste explicite « Je vais bien »
  désactive ; une simple ouverture referme la séquence sans désactiver. Est-ce cohérent partout ?

### 4. SC-07 — c'est le morceau, et je ne l'ai pas fermé

Tu as écrit : « le test *deux ticks qui se croisent* appelle deux fois `invoke()` l'un après
l'autre dans le même `runBlocking` ; il ne vérifie pas deux snapshots concurrents. La suite donne
une assurance supérieure à ce qu'elle teste réellement. »

C'est vrai, et ça reste vrai. **Écris les tests qui manquent** :

- deux exécutions **réellement concurrentes** synchronisées par barrières ;
- annulation exactement **après la réservation et avant le premier envoi** ;
- reset utilisateur **entre le snapshot et la réservation** ;
- échec total qui rend le créneau, puis reprise ;
- flux de décisions de planification avec **horloges injectées**, dont la régression d'échéance
  passée.

Ils vivent dans `app/src/test/java/com/filestech/sms/domain/usecase/TriggerSafetyCallRelanceTest.kt`
et `.../system/scheduler/SafetyCallAlarmSchedulerTest.kt`.

⚠️ **Un test doit être non vacant.** Réintroduis le défaut, montre qu'il passe au rouge, restaure.
Et **ne laisse jamais une injection dans l'arbre entre deux commandes** : un commit a déjà emporté
un défaut injecté pour une preuve.

### 5. Le lot Coffre (`9b71731`), que tu n'as pas vu

- `forceLock()` verrouille désormais aussi la session du Coffre. Y a-t-il un appelant pour qui
  c'est **faux** ?
- `setPin` rend `Ok` **sans rien écrire** en session leurre, pour ne pas révéler à l'agresseur
  qu'une vraie session existe. Est-ce le bon compromis, ou cela piège-t-il l'utilisateur légitime ?
- `markAllIncomingAsRead` exclut le Coffre par sous-requête. La requête est-elle correcte, et
  l'index sur `in_vault` est-il utilisé ?

### 6. La notification de séquence (`bef014c`)

Posée pendant toute la séquence, retirée à la fin. Sa décision se prend sur **l'état persisté**
(`hasRelancePending`) et non sur le type de résultat — j'ai supprimé une énumération qui retirait
la notification à tort sur `SendFailed` et `AlreadySent`.

- Reste-t-il un chemin où la notification survit à la fin de la séquence, ou disparaît pendant ?
- Le retrait immédiat à l'entrée en mode leurre couvre-t-il **tous** les chemins d'entrée ?

## Hors périmètre

- `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `setAlarmClock` : arbitré, écarté.
- Keystore et signature : deux audits l'ont recommandé à tort ; appliqué, cela casserait la chaîne
  de mise à jour de toutes les installations existantes.
- Régénérer une baseline detekt ou lint.
- Le mode urgence : un autre relecteur le couvre en parallèle.

## Format

Pour chaque constat : **fichier:ligne**, l'**entrelacement ou l'état exact**, la **conséquence pour
l'utilisateur**, et un statut **CONFIRMÉ / PROBABLE / À VÉRIFIER**.

Et sois direct : **si un de ces correctifs est mal corrigé plutôt que non corrigé, dis-le
franchement.** C'est ce que tu as fait la dernière fois, et c'est ce qui m'a été le plus utile.

## Gate

```
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt --console=plain
```

Quatre outils, `detekt` sur `:app :data :domain :core`. Vert sur `HEAD` (349 tests).
