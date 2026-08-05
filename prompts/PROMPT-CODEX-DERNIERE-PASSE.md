# Audit Codex — SMS Tech v1.27.2, dernière passe

Dépôt : `J:\applications\sms_tech` · Branche : `fix/audit-codex-2026-08-04` · **HEAD : `590f8c3`**

Périmètre : **`git diff 2f701b7..HEAD`** — quatre commits, `92c914f`, `63328e8`, `804c4bd`,
`c860af7`, `590f8c3`.

Tu as accès au dépôt. Lecture, appelants, exécution, gate.

## Ce que tu relis

Tes cinq constats F-01→F-05 ont été traités, plus deux défauts trouvés **en testant sur appareil**.

| Constat | Ce qui a été fait |
|---|---|
| F-01 | `PhoneIdentity.snapshot()` franchit `hydratedOrNull()` une fois et **fige** la région ; le jumeau `dedupeSameNumberConversations` est traité dans `590f8c3` |
| F-02 | `blockKey` n'est plus une précondition — `phoneIdentityKey` décide, E.164 d'abord |
| F-03 | La clé **stockée** devient l'E.164 ; `planBlocklistRekey` reçoit la clé en paramètre. Pas de migration Room |
| F-04 | Sondes unitaires bornées à `MAX_PROBES_PER_PASS = 300` |
| F-05 | Deux écritures identiques non canonicalisables rendent la même clé |
| — | `DeletionReconciliation` extrait en fonction pure + 9 tests (`63328e8`) |
| — | Conversation fantôme + faux badge après suppression externe (`804c4bd`) |
| — | 9 tests Compose instrumentés du bouton d'urgence (`92c914f`) |

## ⚠️ Ce qui a été VÉRIFIÉ SUR APPAREIL — ne le refais pas

Galaxy S9 (`22dbb7390a057ece`), `com.filestech.sms.debug` détenant le rôle SMS :

- **Rekey sur 178 entrées réelles** : `163 migrated, 0 collapsed`, `room=178` avant **et** après,
  **silencieux au second démarrage**. La conversion sans migration Room converge et ne perd rien.
- **Protocole à deux passes** : `1 suppression(s) en attente d une seconde passe`, puis
  `1 local row(s) dropped`.
- `content://sms/<id absent>` → curseur **vide** ; provider cassé → exception. Distincts.
- 9 tests Compose verts, non-vacuité prouvée par réinjection de C-01 et C-02.

## ⚠️ Mes doutes, à traiter EN PRIORITÉ

**D1 — `snapshot()` dans un flux chaud.** `ConversationRepositoryImpl.observeAll` appelle
`phoneIdentity.snapshot()` **dans le `combine`**, donc à **chaque** émission de la liste des
conversations ou de la liste noire. Chaque appel franchit `hydratedOrNull()` puis, via
`blockedMatcher`, canonicalise les **178** numéros bloqués. Est-ce tenable ? Faut-il mémoriser
l'instantané, et si oui comment l'invalider quand l'utilisateur change son indicatif par défaut ?

**D2 — 🔴 Fenêtre de vulnérabilité à la première ouverture après mise à jour.** `isBlocked` compare
désormais la clé **E.164** à `dao.isBlocked(key)`. Or les entrées ne sont converties que par le
rekey, qui tourne dans `importFromSystem()` au début de `runSync`. Entre le démarrage du processus
et cette première conversion, un SMS d'un numéro bloqué peut-il **passer** ? Si oui, c'est une
régression de sécurité que je n'ai pas vue, et il faut soit un repli sur l'ancienne clé, soit
forcer le rekey avant tout filtrage.

**D3 — `unblock()` sur une entrée non encore convertie.** Il supprime par clé E.164 ; une entrée
restée sur sa clé de neuf chiffres ne serait pas trouvée. L'utilisateur peut-il se retrouver
incapable de débloquer un numéro tant que le rekey n'a pas tourné ?

**D4 — Convergence avec le plafond de 300 sondes.** `nextPending` ne contient que les URI du lot
sondé. Avec 1 000 candidats, la passe suivante resonde-t-elle **le même** lot — donc converge — ou
peut-elle osciller entre lots et ne jamais confirmer deux fois les mêmes ?

**D5 — Le drapeau de dedup rendu `true` quand la région est inconnue.** Dans `590f8c3`,
`dedupeSameNumberConversations` rend `true` s'il n'a rien trouvé **et** que la région est
indéterminable, pour que la migration one-shot rejoue. Est-ce que l'appelant interprète bien
`true` comme « il reste du travail » ? Vérifie `StartupMigrations` : je peux avoir inversé le sens.

**D6 — La conversation fantôme (`804c4bd`) n'est PAS vérifiée sur appareil.** Le nettoyage ne
s'exécute que lorsqu'une suppression a lieu, or la coquille existante datait du tour précédent.
`deleteIfEmpty` supprime-t-il bien la bonne chose, et jamais une conversation du Coffre ?

## Ce que je te demande

Pour chaque finding : ID, titre, **statut** et comment tu l'as établi, gravité, **bloquant
oui/non**, fichier:ligne, l'état exact, la conséquence utilisateur, la correction.

Puis : `## Réponses à mes six doutes` · `## Ce qui est CORRECT` · `## Verdict` (PUBLIABLE ou non,
conditions minimales).

**Ne modifie aucun fichier applicatif.** Rapport dans
`audit/ia-externe/rapport-audit-codex-derniere-passe-2026-08-05.md`.

Gate, verte au moment où j'écris — 395 tests, 0 échec :

```
.\gradlew.bat :app:assembleDebug testDebugUnitTest :app:lintDebug detekt --console=plain
```

Pas de findings de style.
