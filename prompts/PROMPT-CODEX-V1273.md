> ✅ **C'EST CE FICHIER QU'IL FAUT COLLER DANS CODEX.** Il est autonome : Codex a accès au dépôt et
> va lire le diff lui-même. Ne pas confondre avec `PROMPT-GEMINI-V1273-SANS-ACCES-DEPOT.md`, qui est
> incomplet à dessein et destiné à l'API Gemini.

# Audit Codex — SMS Tech, branche `fix/safety-call-etat-terminal-historique`, commit `bce6a76`

Tu as **accès au dépôt**. C'est ton avantage décisif sur la relecture Gemini qui vient d'être faite
sur le même lot : elle n'avait que le diff, et son unique erreur vient de là. **Ouvre les fichiers,
remonte les appelants, lis les tests.** N'affirme rien que tu n'aies vérifié dans le code.

## Le périmètre exact

```
git diff main..fix/safety-call-etat-terminal-historique
```

~1300 lignes de code. Tout est dans le Safety call : l'état terminal de la notification et un nouvel
historique des déclenchements. **N'audite pas le reste du dépôt.**

⚠️ Le diff contient aussi des fichiers sous `prompts/` et `audit/ia-externe/` : ce sont les consignes
d'audit et les rapports précédents, **pas du code à relire**. Ignore-les.

## Contexte : ce que l'appareil a mesuré, et ce que le lot corrige

La séquence complète a tourné pour la première fois sur un vrai téléphone la nuit du 2026-08-06 :
déclenchement 23:53:56, relances 00:13:44 / 00:25:17 / 00:49:11, quatre messages partis et reçus.
Trois défauts sont apparus le lendemain :

1. La notification affichait **11h01** — l'heure de sa dernière re-publication — au lieu de 23:53:56,
   parce que `setWhen()` n'était jamais appelé. Elle remontait en tête du volet à chaque démarrage à
   froid et **a été prise pour un second déclenchement**.
2. **« 4 message sur 4 envoyé »** — pluriel français cassé.
3. Elle disait « les relances s'arrêteront » alors que la séquence était close **et que le Safety call
   s'était désactivé tout seul**, sans que rien ne le dise.

## Les quatre motifs de défaut de ce dépôt

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS.**
2. **Le jumeau asymétrique** — un correctif appliqué à un endroit et pas à son jumeau. Motif
   dominant : ce lot en ferme encore deux, et la relecture Gemini en a trouvé un troisième (F-02).
3. **Le repli qui échoue du mauvais côté.**
4. **Le chemin mort** — code, test ou état qu'aucun appelant réel n'atteint.

## Invariants à vérifier, pas à supposer

- `claimId` **strictement croissant**, jamais remis à zéro (P-01).
- `generation` incrémentée à chaque remise à zéro du cycle, et invalidant les workers de l'ancien
  cycle (C-04).
- `messagesSent` = créneaux **réservés** ; `messagesDelivered` = envois **conclus** (P-06). Confondre
  les deux annonce un SMS qui n'est pas parti.
- **Le mode leurre ne laisse AUCUNE trace** (P-05) : ni notification, ni écran, ni historique.
- Le deadman n'expire que quand **les deux horloges** ont expiré.
- Pas d'alarme exacte : l'icône réveil révélerait le deadman.

## ⚠️ Ce que je te demande en priorité — ce que Gemini n'a PAS pu vérifier

**C1 — Les appelants de `withActivityReset`, exhaustivement.** J'affirme que c'est le point de passage
unique de toutes les remises à zéro, et donc le bon endroit pour archiver. Gemini a trouvé qu'un
chemin s'en passe : le désarmement de fin de séquence, écrit dans la transaction du dernier envoi.
J'ai fermé ça avec `historyWithCurrentCycle`. **Recense TOUS les écrivains de `triggeredAt`,
`messagesSent` et `enabled`** et dis-moi s'il existe encore un état où un cycle ayant réellement
alerté finit par n'être ni lisible ni archivé.

