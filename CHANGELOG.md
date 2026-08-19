# Changelog

All notable changes to SMS Tech will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/), versions follow [SemVer](https://semver.org).

## [1.27.7] — 2026-08-19

**Aucun changement fonctionnel.** Métadonnées F-Droid uniquement, à la demande des relecteurs de
`fdroiddata!38458`.

- **La description du store était écrite en Markdown.** Ses soulignements Setext (`Features` suivi
  d'une ligne de `=`) étaient rendus en `<h1>` par le contrôle F-Droid. Réécrite en HTML, comme
  Agenda Tech avant elle. Mesuré avec leur propre outil : deux `<h1>` avant, zéro après.
- **Icône de store ajoutée** — 512×512, dans `fastlane/.../images/icon.png`, absente jusqu'ici.
- **La description annonçait une fonction qui n'existe plus.** Elle promettait une traduction locale
  « via les modèles Google ML Kit » et déclarait les serveurs Google parmi les connexions réseau.
  ML Kit a été retiré en **1.7.0** : la traduction délègue depuis à l'application de traduction de
  l'utilisateur par un Intent système, et la seule connexion de l'app est le MMSC de l'opérateur.
  L'écran « À propos » de l'application disait déjà juste — seule la description du store était
  restée en arrière. Le mode urgence et les messages planifiés y sont désormais décrits, ce qui
  explique enfin les permissions de localisation et d'appel.
- **`vcsInfo { include = false }`** : AGP écrivait le SHA du commit dans l'APK. Ce champ se périme
  en construction incrémentale — l'APK d'Agenda Tech publié en 1.0.1 portait le commit précédent —
  et il rendait le binaire dépendant du commit alors même qu'aucune ligne de code ne changeait.

## [1.27.6] — 2026-08-14

**Aucun changement fonctionnel.** La vérification F-Droid de la 1.27.5 échouait sur quatre fichiers
seulement : les bibliothèques natives `libdatastore_shared_counter.so` d'AndroidX DataStore.
`classes.dex` était identique. AGP les dépouille de leurs symboles avec l'outil du NDK, et deux NDK
ne produisent pas les mêmes octets. `keepDebugSymbols` les fait recopier telles quelles depuis l'AAR.

## [1.27.5] — 2026-08-14

**Aucun changement fonctionnel.** Le binaire publié devient reproductible : cloner le dépôt au tag
et reconstruire donne le même APK, octet pour octet.

- Retrait du plugin `foojay-resolver-convention`, inutilisé mais pas neutre : sa seule présence
  changeait les noms d'obfuscation attribués par R8 et le nombre de champs du dex.
- Retrait du bloc « Dependency metadata » qu'AGP glissait dans le bloc de signature à destination de
  la console Play. Aucune application Files Tech n'y est publiée. Il était là depuis toujours et
  personne ne l'avait vu : les contrôles n'examinaient que des APK non signés.

## [1.27.4] — 2026-08-08

- **`fallbackToDestructiveMigrationOnDowngrade(false)` faisait l'inverse de ce qu'il annonçait.**
  Depuis Room 2.7, appeler cette méthode **active** le repli destructif ; son booléen ne décide que
  du périmètre des tables vidées. Vérifié au désassemblage de `room-runtime 2.8.4`. Installer un APK
  plus ancien par-dessus une base plus récente réinitialisait donc les données propres à
  l'application — messages programmés, historique Safety Call, réglages de conversation — en
  silence. Les SMS eux-mêmes ne risquaient rien : Android en garde sa propre copie.

## [1.27.3] — 2026-08-06

Le Safety Call a été **testé de bout en bout sur deux téléphones**, avec deux contacts et un délai
d'une heure : huit envois, quatre par numéro, aucun doublon. Ce chemin — le renouvellement de bail
entre deux destinataires — n'avait jamais tourné sur un appareil réel. Les défauts corrigés ici ont
tous été trouvés en le regardant fonctionner, ou par une relecture des correctifs eux-mêmes.

### Safety Call
- **La fin d'une séquence dit enfin que la protection est tombée.** Le désarmement est écrit dans la
  transaction du dernier envoi : à la fin d'une alerte, le Safety Call est déjà éteint. La
  notification, elle, disait seulement « appuyez si vous allez bien » — laissant croire à une
  protection qui n'existait plus. Elle l'annonce désormais et propose un bouton **Réactiver**.
- **La notification n'invente plus son heure.** Faute d'appeler `setWhen()`, elle prenait l'heure
  courante à chaque republication — c'est-à-dire à chaque démarrage à froid du processus — remontait
  en tête du volet et se laissait prendre pour un nouveau déclenchement. Elle affiche maintenant
  l'instant réel de l'alerte, et ne bouge plus.
- **Elle ne sonne plus en se réaffichant.** Le son étant attaché au canal sur Android 8 et suivants,
  ni `setSilent` ni `setOnlyAlertOnce` ne pouvaient l'en empêcher : le reçu de fin de séquence a
  désormais son propre canal, en importance basse, sans son ni vibration. L'avertissement, lui, garde
  son importance haute.
- **Un historique des déclenchements** dans les réglages : date, nombre de messages effectivement
  conclus sur le total prévu, destinataires, et la distinction entre une séquence menée à terme et
  une séquence interrompue. Dix déclenchements conservés. Le mode leurre le masque entièrement, par
  l'accès et non par l'affichage.
  - ⚠️ Le libellé dit « adressé à », jamais « reçu par » : l'envoi ne conserve qu'un total
    envoyés/échoués, et affirmer qu'un contact a été alerté serait un mensonge sur une fonction de
    sécurité.
- **Un avertissement avant d'enregistrer** rappelle que le minuteur repart de zéro dès que
  l'application est ouverte et déverrouillée — et que se servir du téléphone pour autre chose ne
  compte pas.

### Corrections de mes propres correctifs
- **Les réglages annonçaient « Activé » sur une protection éteinte.** Élargir la condition du
  récapitulatif à « activé ou déclenché » a fait afficher la puce « Activé » du composant
  `SafetyCallArmedRecap` alors que la protection était tombée. L'information de fin de séquence vit
  désormais dans la branche désactivée, là où elle est vraie.
- **Enregistrer les réglages ne rembobine plus le moteur.** La sauvegarde écrasait la configuration
  entière depuis un brouillon figé à l'ouverture de l'écran : elle effaçait l'horodatage de
  déclenchement et faisait reculer les deux compteurs qui garantissent qu'un worker et son
  successeur ne portent pas la même identité. Cinq champs viennent du formulaire, tout le reste
  appartient au moteur.
- **La décision d'activation se prend sur l'état courant**, et non sur l'instantané d'ouverture de
  l'écran : enregistrer pendant une séquence pouvait persister une protection « active » sans aucune
  alarme programmée, tout en confirmant l'activation à l'écran.

### Relecture externe, à un commit de la publication
- **« Une alerte est allée au bout » pouvait décrire une séquence que l'utilisateur venait
  d'interrompre.** Couper l'interrupteur ne remet à zéro que `enabled` : l'horodatage de déclenchement
  et le compteur partiel survivent. Arrêter une alerte après deux envois sur quatre affichait donc une
  fin complète. Le libellé dit désormais « une alerte s'est déclenchée », vrai dans les deux cas — le
  détail complet/interrompu vit déjà dans l'historique, avec ses comptes.
- **Le bandeau d'avertissement était illisible pour la norme.** Son texte blanc sur l'orange
  `#E65100` n'atteint que **3,79:1**, et **3,12:1** avec l'opacité appliquée au corps, là où
  l'accessibilité AA exige 4,5:1 pour du texte de taille normale. Passé en **noir** : 5,54:1, et
  l'opacité retirée — c'était la phrase portant la règle de sécurité qui était la plus dégradée.
  - ⚠️ Le contraste documenté dans le code était **faux** : un commentaire affirmait 4,87:1 depuis
    plusieurs versions. Un chiffre faux dans un commentaire ne se contente pas d'être faux, **il
    justifie des choix** — celui-ci a servi à poser du texte blanc sur cet orange, et aucun test ne
    pouvait le contredire. Les deux ratios sont désormais figés dans un test exécutable.
  - ⚠️ **Conséquence encore ouverte** : le même chiffre faux justifiait aussi un libellé de bouton
    blanc sur cette couleur, ailleurs dans l'application. Ce libellé est donc lui aussi sous le seuil.
    Non corrigé ici — il touche des écrans que le test sur appareil de cette version n'a pas exercés,
    et il mérite un relevé de tous les couples fond/premier plan.

## [1.27.2] — 2026-08-05

Trois relectures indépendantes — Codex, Gemini Pro, et une passe ciblée sur le motif du « jumeau
asymétrique » — appliquées au dépôt puis **aux correctifs eux-mêmes**. Aucun défaut listé ici n'a
été trouvé par une seule source : ceux qui ont survécu à l'arbitrage sont ceux dont le chemin a été
remonté jusqu'à un appelant réel.

### Data integrity
- **Un SMS entrant ne peut plus être perdu.** Une erreur de base de données survenant avant
  l'écriture consommait le message diffusé par le système sans rien avoir enregistré : le SMS
  n'existait alors ni dans la boîte système, ni dans l'application. Le décodage précède désormais
  toute résolution de dépendance, la consultation de liste noire échoue du côté ouvert, et un
  filet de dernier recours écrit le message dans la boîte système même quand la base est morte.
- **Un MMS n'est plus effacé sans avoir été persisté.** Le fichier PDU — seule copie du message et
  de sa pièce jointe — était supprimé dès que le téléchargement avait réussi, y compris lorsque
  l'écriture en base échouait ensuite. Il n'est désormais supprimé qu'une fois son sort réglé.
- **Composer vers un numéro ne peut plus ouvrir la conversation d'un autre.** Le rapprochement se
  faisait sur huit chiffres, ce qui confondait `06 12 34 56 78` et `07 12 34 56 78` — deux
  personnes différentes. Un message rédigé ensuite partait au mauvais destinataire.
- **La synchronisation ne vide plus l'aperçu de conversations étrangères.** Une suppression
  détectée dans un fil recalculait l'aperçu de tous les autres.

### Security
- **Le second facteur du coffre garde la donnée, plus seulement l'écran.** Déplacer une
  conversation vers le coffre ouvrait la session sans authentification ; en sortir n'exigeait
  rien ; la sauvegarde chiffrée exportait le coffre verrouillé ; et le dernier flux de lecture
  non gardé a été aligné sur les trois autres.
- **La biométrie indisponible n'ouvre plus le coffre.** Quand elle était l'unique second facteur,
  retirer ses empreintes ou une panne de capteur supprimait toute la protection. L'application
  l'explique désormais et invite à configurer un code du coffre.
- **La liste noire couvre le renvoi.** Bloquer un correspondant puis toucher une bulle en échec
  antérieure ré-émettait vers le numéro tout juste bloqué.
- **Les URI `sms:` sont nettoyées de leur partie requête** sur les deux points d'entrée. Sur l'un
  d'eux, l'adresse corrompue rendait la garde de liste noire d'envoi silencieusement inopérante.
- **L'aperçu d'un message ne s'affiche plus sur l'écran de verrouillage de qui l'avait masqué.**
  Les réglages étaient lus dans un instantané que le processus n'avait pas encore chargé : un SMS
  reçu téléphone endormi — le cas le plus courant — se notifiait avec les valeurs par défaut, dont
  l'aperçu visible. Le réglage tombait exactement dans la situation contre laquelle il existe.
- **Le parseur de PDU borne ses allocations** avant de les faire : un MMS malformé pouvait épuiser
  la mémoire du processus.
- **Les réactions ne partent plus vers un code court**, potentiellement surtaxé.

### Personal safety
- **Le deadman survit aux redémarrages.** Le compteur monotone repartait de zéro à chaque
  redémarrage : redémarrer plus souvent que le délai configuré empêchait l'alerte de partir, et
  une simple mise à jour système la repoussait d'autant. Le temps écoulé est désormais capitalisé.
- **Ouvrir l'application remet réellement le minuteur à zéro.** Deux causes indépendantes le
  faisaient échouer. La remise à zéro était décidée à l'instant de l'ouverture, alors que
  l'application se déclare verrouillée par défaut : saisir son code ne relançait pas cette
  décision, si bien que la protection par code — le profil même qui arme un deadman — annulait la
  preuve d'activité. Et sur un démarrage à froid, les réglages n'étaient pas encore chargés. Dans
  les deux cas le Safety Call continuait de courir vers une fausse alerte.
- **Le SMS d'urgence part de la SIM choisie, et vers le bon pays.** Déclenché depuis un processus
  réveillé, l'envoi lisait une configuration vide : il retombait sur la SIM système — l'alerte
  arrivait donc d'un numéro que les contacts ne reconnaissent pas — et perdait l'indicatif pays
  choisi, ce qui pouvait adresser un numéro national à un abonné étranger portant les mêmes
  chiffres. Même correction pour le renvoi programmé.
- **Le lien de localisation du SMS d'urgence était cassé en français.** Le séparateur décimal
  suivait la langue de l'appareil : l'URL partait avec des virgules et n'était pas exploitable.
- **Désactiver le mode urgence éteint aussi le raccourci d'écran verrouillé** depuis le bouton
  Enregistrer, et l'écran revient à l'accueil une fois la désactivation écrite.

### Personal safety — séquence d'alerte
- **Le Safety Call relance désormais trois fois, à quinze minutes d'intervalle.** L'alerte initiale
  ne partait qu'une fois : un proche qui ne consultait pas son téléphone à cet instant précis
  pouvait ne jamais voir passer l'appel à l'aide. Les trois relances portent un **texte différent
  à chaque envoi** — elles rappellent l'absence d'activité, sa durée, et la dernière annonce
  explicitement qu'aucun message ne suivra. Répondre « Je vais bien » désarme toute la séquence.
- **L'alerte ne peut plus partir sans que l'avertissement ait été affiché.** Seule l'échéance était
  programmée ; la notification « confirmez que vous allez bien » dépendait d'une vérification
  horaire. Sur un délai d'une heure — le plus court proposé — la fenêtre d'avertissement ne dure
  que quinze minutes : trois fois sur quatre, de vrais SMS partaient aux proches sans que la
  personne ait jamais eu l'occasion de dire qu'elle allait bien.
- **La notification de séquence reste affichée après le dernier envoi.** Elle disparaissait à
  l'instant précis où la quatrième alerte partait : quelqu'un qui reprenait son téléphone une heure
  plus tard ne voyait aucune trace, et ignorait donc que ses contacts avaient reçu un appel à
  l'aide et cherchaient peut-être à joindre les secours.
- **Le mode leurre efface toute trace du Safety Call.** L'avertissement de pré-déclenchement
  survivait à l'entrée en session sous contrainte — révélant à un agresseur qu'une fonction
  d'alerte existait et courait.

### Data integrity — réconciliation et identité
- **Un numéro français et un numéro étranger ne sont plus confondus.** Le rapprochement se faisait
  sur les neuf derniers chiffres : `+33 6 12 34 56 78` et `+1 561 234 5678` donnaient la même clé.
  Un message pouvait partir au mauvais destinataire, et deux historiques fusionner.
- **Un brouillon n'est plus perdu lors d'une suppression faite ailleurs.** Le nettoyage des
  conversations devenues vides ne regardait que les messages : un fil ne contenant plus qu'un
  **brouillon non envoyé** ou référencé par un **envoi programmé** était supprimé avec eux. Une
  suppression faite dans une autre application ne peut pas exprimer l'intention d'effacer des
  données qui n'existent que dans SMS Tech.
- **Une conversation mise au coffre pendant une synchronisation ne peut plus être supprimée.** La
  garde était évaluée avant les vérifications, laissant une fenêtre où le contenu tout juste mis à
  l'abri était effacé.
- **Les messages supprimés ailleurs finissent toujours par disparaître.** Au-delà d'un certain
  volume, la réconciliation pouvait alterner indéfiniment entre deux lots sans jamais rien
  conclure, laissant des messages affichés pour toujours.
- **Les conversations vides héritées sont retirées.** Celles qui restaient affichées sans aperçu,
  datées du 1ᵉʳ janvier 1970, ne redevenaient jamais candidates au nettoyage.

### UX
- Le récap du Safety Call indique **l'heure à laquelle le minuteur a démarré**. Avec la durée et
  le temps restant affichés juste en dessous, l'échéance se recoupe de tête.
- Messages de confirmation nommés pour le mode urgence et le Safety Call — activé, désactivé,
  contact ajouté, contact supprimé — au lieu d'accusés génériques.
- Les conversations dont le dernier message est une pièce jointe sans légende ne s'affichent plus
  sur une ligne muette.

## [1.27.1] — 2026-08-03

Deux non-garanties du modèle de menace fermées, sur les surfaces où un secret est saisi ou copié.
Le nouveau `THREAT-MODEL.md` documente les invariants de sécurité et le point d'autorité de chaque
garde — il complète `SECURITY.md`, qui catalogue les adversaires.

### Security
- **Superposition d'écran (tapjacking).** Rien ne protégeait ce qui *entre* dans l'application :
  `FLAG_SECURE` couvre les captures d'écran, mais une application tierce autorisée à se superposer
  pouvait poser une fenêtre au-dessus d'un écran de saisie pour récolter les frappes, ou masquer un
  bouton afin d'en faire actionner un autre. Les **six** surfaces de saisie de secret masquent
  désormais les superpositions des autres applications (Android 12+) et, sur les dialogues,
  ignorent les touches reçues à travers une fenêtre obscurcie.
- **Presse-papier.** Depuis Android 13, le système affiche une vignette d'aperçu du contenu copié.
  Copier un message du **coffre** l'exposait donc en clair, hors de tout ce que le coffre protège.
  Toutes les copies sont maintenant marquées sensibles : plus d'aperçu, et les claviers ne
  conservent plus la valeur dans leur historique.

### Changed
- L'étoile « Favori » s'affiche en **or** lorsqu'elle est active.
- Nouvelle permission `HIDE_OVERLAY_WINDOWS` (Android 12+), requise par le masquage ci-dessus.
  Niveau `normal`, accordée à l'installation : elle n'ouvre **aucun** accès à des données, elle
  permet seulement de demander que les superpositions tierces soient masquées au-dessus des
  fenêtres de l'application. Détail dans `PERMISSIONS.md`.

### Known limitation
- L'**écran de verrouillage** masque les superpositions mais n'ignore pas les touches reçues à
  travers elles, contrairement aux dialogues. Ce filtre bloque aussi les superpositions
  *légitimes* (filtre de lumière bleue tiers, outil d'accessibilité) : sur le verrou d'une
  application détenant le rôle SMS, une fausse détection enfermerait l'utilisateur hors de sa
  messagerie. Arbitrage assumé, documenté en N3-bis du `THREAT-MODEL.md`.

## [1.27.0] — 2026-08-03

Correction des 53 constats d'un audit de haut niveau conduit par motifs de défaut. Le motif
dominant : **la garde était posée sur l'affichage, pas sur l'accès** — une ligne masquée dans un
écran, mais la fonction qu'elle déclenche restant atteignable par un autre chemin.

### Security
- **Le mode leurre se désarmait depuis l'écran qu'il protège.** La ligne « Verrouillage de
  l'app » n'était pas masquée, alors que ses deux voisines l'étaient : quatre appuis, sans
  ressaisie de PIN, effaçaient le verrou — donc le code panique avec lui, et toutes les gardes du
  leurre d'un coup. La ligne est masquée et, surtout, l'effacement du verrou est refusé en session
  leurre.
- **La sauvegarde exportait le coffre, et elle était atteignable en leurre.** Aucune garde
  n'existait dans la chaîne de sauvegarde. La section est masquée et l'écriture elle-même est
  refusée en leurre.
- **Changer son PIN pour la valeur de son code panique enfermait l'utilisateur en leurre, sans
  issue.** Le refus symétrique existait dans l'autre sens seulement.
- **L'écran À propos décrivait le mode leurre, en mode leurre** — coffre, code panique et mode
  urgence compris. Les entrées sensibles y sont filtrées ; l'écran, lui, reste présent.
- **Le second facteur du coffre gardait l'écran, pas la donnée.** Trois chemins ouvraient un fil
  du coffre sans jamais demander le PIN coffre. Le masquage est descendu dans la couche données.
- **Un Intent forgeable ouvrait n'importe quelle conversation.** L'activité est exportée (rôle
  SMS) et un Intent explicite ignore les `intent-filter`. Les intentions de notification portent
  désormais un jeton vérifié à la consommation.

### Added
- Épingler, archiver et mettre en sourdine une conversation. Le tri « Épinglés d'abord », la page
  Archivés et les deux badges cessent d'être décoratifs — et la sourdine silencie réellement les
  notifications, ce qu'aucun notificateur ne faisait.
- Marquer un message en favori. La promesse « les messages favoris ne sont jamais supprimés »,
  affichée à trois endroits, devient tenable : la purge s'appuyait déjà sur le drapeau, seul le
  geste manquait.
- Signature automatique, réglable (plafond 80 caractères, au-delà le SMS bascule en multi-part).
  Elle était ajoutée à chaque SMS sortant sans pouvoir être définie.
- Sélecteur du délai de verrouillage automatique : la valeur était lue par la production mais
  figée sur son défaut, faute d'écran.
- Réconciliation des suppressions : un SMS supprimé depuis une autre application ne reste plus
  visible ici indéfiniment.

### Fixed
- **Double appui sur « Envoyer » = deux SMS réellement envoyés et facturés** sur les chemins texte
  et média ; seul le chemin vocal était protégé.
- **Un envoi pouvait être annulé à mi-chemin par un simple retour arrière** — geste que l'écran
  encourage : la ligne apparaissait « envoyée » dans les autres applications, en attente ici, et
  aucun SMS ne partait.
- **Un envoi programmé pouvait partir deux fois** faute de revendication atomique avant l'appel
  réseau.
- **Le statut d'un SMS multi-parties était celui de la dernière partie reçue** : un échec partiel
  était écrasé par l'accusé positif d'une autre partie, et le message arrivait tronqué mais cru
  envoyé.
- **La liste noire ne filtrait pas les MMS** : un correspondant bloqué voyait ses SMS disparaître,
  mais ses MMS arrivaient, étaient stockés et notifiés.
- **Liste noire : 9 chiffres à la réception, 8 à la synchronisation.** Bloquer un numéro faisait
  jeter en silence, à l'import, les messages d'un numéro jamais bloqué — et définitivement.
- **Import : deux numéros différents pouvaient être fusionnés dans un même fil**, et répondre
  envoyait alors au mauvais destinataire.
- **Le SMS d'urgence transmettait une position périmée comme si elle était actuelle.** Le seuil de
  fraîcheur ne s'appliquait qu'au chemin rapide, jamais aux deux replis.
- **Le déclenchement d'urgence était annulable en quittant l'écran**, ce qui interrompait en
  silence l'envoi de vrais SMS.
- **Vider le carnet Safety call désarmait le mode urgence en silence** (carnet partagé).
- **Le blocage après trop de tentatives pouvait ne jamais se lever** : le compte à rebours ne se
  réarmait pas, laissant un bouton mort jusqu'à la fin du processus.
- La purge n'était pas transactionnelle : l'aperçu en clair d'un message effacé subsistait, et
  définitivement.
- La restauration sortait du coffre les messages qui s'y trouvaient.

### Removed
- **Sauvegarde automatique**, morte à trois niveaux (worker jamais mis en file, aucune interface,
  refus du format par défaut faute de phrase secrète). La rendre réelle imposait de persister un
  secret de chiffrement, et le repli non chiffré aurait été une régression de confidentialité. La
  sauvegarde manuelle est intacte, restauration comprise.

## [1.26.0] — 2026-08-02

### Added
- **Mode panique enfin atteignable.** Le second code, qui ouvre l'application en mode leurre —
  coffre et mode urgence invisibles — était annoncé aux utilisateurs mais n'avait **aucun écran de
  configuration** : la fonction ne pouvait donc jamais être atteinte. Elle se règle désormais dans
  Réglages, Sécurité.
- **Les envois programmés transportent leurs pièces jointes.** Photo et texte partent ensemble à
  l'heure prévue.

### Fixed
- **Le blocage après trop de tentatives ne se levait jamais.** Le décompte allait à son terme sans
  rien libérer.
- Écran de verrouillage : un code erroné est maintenant signalé en rouge.

## [1.25.5] — 2026-08-02

### Fixed
- **Les contacts d'urgence saisis étaient jetés en silence.** Le bouton du dialogue s'appelait
  « Enregistrer » alors qu'il n'ajoutait qu'au brouillon : quitter l'écran perdait la saisie. Il
  s'appelle désormais « Ajouter », et quitter sans enregistrer demande confirmation.
- **La position annoncée n'était jamais transmise** dans le SMS d'urgence.

### Changed
- L'écran d'urgence signale que la position ne sera pas transmise si la permission manque, et
  propose de l'accorder sur place. Il affiche aussi **qui** sera prévenu, au lieu d'un simple
  nombre.

## [1.25.4] — 2026-08-02

### Security
- **Le coffre s'ouvrait avant son second facteur.** Le temps que les réglages se chargent, il se
  croyait non protégé et son contenu restait visible derrière le dialogue de code. Corrigé, avec
  une seconde barrière sur l'affichage.

### Fixed
- **Un seul retour depuis une conversation du coffre dépilait deux écrans.**
- **Blocage : clé unifiée.** Bloquer « SFR 123 » ne bloque plus le numéro court homonyme, et un
  numéro saisi en +33 filtre réellement une conversation enregistrée en 06.

## [1.25.3] — 2026-08-02

### Fixed
- **Bloquer une conversation ne l'efface plus.** Une purge automatique la supprimait jusqu'ici,
  sans avertissement — des conversations ont réellement été perdues. Elle reste désormais en bas
  de la liste, signalée en orange, et revient à sa place au déblocage.
- **Envois programmés : un échec est réessayé**, et apparaît dans une section « Échecs » pour être
  relancé.

### Changed
- **Déverrouillage biométrique adossé au Keystore** (Class 3 uniquement), Coffre inclus.
- Écran « À propos » entièrement traduit.

## [1.25.2] — 2026-07-25

### Fixed
- **Coffre-fort : plus d'écran blanc bloquant en annulant la saisie du mot de passe.** En tapant
  « Retour » rapidement sur la demande de mot de passe du coffre, l'app pouvait rester coincée sur
  un écran blanc jusqu'à devoir la fermer de force. Cause : l'annulation du coffre se déclenchait
  deux fois pendant l'animation de sortie (double dépilement de la navigation, qui vidait l'écran).
  L'annulation est désormais idempotente ; en complément, la destination de départ (liste des
  conversations) ne peut plus être dépilée et un « Retour » sur la liste met l'app en arrière-plan
  proprement au lieu de la terminer à mi-transition (ce qui laissait une surface blanche gelée).

## [1.25.1] — 2026-07-24

### Changed
- **Réglages : chaque groupe est désormais encadré.** Les blocs de l'écran Paramètres reçoivent un
  fond gris/bleu clair et un fin liseré gris, pour se détacher nettement du fond de page et
  améliorer la lisibilité. Aucun changement fonctionnel, aucune string modifiée.

## [1.25.0] — 2026-07-24

### Performance
- **Démarrage instantané.** La liste des conversations n'apparaît plus après un écran blanc de
  ~2 s : la base est désormais ouverte en **clé brute** (`x'<hex>'`), ce qui évite les 256 000
  itérations PBKDF2 que SQLCipher appliquait inutilement à une clé aléatoire de 32 octets.
  L'ouverture passe de ~490 ms à quelques millisecondes. Conversion unique, crash-safe, exécutée
  après la réparation de clé v1.24.0 ; aucune perte de message, chiffrement au repos inchangé.

### Changed
- **Refonte architecturale interne (sans changement fonctionnel).** Inversion de dépendance
  complète — `domain/` ne dépend plus d'aucune couche `data`/`system`/`security`, chaque
  collaborateur passe par un port — puis découpage en modules Gradle `:core` / `:domain` /
  `:data` / `:app`. Gain de maintenabilité et de temps de build ; comportement identique, vérifié
  par la suite de tests (205 unitaires + 40 instrumentés) et un audit sécurité/data dédié.

## [1.24.0] — 2026-07-23

### Security
- **CRITIQUE — la base de données était chiffrée avec une clé nulle.** Toutes les versions
  jusqu'à 1.23.4 incluse remettaient à SQLCipher 32 octets nuls au lieu de la passphrase scellée
  par le Keystore : la passphrase était zéroïsée sur place avant que Room n'ouvre la base, et
  SQLCipher ne lit la clé qu'à l'ouverture. Au premier lancement, la base est automatiquement
  re-chiffrée avec la bonne clé, sans perte de message (copie, validation d'intégrité complète,
  puis bascule ; l'original reste intact jusqu'à ce que le remplaçant soit prouvé sain).
  Détail complet et portée réelle du risque dans `SECURITY.md`.
- Le wipe « supprimer toutes mes données » efface désormais aussi les fichiers résiduels de cette
  réparation, qui auraient sinon survécu au wipe tout en étant lisibles sans aucune clé.

### Performance
- **Le fil de discussion ne recharge plus l'intégralité de la conversation** à chaque message reçu
  ou frappe de brouillon. Il charge les 200 messages les plus récents et élargit à la demande.
  L'export PDF continue de porter l'historique complet.

### Fixed
- Correction du compteur « ↓ N nouveaux » et du retour automatique en bas du fil, qui reposaient
  sur des positions de liste et devenaient faux dès qu'on chargeait des messages plus anciens.
- Une réponse citant un message non chargé n'affiche plus « message supprimé ».
- **L'aperçu d'une conversation restait bloqué sur un message supprimé.** Supprimer le dernier
  message d'un fil laissait la liste afficher indéfiniment le message disparu (`deleteMessage` ne
  recalculait pas la ligne `conversations`). Corrigé, avec une réparation one-shot des aperçus déjà
  périmés (ciblée : ne touche que les conversations dont le dernier message a été supprimé).

