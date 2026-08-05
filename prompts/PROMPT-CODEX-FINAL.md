# Audit Codex — SMS Tech v1.27.2, relecture des correctifs C-01 à C-09

Dépôt : `J:\applications\sms_tech` · Branche : `fix/audit-codex-2026-08-04` · **HEAD : `2f701b7`**

Périmètre : **`git diff eebce87..HEAD`** — trois commits, `9e54372`, `165d7e4`, `2f701b7`.

Tu as accès au dépôt. Tu peux lire, chercher les appelants, exécuter, lancer la gate.

## Ce que tu relis

Tes neuf constats de `audit/ia-externe/rapport-audit-codex-p08-p11-2026-08-05.md`
(verdict NON PUBLIABLE) ont tous été traités. Tu relis **les correctifs**, pas les constats.

| Constat | Ce qui a été fait |
|---|---|
| C-01 | Garde de déplacement rétablie sur **toute** la durée du maintien, seuil dédié `HOLD_DRIFT_TOLERANCE = 24.dp` |
| C-02 | Suivi de l'**identifiant** du pointeur propriétaire, drainage jusqu'au dernier UP |
| C-03 / C-04 | Le ratio disparaît. Vérification **par URI canonique** (`TelephonyReader.smsExists`) + **deux passes** consécutives avant tout DELETE |
| C-05 | `readMmsBatched` rend un `Boolean` de complétude ; curseur principal, adresses et parts doivent tous avoir abouti |
| C-06 | `forceResyncFromTelephony` invalide les **deux** marqueurs et sollicite le manager |
| C-07 / C-08 | Identité **E.164 région-aware** (`PhoneIdentity`), appliquée aux six appelants, **sans migration** — `blocked_numbers` conserve `raw_number` |
| C-09 | `isInWarningWindow` = les deux compteurs ont franchi leur seuil **et** le deadman n'a pas expiré |

## ⚠️ Le motif à chercher en priorité

**Le correctif qui casse autre chose, ou qui ne corrige pas ce qu'il prétend.**

Sur ce dépôt, relire les correctifs d'un audit a trouvé de nouveaux défauts **à chaque passe, sans
exception** — jusqu'à douze en une fois. Aujourd'hui encore :

- j'ai corrigé `readAllSmsIds()` sans reporter sur son jumeau MMS (ton C-05) ;
- j'ai fermé une moitié de l'espace de collision en croyant l'avoir fermé en entier (ton C-07) ;
- et en relisant mon propre correctif de C-05, j'ai trouvé que `readMmsPartsBatched` **posait
  `ok = false` sans jamais le rendre** — le défaut réintroduit à l'intérieur de sa propre
  correction, invisible pour la gate. C'est `2f701b7`.

Les quatre motifs du dépôt restent : garde sur l'affichage plutôt que sur l'accès · **jumeau
asymétrique** (dominant) · repli qui échoue du mauvais côté · chemin mort.

## ⚠️ Mes propres doutes, à traiter EN PRIORITÉ

**D1 — `PhoneIdentity.canonical` lit un `StateFlow` peut-être non hydraté.**
Il appelle `PhoneNumberWireFormatter.defaultRegionIso()`, qui lit
`settings.state.value.sending.defaultRegionIso` — un `stateIn(..., Eagerly, AppSettings())` dont la
**valeur initiale est la configuration par défaut**. C'est exactement le motif qui avait rendu le
Safety call muet (le worker lisait `enabled = false` avant hydratation).
Question : sur un import déclenché tôt au démarrage, le réglage « Indicatif pays par défaut » peut-il
être ignoré au profit du pays de la SIM ? Si oui, quelqu'un avec une SIM étrangère et un override
verrait ses numéros nationaux **échouer fermé** — donc des conversations en double créées à
l'import, **de façon permanente**. Est-ce réel, et si oui quelle est la bonne réponse : attendre
l'hydratation, ou ne pas canonicaliser tant que la région n'est pas fiable ?

