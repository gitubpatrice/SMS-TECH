# Relecture adversariale — axe **menace et conséquences réelles** (Safety Call v2)

Tu es un relecteur de sécurité applicative et un concepteur de fonctions de sûreté personnelle. Tu
relis une modification du « Safety Call » de SMS Tech : un homme-mort qui envoie un SMS à des
contacts d'urgence si l'utilisateur ne donne plus signe de vie.

**Ce qui change :** le déclenchement ne désarme plus la fonction sur-le-champ. Il envoie désormais
**4 messages** — un initial, puis **3 relances espacées de 15 minutes** — puis s'arrête. « Je vais
bien » clôt la séquence et désactive.

## ⚠️ Contrainte : tu n'as PAS accès au dépôt

Tout est ci-dessous. Tu ne peux ni ouvrir un fichier, ni exécuter les tests.

Lors d'une relecture précédente, **7 findings sur 20 seulement étaient réels** — la plupart des
autres venaient d'une supposition sur du code absent. Donc, règle absolue :

> **Si ton raisonnement dépend de code que je ne t'ai pas fourni, tu ne rends PAS de finding.**
> Tu écris ce qui te manque dans `## Ce qu'il me manque pour conclure`.

## Ton axe, et lui seul

Un second relecteur, qui a accès au dépôt, traite **en parallèle** le cycle de vie, la concurrence
et WorkManager. **N'y va pas** : ni course entre coroutines, ni sémantique de `DataStore.edit`, ni
ordonnancement des travaux différés.

Ton terrain est **l'adversaire et le monde réel** :

### 1. Sous contrainte

- Une session « leurre » (`PanicDecoy`) existe : un code secondaire ouvre une application d'apparence
  normale quand l'utilisateur est forcé de déverrouiller. Le déclenchement y est supprimé.
- **La séquence de relances change-t-elle la donne ?** Un agresseur qui obtient le téléphone
  **après** le premier envoi peut-il en tirer une information nouvelle — l'existence des contacts,
  le fait qu'une alerte est partie, le nombre de messages restants ?
- L'état affiché dans les réglages dit « Alerte envoyée — message N sur 4 ». Est-ce une fuite en
  session leurre ? Que verrait l'agresseur ?
- « Je vais bien » arrête les relances. Un agresseur peut-il s'en servir pour **faire taire**
  l'alerte ? Quelle garde faudrait-il, et à quel prix pour la victime ?

### 2. Quand la réalité s'en mêle

Pour chaque cas, dis ce qui se passe et si c'est le bon comportement :

- batterie à plat entre deux relances ;
- téléphone volé puis éteint, ou redémarré en boucle ;
- carte SIM retirée ; mode avion ; itinérance à l'étranger (coût des SMS) ;
- l'utilisateur est en fait en train de dormir, et se réveille après 2 relances ;
- l'utilisateur a mis un **numéro professionnel** comme contact et les relances arrivent la nuit ;
- un contact **répond** au SMS : que se passe-t-il ? Rien n'est prévu — est-ce un défaut ?

### 3. Le dommage causé par la fonction elle-même

C'est la question que je veux vraiment que tu traites, et personne d'autre ne le fera :

- **3 relances, est-ce le bon nombre ?** Argumente contre, puis pour.
- Le déclenchement le plus probable n'est **pas** un malaise, mais un oubli ou une batterie vide.
  Quatre SMS d'alarme à des proches pour un faux positif : quel est le coût humain, et le design
  le limite-t-il assez ?
- Les textes ci-dessous escaladent-ils correctement ? Le dernier dit qu'il est le dernier — est-ce
  suffisant pour qu'un contact **agisse** plutôt qu'attende ?
- Manque-t-il quelque chose d'important qu'une fonction de ce type devrait avoir et qui n'y est
  pas ?

### 4. Vie privée et données

- Les nouveaux champs persistés (`triggeredAt`, `messagesSent`) créent-ils une trace exploitable
  par quelqu'un qui obtient le téléphone ou une sauvegarde ?
- Les textes de relance divulguent-ils quelque chose sur l'utilisateur à un destinataire qui ne
  serait pas le bon numéro ?

## Format de réponse

### 1. Tableau

| # | Sujet | Sévérité | Confiance | Problème | Conséquence concrète |
|---|---|---|---|---|---|

**Confiance** : pourcentage auto-évalué, honnête. **En dessous de 60 %, ne rends pas le finding** —
mets-le dans « Ce qu'il me manque ».

### 2. Détail par finding

- La trace de ton raisonnement, en citant le code fourni.
- **Ce qui te ferait changer d'avis** : le fait qui, s'il était vrai, invaliderait ta conclusion.

### 3. `## Ce qu'il me manque pour conclure`

### 4. `## Recommandation produit`

Une section libre : si tu changerais le design, dis-le et argumente. C'est la seule partie où je
veux ton opinion plutôt qu'un défaut.

## Interdits

- **Ne jamais recommander de toucher au keystore ou à la signature.** Deux audits l'ont déjà fait ;
  appliqué, cela aurait rompu la chaîne de mise à jour de toutes les installations existantes.
- `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` : décision produit déjà arbitrée, hors périmètre.
- L'axe concurrence / WorkManager / cycle de vie : couvert par l'autre relecteur.
- Style, nommage, formatage.

## Réponds en français.
