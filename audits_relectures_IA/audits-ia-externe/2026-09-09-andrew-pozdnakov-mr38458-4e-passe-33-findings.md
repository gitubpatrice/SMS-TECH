# Relecture externe Andrew Pozdnakov — MR F-Droid !38458, 4ᵉ passe (33 findings)

**Date de la note** : 2026-09-09 · **Périmètre audité** : SMS Tech 1.28.2 (291), commit
`28c50cf7517e7d63928caeb5618f11495cc4fb95` · **Branche de correction** :
`fix/relecture-externe-38458`

> Note d'origine : <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38458#note_3806954310>
> Réponse publiée le 2026-09-10, après pipeline F-Droid vert (`fdroid build` + `check apk`) :
> <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38458#note_3811895340> — SMS Tech 1.28.3 (292).

---

## 1. Ce que la note dit, et ce qu'elle ne dit pas

Andrew a changé de méthode : après trois passes où il remontait un défaut à la fois, il a relu
**l'architecture d'ensemble** — identité de conversation, suppression du coffre, travail
programmé, transitions d'état SMS/MMS, notifications, sauvegarde/restauration.

Il prévient lui-même, et c'est à reprendre tel quel auprès de quiconque lira la MR :

> this does not mean "33 remotely exploitable security vulnerabilities"

C'est un relevé d'ingénierie mêlant confidentialité, intégrité de données, machines d'état
incomplètes et incohérences fonctionnelles. **Un seul finding a été reproduit de bout en bout sur
émulateur** (F01) ; les autres sont « source-confirmed » ou modélisés, et il le dit.

## 2. Vérification indépendante — le verdict

Les 33 findings ont été vérifiés dans le source avant toute correction. Aucun fichier `.kt`
n'avait changé entre le commit audité et `main` : la relecture portait bien sur le code courant.

| | |
|---|---|
| Tiennent intégralement | **27** |
| Tiennent partiellement | **5** (F21, F23, F25, F27, F31) |
| Infirmé | **1 volet** — F31b |
| Entièrement faux | **aucun** |

### Le seul point où il se trompe — F31b

Il suppose qu'un échec DataStore réémet `AppSettings()` et **réinitialiserait silencieusement les
réglages de sécurité**. Faux : le `catch` conserve le dernier instantané sans réémettre
(`SettingsRepository`), `AppLockManager` part de `LockState.Locked`, et `entryGate` reste `null`
tant que l'état est inconnu. Les trois échouent du bon côté. Le volet (a) reste vrai :
`settings.flow.first()` sur un flux qui se termine sans émettre lève une `NoSuchElementException`
non capturée → **crash au lancement**, et non blocage sur le splash comme il l'écrit.

### Trois findings qu'il surévalue

- **F25** — la déduplication *de conversations* reparente et fusionne correctement. Seule celle de
  la *restauration* est en cause.
- **F23** — l'écrasement inter-parties a été corrigé en v1.26.1 (monotonie de statut). Reste vrai
  que les rappels ne sont rattachés à aucune tentative.
- **F20** — produit un **blocage définitif**, pas un double envoi : le verrou `claimForSending`
  tient.

### Ce qu'il a manqué ou sous-estimé

- **F01 touche aussi la réception**, pas seulement les brouillons. `SmsDeliverReceiver` ne lit
  jamais le `thread_id` système : deux SMS d'inconnus entre deux resynchros (12 h) et le premier
  fil disparaît. Le texte est rattrapé par la resynchro, mais favoris, réactions et appartenance
  au coffre sont perdus.
- **F02 est pire que décrit** : la carte « Messages programmés » des Réglages n'est pas sous le
  garde du mode leurre, contrairement à ses quatre voisines.
- **F24 dans son scénario nominal** : après réinstallation, la resynchro réimporte tout, la
  restauration collisionne sur l'index UNIQUE, `msgIdMap` reste vide, `imported = 0` — la
  sauvegarde ne rend rien de ce qu'elle seule transportait.
- **F26 : `blockUnknown` est une promesse de sécurité affichée** (« Block unknown numbers ») que
  zéro ligne ne tenait.

## 3. Le motif dominant, qui vaut plus que la liste

