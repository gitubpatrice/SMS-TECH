# SMS Tech — Modèle de menace opérationnel

> **Ce document ne remplace pas `SECURITY.md`.** Celui-ci catalogue *adversaire → protection →
> comment* : il répond à « contre quoi l'app défend ». Le présent document répond à une question
> différente et complémentaire : **où est la garde qui fait autorité, et par quels chemins
> doit-elle passer.**
>
> Origine : l'audit de haut niveau du 2026-08-03 (53 constats, dont 4 critiques). Un seul motif
> explique trois des quatre critiques. Ce document existe pour éliminer le motif, pas les
> symptômes.

---

## 1. Le motif à éliminer

**La garde était posée sur l'AFFICHAGE, pas sur l'ACCÈS.**

Une ligne masquée dans un écran, mais la fonction qu'elle déclenche restant atteignable par un
autre chemin. Quatre exemples réels, tous confirmés :

| Constat | Ce qui était masqué | Ce qui restait atteignable |
|---|---|---|
| C1 | La ligne « Verrouillage de l'app » | `clearPin()`, qui effaçait le verrou **et** le code panique |
| C2 | La section « Sauvegarde » | `BackupService.writeSmsbk`, qui exportait le coffre entier |
| C4 | Rien — l'écran À propos n'était pas gardé | La description écrite du coffre, du code panique et du mode urgence |
| H1 | L'écran Coffre | Trois chemins de données ouvrant un fil du coffre sans second facteur |

**Le corollaire est l'ASYMÉTRIE** : deux chemins jumeaux dont un seul porte la garde.

| Constat | Chemin gardé | Jumeau oublié |
|---|---|---|
| C3 | `setPanicCode` refusait un code égal au PIN | `setPin` ne vérifiait rien → enfermement définitif en leurre |
| H4 | 9 chiffres à la réception | 8 chiffres à la synchronisation → perte silencieuse et définitive |
| H5 | Liste noire sur les SMS entrants | MMS entrants non filtrés |
| H7 | Anti-double-envoi sur le chemin vocal | Chemins texte et média sans garde |
| H10 | `EmergencyShortcutReceiver` sur `ApplicationScope` | Déclenchement UI sur `viewModelScope`, annulable |

### La règle qui en découle

> **Une garde d'affichage n'est jamais une garde.** Elle est un confort d'ergonomie et, au mieux,
> une défense en profondeur. La garde qui compte est celle posée sur le point d'accès à la
> donnée ou à l'effet.
>
> **Une énumération d'écrans vieillit ; un prédicat non.** Interroger le prédicat qui fait
> autorité, ne jamais réénumérer les états ou les routes.

---

## 2. Actifs protégés, par gravité décroissante

| # | Actif | Pourquoi il est en tête |
|---|---|---|
| **A1** | **L'existence** du coffre, du code panique et du mode urgence | Sous coercition, révéler qu'ils existent suffit à mettre l'utilisatrice en danger, même sans en révéler le contenu. C'est une métadonnée, et c'est l'actif le plus critique. |
| **A2** | Le contenu des conversations du coffre | Protégé par un second facteur distinct du verrou d'application. |
| **A3** | Le carnet d'urgence et la position GPS | Leur divulgation expose des tiers, pas seulement l'utilisatrice. |
| **A4** | Le contenu des conversations en clair | Protégé au repos par SQLCipher et par le verrou d'application. |
| **A5** | Les secrets en mémoire (PIN saisi, clés, phrases secrètes) | Leur durée de vie en RAM est une surface d'attaque forensique. |

---

## 3. Adversaires

| # | Adversaire | Capacités | Ce qui doit tenir |
|---|---|---|---|
| **Ad1** | Possession de l'appareil, PIN de l'app inconnu | Manipulation physique, redémarrage, changement d'horloge | A2, A3, A4 |
| **Ad2** | **Un proche qui connaît le PIN de l'app** | Session normale complète, temps illimité, connaît l'utilisatrice | **A1 partiellement, A2** |
| **Ad3** | **Coercition** — l'utilisatrice est forcée d'ouvrir | Observe l'écran, peut fouiller les réglages, peut exiger des explications | **A1 avant tout**, puis A2, A3 |
| **Ad4** | Application tierce sur le même appareil | Intents, superposition d'écran, presse-papier, fournisseurs de contenu | A2, A4, A5 |
| **Ad5** | Analyse forensique hors ligne | Image disque, `adb pull`, extraction | A2, A4, A5 |

**Ad2 et Ad3 sont les adversaires structurants.** Ce sont eux qui justifient l'existence du coffre
et du mode leurre, et c'est contre eux que tous les défauts critiques de l'audit portaient.

> ⚠️ **Ad2 n'est pas Ad3.** En session **normale**, l'existence du coffre **n'est pas un secret** :
> l'icône est visible, l'écran est accessible. Ce que le second facteur protège face à Ad2, c'est
> **le contenu et la liste des correspondants**. En session **leurre**, c'est l'existence même qui
> doit disparaître. Confondre les deux conduit à sur-protéger l'un et sous-protéger l'autre.