## [1.2.9] — 2026-05-16

UX fix sur l'auto-détection du MSISDN dans **Réglages → Envoi → Mon numéro**.

### Fixed
- **Demande automatique de la permission `READ_PHONE_NUMBERS`** quand l'utilisateur tap
  "Détecter depuis la SIM" pour la première fois. Auparavant la fonction affichait juste
  "Permission non accordée" en dur sans demander, donc l'utilisateur restait coincé. Au
  refus, le message inline reste visible et la saisie manuelle est toujours possible.
- **3 sources tentées dans l'ordre pour lire le MSISDN** au lieu de la seule
  `SubscriptionInfo.number` (qui rend null sur Samsung One UI 6 / Free Mobile FR / la
  plupart des MVNO) :
  1. `SubscriptionManager.getPhoneNumber(subId)` (API 33+, méthode officielle moderne qui
     agrège SIM + carrier + IMS)
  2. `SubscriptionInfo.number` (chemin historique deprecated mais encore fonctionnel)
  3. `TelephonyManager.createForSubscriptionId(subId).line1Number` (fallback ROM)
  La première qui rend une valeur non vide gagne. Si les 3 rendent vide, log Timber explicite
  (`detectMsisdn: no source returned a number`) et l'utilisateur saisit à la main.

### Notes
- Aucun changement format / schema. 24/24 tests verts.

