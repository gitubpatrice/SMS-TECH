# SMS Tech — idées à instruire

Registre des idées retenues mais **pas encore engagées**. Une idée n'entre ici que si elle a été
discutée et jugée utile ; ce n'est pas une liste de souhaits.

Chaque entrée porte : la **date** de la décision, un **état**, le **pourquoi mesuré** (pas une
intuition — un fait constaté), les **conditions** de mise en œuvre, et ce qui la **bloque**.

États : `à instruire` · `engagée` · `livrée` · `écartée` (avec le motif — une idée écartée reste
ici, pour ne pas être re-proposée).

---

## 1. Journal technique du moteur Safety Call

- **Date** : 2026-08-06
- **État** : `engagée` — première moitié écrite sur la branche `feat/safety-call-journal`
  (types purs, format, réduction des destinataires, rotation, écrivain de fichier, tests). **Non
  câblée** : points d'appel dans le moteur, réglage opt-in auto-expirant, destruction à l'effacement
  panique et action de partage restent à faire, et demandent une relecture.
- **Portée** : ajout, aucune modification du moteur existant

### Deux défauts trouvés en relisant mon propre code — à ne pas réintroduire

**1. Une garde qui ne converge pas coûte à chaque passage sans rien régler.** L'élagage se déclenche
quand le fichier dépasse un seuil en **octets** (32 Ko), mais il était borné en **nombre de lignes**
(2 000). Le champ `détails` étant de longueur libre, aucun nombre de lignes ne borne une taille : 2 000
lignes pèsent ~200 Ko. Sur un cycle pathologique — une boucle de réveils, exactement ce que le journal
existe pour diagnostiquer — le fichier restait donc **en permanence au-dessus de son propre seuil**, et
chaque écriture relisait puis réécrivait tout, sur le chemin du worker qui envoie les SMS. Corrigé par
un plafond en **octets UTF-8 réels**, nettement sous le seuil de déclenchement. La règle générale :
**une garde doit ramener l'état sous la condition qui l'a déclenchée**, sinon elle se rappelle
indéfiniment.

**2. Un test dont la terminaison dépend de la propriété qu'il vérifie ne la vérifie pas.** Deux tests
remplissaient le journal par `while (fichier.length() <= SEUIL)`. Ils ne se terminaient que parce que
l'élagage **ne** faisait **pas** redescendre le fichier — c'est-à-dire grâce au défaut ci-dessus. Une
fois celui-ci corrigé, ils auraient tourné sans fin. Remplacés par un nombre d'écritures **borné**,
avec une assertion vérifiant que le seuil a bel et bien été franchi — sans quoi le test serait vert sur
un chemin mort.

Ces deux défauts appartiennent à la même famille que le piège décrit plus haut : **un dispositif de
surveillance dont le silence ressemble à un résultat**. Le même jour, une surveillance `adb` cassée est
restée muette 90 minutes et son silence a d'abord été lu comme « aucune relance n'est arrivée », alors
que quatre étaient parties. C'est la raison d'être de `HEARTBEAT`.

### Le défaut trouvé par la relecture externe (Gemini, 2026-08-06) — 🔴 majeur, corrigé

**Réutiliser « la clé canonique » ne suffit pas : il faut celle qui répond à la MÊME question.**

La réduction des destinataires normalisait par `blockKey()`, en se félicitant de ne pas dupliquer la
normalisation du dépôt. Mais `blockKey()` est la clé canonique de la **liste noire** — « ai-je déjà vu
cet expéditeur ? » — et elle ne retient que les **neuf derniers chiffres**, qui ne portent aucune
information de pays. Le dépôt documente lui-même la collision :

```
"+33 6 12 34 56 78"  →  612345678
"+1 561 234 5678"    →  612345678     ← MÊME CLÉ, DEUX PERSONNES
```

Sur un journal dont la raison d'être est de prouver qu'**aucun proche n'a reçu deux fois la même
relance**, deux destinataires distincts partageant un jeton se lisent comme un doublon : le journal
aurait produit le contresens exact qu'il existe pour écarter. Et hors du plan français, deux écritures
du même numéro se seraient scindées en deux jetons.

La bonne fonction existait déjà : **`phoneIdentityKey(raw, toE164)`**, introduite en v1.27.2 sur un
constat Codex pour répondre à « deux écritures brutes désignent-elles le même correspondant ? ». Égalité
**E.164**, repli fermé. Corrigé, et la signature de `redact` prend désormais le résolveur en paramètre,
comme `phoneIdentityKey` et `phoneAddressesMatch`.

⚠️ **Mes tests ne pouvaient pas le voir** : ils n'utilisaient que des numéros **français**, dont le
numéro national significatif fait justement neuf chiffres. Le défaut était hors de leur portée. Un test
vert sur un chemin mort, une fois de plus. La paire de non-régression est maintenant explicite —
`+33612345678` / `+15612345678`, identiques sous `blockKey()` jusqu'aux deux chiffres de queue.

