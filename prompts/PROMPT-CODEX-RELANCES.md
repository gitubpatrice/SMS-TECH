# Prompt Codex — axe **cycle de vie et concurrence** (Safety Call v2, SMS Tech v1.27.2)

> À coller dans Codex, à la racine de `j:\applications\sms_tech`,
> branche `fix/audit-codex-2026-08-04`.

---

## Périmètre

```bash
git diff f9f28b2..HEAD
```

Deux commits, et uniquement ceux-ci :

```text
e45562e  feat(safety call): escalade bornee — 3 relances a 15 min au lieu d'un envoi unique
dbdd95f  fix(safety call): ne plus desarmer le deadman AVANT d'avoir envoye quoi que ce soit
```

## Ton axe, et lui seul : **le temps, l'ordonnancement, la concurrence**

Un second relecteur travaille en parallèle sur l'axe adversarial et produit. **Ne va pas sur son
terrain** : ni menace, ni ergonomie, ni pertinence du choix de trois relances. Ce que je te demande
est ce que tu fais le mieux — remonter des chemins d'exécution réels et trouver l'entrelacement qui
casse.

## Ce que fait ce lot

Le Safety call ne se désarmait plus après son premier SMS. Il envoie désormais **4 messages** — un
initial, puis 3 relances à 15 minutes — et ne se désarme qu'à la fin de la séquence.

Deux propriétés doivent tenir **ensemble**, et elles tirent en sens opposés :

1. **Rien en double.** Le tick périodique (60 min) et le travail ponctuel de relance (15 min)
   peuvent se croiser. Le créneau est donc *réservé* de façon atomique dans un `settings.update {}`
   avant l'envoi.
2. **Rien qui s'éteigne en silence.** Si aucun envoi n'aboutit, le créneau est *rendu* et le
   deadman reste armé.

## Les questions, par ordre de mon doute

### 1. La réservation atomique est-elle réellement atomique ?

`TriggerSafetyCallUseCase` fait :

```kotlin
var claimed = false
settings.update { s ->
    val cfg = s.security.safetyCall
    if (!cfg.enabled || cfg.messagesSent != current.messagesSent) s
    else { claimed = true; s.copy(/* messagesSent + 1, triggeredAt si premier */) }
}
if (!claimed) return Result.AlreadySent
```

- `SettingsRepository.update` s'appuie sur `DataStore.edit {}`. Le transform s'exécute-t-il **sous
  le verrou d'écriture**, et une seule fois ? DataStore peut **rejouer** un transform en cas de
  conflit : que se passe-t-il alors pour la variable capturée `claimed` ?
- Deux invocations réellement concurrentes du use case : trouve l'entrelacement qui envoie deux
  fois, ou démontre qu'il n'existe pas.

### 2. La restitution du créneau

```kotlin
if (sent == 0) { settings.update { /* remet messagesSent, et triggeredAt à 0 si c'était le 1er */ } }
```

- Peut-elle écraser une écriture concurrente légitime — un « Je vais bien », un reset d'activité,
  le jalon monotone du worker ?
- Le processus tué entre l'envoi réussi et cette ligne : quel état reste en base, et le tick suivant
  s'en remet-il ?

### 3. WorkManager — le vrai risque opérationnel

`SafetyCallWorker.scheduleRelance` pose un `OneTimeWorkRequest` unique (`REPLACE`) avec
`setInitialDelay(15 min)`, tandis que le travail **périodique** garde son nom propre.

- Les deux peuvent-ils s'exécuter **simultanément** ? WorkManager sérialise-t-il par nom unique
  seulement, ou globalement ?
- `REPLACE` sur un travail **en cours d'exécution** : quel est le comportement exact, et peut-il
  annuler une relance pendant son envoi ?
- Un redémarrage du téléphone au milieu de la séquence : le travail ponctuel survit-il ? Sinon, le
  tick horaire le repose-t-il vraiment — vérifie la branche `hasRelancePending` du worker.
- Le worker jalonne le compteur monotone (`monotonicAccumulatedMs`) **à chaque tick**, y compris
  pendant la séquence de relances. Est-ce inoffensif, ou cela perturbe-t-il quelque chose ?

### 4. La machine à états ne peut-elle pas se bloquer ?

Champs : `enabled`, `triggeredAt`, `messagesSent`, `lastActivityAt`, `monotonicLastActivityAt`,
`monotonicAccumulatedMs`.

- Existe-t-il une combinaison atteignable où **plus rien** ne part et où l'interface ment sur
  l'état ? Par exemple `triggeredAt > 0` avec `messagesSent == 0`, ou `messagesSent >
  TOTAL_MESSAGES`.
- `isExpired()` rend `false` dès que `triggeredAt` est posé. Après un désarmement de fin de
  séquence, l'utilisateur qui **réarme** repart-il d'un état propre ? Cherche le chemin où
  `triggeredAt` resterait posé.
- Le « Je vais bien » post-déclenchement remet `enabled=false`, `triggeredAt=0`, `messagesSent=0`.
  Le travail ponctuel déjà programmé est-il annulé, ou va-t-il se réveiller dans le vide ?

### 5. Les tests

`app/src/test/java/com/filestech/sms/domain/usecase/TriggerSafetyCallRelanceTest.kt` — 5 tests.

- Le faux `AppSettingsSource` reproduit-il fidèlement l'atomicité de DataStore, ou est-il **plus
  gentil** que la production ? Si oui, quelle propriété n'est pas réellement testée ?
- Que **ne** testent-ils pas parmi ce que ce diff change ? C'est la question qui m'intéresse le plus.

## Format de réponse

| # | Fichier:ligne | Sévérité | Statut | Défaut | Scénario d'échec concret |
|---|---|---|---|---|---|

`CONFIRMÉ` seulement si tu as ouvert le fichier appelant et remonté le chemin. Sinon `À VÉRIFIER`,
en disant ce qui te manque. Scénario = état de départ → ce qui se passe → pourquoi c'est faux.
Si un point ne donne rien, écris-le : un « rien à signaler » motivé m'est utile.

## Interdits

- Keystore, signature, baselines detekt/lint.
- `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` : arbitré, hors périmètre.
- Style, renommage, code hors de ce diff.
- L'axe adversarial et produit : couvert par l'autre relecteur.
