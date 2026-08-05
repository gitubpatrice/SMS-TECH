# Audit Codex — SMS Tech v1.27.2, correctifs P-08 à P-11 (téléphonie, urgence, appariement)

Dépôt : `J:\applications\sms_tech` · Branche : `fix/audit-codex-2026-08-04` · **HEAD : `eebce87`**

Commits à relire : **`34ea1aa`** (les dix constats) et **`eebce87`** (voir D6 ci-dessous).

Tu as accès au dépôt. Tu peux lire, chercher les appelants, lancer la gate.

## Périmètre — et ce qui en est EXCLU

Ce lot ferme les onze constats de ton audit
`audit/ia-externe/rapport-audit-codex-avant-publication-v1.27.2-2026-08-05.md`.

**Tu n'audites ici que quatre d'entre eux : P-08, P-09, P-10, P-11**, plus le commit `eebce87`.

Les constats P-01 à P-07 (protocole de bail, notifications) ont été confiés en parallèle à un autre
relecteur, sur un axe disjoint — son rapport est dans
`audit/ia-externe/rapport-gemini-p02-p07-2026-08-05.md`. **Ne les relis pas** — sauf si tu constates
qu'un correctif de ce périmètre-ci en casse un de celui-là, auquel cas dis-le explicitement.

## ⚠️ Ce qui n'a AUCUN TEST — et c'est pour ça que je t'appelle

Sur les quatre constats de ce périmètre, **un seul est couvert** :

| Constat | Couverture |
|---|---|
| P-11 | `core/src/test/.../PhoneAddressesMatchTest.kt`, 7 tests |
| **P-08** | **aucun** — geste Compose, intestable en JVM |
| **P-09** | **aucun** — `TelephonySyncManager` exige un `ContentResolver` |
| **P-10** | **aucun** |

Une gate verte ne dit donc **rien** de trois correctifs sur quatre ici. C'est le motif « test vert
sur un chemin mort » retourné : il n'y a pas de test du tout. Traite ces trois-là comme non
vérifiés, et dis-moi quels tests écrire — instrumentés compris.

Commande de gate, à lancer telle quelle :

```
.\gradlew.bat :app:assembleDebug testDebugUnitTest :app:lintDebug detekt --console=plain
```

Elle est verte au moment où j'écris : 378 tests, 0 échec.

## Les quatre motifs de défaut de ce dépôt

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS.**
2. **Le jumeau asymétrique** — un correctif appliqué à un endroit et pas à son jumeau. 11 correctifs
   sur 17 lors d'un audit récent. **C'est le motif dominant.**
3. **Le repli qui échoue du mauvais côté.**
4. **Le chemin mort** — code, test ou ressource qu'aucun appelant réel n'atteint.

Et le cinquième, le plus probable ici : **le correctif qui casse autre chose.** Sur ce dépôt, relire
les correctifs d'un audit a systématiquement trouvé de nouveaux défauts introduits par ces
correctifs — jusqu'à douze en une passe.

## ⚠️ Mes propres doutes, à traiter EN PRIORITÉ

**D1 — P-09, la confirmation par seconde lecture est-elle une vraie preuve ?**
`reconcileDeletions` refusait toute suppression massive (> 50 % du miroir), et **ne convergeait
jamais** : une suppression légitime de 80 SMS sur 100 était refusée à chaque passe, laissant
affichés des messages que l'utilisateur avait voulu effacer. J'exige désormais une **seconde lecture
complète rendant exactement le même ensemble** (`confirmsSameAliveSet`).
Trois questions : (a) deux lectures tronquées **de la même façon** sont-elles vraiment
improbables — une troncature déterministe se reproduirait à l'identique ; (b) un SMS reçu entre les
deux lectures fait échouer la confirmation : sur un téléphone qui reçoit régulièrement, cela
peut-il **empêcher indéfiniment** la convergence, c'est-à-dire recréer le défaut sous une autre
forme ; (c) j'ai fait entrer le cas « fournisseur vide » sous cette même règle, donc une base
entièrement vidée est désormais **supprimable**. Est-ce le bon arbitrage ?

**D2 — P-09, `readAllSmsIds()` rend maintenant `LongArray?`.**
`null` = requête échouée, tableau vide = fournisseur réellement vide. **Cherche tous les appelants**
et vérifie qu'aucun ne traite silencieusement `null` comme « vide » — ce serait exactement le repli
qui échoue du mauvais côté, sur le seul chemin de l'application qui efface des messages.