---

## 4. Invariants — le cœur du document

Chaque invariant nomme **un prédicat qui fait autorité** et **la couche où il doit être appliqué**.
Toute modification qui touche un chemin listé doit démontrer que l'invariant tient encore.

### I1 — En session leurre, aucune surface ne révèle A1

*Prédicat d'autorité :* `AppLockManager.LockState.PanicDecoy`
*Couche d'application :* **données** (autorité) + navigation (refus) + UI (masquage)

Surfaces à couvrir, **toutes** : liste des conversations (icône coffre), réglages (verrouillage,
code panique, verrouillage du coffre en quittant, PIN coffre, sauvegarde, urgence, Safety call),
écran À propos (**y compris le texte descriptif**), notifications, envois programmés, sauvegarde.

⚠️ `isPanicDecoy` est passé **sans valeur par défaut** aux composables concernés. Un défaut `false`
serait un repli permissif : l'écran nommerait le coffre tant que l'état n'est pas transmis.

### I2 — Le contenu du coffre n'est lisible qu'après le second facteur

*Prédicat d'autorité :* `VaultSessionState.unlocked`
*Couche d'application :* **données** — les flux masquent sur `inVault && (PanicDecoy || !vaultOpen)`

⚠️ Le porteur d'état est **sans dépendance** délibérément : injecter `VaultManager` dans le
repository fermerait un cycle Hilt, et un `AtomicBoolean` non observable ne ferait jamais
réévaluer un flux Compose.

⚠️ **Limite connue et assumée** : `requestMoveToVault` ouvre encore la session du coffre sans
second facteur. Déplacer une conversation vers le coffre suffit donc à l'ouvrir. Sémantique
inchangée depuis l'origine, **mais elle limite la portée de I2**. À trancher.

### I3 — Aucun état ne peut enfermer l'utilisatrice définitivement

*Points d'autorité :* `setPin`, `setPanicCode`, `refreshLockoutIfExpired`

Le refus « code panique = PIN » doit exister **dans les deux sens**. Une temporisation doit
toujours pouvoir se lever : une boucle de décompte sort sur le **verdict**, jamais sur l'heure.

⚠️ **L'horloge murale reste l'autorité pour le verrou anti-force-brute**, délibérément. Faire de
l'horloge monotone l'autorité inverse la faille : après un redémarrage, reculer l'horloge
libérerait le blocage. Le défaut inverse — une temporisation trop longue après un saut d'horloge —
est auto-résolutif. **Ne pas « corriger » ce point sans relire ce paragraphe.**

### I4 — Un secret est effacé sur *tous* les chemins de sortie

*Couche :* consommateurs de `CharArray` / `SecretBytes`

Enveloppe `try/finally`, jamais un effacement en fin de corps : une fonction à retours anticipés
multiples en laisserait fuir. Le chemin de **refus** compte autant que le chemin nominal.

### I5 — Un repli échoue vers le plus restrictif

Un `getOrDefault(false)` sur une garde anti-divulgation est un défaut, pas un repli.
Un repli de lecture de réglages doit signifier « **pas de nouvelle valeur** », jamais « valeurs par
défaut » — sinon une corruption désarme silencieusement une protection que l'utilisatrice croit
armée.

⚠️ **Jamais `runCatching` autour d'un appel suspend dans un job annulable** : il avale
`CancellationException` et transforme une annulation normale en échec, donc en application du
« chemin le plus strict » au pire moment.

### I6 — Deux chemins jumeaux portent la même garde

*Procédure :* avant de corriger un chemin, **recenser tous ses jumeaux** — réception ↔ synchro,
SMS ↔ MMS, texte ↔ média ↔ vocal, UI ↔ receveur, unitaire ↔ masse.

C'est l'invariant le plus violé de l'audit : cinq constats sur les vingt-deux les plus graves.

### I7 — Ce qui sort de l'app sort du périmètre du coffre

Presse-papier, partage, export PDF, notifications, écriture dans `content://sms`.

*Point d'autorité pour le presse-papier :* `copyToClipboardSensitive` — **jamais**
`LocalClipboardManager`, qui n'expose pas `EXTRA_IS_SENSITIVE`.

⚠️ Le marquage est **uniforme**, non conditionné à l'appartenance au coffre. Le conditionner
obligerait à propager « ce message est au coffre » jusqu'à chaque bouton copier : un drapeau de
plus à ne pas oublier sur chaque nouveau chemin, c'est-à-dire la fabrique d'asymétries décrite en
§1. Un prédicat uniforme ne s'oublie pas sur une branche.

⚠️ **L'écriture dans `content://sms` reste hors de portée** — voir N2.

---

## 5. La règle des trois couches

| Couche | Rôle | Statut |
|---|---|---|
| **Données** | Filtre la donnée à la source | **Autorité.** Seule couche dont la défaillance est une faille. |
| **Navigation** | Refuse la route | Défense en profondeur |
| **UI** | Masque la commande | Confort + défense en profondeur |

