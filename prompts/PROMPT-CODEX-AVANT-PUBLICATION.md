# Relecture Codex — dernière avant publication de v1.27.2

**Dépôt** : `j:\applications\sms_tech` · branche `fix/audit-codex-2026-08-04` · `HEAD = 75c03d4`
**Cible** : `git diff 7bc59bb..HEAD` — 3 commits jamais relus
**Plus** : `c1faad7` (mode urgence), que ta relecture précédente avait explicitement mis **hors
périmètre** et que personne n'a donc jamais relu.

**Question à laquelle je te demande de répondre en une ligne, en tête de rapport :
PUBLIABLE, ou NON PUBLIABLE ?**

## Ce qui a changé depuis ton dernier rapport

Tu avais conclu « NON PRÊT pour livraison en l'état », avec onze constats. Les onze sont traités :

| Commit | Contenu |
|---|---|
| `b6e8a97` | Synchro telephony : garde de proportionnalité sur les suppressions, aperçu de conversation, `CancellationException` |
| `a18d4c2` | C-01, C-02, C-06, C-11 — les quatre qui ne demandaient pas d'arbitrage |
| `75c03d4` | **C-03, C-04, C-05, C-07, C-08, C-09, C-10** — réécriture du protocole de bail |

`75c03d4` est le morceau, et il touche exactement le code que tu as déclaré incomplet.

## Les six questions, dans l'ordre de mon doute

### 1. Le protocole de propriété tient-il vraiment ?

Deux champs persistés nouveaux : `claimId` (compteur monotone, incrémenté à chaque réservation) et
`generation` (incrémentée par `withActivityReset()`). Toute écriture de conclusion — restitution,
levée de bail, désarmement — exige de retrouver les deux.

- **Peut-on encore perdre un message, ou en envoyer deux ?** C'est la seule question qui compte.
- Un worker qui reprend un créneau abandonné écrit `claimId = 0` puis se réserve `claimId + 1`.
  Deux repreneurs simultanés peuvent-ils obtenir le **même** identifiant ?
- `stillOwnsClaim()` lit DataStore **avant chaque contact**. Sur un processus froid, cette lecture
  est-elle sûre — pas d'interblocage avec la barrière d'hydratation de `SettingsRepository` ?
- La conclusion et le désarmement de fin de séquence sont désormais dans la **même transaction**.
  Est-ce correct dans tous les cas, y compris quand `remaining <= 0` et que la réservation vient
  d'être perdue ?

### 2. L'expiration du bail programmée peut-elle produire un doublon ?

`nextWakeUpAt` rend maintenant `min(décision nominale, claimedAt + CLAIM_LEASE_MS)`.

Scénario qui m'inquiète : un envoi **légitime** mais lent — quatre contacts, réseau dégradé — passe
les deux minutes. L'alarme du bail se déclenche, un second worker juge le créneau abandonné, le
reprend et **renvoie le même message**. Le premier finit ensuite et se voit refuser sa conclusion.

Est-ce que ça se produit réellement ? Et si oui, deux minutes est-il le bon seuil, ou faut-il
distinguer « bail expiré » de « propriétaire mort » ?

### 3. La reconciliation de notification est-elle complète ?

`MainApplication` en est désormais l'unique propriétaire, sur le couple (configuration persistée,
état du verrou). Le worker n'y touche plus. La notification de séquence a son propre identifiant,
distinct de l'avertissement de pré-déclenchement.

- Reste-t-il un état où elle **survit** à la fin de la séquence, ou **disparaît** pendant ?
- Les deux notifications peuvent-elles coexister à tort ? (`isInWarningWindow` rend `false` dès que
  `isTriggered` — est-ce suffisant ?)
- La réconciliation se rejoue-t-elle vraiment au démarrage à froid, y compris quand le processus
  est réveillé par un worker plutôt que par l'interface ?

### 4. C-09 : le rendez-vous de rattrapage peut-il s'emballer ?

Une échéance déjà dépassée n'est plus annulée : on programme `now + 5 min`. J'ai déjà introduit une
boucle de réveil une fois aujourd'hui, et je veux être certain de ne pas recommencer.

L'observateur ne réémet que sur un **changement de décision**. Un aller-retour réservation →
restitution produit-il une oscillation de décisions qui reposerait l'alarme indéfiniment ?

### 5. Le mode urgence — jamais relu

`c1faad7` corrige trois constats d'une relecture Gemini :

- `save()` passe de `viewModelScope` à `appScope` — sinon décocher puis enregistrer laissait le
  mode urgence **actif en silence** ;
- la récupération de dérive pose `monotonicLastTriggeredAt = 0L` au lieu de `nowMono`, pour ne pas
  relancer un cooldown de 60 s après chaque redémarrage sur un **bouton de panique** ;
- le maintien de 3 s ne s'annule plus au tremblement : `touchSlop` ne tranche que pendant les 300
  premières ms, ensuite seule une sortie du bouton annule.

Le troisième me préoccupe : il touche la garde anti-scroll posée après un **déclenchement
intempestif réellement signalé** le 2026-05-22. Est-ce que je viens de le rouvrir ?

### 6. La synchro telephony — jamais relue

`b6e8a97` : garde de proportionnalité (refus si plus de la moitié du miroir manque), aperçu de
conversation qui n'est plus écrasé par un message plus ancien, `CancellationException` relancée.

- La garde de proportionnalité peut-elle **bloquer une suppression légitime** ? Quelqu'un qui vide
  vraiment sa messagerie depuis une autre application se retrouve-t-il avec un miroir figé ?
- Deux points restent **ouverts et non corrigés**, dis-moi s'ils doivent bloquer la publication :
  l'import MMS n'a aucun mécanisme de reprise (`needsMmsImport = isFirstRun || !hasAnyMms`, donc un
  import interrompu ne reprend jamais), et l'appariement de conversation sur 9 chiffres peut
  fusionner deux correspondants internationaux distincts.

## Ce que j'attends

Pour chaque constat : **fichier:ligne**, l'**entrelacement ou l'état exact**, la **conséquence pour
l'utilisateur**, un statut **CONFIRMÉ / PROBABLE / À VÉRIFIER**, et une **gravité**.

Et sois direct, comme la dernière fois : **si un de ces correctifs est mal corrigé plutôt que non
corrigé, dis-le franchement.** Ton « la correction de ponctualité est mal corrigée » m'a fait
trouver une boucle de réveil que j'avais introduite moi-même.

## Hors périmètre

- `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `setAlarmClock` : arbitré, écarté.
- Keystore et signature : deux audits l'ont recommandé à tort ; appliqué, cela casserait la chaîne
  de mise à jour de toutes les installations existantes.
- Régénérer une baseline detekt ou lint.
- Le style, l'i18n, les renommages.

## Gate

```
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt --console=plain
```

Quatre outils, `detekt` sur `:app :data :domain :core`. Vert sur `HEAD` — **355 tests unitaires**.

⚠️ **N'écris aucun fichier applicatif.** Le rapport seul. Si tu injectes un défaut pour prouver la
non-vacuité d'un test, injecte, mesure et restaure **dans la même commande** : un commit a déjà
emporté une injection sur ce dépôt, et un `git checkout --` a déjà détruit du travail non commité.