**Score de cette relecture : 1 constat, 1 vrai, `CONFIRMÉ`.** À comparer aux 7 recevables sur 20 du
2026-08-04 : des directives fermant explicitement les portes des faux positifs connus ont changé le
résultat.

### Pourquoi — le moteur est invisible en release

R8 supprime Timber **et** `android.util.Log` en release, et les builds installés sur les téléphones
de test sont des builds **release**. Le moteur Safety Call est donc le sous-système le moins
observable du dépôt — et il l'est *par conception* : rien ne doit s'afficher entre l'armement et la
fenêtre d'avertissement, sans quoi le dispositif se révélerait à qui prend le téléphone en main.

Coût constaté, deux fois en deux jours :

- **2026-08-05** — il a fallu deux corrections successives pour comprendre que « 7 messages envoyés »
  correspondait en réalité à **trois séquences distinctes** (14:48, 16:49+17:06, 23:53→00:49), et non
  à une séquence qui aurait trop envoyé.
- **2026-08-06** — toute la chronologie d'un test a dû être reconstituée à la main depuis
  `dumpsys alarm` et le fournisseur SMS, faute de trace côté application.

Un journal aurait répondu en une ligne dans les deux cas.

### Le principe qui le rend fiable — écrire l'attente, pas seulement l'événement

**Un journal d'événements serait muet exactement là où nos défauts vivent.** Aucun des bugs réels de
ce moteur n'était « l'application a fait la mauvaise chose » ; c'était toujours « l'application n'a
rien fait » : le deadman qui se désarmait avant d'envoyer, l'alarme jamais programmée, `stateIn`
jamais hydraté. Un journal qui ne note que ce qui arrive n'écrit **rien** dans ces cas-là, et une
absence de ligne se lit comme du repos.

D'où deux règles de conception, et ce sont elles qui font la valeur de l'idée :

1. **Chaque cycle déclare ce qu'il attend ensuite.** Une ligne `NEXT` dit « prochain réveil à
   13:44:34, échéance, génération 7 ». Si aucune ligne ne suit ce rendez-vous, **l'absence devient
   une contradiction lisible** au lieu d'un silence. C'est la transposition d'une leçon déjà payée :
   le silence n'est pas une preuve de bon fonctionnement.
2. **Battement de cœur tant que le dispositif est armé.** Une ligne `HEARTBEAT` à chaque réveil,
   même quand rien n'est dû. Un trou dans les battements dit « le moteur a cessé d'être programmé »
   — la seule façon de distinguer *armé et vivant* de *armé et mort*, qui sont aujourd'hui
   indiscernables de l'extérieur.

### Ce qu'il enregistre — et que rien ne capture aujourd'hui

- **Réveil** : heure nominale, heure réelle, **retard mesuré**, et l'état de la plateforme au moment
  du réveil (Doze, économiseur de batterie). C'est ce qui départage « Android a différé l'alarme »
  de « l'application a calculé la mauvaise échéance » — l'ambiguïté exacte rencontrée le 06/08, où
  il a fallu lire `dumpsys alarm` pour trancher.
- **Les deux horloges sur chaque ligne** : `System.currentTimeMillis()` **et**
  `SystemClock.elapsedRealtime()`. Le moteur est un deadman à double horloge ; un journal qui n'en
  note qu'une ne permet pas de rejouer sa décision.
- **`generation` et `claimId` sur chaque ligne.** Le 05/08, comprendre que « 7 messages » était
  **trois séquences distinctes** et non une séquence trop bavarde a coûté deux corrections. Avec ces
  deux colonnes, la question se répond en triant.
- **Résultat par destinataire**, ce que le moteur ne garde pas : `sendToContacts` ne conserve qu'un
  total envoyés/échoués — raison pour laquelle l'historique utilisateur ne peut écrire que
  « Adressé à ». Le journal est le bon endroit pour ce détail, parce qu'il est un outil de
  diagnostic et non une promesse faite à l'utilisateur. **C'est aussi la seule façon de prouver
  « aucun doublon chez le même contact »** — précisément l'objet du test du 06/08, qui a exigé de
  relever à la main les boîtes SMS de deux téléphones. Le journal rendrait ce test
  auto-vérifiable.
- **Renouvellement de bail entre deux contacts** — le chemin qui, s'il fautait, produirait ce
  doublon.
- **Échec d'envoi**, avec son motif.
- **Remise à zéro du minuteur et sa cause** : ouverture réelle de l'application, bouton « Je vais
  bien », action de notification. Aujourd'hui la cause est indevinable après coup.

### Format — une ligne, sept champs, greppable

```
wallMs | elapsedMs | gen | claim | ÉVÉNEMENT | sujet | détails
```

