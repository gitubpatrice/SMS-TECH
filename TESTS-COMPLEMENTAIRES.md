# Tests complémentaires — chemin destructeur du coffre

> Établi le 2026-09-08, après la 3ᵉ passe de revue externe sur la MR F-Droid `!38458`.
> Ce fichier liste **ce qui n'est pas couvert**, pas ce qui l'est. Ce qui est couvert vit dans les
> tests eux-mêmes.

## Ce qui est déjà mesuré, et sur quoi

| Suite | Où | Ce qu'elle prouve |
|---|---|---|
| `SystemRowMatchPolicyTest` | JVM, 5 tests | La table de décision avant suppression. **Seul endroit** où la politique `UNKNOWN` est falsifiable. |
| `VaultPurgeRetryTest` | Room en mémoire, 7 tests | Le second essai de « PIN oublié », la reprise après redémarrage, la course d'un message arrivé pendant le balayage, le contrat **opposé** de la suppression ordinaire, et la sortie assumée. |
| `SystemRowIdentityTest` | Vrai `content://sms`, 7 tests | Date, corps, sens, adresse — et que la même adresse sous une autre notation ne bloque pas. |
| `MmsRowIdentityTest` | Vrai `content://mms`, 3 tests | L'adresse d'un MMS, lue dans `addr`. A été **rouge avant le correctif**. |
| `SettingsResetGuardsTest` | JVM, 9 tests | Ce que le ViewModel décide de la porte « PIN oublié » : premier échec, second échec, sortie assumée, et qu'elle n'est **jamais** prise à la place de l'utilisateur. |
| `MigrationTest` | Room réel, 10 tests | La migration 7→8 : doublon prouvé, collision non prouvée, conversations différentes. |
| `PurgeHistoryPropagationTest` | Room en mémoire, 5 tests | La purge de rétention propage au fournisseur **exactement** les messages qu'elle efface : favoris épargnés, lignes non miroitées écartées par la requête, pagination couvrant 450 lignes, et le contrat opposé à celui du coffre. |

Mesures du 2026-09-08 : gate local vert (assembleDebug, tests unitaires 4 modules, detekt, lint) ;
**90 tests instrumentés, 89 OK + 1 ignoré à raison** sur émulateur Android 11 ; les 15 tests
dépendant du vrai fournisseur **tous verts sur Galaxy S9 / Android 10**, zéro ignoré.

Après l'ajout non publié de la sortie assumée : **92 tests instrumentés, 91 OK + 1 ignoré**,
émulateur Android 11. ⚠️ Une campagne a échoué en cours de route sur
`WorkManager needs to be initialized`, à 35 tests sur 92 — état d'émulateur, levé par un
`am force-stop` et non reproduit ensuite. Si cela revient, c'est l'environnement qu'il faut
regarder, pas la migration que le message désigne.

---

## Ce qui reste à faire — par ordre de valeur

### 1. La purge de bout en bout, à travers Hilt et l'écran de réglages

Les tests actuels appellent `ConversationEraser` directement. Le câblage `SettingsViewModel →
ConversationRepository → ConversationEraser → SystemCopyEraser` n'est vérifié par **aucun test** :
le binding `@Binds` de `SystemCopyEraser` est neuf, et un binding manquant se voit au démarrage,
pas à la compilation.

*Comment* : test Hilt instrumenté injectant `ConversationRepository`, avec un module de test
remplaçant `SystemCopyEraser` par un refus. À faire sur l'émulateur — **pas sur le S9**, un test
Hilt complet touche la vraie base.

### 2. Un MMS sortant dont la date du fournisseur dérive de plus d'une minute

Soulevé par la relecture GPT. La pile système pose la date des MMS sortants ; si elle diffère de
plus de 60 s de celle enregistrée dans Room — réseau absent, envoi différé, reprise opérateur — le
MMS devient `MISMATCH` et **la conversation du coffre ne se purge plus jamais**.

