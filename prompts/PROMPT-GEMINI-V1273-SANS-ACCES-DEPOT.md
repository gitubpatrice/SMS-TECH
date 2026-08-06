> ⚠️ **CE FICHIER EST INCOMPLET À DESSEIN — DESTINÉ À GEMINI, VIA L'API, PAS À CODEX.**
>
> Il annonce « le diff ci-dessous » et ce diff **n'est PAS dans le fichier** : il est concaténé à
> l'envoi (`git diff` + les fichiers neufs), parce que Gemini n'a pas accès au dépôt.
>
> Le coller tel quel dans un relecteur produit une revue vide. **C'est arrivé le 2026-08-06** :
> ce fichier a été collé dans Codex, qui a correctement refusé de spéculer et n'a rendu aucun
> constat — une passe d'audit perdue.
>
> **Pour Codex, utiliser `PROMPT-CODEX-V1273.md`**, qui suppose l'accès au dépôt.

# Relecture adversariale — SMS Tech v1.27.3 : état terminal du Safety call + historique des déclenchements

Tu es relecteur de code Android/Kotlin senior. Tu relis un **lot de correctifs écrit aujourd'hui**
sur un **homme-mort** : une fonction qui envoie de vrais SMS aux contacts d'urgence de quelqu'un
s'il n'ouvre plus son téléphone pendant une durée configurée, puis **trois relances à quinze minutes
d'intervalle**.

Un défaut ici se paie en **fausse alerte envoyée à des proches**, ou — pire — en **alerte qui ne
part jamais**. Les deux échecs ne se valent pas : ne pas partir est le pire, parce qu'il laisse
croire à une protection qui n'existe pas.

## Ce que ce lot corrige, et le contexte mesuré sur appareil

La séquence complète a tourné pour la première fois sur un vrai téléphone la nuit du 2026-08-06 :
déclenchement à 23:53:56, relances à 00:13:44, 00:25:17, 00:49:11. Les quatre messages sont partis
et ont été reçus. Puis **trois défauts se sont révélés le lendemain matin** :

1. La notification affichait **« 11h01 »**, l'heure de sa dernière re-publication, au lieu de
   23:53:56. Cause : le code n'appelait jamais `setWhen()`, et le constructeur met l'heure courante
   par défaut. La réconciliation se rejouant à chaque démarrage à froid, la notification était
   réhorodatée, remontait en tête du volet et **se présentait comme une alerte neuve**. L'utilisateur
   a cru à un second déclenchement.
2. Elle affichait **« 4 message sur 4 envoyé »** — pluriel français cassé.
3. Surtout : elle disait « Appuyez si vous allez bien : **les relances s'arrêteront** », alors que la
   séquence était close **et que le Safety call s'était désactivé tout seul** (le désarmement de fin
   de séquence est écrit dans la même transaction que la conclusion du dernier envoi). Rien ne
   disait que la protection était tombée, et aucune alarme n'était même programmée.

Le lot ajoute par ailleurs un **historique durable des déclenchements**, parce que la notification ne
peut pas en tenir lieu : elle se balaie, ne survit pas au redémarrage, et disparaît dès qu'on la tape.

## ⚠️ Contrainte majeure : tu n'as PAS accès au dépôt

Tout ce que tu peux lire est le diff ci-dessous, plus les deux fichiers neufs en entier. Tu ne peux
ni ouvrir un autre fichier, ni chercher un appelant, ni exécuter les tests.

**C'est la principale source d'erreur attendue de ta part.** Règle absolue :

