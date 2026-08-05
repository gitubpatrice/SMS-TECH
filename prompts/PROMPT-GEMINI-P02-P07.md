# Relecture adversariale — SMS Tech v1.27.2, protocole du Safety call et ses notifications

Tu es relecteur de code Android/Kotlin senior. Tu relis **un lot de correctifs** portant sur le
« Safety Call » d'une application SMS chiffrée : un **homme-mort** qui envoie de vrais SMS à des
contacts d'urgence si l'utilisateur n'ouvre plus l'application pendant une durée configurée
(1 h à 30 jours), puis **trois relances à quinze minutes d'intervalle**.

Un défaut ici se paie en **fausse alerte envoyée aux proches de quelqu'un**, ou — pire — en
**alerte qui ne part jamais**. Les deux échecs ne se valent pas : ne pas partir est le pire.

## ⚠️ Contrainte majeure : tu n'as PAS accès au dépôt

Tout ce que tu peux lire est ci-dessous. Tu ne peux ni ouvrir un fichier, ni chercher un appelant,
ni exécuter les tests.

**C'est la principale source d'erreur attendue de ta part.** Lors d'une relecture précédente, sur
20 findings rendus, **7 seulement étaient réels** — la plupart des 13 autres venaient d'une
supposition sur du code que tu n'avais pas sous les yeux.

Règle absolue :

> **Si ton raisonnement dépend d'un fichier ou d'un appelant que je ne t'ai pas fourni, tu ne rends
> PAS un finding. Tu écris ce qu'il te manque, dans une section séparée
> `## Ce qu'il me manque pour conclure`.**

Un « il me manque X pour trancher » m'est bien plus utile qu'un finding plausible et faux.

## Les quatre motifs de défaut de ce dépôt

Dans l'ordre de fréquence observée, tous audits confondus :

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS** — on masque un écran au lieu de protéger la
   donnée.
2. **Le jumeau asymétrique** — un correctif appliqué à un endroit et pas à son jumeau. 11
   correctifs sur 17 lors d'un audit récent.
3. **Le repli qui échoue du mauvais côté** — en cas d'erreur, le code choisit l'option dangereuse.
4. **Le chemin mort** — du code, un test ou une ressource qu'aucun appelant réel n'atteint.

Et pour ce lot précisément, le cinquième : **le correctif qui casse autre chose.** Ces commits
corrigent les constats d'un audit précédent. Dans l'historique de ce dépôt, relire les correctifs
d'une relecture a systématiquement trouvé de nouveaux défauts **introduits par ces correctifs**.
C'est le cas le plus probable ici.

## ⚠️ Mes propres doutes, à traiter EN PRIORITÉ

Je les liste parce que je les tiens pour les endroits les plus fragiles. Traite-les d'abord,
explicitement, un par un — même pour dire « ce doute n'est pas fondé, et voici pourquoi ».

**D1 — Renouvellement du bail entre deux contacts (`renewClaim`).**
Le bail (`claimedAt`) valait 2 min pour toute la boucle d'envoi ; il mesurait donc l'ancienneté, pas
la progression. Je le renouvelle désormais **entre chaque contact** (4 au maximum), ce qui écrit
dans DataStore à chaque tour. Questions : (a) ce renouvellement peut-il faire qu'un worker
réellement bloqué garde son créneau indéfiniment ? (b) chaque écriture réémet la configuration, donc
recalcule l'alarme et la notification — y a-t-il là un emballement, une boucle, ou une
reprogrammation d'alarme par contact qui coûterait cher ? (c) le terme `cfg.claimedAt == 0L` que
j'ajoute dans `renewClaim` est-il correct, ou peut-il faire perdre son créneau à un propriétaire
légitime ?

**D2 — `messagesDelivered` est un DÉRIVÉ, pas un champ persisté.**
`messagesSent − (bail posé ? 1 : 0)`. Je prétends que c'est exactement le nombre d'envois conclus.
Vérifie-le sur **les quatre** transitions : réservation, conclusion, restitution après échec total,
reprise d'un créneau abandonné. Existe-t-il un état — y compris hérité d'une version antérieure, ou
laissé par un processus tué — où ce dérivé ment ?