**La grande majorité de ces défauts sont des correctifs déjà écrits, mais posés sur un seul des
chemins qui en avaient besoin.** Le dépôt savait, et le disait souvent en commentaire :

| Défaut | Où la connaissance existait déjà |
|---|---|
| F01 | `BackupService` décrit le mécanisme **mot pour mot** depuis la v1.15.2 (audit SECU-M3) et le contourne — pour lui seul. `ensureConversationByThread` aussi. |
| F06 | Le chemin voisin, bien moins grave, attend un état déverrouillé depuis la v1.27.2, KDoc à l'appui. |
| F08 | `IncomingMessageNotifier` applique les trois gardes depuis des versions. |
| F16 | Le chemin **sortant** gère plusieurs pièces jointes et sait étiqueter `text/x-vcard`. |
| F18 | `MmsSentReceiver` consulte `mms_system_id` précisément pour refuser d'agir sur la ligne d'autrui. |
| F22 | Le chemin MMS prend un instantané des pièces jointes — et efface le texte sans condition, **dans la même expression**. |
| F26 | `blockShortCodes` a été retiré en v1.3.5 comme « champ fantôme », deux lignes au-dessus. |

**Deuxième motif** : *les affirmations d'exhaustivité vieillissent mal.*

- Le commentaire de la carte Sauvegarde affirmait être « la SEULE voie de lecture du coffre qui
  n'était pas gardée ». Faux — et il a masqué F02 pendant deux versions.
- `ThreadScreen` promettait « three dialogs, one setting, consistent » en renvoyant à un dialogue
  supprimé en v1.3.4. Vingt versions de faux.
- `Migrations.kt` affirmait que toute migration est additive.
- `ConversationEraser` annonçait « trois dépendances suffisent ».

**Troisième motif** : *un correctif d'audit peut reproduire le défaut qu'il corrige.* F19 : l'audit
F38 avait remplacé une collision « un message sur deux » par `hash or 0x10000`, qui confond toute
paire séparée par le bit 16.

---

## 4. État du chantier de correction

### Corrigés et commités (31 / 32)

| Commit | Findings | Substance |
|---|---|---|
| `54bc43e` | **F01** · Critique | `thread_id` nullable, migration Room **8 → 9**, `upsert(REPLACE)` → `insert(ABORT)`. Sentinelles supprimées des 4 chemins de création. |
| `fbcae84` | **F05** · Critique | Safety Call n'avance que sur l'accusé du radio (`outgoingStatus`), plus sur l'acceptation par `SmsManager`. |
| `f2843e0` | F22, F29, F30 | Les trois chemins d'envoi du composeur suivent les mêmes règles : révision de brouillon, MMS programmable sans légende, confirmation d'envoi rétablie sur le média. |
| `f4cef8b` | **F28** | ZWJ/ZWNJ ne sont plus retirés : emoji composés et persan cessent d'être altérés **à chaque message reçu**. |
| `eb5bd38` | **F18** | La purge de l'OUTBOX exige une preuve de propriété (`mms_system_id`), par lots de 900. |
| `59c67ba` | F03, F04, F09 | La purge du coffre annule les envois programmés, efface les **fichiers** de pièces jointes, et distingue résidu système / échec local. |
| `72b5a76` | **F02** | Les messages programmés du coffre appliquent la visibilité du coffre. |
| `596a348` | **F26** | `blockUnknown` câblé (`IncomingBlockPolicy`) ; `retryFailedAutomatically` retiré. |
| `9a6a09e` | F06, F08, F19 | Authentification avant désarmement ; rédaction des notifications d'échec MMS ; identité des `PendingIntent`. |
| `11d4908` | F15, F16, F17 | Le PDU n'est plus consommé quand la persistance échoue ; toutes les parties sont retenues, vCard compris ; transaction à deux états. |
| `025b13c` | **F20** | Le verrou d'envoi reçoit un **bail** (migration 9 → 10). Un envoi revendiqué puis abandonné se conclut `INTERRUPTED` — issue inconnue, mais **atteignable** — au lieu de rester `SENDING` à vie. L'annulation dit enfin qu'elle n'a pas pris. |
| `88c0e5c` | **F23** | Chaque message porte un numéro de **tentative** (migration 10 → 11), présent dans les extras du `PendingIntent` **et dans son `requestCode`**. L'accusé tardif d'une tentative périmée ne s'applique plus. |
| `00c54fa` | **F21** | Un destinataire bloqué laisse une ligne locale en échec ; `SendReport` compte les trois issues et le fil dit qu'un envoi n'a pas atteint tout le monde. |
| `f63e618` | **F21** (suite) | Le même `continue` muet vivait sur les **deux voies MMS**. Les trois chemins d'envoi appliquent enfin la même règle. |
| `1f7ace8` | **F10, F11** | `erase` atteint la ligne système par `mms_system_id` — **aucun MMS sortant n'a jamais eu de `telephony_uri`** ; la rétention n'entre plus dans le coffre, et son compteur suit. |
| `3adb3bb` | **F12, F14** | Le corps n'est comparé que lorsqu'il décrit le message (sentinelle de réaction), et l'identité exige au moins un discriminant fort réellement confronté. |
| `128de93` | **F24, F25, F32** | Une collision de restauration est une **rencontre** : citations recollées, favoris et réactions reversés. Clé de dédup resserrée. L'écrivain refuse ce que son lecteur refuserait, et relit ce qu'il a écrit. |
| `51b1c88` | **F07, F27, F31a, F33** | Abaisser le verrouillage ne retire plus le seul second facteur du coffre ; trois allocations sans borne bornées ; plus de crash au lancement ; PDF non tronqué, document fermé, pieds de page présents. |