**D2 — `smsExists` sur une ligne absente — ✅ VÉRIFIÉ SUR APPAREIL, ne le refais pas.**
Mesuré sur le Galaxy S9 (`22dbb7390a057ece`, Samsung One UI) :

```
content://sms/999999999   → "No result found."               (curseur VIDE, rc=0)
content://provider_bidon  → IllegalStateException            (curseur null / erreur)
content://sms/11506       → "Row: 0 _id=11506"               (curseur peuplé)
```

Les trois cas sont donc bien distincts : une ligne supprimée rend un curseur **non nul et vide**,
`smsExists` rend `false`, et la réconciliation **converge**. Ce qui reste ouvert et t'appartient :
un OEM où la requête d'une ligne absente rendrait `null` ferait mourir la fonctionnalité **en
silence** — elle échouerait du bon côté, mais plus rien ne serait jamais réconcilié. Dis-moi si tu
juges ce risque suffisant pour exiger une trace explicite quand `smsExists` rend `null` sur
**toutes** les lignes candidates.

**D3 — Le garde-fou « deux passes » n'impose aucun délai.**
`pendingDeletion` est comparé d'une passe à l'autre, mais `runSync` peut être appelé en rafale par
l'observateur. Deux passes séparées de quelques millisecondes ne sont pas plus indépendantes que
les deux lectures que tu as écartées en C-04. La preuve réelle est censée être la requête par URI
canonique — le double passage n'est qu'une ceinture. Est-ce que je me raconte une histoire, et
faut-il un délai minimal explicite entre deux observations ?

**D4 — L'échec fermé de `phoneAddressesMatch` a-t-il cassé un rapprochement légitime ?**
Quand `toE164` rend `null` des deux côtés pour deux numéros **complets**, on refuse. Cherche les cas
réels : numéros avec extension, `*`/`#`, formats OEM, adresses MMS décorées, régions où
`formatNumberToE164` échoue sur une forme nationale valide. Le prix d'un faux refus est une
conversation en double **permanente**.

**D5 — Coût de `PhoneIdentity` sur les chemins chauds.**
`blockedMatcher` indexe par `blockKey` puis canonicalise les seuls candidats du seau. Mais
`observeAll` le reconstruit **à chaque émission** de la liste des conversations, et
`ConversationMirror` appelle `matches` par conversation candidate à chaque import. Sur 50 000
messages et une liste noire fournie, est-ce tenable ?

**D6 — C-01, le seuil de 24 dp.**
Trop bas, il annule le maintien de quelqu'un qui tremble — et le bouton ne sert plus à rien au
moment où il compte. Trop haut, il rouvre ton constat. Est-ce le bon arbitrage, et quelle valeur
recommanderais-tu ? Dis-moi aussi si `HOLD_DRIFT_TOLERANCE` doit être mesuré depuis le point
d'appui **initial** (ce que je fais) ou depuis la position à la fin de la fenêtre de discrimination.

## Ce que je te demande de rendre

Pour **chaque** finding : ID, titre, **statut** (`CONFIRMÉ` / `PROBABLE` / `À VÉRIFIER`, et comment
tu l'as établi), gravité, **bloquant oui/non**, fichier:ligne, **l'état ou l'entrelacement exact**,
la conséquence utilisateur, la correction nécessaire.

Puis obligatoirement :

- `## Réponses à mes six doutes` — D1 à D6, un par un, même pour les écarter
- `## Ce que j'ai vérifié et qui est CORRECT`
- `## Tests à écrire` — priorisés, en distinguant ce qui est testable en JVM, ce qui exige un
  `ContentResolver` factice, et ce qui exige un appareil
- `## Verdict` — PUBLIABLE ou NON PUBLIABLE, avec la liste minimale des conditions

**Ne modifie aucun fichier applicatif.** Rapport dans
`audit/ia-externe/rapport-audit-codex-final-2026-08-05.md`.

Gate exacte, verte au moment où j'écris (382 tests, 0 échec) :

```
.\gradlew.bat :app:assembleDebug testDebugUnitTest :app:lintDebug detekt --console=plain
```

Pas de findings de style : `detekt`, `ktlint` et `lint` bloquants tournent déjà.
