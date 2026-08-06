# ⚠️ CE FICHIER EST INCOMPLET TEL QUEL

**Ne le colle pas dans une interface de discussion.** Il est destiné à un envoi par API, avec le
**diff et les sources concaténés à la suite**. Sans eux, le relecteur n'a rien à lire et rendra zéro
constat — c'est exactement ce qui est arrivé le 2026-08-06 avec un prompt précédent.

Variante pour un relecteur **qui a accès au dépôt** (Codex) : voir la note en fin de fichier.

---

# Relecture ciblée — 2 commits d'interface non relus, avant publication de v1.27.3

SMS Tech, Android/Kotlin/Compose. Application de messagerie contenant une fonction de **sécurité
personnelle** (« Safety Call ») : un *deadman* qui alerte 1 à 4 proches par SMS si l'utilisateur
n'ouvre plus l'application pendant une durée choisie.

Le lot v1.27.3 a déjà été relu deux fois (Codex : 6 constats ; Gemini : 2). **Puis deux commits ont
été ajoutés après ces rapports, et personne ne les a relus.** C'est eux, et eux seuls, que tu relis.

## Pourquoi cette relecture existe

Ces deux commits touchent des **conditions d'affichage**. Or, le même jour, **trois correctifs
successifs dans cette zone ont chacun ouvert un autre défaut** :

1. Un correctif a élargi la condition du récapitulatif à `enabled || isTriggered`. Le composant
   concerné s'appelle `SafetyCallArmedRecap` et affiche une puce **« Activé »** en couleur primaire.
   Résultat : avec `enabled = false, isTriggered = true`, les Réglages annonçaient **« Activé » sur
   une protection éteinte**. L'utilisateur l'a trouvé sur son téléphone en quelques minutes.
   `5146dc6` est le retour arrière.
2. Un autre a laissé la décision d'activation sur un instantané figé à l'ouverture de l'écran :
   enregistrer pendant une séquence pouvait persister `enabled = true` avec un état terminal encore
   posé, donc **aucune alarme programmée** alors que l'interface confirmait l'activation.
3. Un troisième n'appelait pas `setWhen()` : la notification se **réhorodatait** à chaque
   republication et a été prise deux fois pour un nouveau déclenchement.

Le point commun des trois : **une garde posée sur l'affichage plutôt que sur l'état réel**, dans du
code dont le nom ou le libellé affirme déjà un état. C'est là qu'il faut chercher.

⚠️ **Aucun test de ce dépôt ne regarde l'interface.** 470 tests unitaires passent, et n'ont vu aucun
de ces trois défauts. Ne te repose pas sur eux.

## Le modèle d'état, pour raisonner sur les quatre cas

`SafetyCallConfig` porte notamment :
- `enabled` : la protection est armée ;
- `triggeredAt` (> 0 ⇒ `isTriggered`) : une séquence est partie ;
- `messagesDelivered` : envois **conclus** (jamais les créneaux réservés).

Le désarmement de fin de séquence est écrit dans la transaction du dernier envoi : à la fin d'une
séquence menée à terme, l'état est donc **`enabled = false` ET `isTriggered = true`**. Les quatre
combinaisons existent réellement :

| `enabled` | `isTriggered` | Situation |
|---|---|---|
| faux | faux | jamais utilisé, ou remis à zéro |
| **vrai** | faux | armé, en attente |
| **vrai** | **vrai** | séquence en cours |
| **faux** | **vrai** | **séquence terminée — la protection est TOMBÉE** |

La quatrième ligne est celle qui a produit le défaut. **Vérifie que chaque affichage dit la vérité
dans les quatre cas**, et n'en oublie aucun.

## Ce qui est délibéré — ne pas le signaler

- Le récapitulatif ne s'affiche **que** si `enabled`. L'information « une alerte est allée au bout »
  vit dans la branche désactivée. C'est le correctif, pas un oubli.
- Le bandeau d'avertissement est **orange vif** (`BrandWarning`, alias de `BrandBlocked`
  `0xFFE65100`) avec un texte blanc. Alias volontaire : dupliquer le littéral aurait créé un doublon.
- Le mode leurre masque le Safety Call par l'**ACCÈS** (route non composée) et non par l'affichage.
- Pas de constante de version à synchroniser : `versionName` dans `app/build.gradle.kts` est la seule
  source, et `versionCode` se calcule depuis le nombre de commits.

## Ce que je te demande de chercher

### D1 — La garde porte-t-elle sur l'ACCÈS ou seulement sur l'AFFICHAGE ?
Pour chaque nouvelle condition, demande-toi ce qui se passe dans les **quatre** états du tableau, et
si un libellé pourrait affirmer un état que la donnée contredit.

### D2 — Le jumeau asymétrique
Deux branches censées dire la même chose dont une seule a été traitée : branche activée / branche
désactivée, écran de configuration / page principale des Réglages, français / anglais.

### D3 — Le texte dit-il la vérité ?
Le bandeau annonce que le minuteur se réinitialise. **Est-ce exact et complet ?** Le fait établi :
la remise à zéro exige d'**ouvrir ET déverrouiller** l'application (garde `RESUMED` + état
déverrouillé), et se produit **uniquement si la protection est active**. Utiliser le téléphone pour
autre chose ne compte pas. Un texte qui laisserait croire qu'il ne faut plus toucher au téléphone du
tout serait un défaut sur une fonction de sécurité.

### D4 — Accessibilité et contraste
Texte blanc sur `0xFFE65100`. J'affirme 4,87:1, au-dessus du seuil AA pour du texte normal.
**Vérifie ce calcul** plutôt que de me croire. Regarde aussi les opacités appliquées au texte.

### D5 — Parité des chaînes
Trois clés ajoutées de chaque côté. Vérifie qu'aucune n'est orpheline, qu'aucune référence n'est
cassée, et que les apostrophes sont échappées côté français (`\'` obligatoire dans `strings.xml`).

### D6 — Le bump de version
`versionName` passe à `1.27.3`. Vérifie qu'aucune autre surface du diff n'annonce encore l'ancienne.

## Forme de ta réponse

Pour **chaque** constat : **fichier et ligne**, le **scénario d'échec concret** (quel état, quel
affichage faux en résulte), la **gravité**, le **correctif minimal**, et le **statut** `CONFIRMÉ` ou
`À VÉRIFIER`.

Si tu ne peux pas écrire le scénario d'échec, ce n'est pas un constat — ne l'écris pas.

**N'invente rien pour remplir.** « Aucun défaut sur ce motif » est une réponse utile et attendue. Un
rapport de deux constats vrais vaut mieux qu'un rapport de quinze. Aucun renommage, aucune
réorganisation, aucun changement de style, aucune dépendance nouvelle.

---

## Note pour Codex (accès au dépôt)

Le delta à relire est `git diff 3a6beb1..5146dc6` sur la branche
`fix/safety-call-etat-terminal-historique`. Tu peux ouvrir les fichiers entiers, remonter les
appelants, et lire `SafetyCallArmedRecap` (`SettingsScreen.kt:2493`) ainsi que le bloc Safety Call de
la page principale (`SettingsScreen.kt:450`). Ne relis pas le reste du dépôt : il a déjà été audité.

---

## DIFF ET SOURCES

(concaténés ci-dessous au moment de l'appel)