Non mesuré : il faut un vrai envoi de MMS retardé, ce qu'un émulateur ne reproduit pas et qu'un
envoi réel sur le S9 coûte. À faire avant de considérer la fenêtre d'une minute comme acquise.

### 3. La migration 7→8 sur une base réelle et volumineuse

La boucle Kotlin fait trois `execSQL` par ligne à normaliser. Sur une base de plusieurs dizaines de
milliers de messages, Room exécute tout dans une transaction, au premier démarrage après mise à
jour — donc pendant que l'utilisateur regarde un écran vide.

*Comment* : copier la base du S9 (`/data/data/com.filestech.sms/databases/`, via `run-as`), la
rejouer en 7→8 et **chronométrer**. Demande l'accord de Patrice : c'est une copie de ses vrais
messages.

### 4. Le sort d'un fil du coffre supprimé **individuellement**

`delete()` efface la ligne locale même si la copie système résiste — c'est le contrat, et il est
testé. Mais pour un fil **du coffre**, cela laisse une copie système qu'une resynchronisation
complète réimporterait **en clair**.

`SECURITY.md:221` documente déjà que le coffre est un drapeau local et que le fournisseur système
garde les messages en clair : ce n'est donc pas une régression. Reste à décider si la suppression
individuelle d'un fil du coffre doit suivre la règle de la purge plutôt que celle de la
suppression ordinaire. **Décision produit, pas correctif.**

### 5. Deux SMS identiques au même destinataire dans la même minute

Corps, adresse, sens et date égaux : indiscernables, et ils le resteront. Le fournisseur n'expose
rien d'autre sur l'URI d'une ligne. Le cas est bénin — supprimer l'un ou l'autre revient au même —
mais il vaut d'être écrit pour que personne ne croie l'identité plus forte qu'elle n'est.

### 6. Interruption du processus **pendant** la purge

`purgeVault` n'est pas transactionnelle, à dessein : elle touche un fournisseur externe. Un
`kill -9` au milieu laisse un coffre partiellement purgé. La reprise est censée s'en occuper —
c'est ce que teste `laReprisePasseParLaBaseEtNonParUnEtatEnMemoire` avec un objet neuf, mais
**pas avec un vrai redémarrage de processus**.

*Comment* : `adb shell am force-stop` entre deux essais, dans un test qui survit à la mort de
l'application — donc pas un test instrumenté ordinaire.

---

## ✅ Publié en v1.28.2 (291) — la sortie assumée de la porte « PIN oublié »

Décidé par Patrice le 2026-09-08, écrit et testé le jour même, gardé une journée sur `main` sans
être publié — la v1.28.1 venait de sortir — puis **publié en v1.28.2 le 2026-09-08**.

Le point était listé ici comme « impasse permanente d'un `MISMATCH` » et signalé comme tel dans la
réponse à la MR `!38458`. Quand une liaison est durablement fausse — un `telephony_uri` restauré
d'un autre téléphone — le garde d'identité refuse à chaque essai, à l'identique, et l'utilisateur
qui a oublié son PIN de coffre n'a plus d'issue. Une porte de sortie qui ne s'ouvre jamais n'en est
pas une.

**Au SECOND échec seulement**, l'application propose désormais de vider et de retirer le PIN quand
même, après avoir dit ce qui restera sur le téléphone — et le redit une fois l'opération faite. Le
premier échec continue d'inviter à réessayer : une panne passagère (rôle SMS momentanément perdu)
se lève d'elle-même, et offrir tout de suite l'option dégradée pousserait à détruire plus que
nécessaire. Le drapeau qui distingue les deux est **en base**, sinon fermer l'application entre
deux essais ramènerait l'impasse.

Ce n'est pas un assouplissement du garde : la copie système n'est toujours pas supprimée sans
preuve d'identité. Ce qui change est l'arbitrage **local**, et il appartient à l'utilisateur.

Couvert par 5 tests JVM (`SettingsResetGuardsTest`) et 2 instrumentés (`VaultPurgeRetryTest`),
contrôle négatif fait — les deux régressions remises en place font tomber les tests qui les visent.