**D3 — P-10, le marqueur `mmsImportCompleted`.**
Nouveau champ `AdvancedSettings`, câblé aux trois points DataStore (clé, lecture, écriture).
Vérifie : (a) que les trois sont bien là et cohérents ; (b) que le marqueur est posé **après** la
dernière page et **seulement** là ; (c) que les installations existantes, qui ne l'ont pas,
rejouent bien une passe complète — et que ce rejeu est **sans effet de bord**, notamment sur les
badges de non-lus, puisque `isFirstRun` vaut alors `false` ; (d) qu'aucun chemin (« resynchroniser
depuis le système » dans les Réglages, migration) ne laisse un état où le marqueur est vrai alors
que l'import est incomplet.

**D4 — P-11, `phoneAddressesMatch` remplace `blockKey()` aux quatre sites de rapprochement.**
Règle : même seau `blockKey`, **puis** si les deux formes commencent par `+`, exiger l'égalité
complète des chiffres. Vérifie : (a) que les **quatre** sites sont bien couverts — c'est le motif du
jumeau asymétrique, et il y en avait quatre, pas trois — ils sont dans
`ConversationRepositoryImpl.matchOneToOneByBlockKey`, `ConversationMirror.ensureConversation`,
`ConversationMirror.ensureConversationForMms` (ou équivalent) et `ConversationMirror` côté réaction
entrante ; (b) qu'aucun rapprochement **légitime** national ↔ international n'a été perdu ; (c) que
**la liste noire**, elle, utilise toujours `blockKey()` brut — je l'ai laissée volontairement, le
risque étant un blocage croisé et non un mauvais destinataire. **Est-ce défendable, ou est-ce le
jumeau que j'ai oublié ?**

**D6 — Le commit `eebce87`, qui n'a été relu par personne.**
Il ajoute un **second réveil** dans `SafetyCallAlarmScheduler.nextWakeUpAt` : l'ouverture de la
fenêtre d'avertissement, programmée avant l'échéance. Motif : l'avertissement dépendait du tick
horaire, et sur un délai d'une heure la fenêtre ne dure que quinze minutes — l'alerte partait donc
souvent **sans que la personne ait été prévenue**.
Questions : (a) ce second rendez-vous peut-il produire une **boucle de réveil** — le cas que la
version précédente de ce fichier avait déjà connu ; (b) au réveil de l'avertissement, c'est le jalon
monotone du worker qui doit réémettre la configuration pour que le réconciliateur affiche quelque
chose : **est-ce garanti**, ou existe-t-il un état où le worker se réveille et n'écrit rien ; (c) je
déduis `warningStart` de `maxOf(wallDeadline, monoDeadline)` — est-ce cohérent avec
`isInWarningWindow`, qui exige les deux horloges ; (d) j'ai dû modifier **quatre tests existants**
parce que le contrat a changé : vérifie qu'aucun n'a été affaibli au passage plutôt qu'adapté.

**D5 — P-08, le bouton d'urgence revendique le geste.**
Passé les 300 ms de discrimination, le composant appelle `pressed.consume()` et les bornes testées
sont désormais le **disque** visible, plus le carré englobant. Questions : (a) consommer dans la
passe `Main` empêche-t-il **réellement** un parent `scrollable` de défiler, ou faut-il la passe
`Initial` ; (b) ce verrouillage du geste casse-t-il autre chose — accessibilité, TalkBack,
multi-touch, retour arrière par geste ; (c) la garde `pressed.isConsumed` que j'ajoute peut-elle
annuler un maintien **légitime** ; (d) **le cas que tu décrivais** — doigt posé, immobile 300 ms,
puis glissement lent de plus de trois secondes sans quitter le disque — produit-il encore un envoi
de SMS ? Dis-moi précisément ce qu'un test instrumenté Compose devrait exercer, je l'écrirai.

## Ce que je te demande de rendre

Pour **chaque** finding :

- **ID** (C-01…), titre en une ligne
- **Statut** : `CONFIRMÉ` / `PROBABLE` / `À VÉRIFIER` — et pour `CONFIRMÉ`, dis comment tu l'as
  établi (lecture d'appelants, exécution, test)
- **Gravité** + **bloquant pour la publication : oui / non**
- **Emplacement** fichier:ligne
- **L'entrelacement ou l'état exact** qui produit le défaut
- **Conséquence pour l'utilisateur**
- **Correction nécessaire**

Puis obligatoirement :

- `## Réponses à mes six doutes` — D1 à D6, un par un, même pour les écarter
- `## Ce que j'ai vérifié et qui est CORRECT`
- `## Verdict` — PUBLIABLE ou NON PUBLIABLE, et la liste minimale des conditions

**Ne modifie aucun fichier applicatif.** Écris ton rapport dans
`audit/ia-externe/rapport-audit-codex-p08-p11-2026-08-05.md`.

Pas de findings de style : `detekt`, `ktlint` et `lint` bloquants tournent déjà et sont verts.
