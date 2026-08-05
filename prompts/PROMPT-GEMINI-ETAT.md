# Relecture Gemini — machine à états du Safety call, et ce qu'elle laisse voir

**Dépôt** : `j:\applications\sms_tech` · branche `fix/audit-codex-2026-08-04`
**Cible** : `git diff dbdd95f..HEAD` — 3 commits, 10 fichiers, +672 / −125.
**Axe imposé** : **machine à états de bout en bout, et sécurité / vie privée.** Pas
l'ordonnancement WorkManager ni la concurrence — un second relecteur couvre cet axe en parallèle,
inutile de le doubler.

## Ce qu'est cette fonction

Un homme-mort. Si l'utilisateur n'ouvre pas SMS Tech pendant un délai choisi (1 h à 30 jours), un
SMS part vers 1 à 4 proches, suivi de **3 relances à 15 min**, puis le deadman se désarme. Ce sont
de **vrais SMS vers de vraies personnes**, et les utilisateurs visés sont des personnes seules, des
voyageurs solo, des personnes âgées.

Les deux échecs possibles ne se valent pas :
- **ne pas partir** = quelqu'un croit disposer d'une protection qui n'existe pas ;
- **partir sans fin** = les proches apprennent à ignorer l'alerte, et elle est détruite le jour où
  elle est vraie.

## Les états, tels que le code les définit

`SafetyCallConfig` (`domain/.../safetycall/SafetyCallConfig.kt`) porte 4 champs d'état :
`enabled`, `lastActivityAt`, `triggeredAt`, `messagesSent` — plus deux compteurs monotones
(`monotonicLastActivityAt`, `monotonicAccumulatedMs`) qui existent pour qu'un attaquant root
avançant l'horloge du système ne puisse pas déclencher l'alerte prématurément.

Dérivés : `isTriggered`, `hasRelancePending`, `nextRelanceAt()`, `isRelanceDue()`, `isExpired()`,
`isInWarningWindow()`.

## Les cinq questions que je te pose

### 1. Existe-t-il un état où le deadman est armé mais ne partira JAMAIS ?

C'est la question la plus importante du document. Énumère les combinaisons de ces quatre champs et
cherche celles qui sont **absorbantes** : `enabled = true`, mais aucun chemin ne mène plus à un
envoi ni à un désarmement.

Deux pistes que j'ai en tête sans les avoir tranchées :
- `enabled = true`, `triggeredAt > 0`, `messagesSent = TOTAL_MESSAGES` — `isExpired()` rend
  `false` (sortie anticipée sur `isTriggered`), `hasRelancePending` rend `false`, donc plus aucune
  relance. Seul `Result.SequenceComplete` peut fermer, et il exige qu'un tick passe.
- `monotonicLastActivityAt == 0L` sur une configuration héritée : `isExpired()` rend `false`
  **définitivement** jusqu'au premier reset. Le commentaire présente ça comme un filet de
  sécurité — est-ce le bon sens d'échec pour une fonction dont l'échec silencieux est le pire cas ?

### 2. L'asymétrie des deux chemins d'échec est-elle correcte ?

Quand aucun envoi n'aboutit, le créneau est rendu. Mais **pas de la même façon** selon l'entrée :

```kotlin
triggeredAt = if (isRelance) cfg.triggeredAt else 0L,
```

Chemin initial → `triggeredAt` remis à `0L` (rien n'est parti, la séquence n'a pas commencé).
Chemin relance → conservé (le premier message est déjà chez les contacts).

Vérifie que c'est bien le bon sens **dans les deux cas**, et notamment : que se passe-t-il si le
processus meurt **entre** la réservation du créneau et sa restitution ? Le commentaire dit « au
pire un message en double ». Est-ce exact, ou peut-on perdre la séquence entière ?

### 3. Le mode panic-decoy laisse-t-il fuiter quelque chose ?

Sous contrainte, l'utilisateur peut déverrouiller en mode leurre. Dans cet état, le Safety call ne
doit **rien** envoyer — sinon l'agresseur voit partir les SMS et découvre le réseau de soutien de
la victime.