```
1786015774123|53780340|g7|c142|WAKE     |warning  |nominal=13:29:34 retard=+5m48s doze=1 saver=0
1786015774456|53780340|g7|c142|NEXT     |deadline |at=13:44:34
1786016674000|54680340|g7|c142|SEND     |1/2      |to=a3f1‥29 ok
1786016675200|54681340|g7|c142|SEND     |2/2      |to=7b02‥17 fail=GENERIC_FAILURE
1786016676100|54682340|g7|c142|LEASE    |renew    |held=1s
1786016680000|54686340|g7|c142|NEXT     |relance1 |at=14:00:12
1786017600000|55606340|g7|c142|HEARTBEAT|armed    |restant=44m
```

Une seule ligne par fait, largeur fixe pour les deux premiers champs : lisible à l'œil, triable,
`grep`-able par génération, et **parseable par un test** — le journal devient alors un contrat
vérifiable sur le récit du moteur, pas seulement une aide humaine.

### Conditions — non négociables

1. **Opt-in strict, désactivé par défaut**, comme tout le reste de Safety Call.
2. **Auto-expirant plutôt que permanent** : proposer « journaliser les 24 prochaines heures » et non
   un interrupteur qu'on oublie d'éteindre. Plus intuitif à l'usage, et surtout : pas de trace qui
   grossit indéfiniment sur une fonction de contrainte.
3. Dans `filesDir`, **jamais** en stockage partagé.
4. **Borné en nombre de cycles entiers, pas seulement en octets.** Une rotation à l'octet coupe au
   milieu d'une séquence et détruit la corrélation qu'on vient chercher. Le fichier doit être « les
   5 dernières séquences, complètes », pas « les 200 derniers Ko, coupés n'importe où ».
5. Écrit **au mieux, après coup** — **jamais** dans la transaction d'envoi. La séquence tourne dans
   un worker en Doze et son unique mission est que les SMS partent ; une écriture de fichier qui
   peut échouer n'a rien à faire sur ce chemin. Toute exception d'écriture est avalée.
6. **Aucun contenu de message.** Destinataires réduits à **deux derniers chiffres + préfixe d'une
   empreinte salée propre à l'installation** — pas une simple troncature : il faut pouvoir répondre
   « est-ce le même contact que la ligne du dessus ? » (donc détecter le doublon) **sans** que le
   numéro soit lisible. Une troncature seule collisionne ; une empreinte salée donne l'identité sans
   la divulgation.
7. **L'effacement panique doit détruire le journal.** Un effacement de contrainte qui laisserait
   derrière lui une trace nommant les contacts annulerait sa propre raison d'être. De même, en mode
   leurre, **ni le journal ni son action de partage ne doivent être visibles**.
8. Une action **« Partager le journal »** explicite — jamais d'envoi automatique. **Nom de fichier
   neutre** à l'export (`smstech-diagnostic-<date>.txt`), qui ne nomme pas Safety Call : une fois
   sorti du bac à sable, le nom du fichier est la première chose que voit un tiers.

### Non-objectifs, à écrire noir sur blanc dans le code

- **Le journal n'est jamais lu par l'interface.** L'historique utilisateur reste la seule source de
  ce que l'écran affiche. Faire lire le journal à un écran recréerait le doublon corrigé le 06/08.
- Il ne remplace pas les tests : il explique un comportement observé sur appareil, là où le gate ne
  voit rien.

### Ce qui a été écarté au passage — et pourquoi

L'idée de départ était de sortir **l'historique utilisateur** dans un fichier
`safety-call-historique.logs`. Écarté :

- **Deux sources de vérité.** L'écran lirait le fichier ou DataStore ? C'est le doublon que la
  doctrine interdit — et c'est exactement le motif qui a produit le défaut F-02 relevé le même jour :
  deux chemins censés dire la même chose, un seul mis à jour.
- **Aucune durabilité gagnée** : même bac à sable applicatif, même désinstallation.
- **Le sortir du bac à sable serait une régression de sécurité** : un journal en clair, lisible par
  n'importe quelle autre application, sur une fonction de contrainte.
- Le plafond de 10 entrées est une **constante** (`SafetyCallConfig.MAX_HISTORY`), pas un problème de
  support de stockage.

L'historique utilisateur reste donc dans DataStore, clé `security.safetyCall.history`.

### À savoir avant d'implémenter

`SettingsRepository` écrit les réglages **en clair** dans le protobuf DataStore — y compris
`security.safetyCall.contactsJson` et `security.safetyCall.history`. Le seul `encrypt` du fichier
concerne les sauvegardes. Seule la base Room est chiffrée (SQLCipher). Le journal ne dégraderait donc
pas la confidentialité **à contenu et emplacement égaux** — mais il ne doit pas pour autant
l'aggraver, d'où la condition 4.
