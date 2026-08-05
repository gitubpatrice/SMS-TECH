# Relecture adversariale — SMS Tech v1.27.2, le Safety call **pris comme un tout**

Tu es relecteur de code Android/Kotlin senior. Tu relis un **homme-mort** : une fonction qui envoie
de vrais SMS aux contacts d'urgence de quelqu'un s'il n'ouvre plus son téléphone pendant une durée
configurée (1 h à 30 jours), puis **trois relances à quinze minutes d'intervalle**.

Un défaut ici se paie en **fausse alerte envoyée à des proches**, ou — pire — en **alerte qui ne
part jamais**. Les deux échecs ne se valent pas : ne pas partir est le pire, parce qu'il laisse
croire à une protection qui n'existe pas.

## ⚠️ Pourquoi cette relecture, et ce qu'elle a de particulier

Ce code a été **retouché une dizaine de fois aujourd'hui**, par correctifs successifs venus de
quatre audits différents : protocole de bail, identité du propriétaire, génération de cycle,
machine à trois états des notifications, réveil d'avertissement, fenêtre d'avertissement.

Chaque correctif était juste isolément. **Personne ne l'a jamais relu d'un bloc depuis.** C'est
précisément la situation où les incohérences s'installent : deux correctifs qui se marchent dessus,
une garde devenue redondante qui en masque une autre devenue fausse, un état devenu inatteignable.

**Cherche la cohérence de l'ensemble, pas la justesse de chaque ligne.**

## ⚠️ Contrainte majeure : tu n'as PAS accès au dépôt

Tout ce que tu peux lire est ci-dessous. Tu ne peux ni ouvrir un fichier, ni chercher un appelant,
ni exécuter les tests.

**C'est la principale source d'erreur attendue de ta part.** Lors d'une relecture précédente, sur
20 findings rendus, **7 seulement étaient réels**. Règle absolue :

> **Si ton raisonnement dépend d'un fichier que je ne t'ai pas fourni, tu ne rends PAS un finding.
> Tu écris ce qu'il te manque, dans `## Ce qu'il me manque pour conclure`.**

Sur ta relecture précédente, tu as rendu **un seul finding, et il était réel et critique** —
l'alerte partait sans que l'avertissement ait jamais pu s'afficher. Un finding juste vaut mieux que
dix plausibles.

⚠️ **Tu t'étais trompé sur l'attribution** : tu imputais ce défaut au correctif d'arrondi d'alarme,
alors qu'il était antérieur. Le fond était juste, la cause non — et corriger la cause que tu
désignais n'aurait rien réglé. **Sois aussi rigoureux sur l'origine que sur le symptôme.**

## Les quatre motifs de défaut de ce dépôt

1. **La garde est sur l'AFFICHAGE, pas sur l'ACCÈS.**
2. **Le jumeau asymétrique** — un correctif appliqué à un endroit et pas à son jumeau. C'est le
   motif dominant : encore ce soir, un audit m'avait *donné l'emplacement* du jumeau et je ne l'ai
   corrigé qu'à moitié.
3. **Le repli qui échoue du mauvais côté.**
4. **Le chemin mort** — code, test ou état qu'aucun appelant réel n'atteint.

## ⚠️ Mes propres doutes, à traiter EN PRIORITÉ

**D1 — Trois sources pour un seul réveil.** `nextWakeUpAt` arbitre entre l'ouverture de la fenêtre
d'avertissement, l'expiration du bail et la prochaine relance. Existe-t-il un état où deux d'entre
elles alternent d'une émission à l'autre, et donc reprogramment l'alarme en boucle ?
`distinctUntilChanged` ne protège que d'une valeur **identique**.

**D2 — Le bail est renouvelé entre chaque contact**, donc DataStore est réécrit jusqu'à quatre fois
pendant un envoi. Chaque écriture réémet la configuration, ce qui recalcule l'alarme **et** la
notification. Y a-t-il là un scintillement visible, une reprogrammation d'alarme par contact, ou
pire une réentrance ?

**D3 — La fenêtre d'avertissement vient d'être redéfinie** : « les deux compteurs ont franchi leur
seuil **et** le deadman n'a pas encore expiré ». Est-elle cohérente avec le réveil qui la vise
(`deadline − window`, `deadline` étant le plus tardif des deux compteurs) ? Existe-t-il encore un
état où l'on réveille à un instant où le prédicat est faux ?

**D4 — Sur un délai d'une heure**, la fenêtre vaut 15 min et `hoursLeft` vaut donc toujours `0`.
La notification affiche le texte « imminent » et **ne change plus jamais**. Le réconciliateur, qui
déduplique sur cet entier, ne republiera donc rien. Est-ce acceptable, ou l'utilisateur perd-il
l'information du temps restant précisément sur le délai le plus court ?

**D5 — `claimId` strictement croissant et `generation` incrémentée à chaque reset.** Quelqu'un qui
ouvre son application dix fois par jour pendant des années fait-il déborder quoi que ce soit ? Et
un état hérité où `claimId` serait supérieur à ce qu'on croit pose-t-il problème ?

**D6 — Le worker n'affiche plus rien.** Tout l'affichage passe par un collecteur de
`MainApplication` qui ne se réveille qu'à une **émission de configuration**. J'affirme que le jalon
monotone du worker en produit une à chaque tick. Est-ce vrai dans **tous** les états — y compris
quand `monotonicLastActivityAt == 0L`, où le jalon se retire ?

## Format attendu

Pour chaque finding : **ID**, titre, **statut** (`CONFIRMÉ` / `PROBABLE` / `À VÉRIFIER`),
**gravité**, emplacement fichier:ligne, **l'état ou l'entrelacement exact**, la conséquence
utilisateur, la correction nécessaire.

Puis obligatoirement :

- `## Réponses à mes six doutes` — D1 à D6, un par un, même pour les écarter
- `## Ce qu'il me manque pour conclure`
- `## Ce que j'ai vérifié et qui est COHÉRENT` — dis-moi aussi ce qui tient ensemble

Pas de findings de style : `detekt`, `ktlint` et `lint` bloquants tournent déjà et sont verts.
