# Note — le bouton « Ajouter un numéro » était muet pour un lecteur d'écran

> Écrit le **2026-08-17**, mis à jour et **commité le 2026-08-19** une fois le correctif publié.
> Il dit ce qui a été trouvé, ce qui a été mesuré, et **ce qui reste à faire**.

## Où en est le dépôt

| | État vérifié le 2026-08-19 |
|---|---|
| Correctif | `ef9d3bc` sur `fix/a11y-bouton-flottant-sans-nom`, **repris sur `main`** en `ad37ee0` |
| Publié | **v1.27.8 (284)**, commit `c25f65c`, tag poussé le 19/08 à 20:45 |
| Certificat | `b09a9511…687d`, **inchangé** |
| MR F-Droid `!38458` | bumpée en **1.27.8 (284)**, `prebuild:` retiré au profit d'`output:`, **pipeline vert** |
| Vérification | mesure **avant/après sur le S9 dans la même séance** : `NAF="true"` disparu, nœud frère `content-desc="Ajouter un numéro"` |

⚠️ **La CI ne vérifiera pas cette branche.** `android.yml` et `codeql.yml` ciblent
`branches: [ main, develop ]` : un push sur `fix/**` ne déclenche **rien**. C'est le trou déjà
consigné au §9.4 du CLAUDE.md global. Ce qui a réellement vérifié ce commit est la **gate locale à
quatre outils** et une **mesure sur appareil**, décrites plus bas.

## Le défaut, et il était PUBLIÉ

Écran **Numéros bloqués**, bouton flottant « Ajouter un numéro » :
`app/src/main/java/com/filestech/sms/ui/screens/blocked/BlockedNumbersScreen.kt`.

`ExtendedFloatingActionButton` de **material3 1.4.0** (BOM Compose `2026.06.00`) enveloppe son
emplacement `text` dans un **`clearAndSetSemantics`**. Le libellé est donc **dessiné** et **absent de
l'arbre de sémantique fusionné**, celui que lit un lecteur d'écran.

Relevé `adb shell uiautomator dump` sur le **S9**, application **1.27.3 (277) publiée** :

```
<node NAF="true" clickable="true" text="" content-desc=""
      class="android.view.View" package="com.filestech.sms"
      bounds="[468,1860][1032,2028]">
```

`NAF="true"` est la marque que **uiautomator pose lui-même** sur tout nœud cliquable sans nom.
TalkBack annonçait donc « bouton », sans dire lequel, sur le **seul** moyen d'ajouter un numéro à la
liste des bloqués.

⚠️ **Rien dans le code n'était choquant à la relecture.** `contentDescription = null` sur une icône
décorative est la règle, et le libellé était bien là, dans le slot `text`. Le `clearAndSetSemantics`
n'est **pas visible depuis le code appelant** : aucune relecture n'avait de raison de s'en méfier.
Ce défaut ne se trouve que par la mesure.

## Le correctif, et pourquoi il ne nomme pas l'icône

Le nom accessible est posé sur le **bouton** :

```kotlin
val ajouterUnNumero = stringResource(R.string.blocked_add)   // lu une fois, servi deux fois
…
ExtendedFloatingActionButton(
    text = { Text(ajouterUnNumero) },
    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },   // reste decorative
    onClick = { showDialog = true },
    modifier = Modifier.semantics { contentDescription = ajouterUnNumero },
)
```

Nommer l'**icône** fonctionne aussi, et c'était la première correction — **écartée** après que les
deux relectures externes du 2026-08-17 (Gemini Pro, GPT-5.2) ont convergé, chacune avec un argument
distinct :

- si material3 cesse un jour d'effacer le slot `text`, le nœud portera la description **et** le
  texte ⇒ **annonce en double** ;
- si le `mergeDescendants` du composant change, une icône **nommée** peut devenir un **arrêt de focus
  séparé et non cliquable**.

Le libellé appartient à l'**action**, pas au pictogramme. Aucune ressource n'a été ajoutée :
`blocked_add` existait déjà, donc **parité FR↔EN et fastlane inchangés**.

## Ce qui a vérifié ce commit

**Gate locale à quatre outils, verte** : `assembleDebug`, `testDebugUnitTest` (**492 tests, 0 échec,
0 ignoré**), `lintDebug`, `detekt` sur les **quatre** modules (`:app :core :domain :data`).

**Mesure sur le S9**, après installation du debug 1.27.6 :

| | Avant | Après |
|---|---|---|
| nœuds `NAF="true"` sur l'écran | 1 (le bouton) | **0** |
| description du bouton | `""` | **« Ajouter un numéro »** |
| lignes de la liste nommées « Débloquer » | 10 | 10 |

### ⚠️⚠️ Deux faux signaux de ce relevé, à connaître avant de le refaire

1. **Après correctif, uiautomator montre DEUX nœuds jumeaux** aux mêmes bornes — l'un `clickable`
   sans description, l'autre décrit et non cliquable. C'est la traduction Compose → accessibilité,
   **pas** un focus dédoublé : dans l'arbre de **sémantique**, c'est un seul nœud qui porte les deux
   (prouvé côté portage Notes Tech par `hasClickAction() and hasContentDescription(…)`).
2. **Une ligne de liste partiellement visible perd son nœud nommé au rognage** et laisse son
   enveloppe cliquable marquée `NAF="true"`. Les 8 lignes entières étaient nommées, la 9ᵉ non — il
   faut **faire défiler avant de conclure**. J'ai failli compter un défaut de plus.

## Ce qui a été balayé dans le dépôt, et ce qui ne l'a pas été