`TriggerSafetyCallUseCase` et `SafetyCallWorker` gardent tous deux ce cas. Mais **cb74483 ajoute
une alarme système**. Questions :
- Une alarme `setAndAllowWhileIdle` est-elle **observable** par l'utilisateur ou par une
  application tierce ? (Contrairement à `setAlarmClock`, qui affiche l'icône réveil — c'est
  précisément pour ça qu'elle a été écartée. Confirme que `setAndAllowWhileIdle` ne laisse
  aucune trace visible.)
- Le `PendingIntent` est `FLAG_IMMUTABLE` et vise explicitement un composant `exported="false"`.
  Une application tierce peut-elle malgré tout déclencher `SafetyCallAlarmReceiver` et faire partir
  l'alerte en avance — donc **révéler les contacts d'urgence sur commande** ?
- Le contrôle immédiat déclenché par l'alarme respecte-t-il bien la garde panic-decoy ?

### 4. La fenêtre d'avertissement est fausse sur les délais courts — que proposes-tu ?

`WARNING_WINDOW_MS` vaut **6 h en dur**, quelle que soit la durée choisie. Avec un délai d'**1 h**,
la condition `écoulé ≥ délai − 6 h` est vraie **dès l'armement** : la notification « Confirme que
tu vas bien » s'affiche immédiatement et ne quitte plus la barre d'état. Constaté sur appareil le
2026-08-05.

Une notification permanente qui devrait signaler l'imminence ne signale plus rien. Propose une
règle **proportionnée** à la durée, et dis explicitement ce qu'elle donne pour 1 h, 24 h et
30 jours.

### 5. Les textes envoyés

`SafetyCallTemplate.renderRelance(index)` produit 3 textes distincts et progressifs, le dernier
s'annonçant comme dernier. Relis-les du point de vue **du contact qui les reçoit sans contexte**,
en pleine nuit, d'un numéro qu'il connaît :
- Comprend-il quoi faire ?
- Le message peut-il être pris pour une arnaque ?
- La contrainte technique est un segment SMS UCS-2 (70 caractères accentués), deux au pire.

## Fichiers à lire en priorité

```
domain/src/main/java/com/filestech/sms/domain/safetycall/SafetyCallConfig.kt
domain/src/main/java/com/filestech/sms/domain/safetycall/SafetyCallTemplate.kt
domain/src/main/java/com/filestech/sms/domain/usecase/TriggerSafetyCallUseCase.kt
domain/src/main/java/com/filestech/sms/domain/usecase/IAmOkUseCase.kt
app/src/main/java/com/filestech/sms/system/scheduler/SafetyCallAlarmScheduler.kt   (nouveau)
app/src/main/java/com/filestech/sms/system/receiver/SafetyCallAlarmReceiver.kt     (nouveau)
app/src/main/java/com/filestech/sms/system/notifications/SafetyCallWarningNotifier.kt
app/src/main/AndroidManifest.xml
```

## Hors périmètre — ne pas le proposer

- **`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `setAlarmClock`** : arbitré, écarté. Motifs dans le
  fichier `SafetyCallAlarmScheduler.kt`.
- **Toucher au keystore ou à la signature.** Deux audits l'ont recommandé à tort ; appliqué, cela
  casserait la chaîne de mise à jour de toutes les installations existantes.
- **Régénérer une baseline detekt ou lint.**
- L'ordonnancement WorkManager et la concurrence entre workers — couverts ailleurs.

## Ce que j'attends en retour

Pour chaque constat : **fichier:ligne**, un **scénario concret** (quel état, quelle action de
l'utilisateur, quelle conséquence), et un statut **CONFIRMÉ / PROBABLE / À VÉRIFIER**.

Sois économe : je préfère **trois constats vrais et précis** à quinze plausibles. Les relectures
précédentes ont produit beaucoup de faux positifs, et chacun coûte une vérification.

## Gate

```
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt
```

Vert sur `HEAD` — quatre outils, `detekt` sur `:app :data :domain :core` inclus.