### Le seul non corrigé, et pourquoi — F13

**F13 (purge non atomique) est le seul des 33 que je n'ai pas changé**, et c'est une décision
argumentée, pas un oubli :

- sa conséquence dangereuse — retirer le PIN du coffre sur une purge partielle — a été fermée par
  **F09** : `VaultPurgeResult` relit le coffre APRÈS la boucle et distingue résidu système et
  échec local, si bien qu'une purge interrompue ne peut plus lever la protection ;
- le reste de la non-atomicité est **inhérent** : la suppression de FICHIERS ne peut pas
  participer à une transaction SQLite. Rendre atomiques les deux seules écritures en base
  tiendrait de la mise en scène ;
- l'ordre en place est **choisi** pour que le résidu soit rattrapable — une conversation dont les
  fichiers sont partis, plutôt que des fichiers orphelins définitifs — et c'est écrit dans le code
  depuis F03/F04.

### Ce que les correctifs eux-mêmes ont révélé

Trois défauts trouvés **en corrigeant**, dont deux étaient de moi :

1. **Mon correctif F02 a créé une perte de données silencieuse sur le chemin voisin.** Le filet de
   replanification du démarrage lisait `observePending()`, le flux destiné à l'ÉCRAN, que F02
   masque tant que le second facteur du coffre n'a pas été donné — ce qui, au boot, est toujours
   le cas. Corrigé avec F20 (`allUnsettled`).
2. **Mon correctif F21 n'a été posé que sur la voie SMS**, alors que le `continue` muet vivait à
   l'identique sur les deux voies MMS. C'est le motif dominant de cette relecture, reproduit sur
   le finding qui le décrit. Trouvé par la revue de qualité lancée sur mon propre delta — qui n'a
   cité que la voie média ; la voie vocale, je l'ai trouvée en vérifiant l'autre.
3. **Un MMS sans légende restauré devient INVISIBLE.** `toLocalRow` remet `attachments_count` à
   zéro (v1.26.1, M7) ; la ligne prend alors exactement la forme d'une sentinelle de réaction —
   corps vide, aucune pièce jointe, aucune réaction — que cinq requêtes de `MessageDao` excluent.
   Elle est importée, comptée comme importée, et n'apparaît nulle part. **Non corrigé**, découvert
   en écrivant les tests de F25 : le remède propre demande une colonne `hidden` et une migration,
   qui rendrait aussi le correctif F14 plus robuste que sa reconnaissance par forme.

### Les trois audits de fin de chantier

Trois audits lancés sur le delta complet — sécurité/données, cohérence transversale,
qualité/performance. **Sept constats retenus, tous vérifiés par lecture avant correction** : un
audit est une piste, pas une autorité.