Les changelogs fastlane 291 sont en place (`fr-FR` 454 caractères, `en-US` 402, plafond F-Droid
500 **caractères** — `wc -c` compte des octets et fait mentir la mesure sur du texte accentué).
Les dix mentions `v1.28.2` du code désignent bien la version réellement publiée. `SECURITY.md`
porte son entrée d'audit, et le `CHANGELOG.md` — que la recette F-Droid publie par son champ
`Changelog:` — a été **rattrapé de cinq versions** au passage : il s'était arrêté à la 1.27.10.

---

## ✅ Clos pendant cette session — la purge de rétention ne propageait pas

Trouvé par l'audit de cohérence du 2026-09-08, corrigé le jour même. `purgeHistoryNow` faisait un
`DELETE` SQL et s'arrêtait là : les messages que l'utilisateur croyait effacés restaient dans
`content://sms`, lisibles par toute application ayant `READ_SMS`, et « Resynchroniser » les
ramenait tous — alors que le dialogue promettait « Cette action est irréversible ». Le cycle
mensuel de `TelephonySyncWorker` **réécrivait la même recette**, donc corriger l'un sans l'autre
aurait laissé la purge automatique — celle qui tourne sans que personne ne regarde — avec le
défaut. Une seule recette désormais : `ConversationEraser.purgeHistory`, couverte par
`PurgeHistoryPropagationTest` (5 tests, tous rouges sans le correctif).

Deux libellés corrigés dans la foulée, FR et EN : la confirmation dit maintenant que l'effacement
porte sur le téléphone et pas seulement sur l'app, et la description de « Resynchroniser » ne
promet plus de « récupérer un historique effacé par erreur » — tous les chemins de suppression
propagent, il n'y a plus rien à y récupérer.

**Reste non mesuré** : le coût sur un très gros historique. La propagation fait une requête et une
suppression **par message miroité**, paginées par 200 (`findMirroredOlderThan`). Une première
purge portant sur des dizaines de milliers de messages n'a jamais été chronométrée sur un vrai
appareil — le faire exigerait un corpus réel, donc l'accord de Patrice. Sans risque d'ANR (worker
en arrière-plan, dialogue en coroutine IO), mais la durée est inconnue.

---

## Deux pièges d'outillage, mesurés ici

1. **`adb shell content query --uri content://mms` ne rend rien même quand des MMS existent.**
   Le shell n'a pas les droits de l'application. Un « No result found » n'y est donc **pas** une
   mesure d'absence. Le nettoyage des lignes de test se vérifie désormais depuis l'intérieur du
   test (`tearDown` assertif), seul endroit qui a les droits.
2. **`connectedAndroidTest` désinstalle l'application après la campagne**, et un appareil jamais
   déverrouillé depuis son démarrage n'a pas de stockage chiffré par identifiant : `MigrationTest`
   y échouait sur `ENOENT` avant d'avoir rien mesuré. Les deux causes sont invisibles dans le
   rapport de test, qui parle de base de données.

---

## Mesures du 2026-09-09 (v1.28.3)

- **Galaxy S9 / Android 10, campagne complète : 131 cas, 0 échec, 0 ignoré** — le test MMS
  qu'un émulateur sans MMS ignorait s'est exécuté. Rôle SMS reposé avant, réinstallé après.
- **Émulateur Android 11 : 131 cas, 0 échec, 1 ignoré à raison** (aucun MMS sur l'appareil).
- **`MigrationTest` 11 → 12** exécuté sur le S9 après l'ajout de `custom_name` (15/15).
- Ce que la campagne **ne mesure pas** et que seul un humain a vu ce soir : neuf défauts de
  fonctions annoncées (X-02 à X-09, plus X-01), tous trouvés en manipulant l'application sur
  deux téléphones, aucun signalé par un test. Les tests écrits ensuite tombent chacun sur le
  défaut qu'ils visent (contrôle négatif), mais ils n'auraient pas trouvé le défaut.