**D3 — La notification d'avertissement n'est plus posée par le worker.**
Elle l'était dans la branche `isInWarningWindow()` du tick horaire. Elle est maintenant décidée par
`SafetyCallNotice.decide`, dans un collecteur qui ne se réveille qu'à **chaque émission de la
configuration**. Je m'appuie sur le fait que le jalon monotone du worker écrit à chaque tick, donc
réémet. **Existe-t-il un état où plus rien n'écrit, et où l'avertissement ne s'afficherait donc
jamais ?** Regarde en particulier la garde `cfg.monotonicLastActivityAt == 0L` du jalon.

**D4 — Arrondi de l'alarme vers le futur (`ceilToQuantum`).**
La quantification à la seconde existe pour que `distinctUntilChanged` ne réémette pas au jalon
monotone (l'instant calculé dépend de l'écart entre deux lectures d'horloge). En passant de `floor`
à `ceil`, cet invariant tient-il toujours ? Un instant qui oscille autour d'une frontière de seconde
peut-il désormais alterner entre deux valeurs et reprogrammer l'alarme en boucle ?

**D5 — `pastDueRetryAt` est un état MUTABLE dans un `object` Kotlin.**
Il supprime la reprogrammation d'un rattrapage déjà armé. C'est un garde-fou de batterie, pas une
garantie fonctionnelle. Mais : peut-il **supprimer une programmation nécessaire** ? Notamment quand
l'échéance réelle devrait être plus proche que le rattrapage armé, ou après `retryIn` / `cancel`.
C'est un repli — échoue-t-il du bon côté ?

**D6 — `disableSafetyCall(expectedGeneration, stillJustified)`.**
Le prédicat `stillJustified` est une lambda **évaluée dans le transform de `settings.update`**. Pour
le cas « message vide », elle re-rend le corps du message. Est-ce sûr ? Et surtout : les trois
chemins de récupération renvoient toujours `NoContacts` / `EmptyBody` / `SequenceComplete` à
l'appelant **même quand le désarmement a été refusé**. Le worker peut-il en tirer une conclusion
fausse ?

**D7 — `SafetyCallNotice` et l'ordre retrait → publication.**
Les deux notifications partagent le **même code de requête de `PendingIntent`** et chaque
publication appelle `intentToken.rotate()` (nonce à usage unique). Avec un seul état affiché à la
fois, est-ce correct ? Le tap de la notification affichée réinitialise-t-il toujours le minuteur ?
Que se passe-t-il si la notification est republiée entre l'instant où l'utilisateur la voit et
l'instant où il la tape ?

## Ce que je te fournis

1. Le diff complet du commit `34ea1aa`.
2. Les fichiers **entiers** les plus concernés, après correctif.

Le reste du dépôt ne t'est pas accessible — applique la règle absolue ci-dessus.

## Format de rendu attendu

Pour **chaque** finding :

- **ID** (F-01, F-02…), **titre** en une ligne
- **Statut** : `CONFIRMÉ` (tu as sous les yeux tout ce qu'il faut) / `PROBABLE` / `À VÉRIFIER`
- **Gravité** : CRITIQUE / ÉLEVÉE / MOYENNE / FAIBLE
- **Emplacement** : fichier + numéro de ligne
- **L'entrelacement ou l'état exact** qui produit le défaut — pas une généralité
- **Conséquence pour l'utilisateur**, concrètement
- **Correction nécessaire**

Puis, obligatoirement :

- `## Réponses à mes sept doutes` — un paragraphe par doute, D1 à D7, même pour les écarter.
- `## Ce qu'il me manque pour conclure`
- `## Ce que j'ai vérifié et qui est CORRECT` — dis-moi aussi ce qui tient. Un audit qui ne trouve
  que des problèmes ne me dit pas où j'en suis.

Ne rends pas de findings de style, de nommage ou de mise en forme : un `detekt`, un `ktlint` et un
`lint` bloquants tournent déjà et sont verts.