**C2 — `PendingIntent`, `FLAG_UPDATE_CURRENT` et `FLAG_IMMUTABLE`.** Gemini affirmait que
`FLAG_IMMUTABLE` empêche `FLAG_UPDATE_CURRENT` de mettre à jour les extras, ce qui rendrait le tap
« Je vais bien » mort dès la deuxième alerte. **J'ai réfuté** : `FLAG_IMMUTABLE` porte sur le
remplissage à l'**envoi**, pas sur la faculté du créateur de mettre à jour ; sous sa prémisse le tap
serait mort depuis la v1.9.0, alors qu'il fonctionne. **Tranche définitivement**, en citant le
comportement réel de la plateforme. Si j'ai tort, c'est le défaut le plus grave du dépôt : l'unique
moyen d'arrêter une alerte en cours.

**C3 — Le nonce partagé entre deux intents.** `SafetyCallIntentToken.consume` est mono-usage et
`rotate()` invalide le précédent. Je rotationne **une fois** par publication et partage le jeton entre
le tap du corps (`REQUEST_SAFETY_CALL_RESET`) et l'action « Réactiver »
(`REQUEST_SAFETY_CALL_REARM`). Lis `SafetyCallIntentToken` et `MainActivity.handleSharedIntent`, et
dis-moi s'il existe un enchaînement où un bouton atteignable porte un jeton périmé.

**C4 — Le réarmement peut-il être détourné ?** `ACTION_SAFETY_CALL_REARM` réarme et remet le minuteur
à zéro. `MainActivity` est `exported=true` (rôle SMS). Le nonce protège du forgeage, mais : une remise
à zéro répétée pourrait-elle **repousser indéfiniment** un déclenchement légitime ? Et le réarmement
sans contact (`contacts.isEmpty()` ⇒ je n'arme pas) est-il le bon comportement, ou masque-t-il un
échec silencieux ?

**C5 — `save()` et la frontière formulaire / moteur.** J'ai trouvé et corrigé un défaut préexistant :
le brouillon écrasait `safetyCall` **en entier**, effaçant la séquence en cours et **rembobinant
`claimId` et `generation`**. La frontière vit maintenant dans `SafetyCallConfig.withUserEdits`, qui
copie cinq champs. **La liste est-elle juste ?** Un champ manquant rend un réglage inenregistrable ;
un champ en trop rouvre le défaut. Vérifie aussi que `hasUnsavedChanges` est exact après chaque
chemin, `setEnabled(false)` compris.

**C6 — Le mode leurre et l'historique.** Je n'ai mis **aucune** garde `isPanicDecoy` sur la section
d'historique, au motif que l'écran n'est pas atteignable sous contrainte : route dépilée dans
`AppRoot`, section masquée dans `SettingsScreen`. **Va lire ces deux gardes** et dis-moi si elles
suffisent réellement — y compris sur un état de navigation restauré, un raccourci, un lien, ou une
entrée en mode leurre alors que l'écran est **déjà ouvert**.

**C7 — Le codec et la migration.** `security.safetyCall.history` est une clé DataStore neuve. Une
installation existante n'a rien à cet emplacement. Vérifie qu'il n'existe **aucun** quatrième point de
câblage au-delà des trois de `SettingsRepository` (clé, lecture, écriture) — sauvegarde, restauration,
export, migrations de démarrage.

**C8 — Les tests que j'ai écrits sont-ils non vacants ?** 20 nouveaux tests. Cherche ceux qui
passeraient aussi sur du code cassé, et ceux qui manquent. Le dépôt a déjà connu un test vert sur un
chemin qu'aucun appelant n'emprunte, et un test dont le verdict dépendait de ses voisins.

## Format attendu

Pour chaque constat : **ID**, titre, **statut** (`CONFIRMÉ` / `PROBABLE` / `À VÉRIFIER`), **gravité**,
`fichier:ligne`, **l'état ou l'entrelacement exact**, la conséquence utilisateur, la correction.

Puis : `## Réponses à C1–C8`, un par un, même pour les écarter. Puis `## Ce qui est COHÉRENT`.

Pas de constats de style : `detekt`, `ktlint` et `lint` bloquants sont verts, et **509 tests unitaires
passent**. Ne propose jamais de régénérer une baseline.