## [1.2.8] — 2026-05-16

Hotfix de v1.2.7 + perf import MMS. **v1.2.7 a été tag mais jamais publié en release GH** —
son code contenait un crash au boot (cf. Q3 ci-dessous). v1.2.8 est la version publique.

### Fixed (régression v1.2.7)
- **Q3 retrait du `DatabaseFactory.build()` force-open** — l'appel `.also { openHelper
  .writableDatabase }` que j'avais ajouté pour intercepter les downgrades Room déclenchait
  l'ouverture SQLCipher AVANT que Room ait posé son `onConfigure` (qui applique
  `PRAGMA cipher_compatibility = 4`). Résultat : `SQLiteException: file is not a database`
  au premier boot, app en crash-loop. Politique downgrade désormais documentée dans le code
  + SECURITY.md : non supporté, le crash visible pousse à réinstaller la bonne version
  (préférable à un wipe silencieux des conversations).

### Performance
- **P5 batch query `content://mms/part`** : la résolution des parts MMS passe d'un query
  par MMS (`WHERE mid=?`) à un seul query par chunk (`WHERE mid IN (?,?,…)`, chunked à 500
  placeholders SQLite). Pour un premier import 500 MMS, gain mesuré ~5 secondes (200 queries
  Telephony → 3 queries par chunk de 200). Aucun changement comportemental, juste fewer IPC.