Le plus important : **la troisième occurrence du motif dominant, encore de moi.**
`ScheduledSendAttempt` jetait le `SendReport`. F21 avait été posé sur les trois chemins d'envoi,
pas sur leur appelant de fond — qui invoque pourtant exactement les mêmes use cases. Un envoi
programmé vers un destinataire bloqué était en outre **retenté cinq fois**, alors qu'un blocage ne
se résorbe jamais seul, pour finir sur « échec après plusieurs tentatives » — la mauvaise cause.

Et **la sortie de secours du coffre était redevenue une impasse** sur un échec local : boucle sur
un dialogue dont le texte affirmait que ce qui reste est « dans le stockage SMS », faux dans ce
cas. Le KDoc de mon propre test de F09 **décrivait déjà ce trou** sans le fermer — une observation
juste, écrite, et non tirée.

**Deux de mes KDoc affirmaient une garantie que le code ne tenait pas** — le second motif du
registre, reproduit par moi : la fusion des drapeaux ne distingue pas « jamais réagi » de « a
retiré sa réaction » ; et l'adresse ne discrimine qu'entre conversations, pas à l'intérieur de
l'une d'elles. Les deux sont désormais écrits comme des compromis, avec ce qu'il faudrait pour les
lever.

Non retenu, et argumenté : **factoriser la boucle d'envoi** entre les trois use cases. L'argument
est juste — cette triplication a produit le même défaut deux fois. Mais les trois chemins sont
aujourd'hui alignés *et* couverts par des tests qui verraient une divergence, et refactorer trois
chemins d'envoi critiques en fin de chantier, sans pouvoir mesurer un vrai MMS, coûte plus que la
duplication qu'on retire. **À programmer à froid.**

### Ce que le contrôle négatif a appris

En remettant **les deux défauts de F12 et F14 à la fois**, un seul test tombait. Celui de F12
restait vert — mais POUR LA MAUVAISE RAISON : le défaut de F14 refusait déjà la suppression, donc
l'assertion « n'est pas supprimée » était satisfaite par un autre chemin. Il a fallu neutraliser
F12 **seul** pour le voir tomber.

*Deux correctifs posés ensemble peuvent se masquer l'un l'autre ; un contrôle négatif groupé ne
prouve pas ce qu'il semble prouver.*

Et un test de F32 ne pouvait pas échouer : vérifier qu'un fichier tronqué est refusé mesure le
LECTEUR, qui le refusait déjà. Remplacé par un test des bornes de l'ÉCRIVAIN.

---

### Ce que la fermeture de F20, F21 et F23 a appris

- **Un correctif de confidentialité peut créer une perte de données sur le chemin voisin.** Le
  filet de replanification du démarrage lisait `observePending()`, c'est-à-dire le flux destiné à
  l'écran — que le correctif F02 de cette même branche masque tant que le second facteur du coffre
  n'a pas été donné, ce qui au démarrage est toujours le cas. Un envoi programmé depuis une
  conversation protégée cessait donc d'être rattrapé. Trouvé en relisant les appelants du flux que
  je venais de modifier, et non par un test. **Une règle d'affichage ne doit pas décider de ce qui
  part** — `allUnsettled()` est désormais la lecture non masquée que ce filet exige.
- **Le remède de F23 aurait pu reproduire son propre défaut**, exactement comme F19. Séparer les
  tentatives en multipliant le `requestCode` aurait rapproché les identifiants que l'audit F36
  avait justement séparés. Les trois séparations — par tentative, par partie, par message — sont
  donc verrouillées **ensemble**, et non une à une.
- **Ne pas trancher l'incertitude à la place de l'utilisateur.** La tentation, sur F20, était de
  rendre à `PENDING` une ligne dont le bail a expiré. C'était rouvrir le double envoi facturé que
  le verrou avait fermé en v1.26.1 : le processus a pu mourir **après** que `SmsManager` a accepté
  le message. L'état `INTERRUPTED` dit l'incertitude au lieu de la résoudre.

### ⚠️ Le gate instrumenté est trompeur tant que le rôle SMS n'est pas reposé

