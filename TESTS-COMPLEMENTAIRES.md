# Tests complémentaires — chemin destructeur du coffre

> Établi le 2026-09-08, après la 3ᵉ passe de revue externe sur la MR F-Droid `!38458`.
> Ce fichier liste **ce qui n'est pas couvert**, pas ce qui l'est. Ce qui est couvert vit dans les
> tests eux-mêmes.

## Ce qui est déjà mesuré, et sur quoi

| Suite | Où | Ce qu'elle prouve |
|---|---|---|
| `SystemRowMatchPolicyTest` | JVM, 5 tests | La table de décision avant suppression. **Seul endroit** où la politique `UNKNOWN` est falsifiable. |
| `VaultPurgeRetryTest` | Room en mémoire, 5 tests | Le second essai de « PIN oublié », la reprise après redémarrage, la course d'un message arrivé pendant le balayage, et le contrat **opposé** de la suppression ordinaire. |
| `SystemRowIdentityTest` | Vrai `content://sms`, 7 tests | Date, corps, sens, adresse — et que la même adresse sous une autre notation ne bloque pas. |
| `MmsRowIdentityTest` | Vrai `content://mms`, 3 tests | L'adresse d'un MMS, lue dans `addr`. A été **rouge avant le correctif**. |
| `MigrationTest` | Room réel, 10 tests | La migration 7→8 : doublon prouvé, collision non prouvée, conversations différentes. |
| `PurgeHistoryPropagationTest` | Room en mémoire, 5 tests | La purge de rétention propage au fournisseur **exactement** les messages qu'elle efface : favoris épargnés, lignes non miroitées écartées par la requête, pagination couvrant 450 lignes, et le contrat opposé à celui du coffre. |

Mesures du 2026-09-08 : gate local vert (assembleDebug, tests unitaires 4 modules, detekt, lint) ;
**90 tests instrumentés, 89 OK + 1 ignoré à raison** sur émulateur Android 11 ; les 15 tests
dépendant du vrai fournisseur **tous verts sur Galaxy S9 / Android 10**, zéro ignoré.

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

### 5. L'impasse permanente d'un `MISMATCH`

Soulevée par la relecture GPT, et non résolue. Quand une liaison est durablement incohérente,
chaque nouvel essai reproduit le même refus : la porte « PIN oublié » reste fermée, sans recours.
Le refus est **visible** — l'utilisateur lit « purge incomplète, le PIN n'a PAS été retiré » — ce
qui vaut mieux qu'une fuite silencieuse, mais ce n'est pas une sortie.

Il manque un chemin de résolution explicite : reprise du rôle SMS, ou abandon confirmé par
l'utilisateur qui accepte que des copies système survivent. **Décision produit.**

### 6. Deux SMS identiques au même destinataire dans la même minute

Corps, adresse, sens et date égaux : indiscernables, et ils le resteront. Le fournisseur n'expose
rien d'autre sur l'URI d'une ligne. Le cas est bénin — supprimer l'un ou l'autre revient au même —
mais il vaut d'être écrit pour que personne ne croie l'identité plus forte qu'elle n'est.

### 7. Interruption du processus **pendant** la purge

`purgeVault` n'est pas transactionnelle, à dessein : elle touche un fournisseur externe. Un
`kill -9` au milieu laisse un coffre partiellement purgé. La reprise est censée s'en occuper —
c'est ce que teste `laReprisePasseParLaBaseEtNonParUnEtatEnMemoire` avec un objet neuf, mais
**pas avec un vrai redémarrage de processus**.

*Comment* : `adb shell am force-stop` entre deux essais, dans un test qui survit à la mort de
l'application — donc pas un test instrumenté ordinaire.

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