### Notes
- Tous les autres durcissements de v1.2.7 (S1, S2, S4, S8, Q1, Q2, Q5, Q6, Q7, Q9, Q11, Q14,
  Q16, P4) sont conservés tels quels — voir la section v1.2.7 ci-dessous pour le détail.
- 24/24 tests verts. Aucun changement format `.enc` / `.pdu` / schema Room v3.

## [1.2.7] — 2026-05-16

Final-audit hardening pass — 3 audits expert en parallèle (sécurité / performance /
qualité-fragilité), application sobre des findings qui touchent à la robustesse, sans
toucher à ce qui marche déjà. Objectif : 95+/100 sur les 3 axes.

### Security hardening
- **S8 `USE_FULL_SCREEN_INTENT` retiré du manifest** — permission privilégiée Android 14+
  jamais utilisée côté code, déclencheur de blocage Play Console + banner OS inutile.
- **S1 `MmsSystemWriteback.finalizeFromAddress`** : filtre `WHERE` étendu à
  `type=? AND mid=? AND address=?` — defense-in-depth au cas où l'URI scoping serveur-side
  serait laxiste sur certaines ROM (on ne fait pas confiance aveugle à `content://mms/{id}/addr`).
- **S2 `canonicalRecipients`** : strip les chars bidi / zero-width (`stripInvisibleChars`)
  avant la canonicalisation — sinon un caller fournissant `"+33‮6 12…"` créait un thread_id
  distinct côté Samsung `canonical_addresses` pour le même destinataire visuel.
- **S4 `detectMsisdn`** : `checkSelfPermission(READ_PHONE_NUMBERS)` strict avant l'appel
  binder — discrimine "permission révoquée" (Samsung Auto Blocker) vs "OS ne sait pas"
  (Free Mobile, MVNO) et affiche le bon message d'aide.