Mesuré le 2026-09-09, sur le S9 **et** sur l'émulateur : `SystemRowIdentityTest` et
`MmsRowIdentityTest` (8 cas) **passent en campagne ciblée et échouent en campagne complète**.
`connectedAndroidTest` désinstalle l'application en fin de course, et leur prérequis — le rôle SMS
— ne lui survit pas ; leur `assumeTrue` couvre l'écriture, pas la lecture qui lève alors une
`SecurityException`. Vérifié sur l'arbre **sans** les correctifs de cette branche : mêmes huit
échecs. Préexistant, sans lien avec ce chantier, mais il faut poser le prérequis avant chaque
campagne :

```
./gradlew :app:installDebug
adb shell cmd role add-role-holder android.app.role.SMS com.filestech.sms.debug
```

Après quoi la campagne complète rend **114 cas, 0 échec, 0 ignoré** — et ce « 0 ignoré » est la
moitié importante du contrôle.

### Après la relecture : l'audit global et la mesure sur appareil

La branche a ensuite reçu un audit global en quatre passes (registre :
`audits_relectures_IA/audits-ia-interne/2026-09-09-audit-global-quatre-passes.md`) — 9 constats
retenus, 2 réfutés, 7 corrigés ici (`897d2cf` D-02/A-01, `e00a08a` B-1, `2908c41` D-01/D-03,
`15cc067` A-03, `4c40679` C-03) — puis une session de mesure sur Galaxy S9 (Android 10) et S24
(Android 16), qui a trouvé **neuf défauts invisibles à la lecture** :

| Commit | Défaut mesuré |
|---|---|
| `b7471a6` | X-02 — joindre un contact n'a **jamais** fonctionné : la fiche n'est pas un fichier. |
| `3a32a7d` | X-03 — deux photos ne pouvaient pas partir : le plafond se partage entre les images. |
| `7dc39d4` | X-04 — la bulle n'affichait que la première pièce jointe (F16 sans son jumeau d'affichage). |
| `583e3fb` | X-05 — créer un groupe était introuvable : le raccourci « toucher = ouvrir » avait tué le chemin. |
| `6e769d2` | X-06 — un nom tapé devenait un destinataire (`RESULT_ERROR_NULL_PDU`). |
| `9e429b1` | X-07 — le clavier masquait la liste des contacts. |
| `0075ad1`, `715a4e0` | X-08 — un groupe s'intitulait par des numéros, le dépôt affirmant que l'écran « joignait les noms ». |
| `59b35e7` | **X-01** — mettre un groupe au coffre met ses membres au coffre, et l'en sortir les en sort. |
| `a5ffca8` | X-09 — un SMS reçu dans une conversation du coffre était « introuvable après insertion » (relecture masquée). |
| `49db3cb`, `56ac06f` | Groupes nommés (schéma 12) et copie des envois dans le fil du groupe, qui restait vide. |

Le motif dominant s'est encore vérifié deux fois : X-04 (correctif de données sans son jumeau
d'affichage) et X-09 (une lecture masquée pour l'écran utilisée par un receveur). Et **quatre
fonctions annoncées n'avaient jamais fonctionné** sans qu'aucun test ne le dise.

Campagne finale : **131 cas sur S9, 0 échec, 0 ignoré** ; 557 tests unitaires ; migration
11 → 12 exécutée sur appareil.

### Cinquième note d'Andrew (2026-09-10, `note_3814788966`) — R01, R02, R03

Andrew a resserré sur **F03/F04/F09/F13** et mesuré, sur émulateur Android 14 avec la base
SQLCipher réelle et le graphe Hilt de production (`ProductionStackPurgeVerificationTest`, tests
fournis et relus, exécution assistée par IA — dit tel quel), **trois cas où la purge du coffre se
dit complète alors qu'il reste quelque chose** :

| | Ce qui survit | Cause dans le source de 1.28.3 |
|---|---|---|
| **R01** | un envoi programmé dont le `DELETE` est refusé — parent supprimé, PIN retiré, `VaultPurged` émis, et l'orphelin **redevient visible hors coffre** (`COALESCE(c.in_vault, 0)`) ; la reprise ne le retrouve jamais | `annulerEnvoisProgrammes` gobe l'exception ; `purgeVault` compte des conversations, pas des enfants |
| **R02** | un fichier possédé dont `delete()` rend `false` — parent supprimé, PIN retiré | `supprimerFichiersPossedes` journalise `exists() && !delete()` sans rien rendre ; `getOrDefault(emptyList())` sur l'énumération |
| **R03** | un message importé (fournisseur réel, synchro de production) qui **commet après la seconde relecture et avant le `DELETE` du parent** — emporté par la cascade, copie système intacte, purge « complète » ; la resynchro delta ne le rend pas, la complète oui | la relecture d'après-boucle ne voit que ce qui est déjà commis |

