# ⚠️ CE FICHIER EST INCOMPLET TEL QUEL

**Ne le colle pas dans une interface de discussion.** Il est écrit pour être envoyé par API, avec les
**sources concaténées à la suite** au moment de l'appel. Sans elles, le relecteur n'a rien à lire et
rendra zéro constat — c'est exactement ce qui est arrivé le 2026-08-06 avec le prompt précédent.

Pour un relecteur qui a accès au dépôt, utiliser plutôt un prompt qui le dit.

---

# Relecture — journal technique du moteur Safety Call (SMS Tech, Android/Kotlin)

Tu relis **du code neuf, non encore câblé**, destiné à une application Android de sécurité
personnelle. Ton objectif est de trouver des **défauts qui produiraient un comportement faux**, pas
de proposer des améliorations de style.

## Le contexte, à lire avant de juger

SMS Tech contient un « Safety Call » : un *deadman*. Si l'utilisateur n'ouvre plus l'application
pendant une durée qu'il a choisie, l'application envoie automatiquement une alerte à 1 à 4 proches,
puis trois relances. Cette fonction sert à des personnes isolées ou en danger.

Deux propriétés du système encadrent tout ce que tu vas lire :

1. **Le moteur est invisible en release.** R8 supprime `Timber` et `android.util.Log`. Il n'existe
   donc aucune trace de ce que le moteur fait sur l'appareil de l'utilisateur. Le journal que tu relis
   existe pour combler ce trou.
2. **Il y a un mode leurre** (« panic decoy ») : sous contrainte, l'application doit pouvoir se
   présenter comme n'ayant aucune fonction de sécurité. Toute trace qui *nomme* le dispositif annule
   ce leurre.

## Ce qui est délibéré — ne pas le signaler comme défaut

Ces choix sont documentés et assumés. Les signaler serait un faux positif :

- **Le code n'a aucun appelant.** Le câblage dans le moteur, le réglage d'activation, la destruction
  à l'effacement panique et l'action de partage sont la seconde moitié du travail, pas encore écrite.
  « Code mort » / « jamais appelé » n'est pas un constat recevable ici.
- **Les échecs d'écriture sont avalés en silence, sans journalisation.** C'est le contrat : ce code
  est appelé depuis le worker qui envoie les SMS, et il ne doit jamais faire échouer son appelant.
  Journaliser via `Timber` serait circulaire, puisque R8 le supprime en release.
- **Le format est du texte séparé par des barres verticales, pas du JSON.** Choix cohérent avec les
  autres codecs du dépôt : pas de dépendance Android-only, schéma fermé, débuggable tel quel.
- **L'empreinte ne fait que 16 bits.** Assumé et chiffré dans la documentation : le sel n'est pas
  exporté, et sans lui le jeton n'est pas inversible.
- **Les deux derniers chiffres du numéro sont conservés en clair.** Délibéré, pour que le journal
  reste lisible à l'œil.
- **Le fichier peut contenir plus de cycles que `MAX_CYCLES` entre deux élagages.** Documenté :
  `BYTE_TRIGGER` est le déclencheur, pas la règle de conservation.

## Ce que je te demande de chercher, par ordre de valeur

Cherche par **motif de défaut**. Ce dépôt a un historique : quatre familles ont produit la totalité
de ses vrais bugs.

### D1 — Le repli qui échoue du mauvais côté

Pour chaque chemin d'erreur ou de donnée manquante, demande-toi : **dans quel sens échoue-t-il ?**
Un repli qui, faute de sel, écrirait un numéro en clair serait un accident grave sur une fonction de
contrainte. Un repli qui rend un journal vide est bénin. Vérifie chaque cas.

### D2 — Le jumeau asymétrique

Deux chemins censés dire la même chose dont un seul a été traité. Ici : mise en forme et relecture
d'une ligne, écriture et élagage, `read()` et `prune()`. Une asymétrie entre eux produit une donnée
fausse plutôt qu'une absence de donnée.

### D3 — La garde qui porte sur le mauvais objet

Une validation posée sur l'affichage plutôt que sur l'accès, ou sur une valeur déjà échantillonnée
plutôt qu'à l'instant de l'usage.

### D4 — Le test vert sur un chemin mort

Un test qui passe sans exercer ce qu'il prétend couvrir : seuil jamais franchi, branche jamais prise,
assertion qui reflète l'implémentation au lieu de la spécifier. Regarde les tests avec le même œil
que le code : **un test qui ne teste pas est le pire des défauts**, parce qu'il rassure.

### D5 — Décalage de champs et lignes fabriquées

Le format est positionnel. Un séparateur ou un saut de ligne qui passerait dans un champ libre ne
casse pas seulement sa ligne : il en fabrique **une seconde, syntaxiquement valide et fausse**. Sur
un journal censé établir qu'aucun proche n'a reçu deux fois la même relance, une ligne inventée est
pire qu'une ligne perdue. Cherche tout chemin par lequel une donnée non assainie pourrait atteindre le
fichier.

### D6 — Identité des destinataires

Le jeton doit tenir deux promesses opposées : **même proche ⇒ même jeton**, quelle que soit la forme
du numéro, et **numéro non lisible**. Cherche un cas où deux formes du même numéro donneraient deux
jetons, ou deux numéros différents le même. La normalisation s'appuie sur `blockKey()`, qui garde les
9 derniers chiffres d'un numéro et met en minuscules un expéditeur alphanumérique.

### D7 — Concurrence

Deux appelants coexistent réellement : le worker qui envoie la séquence et l'activité qui remet le
minuteur à zéro. Cherche un entrelacement qui produirait un fichier mêlant deux états, ou une perte
de ligne.

### D8 — Bornage

Le contrat est « les N dernières séquences, **complètes** ». Cherche un chemin par lequel un cycle
conservé serait amputé, ou par lequel le fichier croîtrait sans borne.

## Forme de ta réponse

Pour **chaque** constat :

1. **Fichier et ligne.**
2. **Le scénario d'échec concret** : quelles entrées, quel état, et quelle sortie fausse en résulte.
   Si tu ne peux pas écrire ce scénario, ce n'est pas un constat — ne l'écris pas.
3. **Gravité** : critique / majeur / mineur.
4. **Correctif minimal proposé.**
5. **Statut** : `CONFIRMÉ` si tu peux le démontrer par la lecture seule, `À VÉRIFIER` sinon. Sois
   honnête sur cette distinction ; un constat présenté comme certain et faux coûte plus cher qu'un
   doute annoncé.

**N'invente pas de constats pour remplir.** « Aucun défaut sur ce motif » est une réponse utile et
attendue. Le relecteur précédent a produit 7 constats recevables sur 20 ; la moitié de mon temps est
passée à trier. Un rapport de trois constats vrais vaut mieux qu'un rapport de quinze.

Ne propose **aucun** renommage, réorganisation, changement de style, ni ajout de dépendance.

---

## SOURCES À RELIRE

(concaténées ci-dessous au moment de l'appel)