> **Si ton raisonnement dépend d'un fichier que je ne t'ai pas fourni, tu ne rends PAS un finding.
> Tu écris ce qu'il te manque, dans `## Ce qu'il me manque pour conclure`.**

Un finding juste vaut mieux que dix plausibles. Lors d'une relecture précédente sur 20 findings
rendus, 7 seulement étaient réels ; lors d'une autre, un seul finding a été rendu et il était réel
et critique. La seconde était la bonne.

⚠️ **Sois aussi rigoureux sur l'ORIGINE que sur le symptôme.** Une relecture précédente avait imputé
un vrai défaut au mauvais correctif : corriger la cause désignée n'aurait rien réglé.

## Les quatre motifs de défaut de ce dépôt

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS.**
2. **Le jumeau asymétrique** — un correctif appliqué à un endroit et pas à son jumeau. C'est le motif
   dominant : 11 des 17 correctifs d'une journée récente en relevaient, et ce lot en corrige encore
   deux (les Réglages lisaient `messagesSent` là où la notification lisait `messagesDelivered`).
3. **Le repli qui échoue du mauvais côté.**
4. **Le chemin mort** — code, test ou état qu'aucun appelant réel n'atteint.

## Invariants du dépôt à ne PAS casser (vérifie qu'ils tiennent)

- **`claimId` est strictement croissant sur toute la vie de l'installation** et n'est JAMAIS remis à
  zéro (constat P-01). Deux workers portant la même identité, c'est un double envoi.
- **`generation` est incrémentée à chaque remise à zéro du cycle** et invalide les workers de
  l'ancien cycle (constat C-04).
- **`messagesSent` compte les créneaux RÉSERVÉS ; `messagesDelivered` = `messagesSent − (bail ? 1 : 0)`
  compte les envois CONCLUS.** Afficher le premier là où il faut le second annonce un SMS qui n'est
  pas parti (constat P-06).
- **Le mode leurre ne doit laisser AUCUNE trace** : ni notification, ni écran, ni historique
  (constat P-05). Un mode leurre qui laisse une trace n'en est pas un.
- Le deadman n'expire que quand **les deux horloges** (murale et monotone) ont expiré.
- Pas d'alarme exacte (`SCHEDULE_EXACT_ALARM`, `setAlarmClock`) : l'icône réveil révélerait le
  deadman à un agresseur. La dérive de quelques minutes est le prix assumé.

## ⚠️ Mes propres doutes, à traiter EN PRIORITÉ

**D1 — L'archivage vit dans `withActivityReset`.** J'affirme que c'est le point de passage unique de
toutes les remises à zéro (« Je vais bien », tap notification, ouverture de l'app, réarmement,
réactivation du switch). J'affirme aussi que les deux autres écritures de `triggeredAt = 0L` sont des
restitutions de créneau où `messagesDelivered` vaut zéro, donc rien à archiver. **Est-ce qu'un chemin
peut refermer un cycle sans passer par là, et perdre l'archivage ?**

**D2 — `terminal = messagesDelivered >= TOTAL_MESSAGES`.** J'affirme que c'est *exactement* l'état
terminal et pas une approximation, parce que l'atteindre impose les quatre créneaux consommés **et**
aucun envoi en vol. Existe-t-il un état où ce prédicat est vrai alors qu'un envoi court encore — ce
qui ferait annoncer « Safety call désactivé » **pendant** l'envoi de la dernière alerte ?

**D3 — Un seul nonce pour plusieurs intents.** `SafetyCallIntentToken.consume` est mono-usage et
`rotate()` invalide le précédent. Je rotationne **une seule fois** et partage le jeton entre le tap du
corps et l'action « Réactiver », avec deux codes de requête distincts. Y a-t-il un enchaînement où
l'un des deux boutons se retrouve porteur d'un jeton périmé **et reste atteignable** ?

**D4 — L'action « Réactiver » passe par `startActivity`, jamais par un `BroadcastReceiver`.** Mon
raisonnement : une action de notification est atteignable depuis le volet, donc un récepteur ferait
d'un bouton « Désactiver » un coupe-circuit du deadman sans le code de l'application ; le réarmement,
lui, ne peut que remettre la protection en marche, donc il est sans risque dans ce sens.
**Ce raisonnement est-il complet ?** Y a-t-il un abus possible du réarmement (rejeu d'intent, app
tierce, remise à zéro répétée du minuteur pour empêcher indéfiniment le déclenchement) ?

**D5 — `save()` ne fusionne plus que cinq champs.** Le formulaire écrasait `safetyCall` en entier
depuis un brouillon figé à l'ouverture de l'écran, ce qui effaçait la séquence en cours et
**rembobinait `claimId` et `generation`**. Je fusionne désormais `enabled`, `timeoutMs`, `contacts`,
`template`, `customMessage` dans la configuration LIVE relue sous le verrou DataStore.
**Est-ce que la liste est juste ?** Un champ manquant côté formulaire rendrait un réglage
inenregistrable ; un champ en trop rouvrirait le défaut.

**D6 — `settings.update {}` et la variable capturée.** Je capture le résultat de la fusion dans un
`var persisted` déclaré hors du lambda, pour le relire après. Si DataStore réexécute le lambda sur
conflit, est-ce que la dernière exécution est bien celle qui est persistée, et donc ma variable
correcte ? Le dépôt utilise déjà ce motif ailleurs (`var disabled`, `var concluded`).

**D7 — `hasUnsavedChanges`.** Il comparait `_draft != snapshotInitial`. Depuis que `snapshotInitial`
porte l'état d'exécution réel alors que le brouillon garde celui d'à l'ouverture, je compare
désormais `snapshotInitial.withUserEdits(_draft) != snapshotInitial`. **Ce prédicat est-il exact dans
tous les cas**, notamment après le chemin `setEnabled(false)` qui écrit immédiatement en base ?

**D8 — L'historique et le mode leurre.** Je n'ai mis **aucune** garde `isPanicDecoy` sur la section
d'historique, au motif que l'écran n'est pas atteignable sous contrainte : la route est dépilée à
l'entrée en mode leurre et la section qui y mène est masquée dans les Réglages. **Est-ce suffisant ?**
Une garde sur l'accès plutôt que sur l'affichage est la doctrine du dépôt, mais ici elle est *ailleurs*
que dans le code que tu lis.

**D9 — Le codec.** Format `triggeredAt|delivered|total|dest1;dest2`, une entrée par ligne, tolérant
(entrée illisible ignorée, jamais d'exception), séparateurs strippés des libellés. Peut-on encore
provoquer un décalage de champs, une perte silencieuse d'entrées valides, ou une croissance non bornée
du fichier DataStore ?

## Format attendu

Pour chaque finding : **ID**, titre, **statut** (`CONFIRMÉ` / `PROBABLE` / `À VÉRIFIER`),
**gravité**, emplacement fichier:ligne, **l'état ou l'entrelacement exact**, la conséquence
utilisateur, la correction nécessaire.

Puis obligatoirement :

- `## Réponses à mes neuf doutes` — D1 à D9, un par un, même pour les écarter
- `## Ce qu'il me manque pour conclure`
- `## Ce que j'ai vérifié et qui est COHÉRENT`

Pas de findings de style : `detekt`, `ktlint` et `lint` bloquants tournent déjà et sont verts, et les
506 tests unitaires passent.