Il souligne que R01/R02 n'ont **pas besoin de `force`** : la branche de succès ordinaire est
atteinte parce qu'un nettoyage incomplet est rapporté complet. Il donne des critères
d'acceptation, pas un refactor imposé : rendre compte des vrais résultats des aides, garder
ensemble preuve de reprise et protection (garder le parent tant qu'un enfant reste est un
design admis), ne pas classer un enfant par une jointure sur un parent disparu, et coordonner
écrivains et finalisation (une courte transaction locale avec relecture est admise).

**Réponse (v1.28.4, branche `fix/tests-promis-a-andrew`)** — `ConversationEraser.erase` rend
`Issue(systemCopyGone, localeComplete)` : les deux aides rendent un compte d'échecs (énumération
ratée = échec, `delete()` à `false` = échec), un seul échec **garde le parent** pour le coffre et
compte en `localFailures` ; la relecture et la suppression du parent vivent dans **une seule
transaction Room** — SQLite n'ayant qu'un écrivain, un import qui commet pendant la purge attend
le verrou et ne peut plus se glisser entre les deux. Trois tests sur Room réel reproduisent R01
(`TRIGGER` refusant le `DELETE`), R02 (dossier 0500, `delete()` mesuré à `false`) et R03 (message
inséré au point d'injection de l'ordonnanceur), chacun avec sa reprise, et chacun tombe quand
son défaut est remis.

## 5. Décisions prises, et pourquoi

1. **`retryFailedAutomatically` retiré plutôt que câblé.** Le câbler reviendrait à écrire une
   fonctionnalité d'envoi automatique, avec ses risques propres — doublons facturés, message
   reparti sans que l'utilisateur le veuille. C'est une décision produit ; **elle appartient à
   Patrice**. En attendant, un interrupteur qui ne fait rien est un mensonge.
2. **La carte « Messages programmés » reste visible en session leurre.** Ses voisines gardées le
   sont parce que leur existence trahirait ; un message programmé est une commodité ordinaire. Le
   contenu, lui, est désormais filtré.
3. **F28 : retirer ZWJ/ZWNJ de la liste plutôt que stocker le transport.** Andrew proposait de ne
   nettoyer qu'à l'affichage ; cela demanderait de nettoyer à chaque point de rendu, donc d'en
   oublier un. Le motif SEC-02 visait le soft hyphen, conservé.
4. **F03/F04 posés sur `erase`, donc aussi sur la suppression ordinaire.** Ne poser un garde que
   sur le chemin qui l'a motivé est précisément ce qui a produit la moitié de ces défauts.

## 6. Ce qui reste à vérifier avant publication

### État au 2026-09-09, fin de chantier

**32 findings sur 33 traités, plus deux ajouts, 25 commits** sur `fix/relecture-externe-38458` —
branche **non
poussée, non taguée**, arbre propre. Gate : detekt, lint, **579 tests unitaires**, **131
instrumentés sur S9 (Android 10) — 0 échec, 0 ignoré**, parité FR/EN vérifiée (756 clés de chaque
côté, aucun argument de format divergent, aucune apostrophe nue).

### Les trois vérifications qui demandent un appareil

1. **MMS réels entre deux téléphones** — simple, multi-photos, vCard. Le lot F15/F16/F17 change un
   chemin critique sans test automatique, et F21 y a ajouté la trace du destinataire bloqué.
2. **Export PDF d'une conversation contenant un message très long** — F33 découpe désormais une
   bulle plus haute qu'une page. Cela se relit, cela ne se teste pas.
3. **Une purge du coffre** — F10 change la preuve sur laquelle repose le retrait du PIN.

### Les deux décisions produit — tranchées le 2026-09-09