| Contrôle | Résultat |
|---|---|
| `ExtendedFloatingActionButton` | **une seule** occurrence dans tout le dépôt, celle-ci |
| `FloatingActionButton` simple (`ConversationsScreen`) | son icône porte `contentDescription` ⇒ **correct** |
| bouton de débordement de l'accueil | s'annonce **« Plus »** ⇒ correct |
| écrans relevés | accueil, menu de débordement, 3 pages de réglages, numéros bloqués |

🔴 **Le balayage exhaustif de tous les écrans reste à faire.** Le seul instrument fiable est de
chercher `NAF="true"` dans un dump de **chaque** écran, ou un test Compose qui liste les nœuds
**actionnables** — clic **ou appui long** — sans `ContentDescription` ni `Text` dans l'arbre
**fusionné**.

⚠️ **Un test écrit sur `onNodeWithText` resterait VERT sur ce défaut** : la tentation est de le passer
en `useUnmergedTree = true` « pour qu'il trouve », c'est-à-dire exactement l'arbre où le défaut est
invisible.

## Décision du 2026-08-17 — **levée le 2026-08-19** (conservé comme historique)

> « pour SMS-tech attendre que la MR F-Droid !38458 soit tranchée, je pense que c'est mieux. »

⚠️ **Ce qui s'est passé le 19 a rendu cette attente sans objet.** Corriger une métadonnée fastlane
imposait déjà une release — F-Droid lit fastlane **au commit de build** — d'où la **1.27.7**, puis la
**1.27.8** qui a emporté ce correctif. La MR a été bumpée dans la foulée, pipeline vert : elle ne
pointe donc pas une version en retard, ce qui était la crainte du 17.

**Déclencheur** : MR `!38458` fusionnée ou fermée. Elle porte alors `AutoUpdateMode` — vérifier son
mode réel dans le `.yml` avant de conclure qu'un tag suffira.

### ⚠️⚠️ Le déclencheur dépend d'un tiers, et un label le met dans la mauvaise file

État relevé le **2026-08-17** (`glab mr view 38458`) :

| Champ | Valeur |
|---|---|
| état | **open** |
| labels | `New App`, **`waiting-on-response`** |
| commentaires | 58 |
| dernière activité | **2026-08-14**, un message **de Patrice** annonçant le pipeline vert sur **1.27.6 (282)** |

🔴 **`waiting-on-response` veut dire « on attend le soumissionnaire »**, pas « on attend le
mainteneur » — c'est `waiting-for-upstream` qui dit le second (Notes Tech `!37885` le porte). Or la
dernière parole est celle de Patrice, et elle répondait au blocage de reproductibilité. **Le label est
donc probablement périmé**, et tant qu'il l'est la MR reste rangée dans la file « rien à faire côté
mainteneur ».

### 🔧 Ce qu'il faut faire pour le changer — vérifié, pas supposé

**Le label est périmé de trois mois.** Historique complet (`resource_label_events`) : **deux** seuls
événements, `New App` **et** `waiting-on-response` ajoutés **le 2026-05-16 par `linsui`**, et
**jamais retirés** — alors que 58 commentaires ont suivi, dont la résolution du blocage de
reproductibilité.

🔴 **Patrice ne peut PAS le retirer lui-même.** Mesuré deux fois, sur le compte qui a **ouvert** la MR :

| Mesure | Résultat |
|---|---|
| `glab api user` | `gitubpatrice`, id **38027370**, identité liée **`provider: github`** |
| `projects/36528/members/all/38027370` | **404** ⇒ pas membre |
| `projects/36528` → `permissions` | **`{project_access: None, group_access: None}`** |

GitLab exige le rôle *Reporter* au minimum pour poser ou retirer un label ; un auteur de MR externe ne
l'a pas.

⚠️ **Se connecter à GitLab « par GitHub » n'y change rien** — question posée le 08-17. C'est une
méthode d'**authentification** ; l'appartenance au projet est une **autorisation**. Le jeton `glab`
porte précisément cette identité GitHub-liée, donc il n'y a pas de second compte mieux doté. Ce qu'un
compte sans rôle peut faire sur un projet public : ouvrir une MR depuis un fork, commenter, éditer ses
propres messages. Pas les labels.

**Le seul levier disponible est un commentaire** demandant le retrait du label. C'est aussi ce qui
fait ressortir la MR dans les balayages de la file `waiting-on-response`.

⚠️ Ne rien poster sans son accord : 58 commentaires et un blocage de reproductibilité tout juste
résolu se relancent avec précaution.

### ⚠️⚠️ Et une conséquence qui change le plan « attendre puis publier »

| Champ du `.yml` | Valeur |
|---|---|
| `AutoUpdateMode` | **None** |
| `UpdateCheckMode` | **None** |
| `CurrentVersion` / `CurrentVersionCode` | 1.27.6 / 282 |

**Un tag ne suffira PAS** une fois la MR fusionnée : chaque release demandera un bump du `.yml` **et
une nouvelle MR**. C'est l'inverse de Notes Tech `!37885`, qui porte `AutoUpdateMode: Version` +
`UpdateCheckMode: Tags` — ne pas transposer le raisonnement de l'une à l'autre.

Raison du `None` : commit `4773ffe7`, *« older tags predate version.properties »*.

## D'où ça vient

Trouvé en portant **Notes Tech** en Kotlin : son bouton « Nouvelle note » écrivait exactement le même
code, et le relevé mécanique de son écran d'accueil l'a signalé. Le portage a aussi révélé, sur
lui-même, un bouton ⋮ qui s'annonçait « Réglages » — le nom d'**une** de ses deux entrées de menu.
SMS Tech, elle, nomme correctement le sien « Plus ».

Détail complet dans `j:\applications\notes_files_tech\docs\04-PIEGES.md` §71 et §73.