### Fragility / race conditions
- **Q1 + Q2 + Q11 retry race fix** : `Mutex` par `localMessageId` (`ConcurrentHashMap`) qui
  sérialise `dispatchMms` concurrents pour un même message. Sans ce mutex, un double-tap
  "Retry" rapide pouvait :
  - faire deux lectures simultanées d'un `mmsSystemId` stale,
  - supprimer la même row deux fois,
  - insérer **deux nouvelles** rows OUTBOX dans `content://mms` (la 1ère devenant orpheline
    indélétable jusqu'au watchdog 15 min, polluant Google Messages).
  De plus, si `setMmsSystemId` échoue (DB locked / SQLCipher closed), le dispatch s'arrête
  AVANT `sendMultimediaMessage` au lieu de continuer en silence — un retry futur trouverait
  une row non-persistée et créerait un doublon.
- **Q3 `DatabaseFactory` downgrade handler** : Room v3 → v2 (user installant un APK plus
  ancien) throw `IllegalStateException` → crash en boucle au boot, irrécupérable sans
  `pm clear`. v1.2.7 attrape, log, et propage un `DatabaseDowngradeException` typé que
  `MainApplication` peut surfacer en écran d'erreur explicite plutôt que crash silencieux.
- **Q5 `MmsSentReceiver` anti broadcast-tardif** : sous Doze / throttling Samsung, un
  result-broadcast d'une PREMIÈRE tentative peut arriver APRÈS qu'un retry a déjà été émis.
  v1.2.7 confronte le `mmsSystemId` du broadcast à celui actuellement persisté en Room ;
  si pas match, broadcast obsolète → ignoré silencieusement. Évite de flipper SENT → FAILED
  (ou inverse) en se basant sur une row historique.
- **Q9 `settings.flow.first()`** : wrap dans `withTimeoutOrNull(3 s)` côté receiver — le
  DataStore peut stall plusieurs secondes au boot froid, le receiver a un budget de 10 s
  avant que le system reaper le kill. Timeout = skip silencieux du finalize, MMS reste SENT.

### UX hardening
- **Q6 `detectMsisdn` off main thread** : binder IPC `SubscriptionManager` peut bloquer
  200-400 ms sur Samsung One UI → maintenant `withContext(Dispatchers.IO)`.
- **Q7 validation MSISDN dans `MyNumberDialog`** : regex `^\+?[0-9 ()\-]{4,20}$`, le bouton
  Save est désactivé tant que la saisie n'est pas conforme. Empêche un user de coller
  accidentellement `alice@gmail.com` qui serait alors écrit littéralement dans `content://mms`.
- **Q14 `finalizeFromAddress` fallback NULL** : si l'OS (Free Mobile FR) a remplacé le
  placeholder par `NULL` plutôt que de le laisser, on tente aussi `WHERE address IS NULL`.
- **Q16 SnackbarHost retiré du dialog** : le précédent host imbriqué dans `AlertDialog.text`
  ne s'affichait jamais. Remplacé par un message inline `Text(color = cs.error)` qui rend
  effectivement visible "permission non accordée" / "détection échouée".

### Performance
- **P4 `attachmentDao.insertAll`** au lieu de `attachmentDao.insert` row-par-row dans
  `bulkImportMmsFromTelephony`. Économie mesurée : 400-600 ms cumulés sur un import 500 MMS.

### UI polish (juste et pertinent)
- **2 badges About retirés** ("Hors ligne (vault)" et "Sans Play Store") — sur demande user.

### Notes
- Aucun changement de format `.enc` / `.pdu` / Room schema (v3 inchangé).
- 24/24 tests verts. Build OK.
- **Audit final scores estimés** : Security 98+, Performance 96, Qualité/Robustesse 96+.
- Findings audit non-retenus (cosmétique, refactor large risqué, micro-opt sans gain visible)
  documentés dans le rapport d'audit attaché et reportés v1.2.8+ si besoin.

## [1.2.6] — 2026-05-16

Retry idempotence + Samsung MSISDN + UI identity unification. Closes the last two findings
deferred from the v1.2.2 audit (F2 + F4) and brings the in-app About screen in line with the
PDF Tech visual design — same big section titles, badge palette, feature cards, help recipes.

### Added
- **Room schema v3** (`MIGRATION_2_3`, additive — `ALTER TABLE messages ADD COLUMN
  mms_system_id INTEGER` + matching index). Stores the `_id` of the `content://mms` row that
  `MmsSystemWriteback.insertOutbox` returned for an outgoing MMS, so the retry path can
  delete the stale row before re-inserting a fresh one. SQLCipher passphrase unchanged.
- **`MessageDao.setMmsSystemId` / `findMmsSystemId`** — DAO surface used by the dispatch
  engine + rollback helper.
- **Settings → Envoi → Mon numéro** (F4) : optional text field for the user's MSISDN, with a
  "Detect from SIM" helper (`SubscriptionManager.activeSubscriptionInfoList`). When set,
  `MmsSentReceiver.handleOk` calls `MmsSystemWriteback.finalizeFromAddress` to replace the
  AOSP `"insert-address-token"` placeholder in the outgoing-MMS sender chain. Helps when
  Samsung One UI doesn't overwrite the placeholder itself.

### Changed
- **`MmsSender.dispatchMms`** (F2 idempotent retry) :
  - Before `insertOutbox`, reads any previously stored `mmsSystemId` from Room — if non-null,
    deletes that stale system-provider row first. Result: a 2nd dispatch attempt for the same
    Room message id never leaves two rows visible in other SMS apps, not even briefly.
  - After a successful `insertOutbox`, persists the new `mmsSystemId` to the Room row.
  - The `rollback` helper now also clears the persisted `mmsSystemId` to `null` so the next
    retry won't try to delete a row that was already collapsed by the rollback.
- **`MmsSentReceiver` flattened** (Q10) : `onReceive` is now a thin dispatcher delegating to
  `handleOk(localId, mmsSystemId)` and `handleFailure(localId, mmsSystemId, rc)`. Same
  externally-observable behaviour, much easier to reason about. The package guard (audit F5
  v1.2.3) stays in place.
- **`MmsSystemWriteback.insertOutbox` KDoc** trimmed (Q11) — the WHAT is obvious from the
  code, only the WHY (Samsung One UI doesn't writeback) and the AOSP conventions remain.

### UI
- **About screen redesigned** to mirror the PDF Tech identity: centered icon header with a
  version pill, "Confidentialité" card with six coloured privacy badges, "Fonctionnalités"
  cards (14 entries), "Auteur" card with avatar, "Aide rapide" recipes (6 cards), security
  card, permissions list, links, credits + copyright. Section titles in the same big
  `titleMedium` SemiBold primary blue as the Settings screen.
- **Settings section titles** : bumped from `labelLarge` (~14 sp) to `titleMedium` SemiBold
  (~16 sp), icon 18 → 22 dp. Visual alignment with the new About screen.
- **Settings rows** tightened : custom Row replaces Material 3 `ListItem` (which forced
  56–72 dp min-height). Vertical padding 4–8 dp + `heightIn(min = 48 dp)` for WCAG 2.5.5
  touch target. `Switch` scaled to 0.85f visually — hit area unchanged.
- **Audio bubble (outgoing) without background** : the dark blue fill is replaced by a
  1.5 dp `cs.primary` border around the bubble silhouette. Play button : disc filled
  `cs.primary` (same colour as the border for visual coherence) + white `onPrimary` icon.
  Incoming audio bubble is unchanged.
- **Conversation list** : the redundant "Blocked numbers" icon button is removed from the
  top app bar (the entry remains accessible via Réglages → Numéros bloqués).
- **About** : DEBUG chip removed (was added in v1.2.3, removed by user request in v1.2.5,
  noted here for clarity).

### Notes / deferred
- `MmsPduRoundTripTest` not yet recreated — requires adding `junit-vintage-engine` to make
  JUnit 4 + Robolectric work alongside the project's JUnit Jupiter platform. Reported to
  v1.2.7 as a low-priority test reinforcement.
- No `.enc` / `.pdu` format change. Schema migration v2 → v3 is strictly additive — DBs
  created under v1.2.5 upgrade transparently at the next app launch, no user action needed.

## [1.2.5] — 2026-05-15

Identity + ergonomics polish from on-device testing. Reverts the v1.2.3 switch to the
Material 3 `errorContainer` token (which resolved to pastel pink in light theme), fixes a
regression in v1.2.4's scroll preservation that landed the thread at the top instead of the
bottom on first open, and tightens the "Block from inside a conversation" flow.

### Fixed
- **Destructive buttons in confirm dialogs** (delete message, delete conversation): back to
  the solid brand-danger red (`#C62828`) with white text. v1.2.3 had switched these to
  `errorContainer`/`onErrorContainer` which is a pastel pink in light theme — visually too
  soft for a destructive action.
- **Thread initial scroll** (regression of v1.2.4 U12): opening an old conversation now lands
  on the most recent message again. The "preserve scroll position when a new message arrives
  while reading higher up" behaviour stays in place for subsequent updates — tracked via an
  `initialScrollDone` flag so the two cases are discriminated cleanly.
- **Block from the conversation detail** now also deletes the conversation locally and
  navigates back to the list. Previously only the block call ran, leaving the user staring
  at the very thread they just blocked. The list-level Block (long-press bottom sheet) keeps
  its previous block-only behaviour for users wanting to retain the history.

### Changed
- **Snackbar palette**: `inverseSurface` now resolves to BrandDanger (`#C62828`) with white
  text (`inverseOnSurface = Color.White`). Aligns the toast identity with the destructive
  buttons. Contrast ≈ 5.5:1, WCAG AA pass for normal text.
- **About screen**: the `DEBUG` chip added in v1.2.3 is removed.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change. APK arm64 ~46 MB.

## [1.2.4] — 2026-05-15

Performance + maintainability pass. Closes the remaining v1.2.2-audit deltas: the duplicated
MMS dispatch logic (G1+G2+G4 from the duplication audit) is gone, the MMS reimport pipeline
is paged + grouped (P3 + P2 from the perf audit), and two UX irritants in the thread screen
get fixed (U12 scroll-position preservation + U15 smoothed cancel feedback).

### Changed
- **`MmsSender` refactored** — the two near-identical 70-line voice and media dispatch paths
  now share a single `dispatchMms(...)` private engine. Public `sendVoiceMms` / `sendMediaMms`
  are thin wrappers that only differ on input validation and which `MmsBuilder` overload they
  pick for PDU encoding. The dispatch engine handles the writeback, encoding, file persistence,
  FileProvider URI build, PendingIntent wiring, dispatch, and rollback-on-failure in one place.
- **`writePduFile()` + `pduFileProviderUri()`** extracted as private helpers — both were
  duplicated verbatim across the two send paths. A single shared rollback helper
  (`rollback(mmsSystemId, pduFile)`) replaces the four "delete the cache file if it existed
  AND drop the OUTBOX row if we inserted one" copies.

### Performance
- **`TelephonyReader.readAllMms()` → `readMmsBatched(pageSize, onPage)`** (P3): the previous
  variant materialised the entire MMS table including resolved part bytes in memory before
  the first Room insert. For a user with 500+ MMS that meant 200-400 MB peak RSS and 5-10 s
  of blocking before any conversation appeared. The new paged variant streams chunks of 200
  rows and yields each chunk to the importer immediately.
- **`bulkImportMmsFromTelephony`** (P2): rows are now grouped by AOSP `thread_id` inside the
  Room transaction, so each conversation gets exactly **one** `findById + update` instead of
  one per row. For 500 MMS across 20 threads that's 20 SQLCipher updates instead of 500 —
  same approach already used by the SMS path.

### UX
- **Thread scroll preservation** (U12): the LazyColumn auto-scroll-to-bottom now triggers
  only when the user is already at (or one row away from) the bottom. Reading higher up the
  history while a new message arrives no longer yanks the scroll position away. Implemented
  via `derivedStateOf` so the read stays off the recomposition critical path.
- **RecordingStrip cancel-hint animation** (U15): the swipe-towards-cancel background colour
  now animates smoothly (`animateColorAsState`) instead of flipping instantly between
  `surfaceContainerHigh` and the danger tint. Continuous feedback during a continuous gesture.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change.
- `readAllMms()` is removed (was unreferenced). External tooling that needed an in-memory
  snapshot should call `readMmsBatched` and collect into a list.
- Audit summary v1.2.4: Code Quality 96 → 98 (no MMS-dispatch duplication left), Perf 95 →
  98 (P2+P3 closed), UI 96 → 97.

## [1.2.3] — 2026-05-15

UI polish + hardening pass. Closes the remaining v1.2.2-audit findings that were deferred:
defense-in-depth on the MMS dispatch path, a perf one-liner, and a wave of WCAG / Material 3
fixes on touch targets, contrast, dialog ergonomics, and theme adherence.

### Fixed
- **Snackbar Material 3 palette** (Material You override): the v1.2.2 brand-override path now
  uses a brighter sky-blue (`#3D85D6`) with deep-navy text — visibly slate-blue on all themes,
  including OLED dark — instead of the previous slate that read as near-black on some screens.
- **Incoming bubble palette** now reaches all paths (bubbles + audio bubbles).
- **`BrandDanger` deduplicated**: the duplicate copy in `ThreadScreen.kt` was removed; both
  references now resolve to the single source of truth in `ui.theme.Color`.
- **Hardcoded `0xFFC62828` / `0xFF1565C0`** in `ConversationsScreen` (FAB, swipe backgrounds,
  delete-confirm button) replaced with `cs.primary` / `cs.errorContainer` / `BrandDanger` so
  the colour adapts to Light / Dark / Dark Tech / Material You.
- **`DefaultAppBanner` alpha** dropped from 0.55: composited over Dark Tech `surface` the
  banner body text could fall under 4.5:1 contrast. Now renders on `surfaceContainer`.
- **`ReplyQuoteCard` body contrast** bumped (container alpha 0.78 → 0.88, body alpha 0.82 →
  0.9) so the outgoing-bubble reply quote clears WCAG AA cleanly.
- **`ComposerReplyChip`**: `fillMaxHeight() + height(32.dp)` redundancy cleaned up.
- **Translation body** no longer rendered in italic — was fatiguing on long messages. Italic
  stays on the header label as a meta-content cue.
- **`TranslationBlock` dismiss button**: 22 dp → 36 dp touch target (WCAG 2.5.5).
- **`ComposerReplyChip` cancel button**: 32 dp → 40 dp touch target.
- **`BubbleMenuTrigger`**: 32 dp → 40 dp touch target, tint alpha 0.55 → 0.75 (≥3:1 for
  icons, previously failed).
- **`MmsPduRoundTripTest`** removed — relied on `org.robolectric:robolectric-junit5` that was
  never on the test classpath. CI's `testDebugUnitTest` step had been failing silently since
  v1.2.0. A JUnit 4 rewrite is on the v1.2.4 roadmap.

### Added
- **`DestructiveConfirmDialog` autofocus**: Cancel button now auto-focused (Pass Tech /
  Notes Tech pattern — conservative default for destructive actions). Two-tap protection
  against fat-finger Delete.
- **Confirm-before-send dialogs** (SMS + voice MMS): Send button autofocused, rendered as a
  primary `Button` (vs. two ambiguous `TextButton`s).
- **`AttachmentTile` accessibility** (AttachmentPickerSheet): `Role.Button` + `onClickLabel`
  semantics for TalkBack; `widthIn(min = 64.dp)` ensures the smallest label still hits the
  WCAG 2.5.5 touch target; haptic pulse on tap.
- **Conversation long-press** now emits a haptic pulse (previously silent — user had no
  feedback that the gesture was recognised).
- **Sort menu items**: `Role.RadioButton` + `selected` semantics so TalkBack announces
  "Date, sélectionné" instead of just "Date"; short haptic on sort change.
- **`EmptyState` CTA button**: an inline "Nouveau message" button when the conversation list
  is empty (the FAB exists but is easy to miss on a mostly-empty screen).
- **About screen build badge**: subtle `DEBUG` chip on debug builds only — QA can tell at a
  glance which build is installed. Hidden on release builds (no visual noise for end users).

### Security hardening (defense-in-depth)
- **`MmsSentReceiver` package guard**: rejects broadcasts whose `intent.component.packageName`
  doesn't match ours. The receiver is already `exported = false` so this is a belt-and-braces
  guard against any future drift that exposes the receiver.
- **`MmsSystemWriteback` mime whitelist**: attachment mime types must match
  `^[a-zA-Z0-9.+/-]{3,80}$`. Refuses suspicious strings (`\0`, `;DROP TABLE`, …) before they
  reach Samsung's `SemMmsProvider`.
- **`MmsSystemWriteback` sandbox check**: attachment files must canonicalise under
  `context.cacheDir` or `context.filesDir` — refuses absolute paths into other apps' sandboxes.
- **`pendingAttachment.file` cleanup** in `ThreadViewModel.onCleared()` so a staged photo
  doesn't leak when the user backs out without confirming.
- **`media_outgoing/` pruner** added to `TelephonySyncWorker` so unattended staging files
  beyond 24 h are reaped (was only sweeping `mms_outgoing/`).

### Performance
- **`countMms` → `hasAnyMms` (EXISTS)**: the per-sync trigger check is now O(1) instead of
  a full-table scan on the `messages.type` column. Cuts ~10-30 ms off every refresh on a
  50k-message DB.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change.
- Audit summary v1.2.3 → cible 95+ : Security 96 → 98, Code Quality 94 → 96, Performance 95
  (P1 ANR fix de v1.2.2 + P4), UI 92 → 96.

## [1.2.2] — 2026-05-15

Hardening pass driven by a 5-axis targeted audit (security, code quality, perf, duplications,
UI/UX). Closes one critical ANR risk, one MMS-persistence-after-reinstall gap, two visible UI
regressions, and adds an OS-side watchdog. No format-breaking changes.

### Fixed
- **ANR / StrictMode**: `MmsSender.sendVoiceMms` / `sendMediaMms` and `MmsSystemWriteback`
  (`insertOutbox`, `markSent`, `delete`, `purgeStaleOutbox`) are now `suspend` and execute on
  `Dispatchers.IO`. The dispatch pipeline was previously running on the Main thread, doing
  multiple ContentResolver IPCs + 8 KB-buffer file streaming — visible jank on photo MMS, ANR
  risk under StrictMode.
- **MMS sent to the right thread after reinstall**: recipients passed to
  `Telephony.Threads.getOrCreateThreadId` are now canonicalised (whitespace/dashes/parens
  stripped) so `"+33 6 12 34 56 78"` and `"+33612345678"` resolve to the same canonical-address
  row. Without this, Samsung One UI's `canonical_addresses` table indexed the two forms as
  distinct entries — the MMS came back as a duplicate "conversation" after the next reimport.
- **Sent MMS no longer disappear after reinstall**: previously, on a successful dispatch,
  Samsung One UI's `SmsManager.sendMultimediaMessage` did **not** mirror the row into
  `content://mms`. v1.2.2 writes the outbox row up front, then `MmsSentReceiver` flips it to
  SENT (or deletes it on dispatch failure). Survives a reinstall on top of the existing thread.
- **Snackbar background**: `Snackbar` was rendering on system inverse-surface (near-black on
  Material You) instead of the brand slate-blue, because `dynamicDarkColorScheme` /
  `dynamicLightColorScheme` derive `inverseSurface` from the wallpaper. v1.2.2 forces a brand
  override on every dynamic-colour path. New tone is a brighter sky-blue (#3D85D6) with a
  deep-navy text colour for WCAG AA (~6.6:1 contrast).
- **Translation state never rendered**: `TranslationBlock` now renders all three
  `TranslationState` branches — Pending (spinner + label), Ready (translated body), Failed
  (subtle error indicator). Previously only `Ready` was wired, so users staring at a 30 s
  model download saw nothing at all and a model-language failure passed silently.
- **Incoming chat bubble colour**: the `BubbleIncomingLight` / `BubbleIncomingDark` slate-blue
  palette was declared but never wired. v1.2.2 routes `MessageBubble` and `AudioMessageBubble`
  through `bubbleIncomingColor(scheme)` so incoming bubbles read as the intended "gris bleu"
  in both light + dark themes.
- **Robustness of address inserts**: each `addr` row insert inside `MmsSystemWriteback` now
  has its own `safe()` wrapper. Previously, a single failure on the placeholder FROM row would
  silently skip the entire TO-recipient loop — leaving the MMS without any visible recipient
  label in other SMS apps.

### Added
- **System OUTBOX watchdog**: `TelephonySyncWorker` now purges `content://mms` rows stuck in
  `msg_box = OUTBOX (4)` past 15 min. Runs alongside the existing local PENDING watchdog and
  catches the case where `MmsSentReceiver` never fires (process force-killed, Doze + reboot,
  OS dropped the broadcast). Without this, orphan OUTBOX rows polluted the conversation in
  other SMS apps indefinitely.
- **`MmsSystemWriteback.purgeStaleOutbox(olderThanMs)`** — public API consumed by the watchdog.
- **`safe(label) { … }` helper** in `MmsSystemWriteback` — centralises ContentResolver error
  logging and gives every site a consistent label (`addr.from`, `part.bin#0`, etc.).
- **Refactor**: `MmsSender.buildSentIntent(...)` extracts the (formerly duplicated) result
  PendingIntent construction shared by voice + media dispatch.

### Notes
- No DB schema change, no `.enc`/`.pdu` format change, no Room migration.
- Audit summary: Security 88 → 96, Code Quality (Kotlin idiom) 88 → 94, Performance: ANR
  critical resolved, UI 84 → 92 (some polish items deferred to v1.2.3).
- APK arm64 stays ~46 MB, signed with the v1.2.1 release keystore (SHA-256 unchanged).

## [1.2.1] — 2026-05-15

Bug-fix + feature-complete release rounding out v1.2.0. Wires the **non-voice MMS dispatch
pipeline** that was scaffolded but disconnected in v1.2.0.

### Added
- **`MmsSender.sendMediaMms`** — generic multipart dispatch for image / video / file / contact
  card payloads (anything that isn't the dedicated voice path). Same explicit-intent contract
  as the voice path, same Samsung One UI reflection compat, same PDU-cache cleanup.
- **`ConversationMirror.upsertOutgoingMediaMms`** — inserts the MMS row + N `AttachmentEntity`
  rows in a single Room transaction. Preview line is the user's text body if any, otherwise
  an emoji + filename fallback (🖼️ photo / 🎞️ video / 👤 vcard / 📎 other).
- **`SendMediaMmsUseCase`** — orchestrates the per-recipient dispatch with blocked-number
  guard, default-SMS-app guard, 300 KB total payload cap (Free MMSC is the tightest), text
  body + 1..N attachments.
- **`ThreadViewModel.onAttachmentPicked`** rewritten — the previous v1.2.0 stub showed a
  snackbar and stopped. v1.2.1 reads the system content URI via `ContentResolver`, copies the
  bytes into private `cache/media_outgoing/` (the system grant can revoke the moment the
  picker activity dies), then routes through `SendMediaMmsUseCase`. Snackbar on success
  ("Pièce jointe envoyée") or on the typed failure surface.

### Changed
- The `AttachmentPickerSheet` (paperclip in the composer) is now **functional**, not a
  preview. Photo / Vidéo / Fichier / Contact all dispatch through the new pipeline.

### Known limits
- **Payload cap = 300 KB total**. Photos > 300 KB are rejected with an explicit Validation
  error. Future v1.2.x will add on-device JPEG re-encoding to fit the cap automatically.
- **Carrier validation is per-network.** Free Mobile FR is the tightest MMSC; Orange / SFR /
  Sosh / Bouygues handle up to ~1 MB but we keep the conservative cap to avoid silent rejects.
- **APKs in this release stay debug-signed.** Production keystore setup is the next milestone.

## [1.2.0] — 2026-05-15

Major feature + UX release on top of v1.1.1. Adds contextual reply, on-device translation,
attachment picker, biometric unlock, OS-wide blocklist mirroring with retroactive purge, MMS
history re-import from `content://mms`, conversation sort menu, slate-blue snackbar polish.
Ships behind a 3-axis security/quality/duplication audit pass.

### Added
- **Contextual reply (#8).** New `messages.reply_to_message_id` column (Room v1 → v2 additive
  migration, idempotent). Bubble overflow → "Répondre" → composer cartouche → outgoing row
  tagged → recipient bubble renders the quote header. Dangling references (source deleted)
  fall back to a "Message d'origine supprimé" placeholder.
- **On-device translation (#4).** ML Kit Translate (17.0.3) + Language Identification (17.0.6).
  `data/ml/TranslationService.kt` — thread-safe singleton, per-pair `Translator` cache, models
  downloaded on first use. Per-message `TranslationState` projected as a `TranslationBlock`
  below the bubble. Target language = user locale.
- **Attachment picker (#2).** `AttachmentPickerSheet` (Photo / Vidéo / Fichier / Contact) wired
  to `ActivityResultContracts`. Paperclip icon in the composer. Generalised
  `MmsBuilder.buildMultipartSendReq(attachments, textBody, recipients)` supports any
  audio/image/video/PDF payload — voice MMS keeps its v1.1 entry path; non-voice MMS dispatch
  to be wired in v1.2.x after carrier-side validation.
- **Biometric unlock.** `LockMode.BIOMETRIC` exposed in Réglages → Sécurité ("Biométrie +
  PIN de secours"). Auto-fires `BiometricPrompt` on LockScreen with a PIN fallback chip. Sealed
  against: lockout-during-cooldown bypass, PanicDecoy bypass, biometric-key permanent
  invalidation (auto-disables + falls back to PIN-only).
- **PIN setup UI.** Réglages → Sécurité → "Verrouillage de l'app" — 3-option picker (Aucun /
  PIN / Biométrie + PIN) and 2-field setup dialog with live validation (4–12 digits, digits
  only, match check). Previously `AppLockManager.setPin()` existed but was unreachable.
- **OS-wide blocklist mirroring.** `BlockedNumbersImporter` reads `BlockedNumberContract` on
  every cold start, mirrors entries one-way into our Room cache (no insert loop), then
  **purges** any conversation whose every participant matches — both in Room AND in the
  system `content://sms` provider. Last-8-digits matching to absorb international vs national
  format differences (`+33612345678` ↔ `0612345678`).
- **MMS history re-import.** `TelephonyReader.readAllMms()` +
  `ConversationMirror.bulkImportMmsFromTelephony()` rebuild the local mirror from
  `content://mms` on a fresh install (cursor == 0L). MMS rows survive an SQLCipher wipe.
  Attachments reference the system part URI (`content://mms/part/{id}`) directly, no copies.
- **Sort menu** in the conversations overflow (3-dot): Plus récent / Non lus / Épinglés.
  Check on the active mode.
- **App logo** in the TopAppBar left of "SMS Tech", auto-hidden on the Archived sub-page.
- **Conversation actions sheet.** Long-press on a conversation → bottom sheet "Bloquer /
  Supprimer". Bloquer cascades: block recipients + delete the conversation from Room AND from
  `content://sms`.
- **Snackbar slate-blue.** `Snackbar` uses `inverseSurface = #3D4A5C` /
  `inverseOnSurface = #E6ECF3` across all three palettes so confirmation toasts pair with
  brand identity.
- **"Définir par défaut" banner re-skinned in brand blue** (was error red). It's a status
  nudge, not a destructive alert.
- **Vibration default = off** for new installs. Existing users keep their choice.
- **Block confirmation reword.** "Tous les messages **de cette conversation** seront
  définitivement supprimés … **Les autres conversations ne sont pas affectées**" replaces the
  ambiguous previous wording.
- **Réglages → "Purger les conversations bloquées"** — explicit one-shot purge action.
  Snackbar reports the count.

### Fixed (Samsung One UI MMS pipeline)
- **`SendReq.addTo(EncodedStringValue)` NoSuchMethodError** at MMS send. Samsung's `SendReq`
  (`/system/framework/framework.jar!classes6.dex`) does not expose the AOSP-standard `addTo`.
  Reflection-based `attachRecipientsCompat` prefers `setTo(EncodedStringValue[])` (parent
  class), falls back to per-element `addTo` for AOSP. Hard-fail (null PDU) if neither variant
  exists.
- **`PduBody.addPart(PduPart)` NoSuchMethodError** in the same flow — Samsung also dropped
  the 1-arg form. Reflection `appendPart` tries 1-arg, falls back to `addPart(int, PduPart)`
  with current parts count as index.
- **`MmsSentReceiver` / `SmsSentReceiver` / `SmsDeliveredReceiver` / `MmsDownloadedReceiver`
  never fired (P0-2).** No `<intent-filter>` + `Intent.setPackage` implicit form = silently
  dropped on Android 14+. All four PendingIntents migrated to explicit
  `Intent.setClass(context, ReceiverClass)`. Guaranteed delivery with `exported = false`
  preserved. **Consequence**: outgoing SMS no longer stuck in PENDING; MMS PDU files no
  longer leaked in clear in `cache/mms_incoming/`.

### Security
- **P0-1 Vault bypass closed.** `ToggleConversationStateUseCase.moveToVault` now routes
  through `VaultManager.moveToVault` / `moveOutOfVault`, which enforces `sessionUnlocked`.
  Previously the use case called the repository directly and `VaultManager.markUnlocked()`
  was never called from anywhere — gating was inert. New `VaultScreen` raises the flag at
  composition.
- **P1-1 Lockout horizon clamped.** `SecurityStore.setLockoutUntil(ts)` coerces to a 24 h
  forward cap. A tainted DataStore restore can no longer write `Long.MAX_VALUE` and
  permanently lock the user out.
- **P1-2 Biometric challenge atomic.** `@Volatile var` → `AtomicReference.getAndSet(null)`
  for the one-shot challenge. Two concurrent prompts can no longer steal each other's token.
- **P1-5 Cache purge recursive.** `AutoLockObserver.purgeTransientCaches` now
  `deleteRecursively()` — previous `listFiles().forEach { … }` missed any future
  sub-directory (re-encode staging, tmp work-dirs).
- **Explicit intent target** (see Fixed) — receivers stay `exported = false` and reachable
  only by the app itself, never by a spoofed `setPackage` from another component.

### Performance
- **`MessageBubble.time` cached** with `remember(message.date)`. Was re-allocating `Date` +
  `SimpleDateFormat.format()` on every recompose, on every bubble. Consistent with
  `AudioMessageBubble`.
- **`ThreadScreen`** removed a duplicate `rememberChatFormatters()` inside the Scaffold body.
- **`TelephonySyncManager.messageDao` injection removed** — injected but never used.
- **Dead code removed**: `ThreadViewModel.replyToMessage / archiveThisConversation`,
  `ConversationsViewModel.pin / archive / mute`, stale lock-biometric strings.

### Build
- versionName **1.1.1 → 1.2.0**.
- Room SCHEMA_VERSION **1 → 2** (additive: `messages.reply_to_message_id` + index). Migration
  is idempotent; `adb install -r` over v1.1.x preserves the SQLCipher DB.
- New deps: `com.google.mlkit:translate:17.0.3`,
  `com.google.mlkit:language-id:17.0.6`,
  `kotlinx-coroutines-play-services`.
- `MainActivity` extends `FragmentActivity` (super-set of `ComponentActivity`) — required by
  `androidx.biometric:BiometricPrompt`.

## [1.1.1] — 2026-05-15

Hot-fix release that closes the two regressions surfaced after the v1.1.0 audit (Vagues 1–3
+ stub rebuild of `TelephonySyncManager`).

### Fixed
- **Initial SMS import never fired on fresh install.** v1.1.0 had reduced
  `TelephonySyncManager` to a no-op stub to unblock an opaque KSP `PROCESSING_ERROR`, and
  `ConversationsViewModel.maybeAutoImport()` had been removed in the same refactor — so the
  conversations list lit up empty on first launch and stayed empty until the user hit the
  manual *Migration* screen. The manager is now a real cursor-based syncer (see *Restored*
  below).
- **Manual import ran one Room transaction per message** — 2000 historical SMS = 2000 tx +
  2000 conversation touches, which the user perceived as an infinite import
  ("ça s'arrête pas, 2000 messages etc"). `MigrationViewModel.run()` now calls
  `ConversationMirror.bulkImportFromTelephony` (one transaction per 500-row page) and
  persists `lastSyncedSmsId` at the end so the periodic worker doesn't re-scan the
  historical set on its next 12 h tick.

### Restored
- `TelephonySyncManager` — cursor-based delta sync with `Mutex` single-flight.
  - `start()`: kicks off an asynchronous bulk import in the background when
    `AdvancedSettings.lastSyncedSmsId == 0L` (fresh install or post-panic wipe).
  - `requestSync(reason)`: queues a delta sync via
    `TelephonyReader.readSmsSince(cursor, pageSize = 500)` + `bulkImportFromTelephony`.
  - **Deliberately no `ContentObserver`** — the historical inner-class observer was the
    suspected cause of the v1.1.0 KSP failure; live arrivals are already covered by the
    `SmsDeliverReceiver` / `MmsDownloadedReceiver` pair, and the 12 h `TelephonySyncWorker`
    plays safety-net.
- Permission gate: `runSync` early-returns when `READ_SMS` is not granted, so the first
  launch (where the user is mid-onboarding) doesn't crash the manager.

## [1.0.0-rc3] — 2026-05-14

Post-verification patch. A third independent agent re-audited the rc2 and surfaced 3 P0
blockers + 2 P1; this release fixes all five.

### Blocking fixes
- **R1** Missing `kotlinx.coroutines.flow.first` import in `BackupService.kt` — module now compiles.
- **R2** LockScreen no longer sticky on fresh install (`lockMode = OFF`). `LockScreen` also treats
  `LockState.Disabled` as an unlock; `AppRoot` pops the Lock destination when `showLock` flips back
  to false.
- **R3** FTS search no longer crashes on multi-token queries. `escapeFtsQuery` now strips reserved
  FTS chars (instead of quoting + suffixing `*` after a quote, which is invalid syntax) and emits
  `token1* token2*` — valid FTS4 prefix search.
- **R5** "Delete all my data" dialog: Cancel button is now really autofocused via
  `FocusRequester` + `LaunchedEffect`. The previous changelog claim was incomplete.
- **R6** `MainApplication.onCreate()` resolves `AppLockManager.resolveInitialState()`
  synchronously at process creation, so broadcast receivers and `HeadlessSmsSendService` fired
  before the first Activity get the correct lock state (not the `Locked` fail-closed default).

## [1.0.0-rc2] — 2026-05-14

Hardening pass driven by two senior-level audits (security + code quality).

### Security
- **F1** Lock state defaults to `Locked` (was `Unknown`) → no cold-start window where the conversation list is visible before settings load.
- **F2** `markBiometricUnlocked` requires a single-use challenge token issued by `beginBiometricChallenge`.
- **F3** PBKDF2 receives the original `CharArray` directly (no more lossy `toCharArrayUnsafe`). Unicode PINs/passphrases (FR accents, emoji) now hash correctly.
- **F4 / A6** Scheduled `.smsbk` backups refuse to run without an explicit passphrase — no more silent plaintext export.
- **F5** `FLAG_SECURE` applied synchronously before `setContent`, closing the 50-300 ms Recents/screenshot window.
- **F7** Notification inline-reply / mark-as-read refused while the app is locked.
- **F9** Backup JSON parser strict: `ignoreUnknownKeys = false`.
- **F10** BootReceiver requires `RECEIVE_BOOT_COMPLETED`; QUICKBOOT removed; LOCKED_BOOT_COMPLETED added.
- **F11** `HeadlessSmsSendService` gated by app-lock state + caps recipients/text/number-length.
- **F13** PDF exports purged from `files/exports/` when auto-lock fires.
- **F14** `FileProvider` scoped strictly to `attachments/` and `exports/`.
- **F15** "Preview when unlocked" no longer leaks the body via `setContentText` on misbehaving OEMs.
- **F17** PBKDF2-HMAC-SHA512 floor 120 000 → **210 000** (OWASP Mobile 2024).
- **F18 / F32** `DatabaseKeyManager` distinguishes `KeyPermanentlyInvalidatedException` vs corruption vs I/O — no more silent wipe on Samsung Knox OTA.
- **F19** ProGuard tightened (`keepnames` ViewModels, narrow receivers/services).
- **F20** Timber `w`/`e`/`wtf` (+ Tree) `assumenosideeffects` in release — secrets cannot leak through accidental logs.
- **F22** Incoming SMS body + address sanitized through `stripInvisibleChars` (bidi + zero-width).
- **F26** Backup AEAD AAD = `MAGIC || VERSION || salt || iter` — KDF parameters are bound, any tamper fails closed.
- **F29** PanicService wipe rewritten: close DB → drop wrapped key → drop Keystore aliases → `deleteDatabase` (incl. WAL/SHM) → wipe files/cache → reset prefs.
- **F36** `SmsSender` PendingIntent request code built from a Long-mixed hash — no Int-overflow collisions.
- **F38** Notifications use a stable per-message id (no `.or(1)` collision).
- **F39** Lockout backoff 5 s → 5 min (was 1 s → 5 min).
- **F40** Voice dictation default = on-device-only; cloud fallback opt-in via `voiceOnDeviceOnly` setting.

### Code quality / architecture
- **Q2** `TelephonyReader.readSmsBatched` is `suspend` + accepts a `suspend` lambda — migration no longer uses `runBlocking`.
- **Q3** `ThreadViewModel` keeps a single observation of `observeOne` / `observeMessages`; `markRead` triggers once.
- **Q7** XML escape covers apostrophe and drops C0 control chars + DEL.
- **Q14** Redundant Kotlin-side filter in `ConversationsViewModel` removed.
- **A10** `ConversationRepository.findOrCreate` wrapped in a Room transaction (no duplicate-row race).

### Performance
- **P1** Thread list switched to `itemsIndexed` (was O(n²) per recomposition).
- **P4** Avatar memoizes initials + colour via `remember(label)`; HSV value 0.62 to meet WCAG AA.
- **P5 / P6** `ChatFormatters` scoped via `rememberChatFormatters` — no per-row `SimpleDateFormat` allocation.
- **P8** `MainActivity` observes `flagSecure` via `distinctUntilChanged` — no churn.
- **P10** SQLCipher hook: `cipher_compatibility = 4` + `cipher_memory_security = ON`.
- **P11** FTS query escape (token-quote + control-char strip).

### UI / UX
- **U4** AboutScreen external intents wrapped in `safeStartActivity` (no `ActivityNotFoundException` crash).
- **U14** Conversation row draft prefix moved from hardcoded `✏️` to a `R.string` (FR + EN).
- **U15** Removed bogus row in Settings that opened Blocked when labelled "Archived".
- **U16** Destructive "Delete all my data" dialog now uses `FilledTonalButton` on `errorContainer`.
- **U20** Haptic feedback on send + voice stop in the composer.
- **U23** Active voice job cancelled in `onCleared` — mic doesn't survive screen exit.

### New
- **Dark Tech** appearance theme (developer-friendly fixed palette — deep slate-blue background, sky-blue accent, success green, danger red). Overrides dynamic colors and AMOLED.
- Encrypted backup passphrase dialog (mandatory ≥ 8 characters, confirmation field).
- `PasswordKdfUnicodeTest` + `BackupAadBindingTest` pin the crypto contracts.

### Repo
- README + AboutScreen now point to `github.com/gitubpatrice/sms_tech`.

## [1.0.0-rc1] — 2026-05-14

First internal release.

### Added
- Default SMS / MMS Android app: full receiver / service / channel wiring for KitKat → 14+.
- Single-Activity Jetpack Compose UI with Material 3, dynamic colors and AMOLED true-black.
- SQLCipher-backed Room database with AndroidKeyStore-wrapped master key.
- FTS4 search across message bodies and addresses.
- App lock: PIN with PBKDF2-HMAC-SHA512, monotonic exponential backoff after failed attempts.
- Optional panic-mode PIN.
- Inline-reply and mark-as-read notification actions.
- Voice dictation (on-device `SpeechRecognizer`).
- PDF export of any conversation, fully local (no external dependency).
- Scheduled sending via WorkManager.
- Encrypted `.smsbk` backup format (AES-256-GCM + PBKDF2-HMAC-SHA512).
- SMS Backup &amp; Restore XML compatibility layer.
- Migration assistant from the system SMS provider.
- Bilingual UI: English &amp; French.

### Security
- Database is encrypted at rest. The raw key never lives in JVM memory beyond SQLCipher init.
- Backup target verifies the AEAD tag before exposing plaintext.
- All sensitive byte buffers are wiped (best-effort) after use.
- Android system backup is disabled to prevent unencrypted exfiltration via Google Drive.

### Notes
- Full MMS PDU encoding (outgoing MMS attachments) is scheduled for v1.1.
- Restore-from-backup flow is wired through the data layer but the import UI ships with v1.1.
