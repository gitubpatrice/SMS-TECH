# Prompt d'audit externe — SMS Tech

> À coller tel quel dans un autre chat (Gemini, Codex, ChatGPT, Claude…).
> Adapter uniquement le bloc « Portée » si tu veux cibler une partie du code.

---

Tu audites **SMS Tech**, une application Android de messagerie SMS/MMS, écrite en **Kotlin natif** (pas Flutter). Elle remplace l'application SMS par défaut du téléphone : elle a donc accès à la totalité des messages de l'utilisateur, et une défaillance y coûte des données réelles et irremplaçables.

## Ce que tu dois produire

Un rapport de findings. Rien d'autre. Pas de réécriture de code, pas de refactor proposé « tant qu'on y est ».

Pour **chaque** finding, exactement ces champs :

1. **Titre** — une phrase, le défaut, pas la zone.
2. **Statut** — `CONFIRMÉ` ou `PROBABLE`. Voir la règle ci-dessous, elle est stricte.
3. **Emplacement** — `chemin/fichier.kt:ligne`. Une ligne précise, pas « quelque part dans la classe ».
4. **Le code fautif** — cité, 3 à 15 lignes, tel qu'il est dans le fichier.
5. **Scénario d'atteinte** — la suite d'actions concrètes qui produit le défaut. Si tu ne sais pas l'écrire, c'est que le finding n'en est pas un.
6. **Conséquence** — ce que l'utilisateur perd ou ce que l'attaquant gagne. Sois précis : « perte de données » ne veut rien dire, « les messages de la conversation X sont supprimés sans confirmation » veut dire quelque chose.
7. **Gravité** — CRITIQUE / ÉLEVÉE / MOYENNE / FAIBLE, justifiée par la conséquence, pas par l'élégance du code.
8. **Correctif suggéré** — la plus petite modification qui ferme le défaut.

Classe le rapport par gravité décroissante.

## Règle de statut — non négociable

- `CONFIRMÉ` = tu as lu le code des **deux** côtés : celui qui contient le défaut **et** celui qui l'appelle. Tu as vérifié que le chemin est réellement atteignable.
- `PROBABLE` = tout le reste.

Un `CONFIRMÉ` non vérifié est plus nuisible qu'un finding manqué : il consomme du temps de correction et fait perdre confiance dans le reste du rapport.

**Précédent réel** : un audit externe de cette application a rendu 7 findings faux, dont 3 décrivaient du code Flutter — cette application est en Kotlin. Deux autres recommandaient de « révoquer la clé de signature », ce qui aurait **cassé définitivement la chaîne de mise à jour** de toutes les installations existantes. Ne recommande jamais de toucher au keystore.

## Les quatre motifs qui ont produit TOUS les vrais défauts

Ne balaie pas le code linéairement. Cherche **par motif**. Ces quatre familles ont produit l'intégralité des bugs réels de ce portefeuille, et l'asymétrie en explique trois sur quatre.

### 1. La garde est posée sur l'AFFICHAGE, pas sur l'ACCÈS

Le motif dominant. Un écran est masqué, une entrée de menu est cachée, un bouton est grisé — mais la fonction sous-jacente reste appelable. Cherche les protections implémentées dans la couche UI dont la couche métier ne rejoue pas le contrôle.

Question à te poser sur chaque garde : *si j'appelle cette fonction directement, sans passer par l'écran, qu'est-ce qui m'arrête ?*

### 2. Le repli échoue du mauvais côté

Quand une source d'information est indisponible, que fait le code ? S'il continue comme si tout allait bien, c'est un défaut — même si le cas « ne peut pas arriver ».

Ce qui compte n'est pas la probabilité du repli, c'est **le sens dans lequel il échoue**. Un `catch` qui avale, un `?: return true`, un `if (x == null) { /* on ignore */ }` : regarde chacun et demande-toi si l'ignorance mène à « fermé » ou à « ouvert ».

### 3. Le correctif est asymétrique entre jumeaux

Une protection existe à un endroit et manque à son jumeau exact. Cherche systématiquement : quand tu trouves une garde, **cherche les autres chemins qui devraient la porter**.

