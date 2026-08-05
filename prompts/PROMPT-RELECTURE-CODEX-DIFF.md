# Prompt de relecture Codex — les correctifs d'audit du 2026-08-04

> À coller tel quel dans Codex, ouvert sur `j:\applications\sms_tech`.
> Périmètre : `git diff 43feb4b..HEAD` sur la branche `fix/audit-codex-2026-08-04`.

---

Tu relis un **lot de correctifs**, pas une application. Ne produis pas un audit général : cherche le défaut **dans ce qui vient d'être écrit**.

## Le périmètre, et rien d'autre

```bash
git diff 43feb4b..HEAD          # 36 fichiers, +1035 / −134, 19 commits
git log --oneline 43feb4b..HEAD
```

`43feb4b` est la v1.27.1 publiée et testée. Tout ce qui suit a été écrit aujourd'hui en réponse à trois audits (Codex, Gemini Pro, agent de cohérence), et **aucun œil externe ne l'a lu**. Les messages de commit expliquent chaque intention — lis-les, puis vérifie que le code tient la promesse.

## Ce qu'est cette application

L'**application SMS par défaut** du téléphone de son auteur. Elle détient la totalité de ses messages, et le téléphone sur lequel elle tourne est son téléphone réel. Un défaut d'intégrité y coûte des données irremplaçables — c'est déjà arrivé, deux conversations perdues par un chemin d'import.

Trois des correctifs de ce lot touchent des chemins qui **envoient de vrais SMS** ou **effacent des lignes** :
- `SafetyCallConfig` / `SafetyCallWorker` / `MainApplication` — le deadman, qui envoie des SMS d'urgence à des contacts réels ;
- `TriggerEmergencyUseCase` — le SMS d'urgence avec géolocalisation ;
- `TelephonySyncManager.reconcileDeletions` — la passe qui EFFACE des messages ;
- `SmsDeliverReceiver` — la réception, avec un filet qui écrit dans la boîte système.

Priorité de relecture, dans cet ordre : **un correctif qui fait partir un SMS à tort ou qui perd un message** > sécurité > cycle de vie / concurrence > cohérence.

## Les quatre questions à poser à chaque correctif

1. **La garde est-elle posée partout où elle devrait ?** Le motif dominant de ce dépôt est le jumeau asymétrique : une protection présente d'un côté, absente de son jumeau exact. **11 des 18 correctifs de ce lot sont précisément des jumeaux rattrapés** — et deux d'entre eux corrigeaient un correctif écrit une heure plus tôt dans la même session. Pour chaque garde ajoutée ici, cherche les autres chemins qui devraient la porter.

2. **L'instantané est-il restauré en entier ?** Le deadman introduit un champ persisté `monotonicAccumulatedMs` qui doit être remis à zéro **en même temps** que `lastActivityAt` et `monotonicLastActivityAt`, aux quatre sites de reset, et câblé aux trois points DataStore. Vérifie qu'aucun écrivain n'en oublie un — un capital rejoué ferait partir une alerte **en avance**, vers de vrais contacts.

3. **Le repli échoue-t-il du bon côté ?** Plusieurs correctifs changent délibérément un sens d'échec : la liste noire des receveurs échoue désormais **ouvert** (le message survit), le coffre échoue désormais **fermé** (il refuse au lieu de s'ouvrir). Vérifie que chaque sens choisi est le bon **pour cette fonction-là**, et qu'aucun `runCatching` n'avale une `CancellationException` autour d'un appel `suspend`.

4. **Les commentaires disent-ils la vérité ?** Ce lot en corrige plusieurs qui mentaient — dont un qui affirmait exclure « les short codes premium (32665) » avec un seuil de 4 chiffres. Les commentaires ajoutés aujourd'hui sont longs et affirmatifs : **vérifie que chacun décrit ce que son code fait réellement**. Un commentaire faux est un défaut à part entière.

## Points que je signale moi-même comme incertains

Regarde-les en priorité, je ne suis pas sûr d'eux :

- **`SafetyCallWorker`** — le jalon écrit dans `settings.update` puis la décision est prise sur l'instantané d'**avant** jalon, au motif que `settings.state` se ré-hydrate de façon asynchrone. Est-ce que le raisonnement tient ? Une écriture toutes les heures est-elle acceptable ?
- **`PduParser.parseParts`** — `dataLength` est comparé au restant **total**, pas au restant après lecture des en-têtes. Je l'ai jugé bénin (l'allocation reste bornée, la troncature est détectée juste après). Confirme ou infirme.
- **`ThreadViewModel.retry`** — le garde `if (_state.value.isSending) return` est un test-puis-agis. Je l'ai jugé sûr parce que tout se passe sur le thread principal. Vérifie.
- **`VaultScreen`** — le nouveau dialogue « biométrie indisponible » laisse `unlocked` à `null` pour que `revealContent` reste faux. Le contenu du coffre est-il réellement invisible pendant son affichage, dans tous les cas ?
- **`SmsDeliverReceiver`** — le filet de dernier recours peut-il produire un **doublon** si `insertInboxSms` a réussi mais qu'une exception survient juste après ?

## Format attendu

Pour chaque finding : **titre**, **statut** (`CONFIRMÉ` = tu as lu les deux côtés et vérifié que le chemin est atteignable, `PROBABLE` = tout le reste), **`fichier:ligne`**, le **code fautif cité littéralement**, le **scénario concret** qui produit le défaut, la **conséquence pour l'utilisateur**, et la **plus petite modification** qui la ferme.

Classe par gravité de la conséquence. Si tu ne peux pas citer `fichier:ligne`, ne rends pas le finding.

## Ce qu'il ne faut pas faire

- Ne relis pas le code **hors** du diff, sauf pour vérifier un appelant ou un jumeau — c'est justement ce pour quoi il faut en sortir.
- Ne propose ni migration de bibliothèque, ni refonte, ni préférence de style ou de nommage.
- **Ne recommande jamais de toucher à la clé de signature.** Deux rapports l'ont fait ; appliqué, cela aurait cassé définitivement la chaîne de mise à jour de toutes les installations.
- Ne signale pas ces faux positifs déjà arbitrés : `blockObscuredTouches = false` sur l'écran de verrouillage (choix délibéré et documenté), les PIN en `String` (Compose l'impose), R8 qui strippe déjà tous les appels Timber en release, `MessageDao.search` qui filtre déjà le coffre par `in_vault = 0`.

Termine par **« Ce que je n'ai pas pu vérifier »** — les hypothèses non confirmées et les chemins dont tu n'as pas trouvé l'appelant.