**Une garantie qui ne repose que sur les couches 2 et 3 n'est pas une garantie.**
Corollaire : un correctif qui n'ajoute qu'un masquage d'UI ne ferme pas un constat de sécurité.

---

## 6. Non-garanties explicites

Ce que l'app **ne protège pas**. À maintenir honnête : une affirmation faussement rassurante
détourne le prochain auditeur d'un contrôle qui n'existe pas.

| # | Non-garantie | État |
|---|---|---|
| N1 | **Le transport SMS / MMS est en clair.** Aucune app ne peut y remédier. | Par conception |
| N2 | **En tant qu'app SMS par défaut, l'app écrit dans `content://sms`** — lisible par toute app disposant de `READ_SMS`. Le chiffrement Room ne couvre pas cette copie. | Par conception |
| N3 | **Superposition d'écran (tapjacking).** [`ProtectSecretInput`](app/src/main/java/com/filestech/sms/ui/security/OverlayGuard.kt) sur les **six** surfaces de saisie de secret recensées. ⚠️ Portée **déclarée par la surface** : toute nouvelle saisie de secret doit l'appeler. ⚠️ Compte **par fenêtre** — cinq des six surfaces sont des dialogues, qui ont chacun la leur ; un compteur global les laisserait toutes découvertes. Voir N3-bis pour l'écran de verrouillage. | ✅ v1.27.0 |
| N3-bis | **L'écran de verrouillage n'applique PAS `filterTouchesWhenObscured`** (`blockObscuredTouches = false`). Ce filtre fait *ignorer* les touches sous une superposition, **fût-elle légitime** (filtre de lumière bleue tiers, outil d'accessibilité) : sur le verrou d'une app détenant le rôle SMS, ce serait un utilisateur enfermé hors de sa messagerie, sans message ni recours. Il conserve `setHideOverlayWindows`, qui masque sans bloquer. ⚠️ **Conséquence assumée : sous Android 12, le verrou n'a aucune protection anti-superposition.** Asymétrie **délibérée** — ne pas l'« aligner » sur les dialogues. | ⚠️ Arbitré 2026-08-03 |
| N4 | **Presse-papier.** [`copyToClipboardSensitive`](app/src/main/java/com/filestech/sms/ui/security/SensitiveClipboard.kt) pose `EXTRA_IS_SENSITIVE` sur **toutes** les copies — uniforme et non conditionné au coffre, pour qu'aucune branche ne puisse l'oublier. ⚠️ Sans effet avant Android 13, et un clavier tiers reste libre de l'ignorer. | ✅ v1.27.0 |
| N5 | Un appareil **rooté** met tous les invariants hors de portée. | Par conception |
| N6 | La whitelist `EmergencyCallHelper` et le PIN du coffre sont **sans test automatisé**. | ⚠️ Assumé, écrit dans les fichiers concernés |

---

## 7. Procédure de vérification

Un audit qui se contente de lire le code linéairement rate ces défauts — c'est établi
empiriquement. La procédure qui les trouve :

1. **Partir du motif, pas du fichier.** Pour chaque invariant, chercher les chemins qui
   *devraient* passer par le prédicat d'autorité et ne le font pas.
2. **Recenser TOUS les appelants avant de corriger.** Deux chemins corrigés puis conclusion hâtive
   = régression. Constaté plusieurs fois.
3. **Se méfier du vert.** Un test peut passer sur un chemin qu'aucun appelant n'emprunte ; une
   ressource traduite peut n'avoir aucun référent. Un build vert ne prouve pas l'exécution — lire
   le rapport XML.
4. **Relancer une revue sur les correctifs eux-mêmes.** Sur l'audit du 2026-08-03, cette règle a
   trouvé **douze** défauts dans les correctifs, dont une régression déjà installée sur appareil.
5. **Vérifier sur appareil.** Ce sont les tests sur appareil qui trouvent les vrais défauts, pas
   les audits statiques.

---

## 8. Points ouverts

- **I2 limité** par `requestMoveToVault` — décision de conception à prendre.
- **Dédoublonnage de la synchronisation** : `TelephonySyncManager` ne dédoublonne que par
  `telephony_uri`, et le sweep re-propose chaque SMS reçu en direct (le receveur n'avance pas le
  curseur). La protection tient parce que le fournisseur système est conforme, **pas par
  construction**. `insertInboxSms` propage un `null` sans repli, et une ligne Room sans URI reste
  indédoublonnable à vie. Mesuré le 2026-08-03 sur Galaxy S9 : aucun doublon observé. Mécanisme
  théorique, défaut non démontré.
- **La sauvegarde** est gardée contre le leurre mais **pas contre le coffre verrouillé**.

---

## Journal des révisions

| Date | Révision |
|---|---|
| 2026-08-03 | Création, après l'audit de haut niveau (53 constats) et la release v1.27.0. |