Exemples vécus dans ce portefeuille : une garde posée sur le chemin par mot de passe et absente du chemin biométrique ; une vérification présente à la création et absente à la modification ; un compteur relevé dans une fonction longue et pas dans les quatre autres fonctions longues du même fichier.

Un commentaire qui énonce une règle générale (« toute opération qui … doit … ») est un signal fort : **vérifie que le fichier applique réellement cette règle partout**, pas seulement à l'endroit où le commentaire est écrit.

### 4. Test vert sur un chemin mort, ou ressource orpheline

Une fonctionnalité annoncée mais **inatteignable** : chaîne de caractères définie et jamais affichée, écran de configuration jamais routé, option de réglage sans écran pour la poser. Un test peut passer sur un chemin qu'aucun appelant n'emprunte.

Vécu ici : un « mode panique » annoncé dans le changelog et **totalement inatteignable**, faute d'écran de configuration. Un blocage anti-force-brute qui **ne se levait jamais**.

Pour chaque fonctionnalité que tu vois déclarée, remonte la chaîne d'appel jusqu'à un point d'entrée utilisateur réel. Si tu n'y arrives pas, c'est un finding.

## Axes techniques à couvrir

**Sécurité** — base chiffrée SQLCipher (vérifie d'où vient la clé et ce qui se passe si sa dérivation échoue), verrouillage biométrique, coffre de messages, mode urgence, liste noire, presse-papier, protection anti-superposition d'écran, exports.

**Intégrité des données** — c'est l'axe le plus important. Cette application est le gestionnaire SMS par défaut : toute suppression, import, synchronisation ou migration touche des messages réels. Cherche en priorité les chemins qui **effacent ou écrasent** sans que l'utilisateur l'ait demandé. Un défaut de cette famille a déjà coûté deux conversations réelles.

**Concurrence et cycle de vie** — coroutines, `CancellationException` avalée par un `runCatching`, `StateFlow` avec repli, gardes de navigation Compose, opérations longues traversées par une mise en arrière-plan.

**Cohérence** — mêmes motifs appliqués partout, ou déviations locales ? Helpers contournés ? Conventions cassées ?

## Portée

Racine du dépôt. Quatre modules Gradle : `:app` (UI Compose, ~27 000 lignes de Kotlin), `:domain`, `:data`, `:core`. Version **1.27.1**, branche `main`.

Les fichiers les plus volumineux, donc les plus susceptibles de contenir des chemins oubliés :

```
app/src/main/java/com/filestech/sms/ui/screens/settings/SettingsScreen.kt        3201 lignes
app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadScreen.kt            2142
app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadViewModel.kt         1555
app/src/main/java/com/filestech/sms/ui/screens/conversations/ConversationsScreen.kt 1428
app/src/main/java/com/filestech/sms/ui/screens/vault/VaultScreen.kt               756
app/src/main/java/com/filestech/sms/MainActivity.kt                               631
```

Le `versionCode` est **dynamique** (nombre de commits Git) — ne le signale pas comme une anomalie.

## Ce qu'il ne faut PAS faire

- Ne signale pas de préférences de style, de nommage ou d'organisation.
- Ne propose pas de migration de bibliothèque ni de refonte d'architecture.
- N'invente pas de chemin de fichier. Si tu ne peux pas citer `fichier:ligne`, ne rends pas le finding.
- Ne recommande jamais de changer la clé de signature.
- Ne suppose pas qu'un commentaire dit la vérité sur le code qu'il accompagne : **plusieurs commentaires de ce dépôt décrivaient une règle que leur propre fichier n'appliquait pas**. Le code fait autorité ; un commentaire faux est lui-même un finding.

## Format de sortie

Markdown. Un titre de niveau 2 par finding. Un tableau récapitulatif en tête : numéro, titre court, gravité, statut, fichier.

Termine par une section **« Ce que je n'ai pas pu vérifier »** — les zones que tu n'as pas lues, les hypothèses que tu n'as pas pu confirmer, les chemins dont tu n'as pas trouvé l'appelant. Cette section vaut autant que les findings : elle dit où le rapport ne protège pas.
