# Relecture ciblée — SMS Tech, lot v1.27.4 (notifications Safety Call + contraste)

Tu relis un lot de trois correctifs sur une application Android Kotlin de sécurité personnelle.
Le « Safety Call » est un **deadman switch** : si l'utilisateur n'ouvre pas l'application pendant
un délai choisi, une séquence de SMS part vers ses proches. C'est une fonction utilisée dans des
contextes de violences conjugales : les propriétés de sûreté comptent plus que l'élégance.

## Contexte indispensable pour juger

- `versionCode` = nombre de commits. `MainActivity` est `exported="true"` (rôle SMS par défaut),
  donc tout intent qu'elle reçoit est **forgeable** par une application tierce : d'où le nonce
  mono-usage `SafetyCallIntentToken` (`rotate` / `consume`).
- `SafetyCallNotice.decide` est une fonction **pure** rendant trois états exclusifs : `None`,
  `Warning(hoursLeft)`, `Sequence(delivered, total, inFlight, terminal, canRearm, triggeredAt)`.
  `reconcile` retire l'autre identifiant **avant** de publier.
- Dans l'état `terminal`, le Safety Call est **déjà désactivé** (`enabled = false`) : le désarmement
  est écrit dans la même transaction que la conclusion du dernier envoi. Aucune alarme n'est
  programmée.
- `withActivityReset(disarmIfTriggered)` archive le cycle courant dans `history`, remet
  `triggeredAt`/`messagesSent`/`claimedAt` à zéro, et **incrémente `generation`** — ce qui invalide
  tout worker parti sur le cycle précédent. `claimId` n'est jamais remis à zéro (doit rester
  strictement croissant).
- Le thème comprend **Material You** : `surface` peut dériver du fond d'écran.
- Il existe un **mode leurre** (`PanicDecoy`) : sous contrainte, rien ne doit révéler qu'une
  fonction d'alerte existe.

## Ce que je te demande de chercher, par ordre de valeur

1. **Une faille de sûreté dans l'asymétrie du `deleteIntent`.** Il n'est posé que sur l'état
   terminal. Mon argument est qu'acquitter y est inoffensif car la protection est déjà coupée.
   Cherche un état où `terminal` serait vrai alors que le deadman est encore armé, ou un chemin par
   lequel ce `deleteIntent` serait déclenché hors état terminal (`PendingIntent` mis en cache par le
   système et rejoué ? `FLAG_UPDATE_CURRENT` ? une notification republiée avec un intent d'une
   publication antérieure ?).
2. **Un `deleteIntent` déclenché par un retrait PROGRAMMATIQUE.** J'affirme que
   `NotificationManager.cancel()` ne le déclenche pas et que seul un rejet utilisateur le fait. Si
   c'est faux sur une version d'Android ou via un chemin particulier, alors chaque réconciliation
   archiverait le cycle qu'elle vient d'afficher — défaut majeur.
3. **Une perte de confirmation** dans `SafetyCallAckOverlay` : un cas où l'utilisateur fait le geste
   et ne voit jamais rien (recomposition, changement de configuration, rotation, `showLock` qui
   bascule, process mort entre le geste et l'affichage).
4. **Une confirmation AFFIRMÉE À TORT** : un chemin où le message s'affiche alors que l'écriture n'a
   pas abouti. C'est le pire défaut possible ici.
5. **Une fuite en mode leurre** : la confirmation, ou la notification, visible en session `PanicDecoy`.
6. **Le calcul de contraste** `onBrandContainer` : correction de la formule WCAG 2.1, et tout site
   d'appel restant qui poserait encore un premier plan écrit en dur sur un fond de marque.

## Choix délibérés — NE PAS les signaler comme des défauts

- Le balayage de l'avertissement et de la séquence en cours est **inerte volontairement**. Il fait
  revenir l'information au lieu de la perdre. Ce n'est pas un oubli.
- L'absence de nonce sur le `deleteIntent` est raisonnée : récepteur non exporté, et un nonce
  propre invaliderait celui des autres surfaces (`consume` est mono-usage).
- L'absence de garde `appLock` sur ce récepteur est raisonnée : rien n'est envoyé, rien n'est
  désactivé.
- Le TTL de 120 s du porteur de confirmation est plus généreux que celui de la navigation en
  attente : c'est argumenté (une confirmation énonce un fait acquis, une navigation désigne une
  cible).
- Les 4 sites de **texte** orange posé sur `surface` sont connus, mesurés à 3,79:1 en thème clair,
  et **délibérément non corrigés** dans ce lot : sous Material You aucune couleur ne peut garantir
  un ratio contre un fond dérivé du fond d'écran. C'est une décision de design, pas un oubli.
- Style, nommage, longueur des commentaires, découpage en fichiers : hors sujet.
- `runCatching` autour d'un appel suspend : les deux occurrences relèvent désormais
  `CancellationException`. Ne pas resignaler celles-là.

## Format de réponse EXIGÉ

Pour **chaque** constat :

```
### <IDENTIFIANT> — <titre court>
- **Fichier:ligne** :
- **Gravité** : CRITIQUE | MAJEUR | MINEUR
- **Scénario d'échec concret** : la suite d'actions ou d'états qui produit le mauvais résultat.
  Un constat sans scénario reproductible n'est pas recevable.
- **Pourquoi ce n'est pas dans la liste des choix délibérés** :
- **Correctif proposé** :
```

« Aucun défaut sur ce motif » est une réponse attendue et utile. Ne remplis pas la réponse de
constats faibles pour faire nombre : je mesure la précision de chaque relecteur au fil des lots, et
un faux positif coûte plus qu'un silence.