1. **`retryFailedAutomatically` : la question est close, il ne sera pas câblé.** Le câbler
   contredirait F20, qui refuse de renvoyer un envoi dont le bail a expiré parce que le processus
   a pu mourir *après* que `SmsManager` a accepté. Et le besoin qu'il portait est servi autrement
   depuis ce soir : une **notification d'échec d'envoi avec action « Renvoyer »**. L'utilisateur
   obtient ce que le réglage promettait — ne pas perdre un message — sans que rien ne parte sans
   qu'il le demande.

2. **La colonne `hidden` : à faire, sur sa propre branche, et pas pour la raison annoncée.**
   Correction d'enjeu : la sauvegarde ne transporte aucune pièce jointe, donc un MMS sans légende
   restauré est une ligne sans contenu récupérable — ce n'était pas une perte de contenu mais de
   *trace*, et cette trace est désormais **comptée et annoncée** à l'utilisateur. Ce qui reste, et
   qui tient seul, c'est la fragilité : reconnaître une sentinelle de réaction **par sa forme**
   dans cinq requêtes et dans l'effaceur. À faire comme un travail de robustesse, pas comme un
   correctif de données — et pas sur cette branche, où toucher `observeForConversation` ferait
   disparaître des messages des fils en cas d'erreur.

### Le travail à froid, et sa priorité a changé

**Factoriser la boucle d'envoi entre les trois use cases — à faire EN PREMIER**, avant la colonne
`hidden`. Quand je l'ai écarté, j'avais deux occurrences du défaut ; il y en a **trois** : le
dernier audit a montré que `ScheduledSendAttempt` jetait le `SendReport`. Trois fois le même
défaut, sur le même code, en une session — ce n'est plus une duplication esthétique, c'est un
générateur de bugs mesuré.

La raison de ne pas l'avoir fait en fin de chantier reste bonne : cela demande de pouvoir envoyer
un vrai MMS pour vérifier. **Une précision qui change la façon de le faire** : factoriser la
boucle *et son appelant*. Ne factoriser que les trois use cases laisserait
`ScheduledSendAttempt` comme quatrième chemin capable de diverger — c'est précisément celui qui a
divergé.

### ⚠️ Le piège de méthode à ne pas réapprendre

Le gate instrumenté **ment** tant que le rôle SMS n'est pas reposé avant chaque campagne —
`connectedAndroidTest` désinstalle l'application en fin de course. Huit tests échouent alors pour
une raison sans rapport avec le code :

```
./gradlew :app:installDebug
adb shell cmd role add-role-holder android.app.role.SMS com.filestech.sms.debug
```



- ⚠️ **Le lot MMS entrant (F15, F16, F17) n'a aucun test automatique** et modifie un chemin
  critique. **Envoyer de vrais MMS entre deux appareils** : un simple, un multi-photos, un vCard.
- ⚠️ **F06, F08, F22, F29, F30 non couverts** : `MainActivity` et `ThreadViewModel`
  (21 dépendances) demandent Robolectric. Vérifiés par lecture des deux côtés.
- **Le rejeu des PDU conservés n'existe toujours pas** : ils survivent au plus 24 h et personne ne
  les rouvre. La conservation ouvre une fenêtre, pas une reprise.
- **Dette de testabilité** : `ThreadViewModel` reste non testable. Même motif que celui relevé en
  v1.28.1 sur le chemin destructeur (« code privé = code non testé »).

## 7. Méthode appliquée à chaque correctif

Chaque défaut fermé a reçu un test **et un contrôle négatif exécuté** : le défaut remis en place
doit faire tomber le test qui le vise, **et lui seul**. Les contrôles positifs sont écrits
explicitement — sans eux, un correctif qui refuserait tout, ou masquerait tout, passerait pour bon.

Deux pièges rencontrés, à retenir :

- Un test `= runBlocking { … }` finissant sur une assertion **ne rend pas `void`**, et JUnit rejette
  alors la classe **entière** — les tests ne s'exécutent pas. Toujours vérifier le nombre de cas
  exécutés dans le rapport, jamais le seul « BUILD SUCCESSFUL ».
- `MigrationTest` tourne sur le vrai open-helper **SQLCipher**, pas sur SQLite en clair. C'est ce
  qui rend la preuve de non-cascade du `DROP TABLE` réelle et non théorique.
