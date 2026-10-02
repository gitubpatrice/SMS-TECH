# SMS Tech — Modèle de sécurité

🇬🇧 [English](SECURITY.md) · Version française

Version actuelle : **v1.28.13** (2026-10-02)

Ce document décrit le modèle de menace contre lequel SMS Tech protège, les primitives
cryptographiques qu'elle utilise, les choix d'architecture qui donnent un sens à ces primitives, et
les limites connues au-delà desquelles l'application ne peut pas se défendre (parce qu'aucune
application ne le peut).

Si vous trouvez une vulnérabilité, merci de la signaler à **contact@files-tech.com** avec l'objet
`SMS Tech security report`. Nous répondons sous 5 jours ouvrés.

---

## Modèle de menace

> 📎 **Complément opérationnel : [`THREAT-MODEL.md`](THREAT-MODEL.md).** La table ci-dessous
> répond à « contre quoi l'app défend ». `THREAT-MODEL.md` répond à « **où est la garde qui fait
> autorité, et par quels chemins doit-elle passer** » : invariants, couche d'application, chemins
> jumeaux à recenser, et **non-garanties assumées** (superposition d'écran, presse-papier).
> Toute modification touchant une garde de sécurité doit être confrontée à ses invariants.

| Adversaire | Ce contre quoi nous protégeons | Comment |
|---|---|---|
| adb pull au niveau de l'appareil / image forensique | Corps des SMS / MMS en clair, contenu du coffre, brouillons de messages, enregistrements | Toute la base Room chiffrée au repos par SQLCipher ; clé dérivée d'une passphrase enveloppée dans l'Android Keystore. Caches transitoires (préparation des PDU, brouillons vocaux, exports) effacés au verrouillage automatique + lors d'une panique. |
| Téléphone perdu / volé (PIN inconnu) | Accès en lecture à la liste des conversations et au coffre | Verrou de l'application (PIN / passphrase / biométrie) avec temporisation exponentielle. `FLAG_SECURE` sur chaque surface sensible, pour que l'écran de verrouillage + l'aperçu des applications récentes ne laissent jamais fuir de contenu. |
| Déverrouillage sous contrainte (« montre-moi ton téléphone ») | Divulgation du coffre caché sous la contrainte | Le déverrouillage par code panique (état `PanicDecoy`) expose la liste de conversations standard tout en barrant chaque point d'entrée du coffre — l'UI masque l'icône du coffre, la navigation refuse la route, la couche de données renvoie des listes vides. |
| Rejeu d'une lecture biométrique réussie | Contournement de l'état de verrouillage via un « événement d'authentification » volé | Jeton de défi à usage unique émis par `AppLockManager.beginBiometricChallenge()`, consommé de façon atomique (`AtomicReference.getAndSet(null)`) par `markBiometricUnlocked(token)`. Un second appel avec un jeton périmé est sans effet. |
| PIN par force brute | Tentatives en ligne pour deviner un PIN court | PBKDF2-HMAC-SHA512 avec itérations calibrées (>= 210 k) + empreinte salée. Blocage exponentiel (5 s, 10 s, 30 s, 1 min, 2 min, 5 min) à partir de 5 échecs. `setLockoutUntil` borné à un horizon de 24 h dans le futur, pour qu'une restauration de sauvegarde altérée ne puisse pas rendre l'application inutilisable. |
| Composant malveillant sur le même appareil envoyant un Intent | Forger un broadcast `MmsSent` / `SmsSent` pour manipuler le statut d'une ligne | Chaque `PendingIntent` de rappel de résultat utilise un `Intent.setClass(context, ReceiverClass)` **explicite** plutôt qu'un `setPackage` implicite. Les récepteurs restent `exported = false`. |
| Côté opérateur / MITM | Intercepter les octets des messages en transit | **Hors périmètre.** Le SMS / MMS n'est pas chiffré, par conception du protocole ; nous ne pouvons pas y remédier. Les utilisateurs qui ont besoin d'un chiffrement du transport devraient utiliser Signal ou un équivalent. |
| SMS Safety call automatique non souhaité pendant une contrainte (v1.9.0) | Le déclenchement de la fonction homme mort alors que la victime est forcée dans la session `PanicDecoy` — ce qui révélerait ses contacts d'urgence à l'agresseur | `SafetyCallTriggerService` et `SafetyCallWorker` vérifient tous deux `AppLockManager.LockState.PanicDecoy` et court-circuitent avant tout envoi. Le tick du worker réessaie automatiquement une fois l'état leurre quitté. |
| Intent de réinitialisation Safety call usurpé (v1.9.0) | Une application tierce sur l'appareil forgeant `ACTION_SAFETY_CALL_RESET` (l'activité est `exported=true` à cause du rôle SMS) pour neutraliser l'homme mort à distance | `SafetyCallIntentToken` renouvelle un nonce `SecureRandom` de 63 bits à chaque notification d'avertissement ; `MainActivity` valide l'extra contre le jeton détenu en mémoire du processus et le brûle à la consommation. Un intent forgé sans jeton ou avec un mauvais jeton est journalisé et ignoré. |
| Réinitialisation sous contrainte du minuteur Safety call par ouverture de l'application (v1.9.0) | Un agresseur qui connaît l'icône de l'application pourrait ouvrir SMS Tech à répétition (sans PIN) pour neutraliser l'homme mort | `MainActivity.onResume` ne réinitialise `lastActivityAt` que lorsque `AppLockManager.LockState` vaut `Unlocked` ou `Disabled`. Les sessions `Locked` / `PanicDecoy` ne réinitialisent jamais le minuteur. |
| SMS d'urgence déclenché par l'utilisateur pendant une contrainte (v1.10.0) | Le nouveau mode urgence (bouton à maintenir 3 s) pourrait révéler les contacts d'urgence de la victime si l'agresseur force un déverrouillage vers `PanicDecoy` et voit le bouton URGENCE | Trois couches de défense : (a) le garde de navigation d'`AppRoot` retire les routes `Emergency` et `EmergencySetup` dès que `PanicDecoy` devient actif, (b) `SettingsScreen` masque les sections « Mode urgence » et « Safety call » quand `isPanicDecoy = true` (l'agresseur n'apprend pas que la fonction existe), (c) `TriggerEmergencyUseCase` vérifie le même état de verrouillage et court-circuite avant l'envoi de tout SMS. |
| Manipulation de l'horloge murale pour contourner le délai anti-spam (v1.10.0) | Un agresseur disposant du root avance `Settings.Global.AUTO_TIME=0; date <future>` pour sauter le délai de 60 s du mode urgence et redéclencher le SMS afin de harceler les contacts | `EmergencyConfig.isInAntiSpamWindow()` vérifie à la fois l'horloge murale ET `SystemClock.elapsedRealtime()`. Le délai est actif si L'UNE OU L'AUTRE horloge est encore dans la fenêtre. Un delta monotone négatif (après un redémarrage, avant la récupération de la dérive) est aussi traité comme « toujours dans le délai » — repli sûr face à root + redémarrage + horloge avancée. Même défense sur `SafetyCallConfig.isExpired()` (SEC-11) — les deux horloges doivent avoir expiré avant que l'homme mort se déclenche. |
| Manipulation de l'horloge murale pour déclencher le Safety call en avance (v1.10.0 SEC-11) | Un agresseur disposant du root avance l'horloge murale pour que `lastActivityAt + timeoutMs < now()` s'évalue immédiatement à `true` et déclenche le SMS afin d'exposer le réseau de soutien | `SafetyCallConfig.isExpired()` exige désormais que l'horloge murale ET l'horloge monotone aient TOUTES DEUX franchi `timeoutMs`. `SystemClock.elapsedRealtime()` n'est pas manipulable via Paramètres → Date et heure. La dérive entre les deux horloges déjoue l'attaque. La récupération de la dérive dans `MainApplication.onCreate` réaligne l'horloge monotone après un redémarrage si la valeur stockée dépasse le temps de fonctionnement actuel. |
| Double déclenchement du SMS d'urgence par une course dans l'UI (v1.10.0) | Un utilisateur paniqué maintient le bouton URGENCE, le relâche, puis le maintient de nouveau immédiatement avant la fin de l'écriture DataStore ; sans protection, un second SMS pourrait partir dans les ~50–300 ms | `EmergencyViewModel.trigger()` utilise `AtomicBoolean compareAndSet(false, true)` comme garde d'exécution en cours. Un second appel à `trigger()` pendant que le premier s'exécute encore rend la main immédiatement. Le drapeau est réinitialisé dans `finally`, si bien que toute exception dans le UseCase le libère quand même. |
| Fuite d'écouteur dans `LocationResolver` (v1.10.0) | Fuite théorique de l'écouteur GPS si une `SecurityException` est levée sur le fournisseur NETWORK après l'enregistrement de l'écouteur GPS | `awaitFirstFix` utilise `AtomicBoolean resumed` pour imposer une reprise unique sur `suspendCancellableCoroutine`, et `cleanup()` est toujours appelé dans le bloc `catch (SecurityException)`, quel que soit l'état de `resumed`. Le même `cleanup()` est branché dans `invokeOnCancellation`. |

---

## Normalisation de l'adresse sortante (v1.21.0)

Avant qu'un SMS / MMS soit remis à la pile téléphonie, la **destination** est normalisée en E.164
(`PhoneNumberUtils.formatNumberToE164`) à partir du pays de la SIM — ou d'une région par défaut
réglée par l'utilisateur (Paramètres → Envoi → « Pays par défaut ») lorsqu'elle est définie. Cela
corrige la non-distribution silencieuse lors d'un envoi depuis une SIM étrangère (un `06…` national
n'est pas routable à l'étranger ; le réseau a besoin de `+33…`).

Invariants relevant de la sécurité :

- **Seule l'adresse transmise sur le réseau change.** Les lignes des fournisseurs `content://sms` /
  `content://mms` et le miroir Room chiffré par SQLCipher conservent la chaîne brute d'origine de
  l'utilisateur — le regroupement en fils, l'affichage et l'historique sont inchangés octet pour
  octet. La normalisation a lieu exclusivement dans `SmsSender` / `MmsSender`, sur la destination
  transmise.
- **Repli ouvert sur la valeur brute.** Si la région est inconnue, si la valeur est un numéro court /
  un expéditeur alphanumérique, ou si ce n'est pas un numéro valide pour la région, la chaîne brute
  est envoyée telle quelle — aucune régression, et jamais un numéro du mauvais pays (un numéro
  national étranger ambigu donne `null` → brut).
- **Aucune journalisation de données personnelles.** `WireAddress` et `PhoneNumberWireFormatter` ne
  journalisent jamais un numéro de téléphone.
- **Aucune nouvelle surface d'attaque.** `formatNumberToE164` est une API de la plateforme ; le garde
  préexistant sur les cibles e-mail des MMS (`MmsBuilder.formatAddressForMms` rejetant `@`) n'est
  pas affecté.

---

## Primitives cryptographiques

| Sujet | Primitive | Source de la clé | Remarques |
|---|---|---|---|
| Base Room au repos | SQLCipher v4 | Passphrase aléatoire de 32 octets enveloppée par l'alias Keystore `db_master` | La passphrase désenveloppée reste résidente pendant toute la durée de vie du processus — SQLCipher la garde par référence et en a besoin pour rouvrir la base après un `close()`. L'effacer plus tôt (comme le faisaient les versions jusqu'à 1.23.4) revenait à transmettre à SQLCipher une clé entièrement nulle ; `LegacyZeroKeyRekey` rechiffre les bases concernées au premier lancement de la 1.24.0. |
| Clé d'enveloppe du coffre (réservée) | AES-256-GCM | Alias Keystore `vault_kek` (Android 9+) | Sert actuellement d'ancrage structurel ; le cloisonnement effectif du coffre est appliqué dans la couche de données via `in_vault = 1` + des vérifications de session. La v1.3.x doit ajouter une enveloppe distincte. |
| PIN / passphrase du verrou de l'application | PBKDF2-HMAC-SHA512 | Secret de l'utilisateur + sel aléatoire de 16 octets | Itérations calibrées sur l'appareil (>= 210 k) ; stockées dans DataStore avec le sel. Nous ne stockons jamais le secret lui-même. |
| Code panique | PBKDF2-HMAC-SHA512 | Mêmes paramètres que le PIN | La comparaison est en temps constant. Une correspondance avec le code panique remet le compteur d'échecs à 0, pour ne pas laisser d'empreinte des tentatives dans l'état persisté. |
| Blobs AEAD des réglages | AES-256-GCM | Alias Keystore `settings_aead` | Utilisé pour des préférences sensibles de faible volume (nom d'affichage en cache, futurs points d'accroche pour la récupération par un héritier). L'IV est aléatoire, de 12 octets, à chaque chiffrement. |
| Défi biométrique | 32 octets issus de `SecureRandom` | — | Usage unique, consommation atomique sur un seul fil (`AtomicReference.getAndSet(null)`). La comparaison utilise `MessageDigest.isEqual` pour éviter les canaux auxiliaires temporels. |

Toutes les clés Keystore sont non exportables, adossées au matériel lorsque c'est possible, et liées
à l'authentificateur biométrique sur le chemin BiometricPrompt (`setUserAuthenticationRequired = true`
sera branché en v1.3 une fois finalisée la décision sur le cloisonnement par biométrie forte — la
v1.2.0 est livrée avec la classe BIOMETRIC_WEAK pour l'empreinte digitale **OU** le visage).

---

## Historique des audits

### v1.28.13 — Ce qu'a révélé le refus de chaque permission, une par une

**Versions affectées : voir chaque point.** Signalé sur la demande de fusion F-Droid
(fdroid/fdroiddata!38458) par un testeur, by-architect, qui a lancé la 1.28.12 sous Android 16 en
refusant les permissions facultatives, comme le prévoit le protocole de test de F-Droid — puis
confirmé dans le code source par un second relecteur, mezinster. Le plantage trouvé a conduit à
passer en revue chaque permission, refusée une à une ; les trois points ci-dessous en sont issus, et
aucun ne fait planter l'application. Ils touchent à la sûreté d'une personne, d'où leur place ici.

#### Mode urgence : sans la permission d'appel, toucher 112 ne faisait rien

**Versions affectées : 1.14.1 à 1.28.12.** Disponibilité d'une fonction de sûreté des personnes, sur
l'écran que l'on atteint en situation de crise.

Sans `CALL_PHONE`, une tuile d'urgence ne faisait que redemander la permission, et la réponse
n'était lue par personne : refusée, la tuile ne faisait rien — ni appel, ni composeur, ni message.
Après deux refus, Android n'affiche plus la boîte de dialogue, et la tuile restait inerte pour de
bon. Les commentaires annonçaient un « repli sur le composeur » depuis la v1.14.1 ; il n'existait
pas. La permission était en outre lue à la composition : un accord pouvait rester sans effet tant que
rien ne recomposait l'écran.

Mesuré sur émulateur API 34, `CALL_PHONE` refusée : la 1.28.12 reste sur l'écran après le toucher de
112 ; la 1.28.13 ouvre le composeur avec 112 prérempli.

**Correctif.** La permission est lue au moment du toucher, et la réponse à la demande compose l'appel
ou ouvre le composeur, qui ne demande aucune permission. Un proche de confiance a le même repli par
`EmergencyCallHelper.openTrustedContactDialer`, puisque `openDialer` écarte tout numéro hors de la
liste des numéros d'urgence. L'audit d'avant publication a ensuite trouvé le jumeau : après deux
refus, les boutons « Autoriser la localisation » et « Autoriser la position exacte » ne faisaient
rien non plus. Un refus qui revient plus vite qu'une personne ne lit une boîte de dialogue ouvre
désormais la fiche Android de l'application, où la permission reste accordable — mesuré dans les deux
sens sur émulateur.

#### Une position « approximative » était traitée comme une absence de position

**Versions affectées : 1.10.0 à 1.28.12, sous Android 12 et suivants.** Le SMS d'urgence partait sans
position alors que l'utilisateur avait accepté d'en donner une.

Depuis Android 12, la boîte de dialogue de localisation propose « Approximative », qui n'accorde que
`ACCESS_COARSE_LOCATION`. Tous les contrôles de l'application testaient `ACCESS_FINE_LOCATION` : les
écrans affichaient « permission non accordée », et `LocationResolver` ne rendait rien. Ce contrôle
passé, la demande GPS lève `SecurityException` sans la position exacte — dans le même `try` que la
demande réseau, qui n'était donc jamais faite.

**Correctif.** L'une ou l'autre permission suffit, le GPS n'est utilisé qu'avec la position exacte,
et la précision du relevé est portée jusqu'au message : au-delà d'un kilomètre, le lien de carte est
suivi de `(+/-N km)`. Android décale une position approximative d'environ deux kilomètres alors que le
lien garde cinq décimales ; sans la marge, il désignerait la mauvaise rue — la variante « transmise
mais fausse » déjà écartée pour les positions périmées en v1.26.1. La marge s'écrit en ASCII : `±`
ferait passer tout le SMS en UCS-2.

#### Notifications coupées : le Safety call et le raccourci de l'écran verrouillé échouaient en silence

**Versions affectées : de leur introduction à la 1.28.12.**

Sans `POST_NOTIFICATIONS`, ou avec les notifications de l'application ou l'un de ses canaux coupés,
les notificateurs ne postent rien, par conception : `notify()` ne lève pas d'exception. Mais rien ne
le disait. Le Safety call perdait son avertissement avant envoi, la notification qui arrête une
séquence, et le signal « armé mais rien ne partira » de la v1.28.12 ; le raccourci de l'écran
verrouillé restait « activé » dans les Réglages sans exister nulle part.

**Correctif.** Une bannière sur l'écran du Safety call, sous l'interrupteur du raccourci et dans
Réglages → Notifications, relue à chaque retour au premier plan. Elle vérifie l'interrupteur global
**et** les canaux propres à chaque fonction (`CanauxVisibles.kt`), puisqu'Android permet de couper les
canaux un par un.

#### Deux renforcements qui ne sont pas des failles

Le plantage lui-même : avec `READ_CONTACTS` refusée, ouvrir « Nouveau message », ou n'importe quelle
conversation, levait une `SecurityException` non rattrapée. Le refus est rattrapé chez ces deux
appelants et volontairement **pas** dans le dépôt de contacts : `IncomingBlockPolicy` a besoin de
l'exception pour distinguer « vérification impossible » de « expéditeur inconnu », sans quoi
« bloquer les inconnus » bloquerait tous les messages entrants. Un test verrouille cet invariant.

Et l'application ne détient plus `INTERNET`. Elle n'ouvrait aucune connexion : les MMS passent par
`SmsManager`, et c'est le service MMS d'Android qui parle à l'opérateur. Mesuré sur un Galaxy S9 : le
processus de l'application est hors du groupe noyau `inet` (pas de gid 3003), avec Fennec mesuré dans
la même session comme témoin positif ; les MMS ont été confirmés dans les deux sens entre deux
téléphones. `.github/scripts/permissions-manifeste.py` fait échouer la CI si `INTERNET` atteint un
jour le manifeste fusionné release, et prouve d'abord qu'il sait échouer.

---

### v1.28.12 — Ce qu'a montré le fait de parcourir l'application dans une autre langue

**Versions affectées : voir chaque point.** Aucun signalement reçu. Les trois défauts ci-dessous
ont été trouvés en traduisant l'application, puis par cinq vagues d'audit et deux relectures
externes ; tous étaient présents dans la 1.28.11 publiée.

#### Le bandeau « Je vais bien » pouvait s'afficher en session leurre

**Versions affectées : 1.26.1 à 1.28.11.** C'est le threat model de l'application qui est en cause,
pas une commodité : la session leurre existe pour qu'une personne sous contrainte puisse céder un
code sans rien livrer.

`showIAmOkChip` porte bien `!isPanic`, mais il est calculé dans le flux **agrégé**, servi par
`stateIn(WhileSubscribed(5 s))`. Quand l'écran de verrouillage se pose par-dessus, cet écran quitte
la composition ; cinq secondes plus tard l'amont s'arrête et `state.value` **gèle**. Au retour, la
valeur en cache est servie — calculée avant le verrou, donc avec `isPanicDecoy = false` — jusqu'à ce
que le `combine` réémette, ce qui coûte une requête chiffrée et un appel Binder sur IO.

Le scénario : la victime déclenche l'urgence, les messages partent, quelqu'un lui prend le téléphone
et lui arrache le code panique. L'écran leurre s'ouvre — et le bandeau est là. **L'agresseur apprend
qu'un appel à l'aide vient de partir.** L'icône du coffre révélait qu'un coffre existe ; ceci révèle
l'alerte elle-même.

C'est exactement le mécanisme que l'audit H18 avait corrigé en v1.26.1, et le KDoc qu'il a laissé le
décrit mot pour mot. Ce correctif dérivait `isPanicDecoy` directement du verrou, en `Eagerly` — et
il a été appliqué à **ce seul champ**. Six emplacements de cet écran gardaient déjà la valeur
fraîche ; le bandeau était le septième, et le seul resté sur le cache.

**Correctif.** Le bloc devient son propre composable, avec le garde en **un** endroit au lieu du
milieu de quarante branches.

#### Safety call et mode urgence pouvaient s'armer puis se taire

**Versions affectées : 1.10.0 à 1.28.11**, dès lors que l'application ne détient pas le rôle SMS par
défaut. Disponibilité d'une fonction de sûreté des personnes — la pire des façons d'échouer, parce
que l'utilisateur croit être couvert.

`SendSmsUseCase` refuse tout envoi sans le rôle, et les deux fonctions passent par lui. À l'échéance,
l'envoi échouait, le créneau était libéré — ce qui réinitialise `triggeredAt`, donc referme la porte
`isTriggered` qui aurait posté un avis — et **rien n'était affiché**. Le tick suivant réessayait,
échouait pareil, indéfiniment, pendant que l'écran affichait « armé » et un décompte.

Aucun des deux écrans d'armement ne mentionnait le rôle : `isDefault` n'y figurait nulle part. Pire,
l'écran depuis lequel on **déclenche** l'urgence annonçait « prêt » alors que rien ne serait envoyé.

**Correctif.** Un bandeau commun signale le rôle manquant et propose de le demander, sur les trois
écrans concernés — armement du Safety call, préparation de l'urgence, et l'écran d'urgence lui-même.
Le bouton n'est **pas** désactivé : en situation de crise, un bouton mort est pire qu'un bouton qui
tente. Un test de câblage lit les trois sources et exige la présence du bandeau dans chacune.

#### Trois clés retirées survivaient à « Supprimer toutes mes données »

**Versions affectées : toutes celles où ces clés ont existé, jusqu'à 1.28.11.** Complétude de
l'effacement, pas confidentialité du chiffrement.

`PanicService.nukeEverything` ne **vide** pas le magasin de préférences : il écrit un `AppSettings()`
neuf par-dessus. Une clé que l'écrivain ne nomme pas n'est donc jamais touchée. `locale.tag`,
`locale.firstDay` et `advanced.isDefault` avaient cessé d'être lues, ce qui ne les avait pas
effacées : sur toute installation antérieure, la langue choisie survivait à une purge **qui se
déclarait complète** — et c'est cette version-ci qui a commencé à compter les résidus et à dire
quand elle n'avait pas tout effacé.

**Correctif.** L'écrivain retire ces clés à **chaque** écriture, depuis une liste nommée unique.
Toute clé retirée plus tard va là et nulle part ailleurs : c'est le seul endroit qui garantisse sa
disparition des installations existantes. Le test écrit les clés dans un vrai DataStore et **vérifie
d'abord qu'elles y sont** — sans quoi un magasin vide ferait passer le test sans rien mesurer.

#### Deux corrections de sûreté qui ne sont pas des failles

Les corps des SMS d'urgence et de Safety call étaient **codés en français en dur** depuis la
v1.10.0, hors de `strings.xml` — donc invisibles à tout contrôle de parité. Un destinataire
germanophone recevait un appel à l'aide qu'il ne pouvait pas lire. Et les appels d'urgence
composaient les numéros **de la France** où que soit le téléphone : le pays vient désormais du
**réseau** sur lequel il est enregistré, jamais de la langue de l'application.

---

### v1.28.11 — Un marqueur d'optimisation ne peut pas porter l'accès aux données

**Versions affectées : 1.25.0 à 1.28.10.** Disponibilité des données, pas confidentialité : le
chiffrement au repos, l'algorithme et le threat model sont inchangés. Aucun signalement reçu.

Depuis la 1.25.0, la base est ouverte en **clé brute** (cf. l'entrée v1.25.0 plus bas). Or la
réparation clé-nulle qui s'exécute juste avant, `LegacyZeroKeyRekey.rekeyIfNeeded`, sondait le
fichier avec la **passphrase en clair** — une sonde fausse par construction sur toute base écrite
depuis. La sonde de la clé nulle héritée échouait elle aussi, et le code concluait alors que la base
« ne se déchiffre avec rien » : `Failure` levée, écran de réparation, **messages et coffre refusés à
chaque lancement**, sans autre issue qu'une réinstallation — sur une application dont
`allowBackup=false` ne laisse aucun autre exemplaire.

La seule chose qui empêchait cette conclusion était le marqueur `shared_prefs/db_repair.xml`, écrit
par `apply()`, c'est-à-dire de façon **asynchrone**. Un processus tué avant que cette écriture
n'atteigne le disque perdait le marqueur en laissant la base intacte. `adb install -r` produit
exactement cet enchaînement ; le système aussi, quand il récupère un processus peu après son premier
lancement.

**Mesure A/B**, même appareil, mêmes fichiers, mêmes APK : **5 contrôles sur 5 passent avec le
marqueur, 0 sur 5 avec ce seul fichier retiré.**

**Correctif.** La sonde accepte **les deux formes de la même clé**, la brute d'abord — comme son
jumeau `ensureRawKeyed` l'a toujours fait. Le marqueur redevient une économie de travail et non
l'unique porte d'entrée des données. Les marqueurs passent en `.commit()` : un marqueur de
réparation dont l'écriture ne survit pas à la mort du processus ne remplit pas son office.

**Ce que le défaut dit du reste.** C'est, une troisième fois, le motif du correctif posé sur un seul
des deux chemins jumeaux (cf. v1.28.3). Il a été trouvé non par relecture mais par un contrôle
d'intégration continue neuf, qui installe la version précédente, sème un jeu d'essai dans la vraie
base chiffrée, installe la nouvelle par-dessus et exige que tout se relise — contrôles négatifs
compris. Régression figée par
`RawKeyMigrationTest.rawKeyedDb_withoutRepairFlag_isNotDeclaredUnreadable`, écrite ROUGE avant le
correctif.

### v1.28.9 — Ce qui résiste est gardé et dit, jamais annoncé effacé

Septième note d'Andrew Pozdnakov sur la MR F-Droid !38458 : cinq constats déduits du source 1.28.8, tous
confirmés dans le code et corrigés. Leur point commun : un effacement qui échouait, ou qui ne savait pas,
était traité comme un effacement réussi.

**Fichiers de pièces jointes partagés.** Un même fichier est cité par plusieurs lignes (un envoi à
plusieurs destinataires, l'écho d'un groupe, un envoi programmé). Le premier effacement l'emportait et
laissait les autres lignes vers un fichier absent. Un fichier ne part plus qu'avec sa dernière citation ;
une lecture des citations qui échoue le garde et compte l'échec. Même règle pour la purge de rétention,
dont les fichiers restaient sur le téléphone sans plus rien pour y mener.

**Conversation du coffre dont la copie système résiste.** Sans le rôle SMS, ou sur un refus du fournisseur,
elle disparaissait de l'application et la resynchronisation la recréait **hors du coffre**, en clair. Elle
est désormais gardée dans le coffre, et l'utilisateur en est averti. Hors coffre, le contrat reste celui
d'avant : la conversation disparaît, sa copie système peut revenir.

**« Supprimer toutes mes données ».** Une liste des conversations illisible passait pour une liste vide,
et le dialogue disait « effacé » ; en session leurre, rien n'était effacé. Ce qui résiste — copies
système, échecs locaux, liste illisible — est compté et dit. Les alias Keystore sont relus après leur
suppression (`deleteKey` avale ses erreurs), et la clé du second facteur biométrique, oubliée de la
liste, y est ajoutée.

**MMS reçus (F17).** Aucun MMS entrant n'est écrit dans `content://mms` : le PDU téléchargé est la seule
copie. Quand un média ne pouvait pas être écrit, il était gardé mais jamais rouvert, puis balayé à 24 h.
Il est repris : une clé de transaction — SHA-256 du `transactionId`, de l'adresse de téléchargement et de
la SIM, chaque champ préfixé par sa longueur — est portée par le nom du fichier et par la base (schéma 14,
index unique ; exclue des sauvegardes). Ni l'adresse du MMSC, qui peut porter un jeton, ni l'identifiant
opérateur n'apparaissent en clair. La reprise est idempotente ; la présence du PDU est relue **dans** la
transaction d'écriture, si bien qu'un message supprimé pendant la reprise ne ressuscite pas ; le PDU part
avec son message, et un PDU arrivé pendant une suppression garde la conversation. Un média qui échoue dans
une conversation du coffre ne publie aucune notification d'échec, qui nommerait le correspondant — mesuré
sur appareil.

**Limites écrites.** Un PDU écrit avant cette version (sans clé) ou sans expéditeur n'est pas repris ; un
envoi programmé déjà remis à la radio n'est pas rappelé.

**Vérification.** Chaque garde a son test (Room et fournisseur réels pour les chemins de données) et son
contrôle négatif lu dans le rapport XML : 53 mutations, toutes tombées. Tests sur Galaxy S9 (Android 10)
et S24 (Android 16) des quatre scénarios, rôle SMS retiré et rendu. Relectures : Gemini 3.1 Pro
(conception F17), GPT 5.2 (code), audits data-room, cohérence et 3 axes — aucun constat critique ni élevé.

### v1.28.8 — La recherche traverse l'historique, jamais le coffre

**La recherche dans le texte des messages est branchée** (issue GitHub #17). Elle existait côté
données sans aucun appelant ; le champ ne cherchait que le nom, le numéro et le dernier aperçu.
Portée sécurité : les résultats sont bornés **dans le SQL** — jamais un message d'une conversation du
coffre (`in_vault = 0`), donc rien de plus en session leurre, qui voit la même liste hors coffre ;
jamais une ligne de service (`hidden = 0`) ; le texte seul, jamais les numéros. Les conversations des
résultats sont relues avec la même exigence dans le SQL, contre un passage au coffre entre deux
lectures, et le flux se réémet quand une conversation passe au coffre pendant qu'une recherche est
affichée — mesuré sur appareil. Chaque garde a son test instrumenté et son contrôle négatif. L'index
plein texte couvre toute la base, coffre compris, dans la même base chiffrée : seuls les résultats
sont bornés, comme avant cette version.

**Épinglées en tête dans tous les tris**, et **isolement des tests DataStore** : sans portée sécurité.
Le second touche `SecurityStore` (constructeur principal recevant le `DataStore`, constructeur injecté
inchangé) : graphe Hilt, fichier, clés et comportements identiques — relu par GPT 5.2 et par un audit
sécurité dédié.

### v1.28.7 — Ce qui est supprimé ne s'affiche plus, sur tous les chemins

**Trois suppressions laissaient leurs notifications**, consignées sans correction en 1.28.6 par la
relecture sécurité du delta final : la purge de rétention (un `DELETE` de masse), la réconciliation de
synchronisation quand un message disparaît du fournisseur par une autre application, et la fusion de
conversations en double. Un message effacé restait lisible dans le volet, expéditeur et texte compris.
Les deux premières relèvent désormais les messages qu'elles vont effacer — dans la même transaction et
avec la même clause SQL que le `DELETE`, coffre exclu — puis annulent leurs notifications après la
validation, et seulement si des lignes sont parties ; la fusion annule celles des conversations
victimes réellement supprimées. L'annulation groupée lit une seule fois les notifications actives et
n'annule que les paires tag + identifiant visées, jamais une notification sans tag. L'inventaire de
toutes les suppressions de l'application est fermé : chacune annule ses notifications, ou ne peut pas en
avoir.

Une relecture GPT 5.2 a trouvé une erreur dans le correctif lui-même avant publication : la fusion
annulait les victimes de tous les plans, y compris d'un plan sauté sans rien supprimer — donc les
notifications de conversations toujours présentes. Corrigée.

**Bouton ⋮ écrasé sur écran étroit** (sans portée sécurité ; accessibilité) : la bulle, plafonnée à une
largeur fixe, était mesurée avant le bouton — 16 dp au lieu de 40 sur un écran de 360 dp avec un long
message, 0 dp sur 320 dp, menu alors inatteignable. Seules les bulles reçues étaient touchées.

### v1.28.6 — Une seconde voie de copie, alignée sur la première

*Deux retours d'utilisateurs, sans rapport avec la relecture F-Droid.*

**La sélection libre d'un extrait de message copie par le menu système**, donc par
`LocalClipboard` de Compose et non par `copyToClipboardSensitive`. Sans rien, un extrait d'un
message du coffre ressortait en vignette d'aperçu sous Android 13+ — le défaut N4 exactement,
rouvert par une voie neuve. `SensitiveClipboard` enveloppe le presse-papiers Compose sous le
conteneur de sélection et pose la même marque ; `ClipData.markSensitive()` est l'unique écriture
de cette marque, désormais posée sur toute version (le système l'ignore avant Android 13), ce qui
la rend **vérifiable** sur l'appareil de mesure. Test instrumenté : appui long, copie par la barre
d'outils, lecture de la description du clip système ; contrôle négatif : sans l'enveloppe, la
marque est absente.

**« Supprimer toutes mes données » s'exécutait en session leurre, en totalité.** Trouvé par un
audit de motif lancé après le correctif du splash, pour chercher ses voisins : le bouton est
volontairement visible en leurre (v1.27.11, même raison que « Réinitialiser tous les réglages » :
une application SMS ordinaire sait s'effacer), mais son effet détruisait le coffre réel, le PIN et
le code panique depuis une session dont la raison d'être est de les préserver. Aucun test ne
couvrait `nukeEverything`. En leurre, la purge n'efface désormais que ce que le leurre montre :
chaque conversation hors coffre par `ConversationEraser` en mode ordinaire (copie système, envois
programmés, fichiers, comme une suppression à la main), les fichiers transitoires, et les réglages
en préservant le bloc sécurité — le splash se rejoue, l'écran est celui d'une purge réelle. La base,
sa clé, le Keystore, le PIN, le code panique et les compteurs ne bougent pas. La branche est prise
dans `PanicService`, point d'entrée unique, et non dans l'écran. Trois tests unitaires, contrôle
négatif fait. Gravité retenue : moyenne — le leurre protège l'existence du coffre, pas sa
disponibilité, et qui tient le téléphone peut désinstaller ; mais perdre le coffre ET le code
panique en trois tapes depuis le leurre n'était pas acceptable.

**« Supprimer toutes mes données » ne supprimait aucun message du téléphone.** Soulevé par
Patrice, d'une question qui valait mieux qu'un audit : si l'on supprime tout, c'est pour supprimer
les messages. La purge détruisait le **fichier** de base sans jamais passer par
`ConversationEraser`, seul chemin qui propage au fournisseur du système. La copie de chaque message
restait donc dans `content://sms`, et la resynchronisation du lancement suivant — curseur remis à
zéro par cette même purge — les ramenait **tous**. Y compris ceux du COFFRE, dans la liste
principale et **en clair** : leur copie système n'a jamais été supprimée (limite N2, assumée) et le
drapeau `in_vault` ne vivait que dans la base qu'on venait de détruire. C'était le seul chemin de
suppression de l'application à contourner le téléphone. Désormais : balayage de toutes les
conversations par l'effaceur, sous la barrière de purge, avant la destruction de la base ; ce qui
résiste est compté et **dit** avant le redémarrage, au lieu d'être promis effacé. Mesuré de bout en
bout sur S9 avec le rôle SMS détenu par l'application : 10 messages dans le téléphone avant, 0
après, 0 copie système restante.

**La purge pouvait bloquer l'application définitivement**, sur la 1.28.5 déjà publiée. Elle détruit
la base, le fichier de clé enrobée et les alias du Keystore, mais le processus SURVIT en gardant la
passphrase SQLCipher en mémoire — `DatabaseFactory` documente pourquoi on ne peut pas l'effacer. La
moindre réouverture de Room réécrivait une base chiffrée avec une clé dont l'enrobage n'existait
plus, et le lancement suivant affichait « ne parvient pas à ouvrir sa base de données », pour
toujours. Deux couches : le processus redémarre après la purge, et au démarrage une base qu'aucune
clé existante n'ouvre — clé enrobée absente — est écartée au lieu de bloquer, ce qui répare les
installations déjà bloquées. **La doctrine F18 reste entière** : clé enrobée PRÉSENTE et base
illisible lève toujours, sans rien supprimer, et un test instrumenté tient ce contrôle positif.

**Le magasin sécurisé ne partait que par huit clés nommées sur dix-huit.** Survivaient à la purge
l'empreinte du PIN **du coffre** (v1.13.0), sa temporisation (v1.27.10), l'horodatage du dernier
déverrouillage et le jeton persistant de notification — dans un DataStore de préférences **non
chiffré**. Une empreinte PBKDF2 de code à quatre chiffres se casse hors ligne, et sa seule présence
prouve qu'un coffre a existé, ce que le leurre existe pour taire. Aucun défaut de comportement en
revanche, vérifié : la porte du coffre exige l'empreinte ET le drapeau `vaultPinEnabled`, que la
purge remet à faux, et reposer un PIN réécrit l'empreinte en purgeant la temporisation. C'est le
motif d'asymétrie habituel de ce dépôt — `pin.*` et `panic.*` effacés, leur jumeau `vault.*` jamais
rattaché à la liste. Corrigé par un `clearAll()` unique : une liste de clés à tenir à jour est un
rendez-vous manqué à chaque nouvelle clé, et il a été manqué trois fois. La session leurre ne
l'appelle jamais, et un test le tient.

**Les notifications survivaient à toute suppression.** Le seul appel d'annulation du dépôt vivait
dans « marquer comme lu » : l'effaceur, règle unique de suppression depuis la v1.28.1, n'avait même
pas la dépendance. Supprimer une conversation non lue laissait son expéditeur et son texte dans le
volet système ; « Supprimer toutes mes données » les y laissait tous, après un dialogue qui promet
l'irréversible, et au pire moment — on purge parce que quelqu'un va prendre le téléphone. Celle du
raccourci d'urgence est `ongoing`, donc pas même balayable à la main. Borne vérifiée : une
conversation du coffre ne notifie jamais, la purge du coffre ne laissait donc rien. L'annulation est
posée dans l'effaceur — la suppression ordinaire et celle d'un seul message sont réparées du même
geste — et la purge totale annule tout, dans les deux sessions avec le même effet visible (une
différence entre leurre et session réelle serait la fuite que I1 interdit).

**Le presse-papiers aussi.** Cette version ajoute la copie d'un extrait de message : on
sélectionne, on copie, on purge — et le texte restait dans le presse-papiers du téléphone, lisible
par toute application. Le presse-papiers est hors du périmètre du coffre (I7/N4), limite assumée et
écrite ; ce qui ne l'était pas, c'est qu'une purge se disant irréversible le laisse garni. Il est
vidé dans les deux sessions, au même point que les notifications. `clearPrimaryClip` demande
Android 9 alors que `minSdk` vaut 26 : un repli par clip vide couvre Android 8, faute de quoi la
ligne n'y aurait rien fait sans le signaler. Android 10 et suivants n'autorisent cette écriture qu'à
l'application au premier plan — ce qu'elle est au moment du tap —, et le code le dit plutôt que de
le promettre.

**Ce que l'annulation des notifications ne couvre pas encore.** La relecture sécurité du delta
final a trouvé trois chemins qui suppriment sans passer par l'effaceur, et laissent donc leurs
notifications : la purge de rétention, la réconciliation de synchronisation quand un message
disparaît du fournisseur par une autre application, et la fusion de doublons. Préexistants et
d'impact borné — une notification ne survit pas au redémarrage du téléphone, la rétention vise des
messages anciens, la fusion ne supprime aucun message —, ils sont consignés pour une version
ultérieure plutôt que corrigés au dernier moment. De même, `cancelAll()` ne retire pas la
notification d'un service au premier plan actif : celle du mode « résistant » part quand la remise à
zéro des réglages arrête le service. La même relecture a trouvé la seule étape de la purge sans
filet — le presse-papiers, dont une exception aurait fait planter l'application après la destruction
des données et avant le dialogue de confirmation — ; elle est corrigée.

**La purge n'était non annulable que sur sa fin**, et c'est l'audit pré-release qui l'a trouvé, sur
du code écrit dans cette même version. Elle vit dans un `viewModelScope` ; la v1.28.5 avait mis ses
écritures finales sous `NonCancellable` pour cette raison précise, et le balayage des conversations
ajouté ici est passé **au-dessus** de ce bloc, alors qu'il est suspendu et long. Quitter les
Réglages pendant la purge annulait le balayage, puis les étapes synchrones détruisaient la base :
les messages non propagés revenaient à la synchronisation suivante — le défaut que cette version
ferme. La garantie est portée par la fonction entière. Le commentaire qui justifiait `exitProcess`
affirmait « `NonCancellable` de bout en bout » : il était faux quand il a été écrit, il est
rectifié et nomme l'endroit où l'invariant est tenu. *Une justification qui s'appuie sur un
invariant doit dire où il est tenu, faute de quoi elle survit à sa propre vérité.*

**L'écran de bienvenue restait bloqué après une réinitialisation** (garde d'idempotence jamais
réarmé). Pas de portée sécurité, mais le chemin est celui de « Supprimer toutes mes données » :
un utilisateur qui vide l'application doit pouvoir la reprendre sans la tuer.

### v1.28.5 — Six arêtes déduites du source, toutes réelles

*Sixième note d'Andrew Pozdnakov sur la MR F-Droid !38458 (2026-09-11) : il clôt R01/R02/R03
sur revue du source de la 1.28.4, puis liste cinq candidats déduits du code et non mesurés, plus
une arête mineure. Vérifiés un par un dans le source avant de répondre : **les six sont réels.**
Registre : `audits_relectures_IA/audits-ia-externe/2026-09-11-andrew-pozdnakov-mr38458-6e-note-6-aretes.md`.*

**1. La sortie assumée levait plus que la condition « copie système ».** `purgeVault(force = true)`
appelait `erase(preserveOnSystemFailure = false)`, et ce même booléen gardait aussi le compte des
dépendants et la relecture d'arrivée tardive. Sous `force`, un `delete()` refusé ou un `cancel()`
qui lève passait donc à la suppression du parent avec `localeComplete = true` : le journal de
reprise disparaissait, la purge se disait localement complète, et `residuSystemeSeul` autorisait
le retrait du PIN. **Ma note du 10 septembre affirmait le contraire du code** — j'ai décrit
l'intention, pas la ligne. Trois contrats, trois noms : `ConversationEraser.Mode` (`ORDINAIRE`,
`COFFRE`, `COFFRE_FORCE`). Sous `COFFRE_FORCE`, seule la condition « copie système » est levée ; un
dépendant qui résiste garde le parent ; un message arrivé tard part, compté en résidu système.
Quatre tests sur Room réel, avec le vrai producteur d'échec (dossier `0500`, `cancel()` qui lève,
message inséré pendant le balayage), et le contrôle positif de ce que `force` lève encore.

**2. La barrière de purge était un test-puis-agir.** Une entrée lisait `enCours`, puis écrivait en
base plus tard ; une purge levée entre les deux ne la voyait pas et pouvait relire `remaining = 0`
avant son commit. Et la barrière retombait **avant** le retrait du PIN. `VaultPurgeBarrier` est
linéarisée : une entrée s'inscrit sous le même verrou que celui qui lève la purge, la purge attend
les entrées inscrites avant elle, une entrée arrivée après est refusée. Le retrait du PIN s'exécute
**sous la barrière**, par un rappel `apresPurge` que `deleteAllInVault` invoque avec le résultat
avant de l'abaisser ; le ViewModel y prend sa décision une fois, et ses événements découlent de ce
qui a été fait. Tests : entrée en vol attendue puis commise, nouvelle entrée refusée pendant
l'attente, entrée qui lève désinscrite quand même, PIN retiré barrière levée (mesuré, pas supposé).

**3. MMS de groupe : miroir avec les bloqués, PDU sans eux.** Le fil local était `A+B+C`, le MMS
transmis `A+B` ; le rapprochement à la réception exigeant le même ensemble de membres, la réponse
de A revenait dans un second groupe. Le miroir reçoit désormais les mêmes cibles que le PDU, dans
les deux voies (photo, vocal). Test : trois membres dont un bloqué, le miroir porte `A;B`.

**4. Le garde d'abaissement échouait ouvert.** `countInVault()` en échec devenait `0` et le facteur
illisible `null` — exactement la combinaison qui autorise l'abaissement, obtenue sans rien lire.
Les deux lectures rendent `VaultStateUnknown`, refus dit à l'écran. Tests d'injection de panne sur
chaque lecture, contrôle positif sur un coffre lu vide.

**5. Le geste « Je vais bien » venu d'une notification était retenu en session leurre.** La
politique « attendre l'authentification » était voulue, mais fausse pour `LockedOut` et
`PanicDecoy` : une ouverture réelle ne fait que remettre le minuteur à zéro, jamais désarmer ; un
geste retenu pendant le leurre ajoutait donc, au premier vrai déverrouillage, un désarmement que
l'utilisateur n'avait pas fait. Le geste est **jeté** sur ces deux états, retenu sur `Locked`
seulement, et la notification est republiée pour qu'un geste légitime reste possible. Le jumeau
de remise à zéro, qui ne désarme rien, continue d'attendre.

**6. Copie bornée : un fichier partiel après exception.** `LectureBornee.recopier` ne nettoyait
que sur dépassement ; une source qui lève au milieu laissait ce qui était écrit. Le fichier part
avant que l'exception ne remonte. Test : source qui lâche après 200 octets.

**7. Trouvé en vérifiant le point 2, pas signalé.** En cherchant « tout autre écrivain de
`in_vault` qui contourne la barrière » : la **restauration d'une sauvegarde** insère des
conversations avec le `in_vault` de la sauvegarde (`BackupService.importPayload`), hors barrière.
Une restauration lancée pendant une purge pouvait donc remplir le coffre entre la relecture de
`remaining` et le retrait du PIN. La restauration s'inscrit désormais comme une entrée
(`barriere.enEntrant`) ; une purge levée la refuse avant de lire un octet, passphrase effacée.
Test avec doublures strictes, contrôle positif existant conservé. Dans le même geste,
`ConversationRepository.moveToVault(id, inVault)` — un `setInVault` nu, sans barrière ni second
facteur, sans aucun appelant depuis que `VaultManager` porte les gardes — est **retirée** de
l'interface : une voie non gardée laissée publique à côté de sa jumelle gardée est un piège pour
le prochain appelant, et l'audit du 2026-08-03 (F11) l'avait déjà demandé.

**8. Deux dérives de cohérence, trouvées par l'audit de l'application entière lancé dans la
foulée.** (a) **Supprimer UN message laissait ses fichiers en clair** : `deleteMessage` vivait
dans le repository, hors de `ConversationEraser` ; la ligne `attachments` partait en cascade, le
fichier de `filesDir` restait — la classe de défaut fermée en 1.28.3 (F04) pour la conversation
entière, rouverte sur le chemin le plus fréquent, supprimer un MMS gênant. `eraseMessage` vit
désormais dans l'effaceur, avec le même bac à sable ; le repository délègue. Test sur Room réel,
fichier réellement créé puis mesuré absent. (b) La **réponse rapide depuis un appel**
(`HeadlessSmsSendService`) appelait `SendSmsUseCase` en direct : troisième point d'envoi, celui
que l'aiguillage unique du 2026-09-10 n'avait pas vu. Il passe par `EnvoyerMessageUseCase`.

**9. Lecture ciblée sur les angles morts du coffre en session leurre** (agent, application
entière). Quinze surfaces de lecture vérifiées une à une — recherche plein texte, notification
entrante, identité du correspondant, badge de non-lus, marquage lu, envois programmés, export
PDF, deep-link, partage entrant : **toutes gardées**, `in_vault = 0` en SQL ou garde de session.
Deux points moyens corrigés : (a) le chemin « Réactiver » de la notification Safety Call
(`ACTION_SAFETY_CALL_REARM`) n'avait pas le garde de son jumeau — écriture persistée des réglages
de sécurité sans preuve de déverrouillage ; il applique désormais `attendreOuvertureOuJeter`.
(b) Restaurer une sauvegarde contenant des conversations du coffre sur un appareil **sans second
facteur** les rendait lisibles en deux tapes sans que rien ne le dise. Ce n'est pas un trou de la
restauration (écrire au coffre n'exige pas de secret, `VaultSecondFactor.NONE` est une
configuration assumée), c'est un silence : `RestoreResult.vaultRestoredWithoutSecondFactor` le
dit à l'écran et renvoie vers les Réglages.

**10. Balayage des 245 `runCatching` du dépôt** (agent, lecture seule) : lesquels englobent un
appel `suspend` sans relancer `CancellationException`. Un constat de **sécurité** : les quatre
écritures finales de « supprimer toutes mes données » (`PanicService.nukeEverything` — retrait
du PIN, du code panique, des compteurs de verrouillage, des réglages) vivaient dans un
`viewModelScope` ; si l'écran des Réglages quittait la pile à cet instant, chaque `suspend`
levait une annulation que `runCatching` avalait **sans un mot**, et la fonction rendait la main
en laissant le PIN et les contacts du Safety call. Les quatre écritures sont désormais sous
`NonCancellable`, échecs journalisés. Cinq sites de données corrigés par un helper commun
`runCatchingCancellable` qui laisse remonter l'annulation : cache négatif des noms de contacts
(empoisonné par une annulation), fichiers d'un envoi programmé annulé (jamais effacés),
instantané de région de numérotation (F-01 rouvert), ligne système précédente d'un MMS
(orpheline dans `content://mms`), et `runCatchingOutcome` lui-même (export et restauration
interrompus s'affichaient « échec de stockage »). Les ~30 autres sites relevés sont du bruit de
journal avec un repli du côté sûr ; listés dans le registre, non modifiés.

**11. Relecture externe (Gemini) de la seconde vague**, deux constats retenus : (a) conséquence
directe du point 10, la passphrase de restauration n'était plus effacée quand l'annulation
remontait — `restaurer` est en `try/finally` ; (b) préexistant depuis la 1.28.3, l'attente du
geste « Je vais bien » vivait dans un `lifecycleScope.launch` qui survit à l'arrière-plan :
notification tapée puis application laissée verrouillée, le geste s'exécutait au premier
déverrouillage venu, des heures plus tard. `lancerGesteDeNotification` borne les deux jumeaux
au premier plan : à l'arrêt de l'activité, le geste en attente est annulé et la notification
republiée.

### v1.28.4 — Un nettoyage incomplet rapporté complet

*Cinquième note d'Andrew Pozdnakov sur la MR F-Droid !38458 (2026-09-10) : il a resserré sur la
purge du coffre (F03/F04/F09/F13) et mesuré, sur émulateur Android 14 avec la base SQLCipher
réelle et le graphe Hilt de production, **trois cas où la purge se dit complète alors qu'il
reste quelque chose**. Registre : même fichier que la 1.28.3, section « Cinquième note ».*

**Ce qu'il a trouvé, et pourquoi c'est grave.** La 1.28.3 avait rendu la purge reprenable en
conservant le parent quand la copie système résiste. Mais ses **dépendants** — envois programmés,
fichiers de pièces jointes — étaient nettoyés par des aides qui ne rendaient rien : une exception
gobée, un `delete()` à `false` journalisé et oublié. La branche de succès ordinaire était donc
atteinte **sans `force`**, le parent partait, le PIN était retiré, `VaultPurged` émis — et un
envoi programmé orphelin **redevenait visible hors coffre** (`COALESCE(c.in_vault, 0)`), sans que
la reprise puisse jamais le retrouver, son parent ayant disparu. Troisième cas : un message
importé par la synchronisation de production qui **commet entre la seconde relecture et le
`DELETE` du parent** est emporté par la cascade, copie système intacte, purge « complète ».

**Ce qui change.** `ConversationEraser.erase` rend `Issue(systemCopyGone, localeComplete)`. Les
deux aides rendent un compte d'échecs (énumération ratée = échec, `delete()` à `false` = échec) ;
un seul échec **garde le parent** pour le coffre et compte en `localFailures`, donc jamais
« complet ». La relecture et la suppression du parent vivent dans **une seule transaction Room** :
SQLite n'ayant qu'un écrivain, un import qui commet pendant la purge attend le verrou et ne peut
plus se glisser entre les deux. Les lignes programmées sont supprimées dans cette même
transaction, plus par l'aide.

**Ce que la mesure a ajouté.** Trois tests sur Room réel reproduisent R01 (`TRIGGER` refusant le
`DELETE`), R02 (dossier `0500`, `delete()` mesuré à `false`), R03 (message inséré au point
d'injection de l'ordonnanceur), chacun avec sa reprise. Le **contrôle négatif de R01 ne tombait
pas** : le `DELETE` refusé vit désormais dans la transaction finale et remonte par un autre
chemin — le compte d'échecs de l'annulation elle-même n'était couvert par rien. Un quatrième test
fait lever `cancel()` de l'ordonnanceur et tombe seul quand ce compte est neutralisé.

**Deux branches du coffre jamais testées depuis la v1.26.1**, fermées : le refus d'export **et de
restauration** en session leurre, atteinte par le vrai chemin (PIN principal, code panique,
déverrouillage par le code panique) ; et le second facteur **biométrique**, que la politique doit
rendre pour un coffre sans PIN de coffre et que l'export doit respecter. Contrôles négatifs sur la
politique, sur le garde d'export, sur le garde de restauration.

**Deux limites, dites plutôt que tues.** (1) Annuler un envoi programmé est une demande à
WorkManager : une opération radio déjà remise par une tentative en cours n'est pas rappelée, la
ligne est retirée et, si le radio avait déjà accepté l'envoi, le message part. (2) Un message qui
commet **après** la transaction finale vise un parent disparu : Room applique la clé étrangère,
l'insertion échoue au lieu de créer un orphelin protégé, et la ligne du fournisseur est importée
plus tard dans une conversation ordinaire neuve — comme tout message de ce correspondant arrivé
après la purge. Le coffre protège ce que l'application connaît.

**Hors sécurité, dans la même version** : MMS de groupe (réglage désactivé par défaut), colonne
`hidden` (schéma 13) pour ne plus reconnaître une sentinelle de réaction par sa forme, boucle
d'envoi et aiguillage écrits une seule fois après une quatrième divergence entre chemins jumeaux.

### v1.28.3 — Un correctif posé sur un seul des chemins qui en avaient besoin

*Quatrième passe de la relecture externe d'Andrew Pozdnakov sur la MR F-Droid !38458 — 33
constats, tous vérifiés dans le source avant correction, 32 corrigés — suivie d'un audit global
et d'une session de mesure sur deux téléphones. Registre :
`audits_relectures_IA/audits-ia-externe/2026-09-09-andrew-pozdnakov-mr38458-4e-passe-33-findings.md`.*

**Le motif dominant, qui vaut plus que la liste.** La grande majorité de ces défauts étaient des
correctifs **déjà écrits**, mais posés sur un seul des chemins qui en avaient besoin. Le dépôt
savait, et le disait souvent en commentaire : la sauvegarde décrivait mot pour mot le mécanisme de
F01 et le contournait pour elle seule ; le notificateur des messages entrants appliquait les trois
gardes de rédaction que celui des échecs n'avait pas (F08) ; le chemin sortant gérait plusieurs
pièces jointes quand le chemin entrant n'en gardait qu'une (F16). Et le motif s'est reproduit
**pendant la correction** : F21 posé sur la voie SMS seule alors que le même `continue` muet
vivait sur les deux voies MMS ; puis sur leur appelant de fond, l'envoi programmé.

**Deuxième motif : les affirmations d'exhaustivité vieillissent mal.** « La SEULE voie de lecture
non gardée », « three dialogs, one setting, consistent », « toute migration est additive » — trois
commentaires faux, dont un a masqué F02 pendant deux versions.

**Ce qui touche la sécurité, en substance.**
- **F01** — deux conversations locales sans fil système partageaient la sentinelle `thread_id = 0`
  sous un index UNIQUE : la seconde effaçait la première, avec ses favoris, réactions et son
  appartenance au coffre. `thread_id` est nullable (migration 8 → 9), l'insertion ne remplace
  plus.
- **F02, F03, F04, F09, F10, F11** — le coffre : ses messages programmés se lisaient sans le PIN,
  sa purge laissait des envois programmés, des fichiers, et croyait avoir supprimé des lignes
  système qu'elle n'atteignait pas (aucun MMS sortant n'a jamais eu de `telephony_uri`).
- **F06, F07** — désarmer le verrou n'exigeait pas de s'authentifier ; abaisser le mode retirait
  le second facteur du coffre.
- **F26** — « Bloquer les numéros inconnus » était une promesse affichée que zéro ligne tenait.
  Câblé, avec la règle qui compte : une permission contacts refusée **ne bloque rien**, parce
  qu'une ignorance n'est pas une connaissance.
- **X-01** (trouvé en vérifiant un constat de l'audit global qui était faux) — répondre depuis un
  groupe du coffre écrivait hors du coffre, et la réponse d'un membre y arrivait de même. Le SMS
  n'a pas de groupe : mettre un groupe au coffre y met désormais ses membres, et l'en sortir les
  en sort.
- **A-03** — la restauration ne refusait pas en session leurre, contrairement à l'export.

**Ce que la mesure sur appareil a appris, et que la lecture ne pouvait pas donner.** Neuf défauts
trouvés en une soirée sur un Galaxy S9 et un S24, dont quatre sur des fonctions annoncées qui
n'avaient **jamais** fonctionné : joindre un contact (la fiche n'est pas un fichier), envoyer deux
photos (chaque image tenait seule sous le plafond, jamais ensemble), créer un groupe (le raccourci
« toucher = ouvrir » avait tué le chemin), voir toutes les pièces jointes d'un MMS (le correctif
de données F16 n'avait pas son jumeau d'affichage). Un test unitaire vert ne dit rien d'un chemin
que personne n'emprunte.

**Ce que le contrôle négatif a appris.** Deux correctifs remis ensemble peuvent se masquer : F12
restait vert pour la mauvaise raison tant que F14 était neutralisé avec lui. Et un contrôle
négatif dont le rapport est périmé n'a rien mesuré — vu deux fois dans la session, une fois sur
une compilation refusée, une fois sur un verrou de fichier Windows.

Campagne instrumentée : **131 cas sur Galaxy S9 / Android 10, 0 échec, 0 ignoré** ; 557 tests
unitaires ; migration 11 → 12 exécutée sur appareil.

### v1.28.2 — Un garde qui refuse toujours de la même façon n'est plus une protection

*Suite directe de la v1.28.1, décidée après elle et non signalée par la relecture externe : c'est
le correctif de la v1.28.1 lui-même qui a créé le cas.*

La v1.28.1 a eu raison de **conserver** la conversation du coffre quand sa copie système résiste.
C'est ce qui rend la purge reprenable, et c'est ce qui a fermé la fuite du second essai. Mais elle
supposait l'échec **passager** — un rôle SMS momentanément perdu, un fournisseur indisponible —
et cette hypothèse est fausse dans un cas précis et durable.

Une sauvegarde restaurée d'**avant la v1.27.10** recopiait les `telephony_uri` du téléphone
**source**. Un message peut donc porter, définitivement, une liaison qui désigne ici un **autre**
message. Le garde d'identité posé en v1.28.1 fait alors exactement ce qu'on lui demande : il
refuse de supprimer la ligne système, parce qu'elle n'est pas prouvée être celle-là. Il refuse au
premier essai, au deuxième, au centième — à l'identique. Et l'utilisateur qui a oublié son PIN de
coffre n'a plus **aucune** issue.

**Une porte de sortie qui ne s'ouvre jamais n'en est pas une.** Le garde finissait par protéger
l'application contre son propriétaire.

**Ce qui change, et ce qui ne change pas.** Au **second** échec seulement, l'application propose
de vider le coffre et de retirer le PIN quand même, après avoir énoncé ce qui **restera** sur le
téléphone — et le redit une fois l'opération faite. Le premier échec continue d'inviter à
réessayer : une panne passagère se lève d'elle-même, et offrir tout de suite l'option dégradée
pousserait à détruire plus que nécessaire.

`SystemCopyEraser` ne bouge **pas d'une ligne** : la copie système n'est toujours pas supprimée
sans preuve d'identité, et ce n'est donc pas un assouplissement du garde. Ce qui change est
l'arbitrage **local** — conserver ou non la ligne Room quand la propagation a échoué — et il
appartient désormais à l'utilisateur, informé, au second échec.

**Le drapeau qui distingue les deux échecs est en base** (`vaultPurgeFailedOnce`), pas en mémoire.
Un compteur en mémoire ne survit pas à ce qu'on lui demande de survivre : fermer l'application
entre deux essais ramènerait l'impasse, et c'est précisément ce que ferait quelqu'un de bloqué.

**La règle générale, à appliquer aux prochains gardes.** Distinguer l'échec **passager** de
l'échec **répété**. Le premier invite à réessayer. Le second ouvre une sortie assumée, à trois
conditions : le garde lui-même ne bouge pas, l'utilisateur décide après avoir lu ce qui
subsistera, et l'état qui distingue les deux échecs est persistant.

Couvert par 5 tests JVM (`SettingsResetGuardsTest`) et 2 instrumentés (`VaultPurgeRetryTest`),
contrôle négatif effectué : les deux régressions remises en place font tomber les tests qui les
visent.

### v1.28.1 — La porte de sortie du coffre s'ouvrait au second essai

*Troisième passe de la relecture d'Andrew Pozdnakov sur la MR F-Droid !38458, 2026-09-08, qu'il
a reproduite sur émulateur Android 14. Suite directe de la v1.27.11, qui avait corrigé le même
chemin sans le fermer.*

La v1.27.11 avait rendu la purge du coffre **honnête** : elle disait ce qui avait échoué, et le
PIN n'était retiré que sur un coffre démontrablement vide. Elle ne l'avait pas rendue
**reprenable**, et c'est ce qui manquait : la ligne Room partait quand même, y compris quand la
copie système résistait.

L'enchaînement est plus grave que le constat isolé. Premier essai : le PIN est correctement
conservé, mais la conversation est déjà effacée. Second essai : `idsInVault()` rend une liste
vide, `VaultPurgeResult` vaut `0/0/0`, et `isComplete` est vrai **par vacuité**. Le PIN part, la
copie système survit, et la resynchronisation suivante la ressuscite **en clair** — précisément
l'état que la v1.27.11 prétendait empêcher.

**La leçon, plus utile que le défaut.** Rendre compte d'un échec ne sert à rien si l'on détruit au
passage l'état dont la reprise a besoin. Le correctif n'ajoute aucun journal de purge : **la ligne
du coffre EST le journal**. Conservée, elle est relue au prochain essai comme après un
redémarrage, elle compte dans `remaining`, et la suppression système est retentée.

**Deux gardes renforcés dans la foulée.** L'identité d'une ligne du fournisseur ne tenait qu'à sa
date, à une minute près — c'est-à-dire à rien, une minute étant la durée ordinaire d'un échange.
Elle compare désormais date, corps, sens et adresse côté `content://sms`, et une identité
invérifiable échoue du côté sûr au lieu de déclencher la suppression. Côté `content://mms`, la
table désignée par l'URI ne porte ni corps ni adresse : l'identité s'y réduisait à date + sens, et
le test écrit pour le vérifier a **supprimé le MMS d'un autre correspondant** sur un Galaxy S9
avant que le correctif n'existe. L'adresse y est maintenant relue dans `content://mms/<id>/addr`.

**L'effacement automatique de l'historique ne propageait pas au fournisseur du système**,
contrairement aux trois autres chemins de suppression, et rien ne le documentait. Pour un réglage
de confidentialité, le défaut est le plus coûteux possible : il ne se voit pas. Les messages que
l'utilisateur croyait effacés restaient dans `content://sms`, lisibles par toute application ayant
`READ_SMS`, et « Resynchroniser » les ramenait tous — alors que la confirmation promettait déjà
« Cette action est irréversible ». Trouvé en audit de cohérence, pas par la relecture externe.

⚠ **Changement de comportement destructeur.** Sur un appareil où la rétention automatique est
activée, la prochaine purge supprime les messages **du téléphone** et non plus seulement de
l'application. La confirmation le dit désormais explicitement, et la description de
« Resynchroniser » ne promet plus de récupérer un historique effacé.

**La règle qui unifie les quatre chemins destructeurs**, et qui manquait : *la ligne locale ne
survit à un échec de propagation que si une décision de **sécurité** dépend de cette propagation.*
Retirer le PIN du coffre en est une — on exige la preuve. Une suppression ordinaire ou une purge
de rétention n'en lèvent aucune : y conserver la ligne enfermerait l'utilisateur dans un
historique qu'il a demandé à voir disparaître, sans rien protéger de plus.

**Pourquoi trois défauts sont passés sur le même chemin en trois versions.** Il vivait en
`private` dans `ConversationRepositoryImpl`, au milieu de onze dépendances dont neuf ne le
concernaient pas : aucun test ne pouvait l'atteindre sans construire tout le repository, et
personne ne l'a fait. Il est désormais en propre (`SystemCopyEraser`, `ConversationEraser`), la
politique de suppression est une table testée pour elle-même, et chaque correctif a fait l'objet
d'un contrôle négatif — le défaut remis en place fait tomber les tests qui le visent.

### v1.27.10 — Le second facteur du coffre se remplaçait sans lui-même

*Relecture externe d'Andrew Pozdnakov sur la MR F-Droid !38458, 2026-09-07. Suite directe de
l'entrée v1.27.2 ci-dessous : celle-là avait montré que la garde du coffre tenait l'écran et
non la donnée ; celle-ci montre que le secret lui-même n'était pas gardé.*

Le PIN du coffre pouvait être **remplacé** (Réglages → « Changer le PIN du coffre ») ou
**retiré** (bascule OFF) sans qu'on demande jamais celui en place. Le second facteur ne
résistait donc pas à son adversaire déclaré : celui qui connaît le PIN d'application. Reproduit
sur émulateur Android 14 par le relecteur.

**Ce n'était pas un oubli, et c'est ce qui en fait une leçon.** La KDoc de `VaultPinManager`
documentait cette désactivation comme la porte de sortie en cas de PIN oublié, vingt lignes
sous le « threat model » qu'elle contredisait mot pour mot. Les deux paragraphes avaient été
relus des dizaines de fois sans que la contradiction saute aux yeux, parce qu'ils étaient justes
séparément. **Une porte de sortie qui n'exige rien de plus que le facteur dont on se protège
n'est pas un compromis : c'est le contournement.**

Depuis :

- `changeVaultPin` et `disableVaultPin` exigent le PIN en place, vérifié par le même PBKDF2 et
  sous la même temporisation que l'entrée dans le coffre ;
- `configureVaultPin` refuse d'écraser un coffre réellement gardé (hash posé **et** drapeau ON) —
  garde de dernier recours si un futur écran rebranchait le mauvais dialogue ;
- la porte de sortie subsiste, parce qu'un secret irrécupérable sans issue est un piège, mais
  elle est **destructive** : elle vide le coffre avant d'en retirer le PIN. Détruire est un
  pouvoir que le porteur du PIN d'application avait déjà ; lire est celui qu'on lui refuse ;
- `verifyVaultPin` porte enfin une temporisation exponentielle **dédiée**. Le raisonnement
  d'origine — « le coffre n'est atteignable qu'après le verrou d'application, déjà borné » —
  était vrai tant que le coffre n'était qu'une porte derrière une autre, et faux dès lors que
  le second facteur doit résister à quelqu'un ayant **déjà** franchi la première : ses essais ne
  produisent aucun échec côté application. Jeu de clés `vault.*` séparé d'`auth.*`, sans quoi un
  simple verrouillage/déverrouillage aurait effacé la temporisation à volonté.

Verrouillé par 12 tests dans `VaultPinGuardsTest`, posés sur `VaultPinManager` et non sur
l'écran — la garde fautive vivait dans l'interface, c'est précisément pourquoi elle ne gardait
rien.

### v1.27.2 — Le second facteur du coffre gardait l'écran, pas la donnée

**Versions affectées : toutes, jusqu'à 1.27.1 incluse.**

Quatre chemins ouvraient ou exposaient le coffre sans que son code distinct soit demandé :

- **Déplacer une conversation vers le coffre armait la session.** L'auto-déverrouillage datait de
  la v1.11.0, antérieure au code du coffre (v1.13.0). Depuis, quiconque passait le verrou principal
  déplaçait une conversation quelconque puis ouvrait le coffre — code et biométrie sautés, l'écran
  s'initialisant depuis cette session.
- **En sortir n'exigeait rien.** Sortir une conversation du coffre révèle du contenu protégé ;
  seule la variante non groupée portait la garde.
- **La sauvegarde chiffrée exportait le coffre verrouillé.** `buildPayload` lit toutes les
  conversations, coffre compris. Le fichier était déchiffrable hors de l'appareil avec la
  passphrase choisie par celui qui l'exportait.
- **`observeVault` était le dernier flux de lecture non gardé**, alors que ses trois frères
  consultaient la session depuis la v1.26.1.

**Correctif.** La garde porte désormais sur l'**accès** et non sur l'affichage : plus
d'auto-déverrouillage, sortie et export conditionnés au second facteur, et tous les flux de lecture
alignés. Quand la biométrie est l'unique second facteur et devient indisponible, le coffre n'est
plus ouvert par défaut — l'application l'explique et renvoie vers la configuration d'un code
distinct, joignable hors du coffre.

### v1.27.2 — Perte de messages entrants sur erreur de base

**Versions affectées : toutes, jusqu'à 1.27.1 incluse.**

Une indisponibilité momentanée de Room/SQLCipher pouvait faire disparaître un message entrant sans
trace :

- **SMS.** La consultation de liste noire précédait l'écriture. Son échec terminait le message
  diffusé par le système sans que `insertInboxSms` ait été appelé : le SMS n'existait ni dans le
  fournisseur téléphonie, ni dans l'application.
- **MMS.** Le fichier PDU — seule copie du message et de sa pièce jointe — était supprimé dès que
  le téléchargement avait réussi, y compris lorsque l'écriture en base échouait ensuite.

**Correctif.** Le décodage précède toute résolution de dépendance ; la consultation de liste noire
échoue du côté **ouvert** (une erreur laisse passer le message, seul un blocage franc écarte) ; un
filet de dernier recours écrit le SMS dans la boîte système même base morte ; et le PDU n'est
supprimé qu'une fois son sort réglé. `CancellationException` traverse ces gardes au lieu d'être
convertie en « non bloqué ».

⚠️ **Limite assumée.** Si le fournisseur système valide l'insertion puis échoue avant le retour de
l'appel, le filet réinsère : sémantique « au moins une fois ». Un doublon se voit et s'efface, une
perte est définitive.

### v1.27.2 — Alerte de sécurité personnelle neutralisable par redémarrage

**Versions affectées : 1.10.0 à 1.27.1.**

Le déclenchement exige que les **deux** horloges aient expiré — murale et monotone — pour qu'une
avance d'horloge ne puisse pas provoquer une alerte prématurée (SEC-11). Or `elapsedRealtime()`
repart de zéro à chaque redémarrage, et la récupération de dérive re-calait le compteur sur cette
valeur : redémarrer plus souvent que le délai configuré empêchait l'alerte de partir,
indéfiniment. Un vol suivi de redémarrages réguliers la neutralisait ; pour un usage honnête, une
mise à jour système repoussait l'échéance d'autant.

**Correctif.** Le temps monotone écoulé est capitalisé dans un champ persisté, jalonné à chaque
tick horaire du worker. Un redémarrage ne coûte plus que le segment non encore jalonné, borné à un
tick. Aucune horloge murale n'entre dans ce calcul : la protection SEC-11 reste entière.

### v1.25.0 — Ouverture SQLCipher en clé brute (performance, sans changement de sécurité)

Depuis la 1.25.0, la base est ouverte avec la **clé brute** (`SupportOpenHelperFactory` reçoit
`x'<64 hex>'`) au lieu de laisser SQLCipher dériver la clé par **PBKDF2** (256 000 itérations).

**Pourquoi c'est neutre côté sécurité.** PBKDF2 sert à *étirer* un secret à faible entropie (un mot
de passe humain) pour le rendre coûteux à brute-forcer. Or la passphrase de SMS Tech est déjà
**32 octets aléatoires (256 bits) scellés par le Keystore** (depuis le correctif clé-nulle v1.24.0) :
sur une clé déjà à pleine entropie, les itérations PBKDF2 n'ajoutent **aucune** résistance — elles ne
font que ralentir l'ouverture (~490 ms → quelques ms). Le chiffrement au repos, l'algorithme
(AES-256) et le threat model sont **inchangés** ; seule l'étape de dérivation, inutile ici, est sautée.

**Conversion.** Une bascule unique, crash-safe (`LegacyZeroKeyRekey.ensureRawKeyed`, même pattern
copie → validation `cipher_integrity_check` + comptage de lignes → swap que la réparation v1.24.0),
est exécutée au premier lancement de la 1.25.0, après la réparation clé-nulle. Aucune perte de
message : l'original n'est jamais détruit avant que le remplaçant ne soit prouvé sain.

### v1.24.0 — SEC-CRIT : la base était chiffrée avec une clé nulle

**Versions affectées : toutes, jusqu'à 1.23.4 incluse.**

`DatabaseFactory` remettait la passphrase SQLCipher à `SupportOpenHelperFactory` puis la zéroïsait
immédiatement (`raw.wipe()`, soit `Arrays.fill(this, 0)` — une mutation **sur place**). Or aucun
maillon de SQLCipher ne copie ce tableau : vérifié par désassemblage de `sqlcipher-android-4.16.0`,
`SupportOpenHelperFactory` en stocke la référence, `SupportHelper` la transmet, et
`SQLiteOpenHelper` la conserve dans `mPassword` qu'il ne lit que dans `getDatabaseLocked()`,
c'est-à-dire **à l'ouverture de la base**. Room ouvrant paresseusement, au premier accès DAO,
SQLCipher recevait 32 octets nuls.

**Conséquence : `smstech.db` était chiffrée avec une clé constante et publique**, et non avec la
passphrase de 32 octets aléatoires scellée par le Keystore. Le défaut était invisible parce qu'une
clé nulle est parfaitement stable d'un lancement à l'autre. Il était présent depuis le premier
commit du fichier — l'affirmation « 32-byte random passphrase wrapped by Keystore » du tableau des
primitives ci-dessus n'a donc jamais été tenue avant la 1.24.0.

**Portée réelle du risque.** Elle doit être énoncée honnêtement dans les deux sens :
- en tant qu'application SMS par défaut, SMS Tech est **tenue** de miroiter tous les messages dans
  `content://sms`, en clair, y compris ceux des conversations du coffre (`in_vault` est un simple
  drapeau Room). La base SQLCipher est de la défense en profondeur, jamais l'unique copie ;
- ce que la clé nulle exposait **en plus** : les métadonnées propres à SMS Tech — appartenance au
  coffre, brouillons, messages programmés, réactions, verdicts anti-smishing — et tout message
  supprimé par l'utilisateur dans SMS Tech.

**Correctif (`LegacyZeroKeyRekey`).** Au premier lancement de la 1.24.0, avant que Room n'ouvre le
fichier : checkpoint du WAL, copie vers un fichier temporaire, re-chiffrement de la **copie** avec
la vraie passphrase, validation de bout en bout (`PRAGMA cipher_integrity_check` + comptage ligne à
ligne par table), puis bascule. L'original n'est jamais modifié ni supprimé avant que le remplaçant
ne soit prouvé sain : un arrêt du processus à n'importe quelle étape laisse une base exploitable.
Une base illisible par les deux clés est signalée, jamais effacée.

**Ce que le correctif ne peut pas faire.** `File.delete()` ne réécrit pas les blocs : les secteurs
qui contenaient l'ancienne base restent physiquement lisibles jusqu'à ce que le système de fichiers
les réutilise. Un écrasement applicatif serait illusoire sur f2fs (copy-on-write) et avec le
wear-levelling des mémoires flash. La seule action qui élimine réellement ce résidu est une
**réinitialisation d'usine**, qui détruit la clé de chiffrement FBE de l'appareil.


### v1.14.7 (cette version) — Protection cache MMS reçus + filets sync + splash transparent + audit fixes

User remontée 2026-05-23 : sur S24 (après cycles désinstall/réinstall pour tester v1.14.5/6), les attachments audio MMS reçus avaient disparu et la sync semblait gelée. Root cause identifiée par diag logcat : (a) `cacheDir/mms_incoming/` (où vivaient les fichiers audio des MMS reçus) est volatile — Android Storage Manager le purge sous pression mémoire, et "Effacer le cache" via Réglages → Apps le vide aussi, laissant les `AttachmentEntity.localUri` Room pointer sur des fichiers absents ; (b) sur Samsung S24 Android 15 le ContentObserver système rate parfois des émissions après sleep long + Freecess gèle les workers en background.

**Trois changements + 3 audit fixes** :

1. **Protection cache → filesDir pour les attachments MMS reçus**. `MmsDownloadedReceiver.persistAttachment` écrit désormais dans `filesDir/mms_attachments/` (persistant, ne disparaît qu'avec `PanicService.nukeEverything` ou `clearData`) au lieu de `cacheDir/mms_incoming/`. `FileProvider` paths déjà OK (`files-path "attachments" path="mms_attachments/"` existant). `AutoLockObserver.purgeTransientCaches` doc mise à jour : NE purge PAS le nouveau filesDir/mms_attachments (par design, sinon les attachments disparaîtraient à chaque auto-lock). `PanicService.nukeEverything` continue de wipe filesDir/mms_attachments correctement (déjà dans sa liste, vérifié).

2. **Migration cold-start one-shot** `MainApplication.migrateAttachmentsToFilesDirIfNeeded()`. Flag DataStore `AdvancedSettings.attachmentsMovedToFilesDirV147` idempotent. Pour chaque `AttachmentEntity.local_uri` commençant par `cacheDir/mms_incoming/`, déplace physiquement le fichier vers `filesDir/mms_attachments/` (rename atomique si même partition, fallback copy+delete) puis `attachmentDao.updateLocalUri(id, newPath)`. Edge cases gérés : (a) fichier source absent (cache déjà clearé) → flip quand même le path Room pour cohérence, (b) destination existe déjà → garde dest, delete source. **Audit S1 fix** : `canonicalFile` + `startsWith(newDir.canonicalFile)` check anti path-traversal avant écriture, defense-in-depth contre une régression future du générateur de noms. **Audit P2 fix** : `withContext(Dispatchers.IO)` explicite autour de la migration (les IO `renameTo` / `copyTo` ne saturent plus le thread pool Default). Async dans `appScope.launch` — pas de blocage main thread.

3. **Filet de sécurité sync onResume**. `MainActivity.onResume()` appelle `telephonySyncManager.requestSync("MainActivity.onResume")` (idempotent via Mutex côté manager) + `TelephonySyncWorker.enqueueOneShot(this)` (belt-and-braces si le manager est en état inattendu). **Audit P1 fix** : throttle 30s mono sur le `enqueueOneShot` WorkManager (sans le throttle, switch rapide entre apps spammait WorkManager SQLite interne → risque Freecess throttling). `requestSync` lui ne nécessite pas de cooldown (Mutex single-flight absorbe).

**Polish** : `splash_logo.xml` v1.14.5 retiré (user remontée "préfère l'ancien splash, le rond Android 12+ cache mon logo carré") → nouveau `drawable/splash_transparent.xml` = `<shape rectangle solid transparent />`. `windowSplashScreenAnimatedIcon = @drawable/splash_transparent` (light + night themes) → Android 12+ affiche juste un flash de fond uni, aucun cercle visible. La Compose `SplashScreen.kt` fade-in intro 1re ouverture reste intacte. String `emergency_topbar_warning_cd` orpheline retirée (FR+EN).

**Threat model** inchangé. Aucun changement crypto / Keystore / Room / SQLCipher. Cert SHA-256 stable. Pas de migration Room. `lintVitalRelease` clean, `testReleaseUnitTest` green. Audit final 3 axes — 1 MEDIUM (P1) + 3 LOW TOUS FIXÉS, zéro Critical/High.

### v1.14.6 — Label `(défaut)` du picker de réactions

Le picker `Réglages → Format des réactions` portait `(défaut)` sur "Français lisible" alors que la valeur réelle par défaut a été basculée sur `EMOJI_WITH_QUOTE` en v1.14.4 (cf. section ci-dessous). Strings FR+EN corrigées (`settings_reaction_format_fr` retire `(défaut)`, `settings_reaction_format_emoji_quote` ajoute `(défaut)`). Aucun changement comportemental, uniquement étiquette d'interface. Aucun changement crypto / Room / Keystore / threat-model.

### v1.14.5 — Mode urgence polish UX : emoji ⚠️ + toggle GPS direct + reset complet sur disable + nettoyage dry-run + splash carré

UX polish post-v1.14.4 sur 6 axes :

1. **Emoji ⚠️ en tête du corps SMS d'urgence** (templates NEED_HELP + DANGER). Quand le destinataire reçoit le SMS, la notification heads-up affiche immédiatement le triangle d'alerte en preview → caractère d'urgence visuellement reconnaissable avant même d'ouvrir le SMS. **Trade-off accepté** : le ⚠️ (U+26A0 + U+FE0F variation selector) force l'encodage UCS-2 → 70 chars/segment au lieu de 160 GSM-7 → potentiel multi-segment. Audit SEC-5 v1.10.0 préservait 1-segment GSM-7 par défaut pour fiabilité en zone radio faible. **Décision v1.14.5** : la visibilité du caractère d'urgence prime sur la robustesse marginale (opérateurs FR 2026 fiables sur multi-segment). Tests `AuditV1100Test` mis à jour (`startsWith("⚠️ URGENCE")`). **DISCREET PAS modifié** : la variante neutre/anxiogène-évitante préserve son but (signaler malaise sans alarmer, éviter de révéler la situation à un agresseur lookant l'écran). Reste 1-segment GSM-7.

2. **Toggle "Inclure position GPS dans le SMS" directement dans Settings → Mode urgence**. Avant : seul `EmergencySetupScreen` exposait ce toggle. v1.14.5 : `ToggleRow` direct accessible dans `SettingsScreen` principal. Au passage OFF→ON, demande `ACCESS_FINE_LOCATION` runtime immédiatement via `rememberLauncherForActivityResult`. **Audit SEC-1 fix** : si l'user refuse la permission, le callback `revert` automatiquement `includeLocation = false` en DataStore + snackbar erreur "Permission refusée — inclusion GPS désactivée". Pattern miroir de `revertCallBehaviorIfPermissionRevoked` (v1.10/v1.14.1) — pas d'état sale "toggle ON mais SMS sans coords".

3. **Hotfix banner "Je vais bien" orphelin** (user remonté 2026-05-22). `EmergencyViewModel.disableEmergencyMode()` clear désormais AUSSI `lastTriggeredAt = 0L` + `monotonicLastTriggeredAt = 0L` en plus de `enabled = false` + `emergencyShortcutEnabled = false` + `emergencyCallPoliceEnabled = false`. Reset complet en une transaction atomique DataStore. **Cold-start repair migration étendue** : `MainApplication.onCreate` détecte `hasOrphanShortcut || hasOrphanTrigger` (cooldown actif alors que mode désactivé) et repair automatiquement → users existants avec état sale post-v1.14.x sont nettoyés au prochain démarrage. Idempotente.

4. **Cleanup "Tester sans envoyer"** retiré de la page urgence sur demande user (encombrait l'UI, le mode actif est déjà visible via la section recap). Suppression : bouton + composable `EmergencyDryRunDialog` + `EmergencyViewModel.previewTrigger/dismissPreview/_previewState/_isPreviewLoading/DryRunPreview/redactPhoneNumber` + constructor param `locationResolver` + 12 strings `emergency_dry_run_*` (FR+EN). Zéro référence orpheline.

5. **Splash logo carré** (user remonté : "c'est en cercle le logo c'est pas beau, mon logo est carré"). Nouveau `drawable/splash_logo.xml` (inset 20% wrap `sms_tech_icon.png`). `windowSplashScreenAnimatedIcon` → `@drawable/splash_logo`. Ajout `windowSplashScreenIconBackgroundColor` matching `windowSplashScreenBackground` (light + dark) → le masque circulaire Android 12+ devient visuellement invisible, le logo carré branded apparaît tel quel.

6. **AboutScreen + site files-tech.com sms-tech.php** : nouvelle entrée `Feature` "Mode urgence" + nouvelle `HelpRecipe` "Utiliser le mode urgence" (4 étapes concises). Site web : nouvelle carte feature ⚠️ + ligne permissions `ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION` (optionnelle, mode urgence). Version site bumpée à v1.14.5.

Audit final 3 axes (sécu / perf+qualité / UI+branchements) — 1 MEDIUM finding fixé (SEC-1 revert toggle GPS sur permission denied), aucun Critical/High. Pas de changement crypto / Room / Keystore / threat-model. Pas de migration Room. `lintVitalRelease` clean, `testReleaseUnitTest` green.

### v1.14.4 — Format de réaction par défaut `EMOJI_WITH_QUOTE` (demande utilisateur)

Petite release UX : le format par défaut des SMS de réaction emoji passe de `READABLE_FR` ("J'ai réagi par ❤️ à : «…»") à `EMOJI_WITH_QUOTE` ("❤️ «…»"). Demande user 2026-05-22.

**Pourquoi** : `EMOJI_WITH_QUOTE` est compact, conserve le contexte (citation du message d'origine), et ne contient pas de phrase explicative parasite. Plus naturel pour les conversations actuelles où les réactions sont nombreuses et où le destinataire n'a pas besoin de relire "j'ai réagi par".

**Périmètre** : default DataStore pour les NOUVEAUX installs uniquement. Les users existants conservent leur choix (DataStore persistant). Le picker Settings → Envoi → "Format des réactions" expose les 4 options inchangé.

Pas de changement crypto / Room / Keystore / threat-model. Pas de migration.

### v1.14.3 — Hotfix migration one-shot : réparation du drapeau `emergencyShortcutEnabled` resté incohérent

PATCH urgent post-v1.14.2 — un utilisateur ayant désactivé le Mode urgence en v1.14.0 ou v1.14.1 voyait la notification persistante lock-screen ré-apparaître à chaque lancement de l'app malgré la désactivation. Cause racine : avant le fix cascade-disable de v1.14.2, le bouton "Désactiver le mode urgence" ne flippait QUE `emergency.enabled = false`, laissant `emergencyShortcutEnabled = true` orphelin en DataStore. Le fix v1.14.2 corrige les FUTURES désactivations mais ne nettoie pas l'état dirty existant.

**Fix v1.14.3** : migration one-shot au cold-start de l'app dans `MainApplication.onCreate`. Si `emergency.enabled == false` ET (`emergencyShortcutEnabled == true` OU `emergencyCallPoliceEnabled == true`), force-clear les 2 flags dans la même transaction `settings.update`. Idempotent : si l'invariant est déjà respecté, le `update` ne re-écrit pas. Exécuté UNE fois par cold-start, perf négligeable (`first()` snapshot DataStore + éventuellement 1 write).

Log Timber au repair pour audit trail. Pas de migration Room, pas de changement crypto.

### v1.14.2 — Hotfix CRITIQUE : 3 voies de déclenchement SMS d'urgence accidentel fermées

**HOTFIX URGENT** post-v1.14.1 sur bug critique remonté user 2026-05-22 : "beaucoup de mms envoyés sans rien faire, mode urgence désactivé". Investigation a identifié **3 voies indépendantes** de déclenchement accidentel du SMS d'urgence aux contacts SafetyCall. Toutes fermées dans v1.14.2.

**3 fixes critiques** :

1. **`EmergencyHoldButton` interprétait les gestes scroll comme un hold-3s**. v1.14.1 a ajouté `Modifier.verticalScroll(rememberScrollState())` sur la page EmergencyScreen. Quand l'user scrollait verticalement à travers le gros bouton URGENCE, le pointerInput interceptait l'événement DOWN, mettait `isHolding = true`, et le `LaunchedEffect(isHolding) { delay(3000) }` fired le trigger SMS. Le scroll modifier parent prenait ensuite le contrôle visuel (faisait scroller la page), mais le hold logique avait déjà commencé. Un scroll lent ≥ 3 s déclenchait l'envoi SMS aux contacts.

   **Fix** : ajout d'une détection de drag via `viewConfiguration.touchSlop`. Si le pointer bouge de plus que la slop (~24 dp), `isHolding = false` immédiatement + drain les pointer events restants jusqu'au UP pour ne pas re-fire le hold sur le même geste. Code dans `EmergencyHoldButton.kt:121-167`.

2. **Quick action URGENCE retirée de la notif persistante lock-screen**. La notif posée par `EmergencyShortcutNotifier` avait 3 quick actions : URGENCE + 112 + 17 (police opt-in). Le tap sur URGENCE = `ACTION_TRIGGER_EMERGENCY` reçu par `EmergencyShortcutReceiver.handleTrigger` qui appelait `TriggerEmergencyUseCase` → SMS aux contacts. Un mistap (pocket-tap, dismiss confondu avec action, confusion avec body-tap) = SMS broadcasté. Les notif actions Android sont single-tap par design — impossible d'y poser un hold-3s anti-pocket-dial.

   **Fix** : la quick action URGENCE est **supprimée** de la notif lock-screen. Pour déclencher URGENCE depuis le lock-screen, l'user tape désormais le **corps** de la notif → `setContentIntent` (ajouté v1.14.1) ouvre la page in-app Emergency → hold 3 s sur le gros bouton URGENCE (lui-même protégé par le fix #1 ci-dessus). Trois gestes délibérés au lieu d'un mistap. Les quick actions 112 et 17 (Police FR opt-in) restent — elles utilisent `ACTION_DIAL` (composeur, l'user confirme dans le dialer, pas d'auto-call).

3. **`disableEmergencyMode()` n'effaçait pas le raccourci notif**. v1.14.1 ajoutait le bouton "Désactiver le mode urgence" sur EmergencyScreen. Il flippait `emergency.enabled = false` MAIS laissait `emergencyShortcutEnabled = true`. Conséquence : la notif persistante lock-screen ré-apparaissait à chaque lancement de l'app (MainApplication combine flow). User confondu, tap notif (souvent URGENCE quick action AVANT le fix #2), SMS envoyé.

   **Fix** : `disableEmergencyMode()` met maintenant `emergency.enabled = false`, `emergencyShortcutEnabled = false`, ET `emergencyCallPoliceEnabled = false` dans la même transaction DataStore. Désactivation complète en un seul clic, sans setting résiduel actif. `EmergencyViewModel.kt:188-203`.

**Threat model corrigé** :

- Le hold-3s de `EmergencyHoldButton` est désormais une vraie garde anti-pocket-dial (pas seulement contre tap accidentel — aussi contre drag/scroll qui était une voie ouverte v1.14.1).
- La notif lock-screen ne permet plus de déclencher l'envoi SMS aux contacts en 1 tap. Le déclenchement URGENCE est gated derrière nav-vers-page-in-app + hold-3s = 3 gestes délibérés.
- La fonction "Désactiver" est atomique : 1 confirm dialog + tap → tous les flags urgence + raccourci off. Aucun setting résiduel ne peut faire repop la notif.

**Verdict** : aucun changement crypto / DB / threat-model étendu. Hotfix purement défensif sur 3 voies de déclenchement non-intentionnel.

### v1.14.1 — Refonte pleine page de l'écran d'urgence + 15 SAMU + 18 Pompiers + « Appeler un proche » + désactivation du mode + tap sur la notif qui ouvre la page + 5 correctifs d'audit

PATCH release post-v1.14.0 répondant à un retour user pour rendre la page Mode urgence "plus claire avec toutes les actions visibles, sans manipulation". Toutes les actions urgence sont désormais regroupées sur un seul écran avec gros boutons couleurs.

**4 sujets livrés** :

1. **EmergencyScreen full-page redesign** — 3 sections claires : "Appeler directement" (5 tuiles), "Envoyer un SOS aux proches" (preview + hold-3s SMS), "Autres actions" (Tester / Désactiver). Scroll vertical pour petits écrans. Toutes les actions urgence sur la même page, plus de navigation cachée.

2. **Numéros français complets — 4 tuiles d'appel direct + 1 tuile proches** :
   - **112** (SOS européen, BrandDanger rouge)
   - **15** (SAMU, teal `#00796B`)
   - **17** (Police, navy `#1565C0`)
   - **18** (Pompiers, orange `#E65100`)
   - **★ Appeler un proche** (primary brand-blue, si ≥1 contact SafetyCall) — si 1 contact, call direct ; si ≥2, picker dialog
   - Toutes les couleurs WCAG AA ≥ 4.5:1 vs white text.
   - **Tap = appel direct** (`ACTION_CALL`) sans passer par le composeur. Fallback automatique sur composeur si CALL_PHONE refusée.

3. **Bouton "Désactiver le mode urgence"** — sur la page elle-même, avec dialog de confirmation. Met `emergency.enabled = false` en DataStore. Effet immédiat : URGENCE button grisé, notif lock-screen cancel, sections Settings affichent "désactivé". Réactivable depuis Settings ou re-setup. PanicDecoy déjà gated en amont (cf. v1.10.0 SEC-1).

4. **Tap notification persistante → ouvre la page in-app** — `setContentIntent` ajouté sur le NotificationCompat.Builder avec PendingIntent `getActivity` vers MainActivity, action `ACTION_OPEN_EMERGENCY`. `MainActivity.handleSharedIntent` route → `pendingNav.set(Pending(openEmergency = true))`. `AppRoot.LaunchedEffect` consume → `nav.navigate(Emergency)`. Préserve PanicDecoy guard : si décoy actif, le pending est holding 30s sans push (TTL `PENDING_TTL_MS`). Reprise normale si user sort du décoy avant expiration.

#### Sécurité — élargissement whitelist `EmergencyCallHelper`

- `ALLOWED_NUMBERS = setOf("15", "17", "18", "112")` (étendu de 2 à 4).
- Nouvelle méthode `placeTrustedContactCall(context, phoneNumber)` SANS whitelist par design (le numéro vient du DataStore SafetyCall configuré user, pas d'une source intent extra). Refacto interne `executeCall(...)` privé partagé entre `placeCall` (whitelist stricte) et `placeTrustedContactCall` (contact trusted).
- L'UI route les contacts SafetyCall via `viewModel.safetyCallContacts` (StateFlow → DataStore privé) ; aucun chemin Intent extra → `placeTrustedContactCall`. Vérifié par audit.

**Audit final multi-axes** (sécurité + perf + qualité + branchements + cohérence + vulnérabilités) — 2 MEDIUM + 5 LOW findings, tous fixés :

- **MEDIUM SEC-1** — `safetyCallContacts.collectAsStateWithLifecycle()` hoisté au top du Composable EmergencyScreen (vs body Scaffold conditionnel) pour respecter les règles de position des hooks Compose. Suppression du double `.let { _ -> }` mort qui masquait la précédente `state`.
- **MEDIUM COH-1** — Setting `emergencyCallBehavior` (DIALER_ONLY / HOLD_3S_DIRECT_CALL ajouté v1.14.0) devenu orphelin avec le redesign v1.14.1 (la page utilise toujours direct-call avec fallback). Nettoyage : suppression `EmergencyCallBehaviorPickerDialog` + `EmergencyBehaviorRadioRow` de SettingsScreen + suppression `EmergencyViewModel.callBehavior` StateFlow + suppression `revertCallBehaviorIfPermissionRevoked()` + suppression `DryRunPreview.callBehavior` field. La clé DataStore `emergencyCallBehavior` est PRÉSERVÉE pour backward compat (downgrade safe) mais n'est plus consommée par l'UI.
- **LOW SEC-2** — Commentaire explicatif ajouté sur la séparation `ACTION_OPEN_EMERGENCY` (handled par MainActivity, pas par EmergencyShortcutReceiver) pour maintenance future.
- **LOW COH-2** — Strings `settings_emergency_call_police_title/desc` FR+EN mises à jour pour clarifier que le toggle ne contrôle plus que la 3e action de la notif lock-screen (le bouton 17 in-app est toujours visible v1.14.1).
- **LOW PERF-1+PERF-2** — `callPhonePermLauncher` hoisté au top du Composable. `callPhoneGranted` lu à chaque recomposition (cheap), captureur recompose au retour ON_RESUME → status à jour.
- **LOW UI-1** — String orpheline `emergency_call_close_no_contacts` retirée FR + EN (le bouton "Appeler un proche" est conditionné par `if (safetyContacts.isNotEmpty())`, pas d'état "disabled" affiché).

#### Threat model — précisions

- Direct call via CALL_PHONE permission : risque pocket-dial mitigé par (a) nav explicite vers EmergencyScreen + (b) tuiles disposées en colonne (pas un seul tap accidentel sur tap-target oublié) + (c) confirm Désactiver mode pour annuler. Acceptable pour la feature demandée.
- Le picker "Appeler un proche" ne montre que les contacts SafetyCall (déjà configurés par user, autorisés par défaut). Pas d'accès Contacts Android natif → pas de leak `READ_CONTACTS`.
- Le bouton "Désactiver le mode urgence" : action réversible (Settings re-enable). Pas d'effet destructif sur les contacts ni le template. PanicDecoy déjà gated par `AppRoot` upstream.
- `ACTION_OPEN_EMERGENCY` : action constante, PendingIntent FLAG_IMMUTABLE, targeting `MainActivity::class.java` explicite. Pas exposé en intent-filter manifest → pas d'exfiltration possible par une autre app.

### v1.14.0 — Verrouillage automatique du coffre + appel d'urgence par maintien de 3 s (CALL_PHONE) + kill-switch « Je vais bien » + aperçu dry-run + 4 correctifs d'audit

MINOR release plafonnant le mode urgence de SMS Tech avant la sortie d'une app dédiée **SOS Tech** (Files Tech n°8) pour les features étendues (vocal, sirène, GPS live). Quatre sujets livrés :

1. **Auto-lock coffre à la sortie explicite de VaultScreen**. Tap back arrow / system back / cancel PIN dialog / biometric refused → `VaultManager.lock()` immédiat. Préserve le fix v1.13.1 sur la navigation ThreadScreen ↔ VaultScreen : le `sessionUnlocked` AtomicBoolean Singleton persiste pendant qu'on ouvre une conv vault et qu'on en revient (composable VaultScreen reste dans le back stack), mais lock dès qu'on sort vraiment. Cohabite avec `lockVaultOnLeave` existant (lock au process-background) — les deux sont idempotents et orthogonaux. Nouveau helper `VaultViewModel.lockVaultSession()`.

2. **Boutons 112 / 17 — 2 niveaux de comportement** :
   - **DIALER_ONLY** (default, comportement v1.12–v1.13) : `ACTION_DIAL`, le user confirme dans le composeur pré-rempli. Zéro permission requise.
   - **HOLD_3S_DIRECT_CALL** (opt-in) : maintien 3 secondes sur le bouton → appel direct via `ACTION_CALL` + permission runtime `CALL_PHONE`. Anti-pocket-dial via hold obligatoire (anneau de progression visible). Pas de NIVEAU 2 (tap unique → call direct) volontairement : risque pocket-dial trop élevé pour gain marginal.

   Toute la voie d'appel passe par `EmergencyCallHelper` (nouveau) avec une **whitelist stricte de numéros** : seuls `"112"` et `"17"` sont acceptés, tout autre numéro retourne `INVALID_NUMBER` sans aucun Intent émis. Élimine toute possibilité de redirection vers un numéro premium via Intent extra forgé. `EmergencyShortcutReceiver.handleDial` (lock-screen actions) délégué au même helper pour cohérence ; sur lock-screen on garde `openDialer` (jamais `placeCall`) car le tap accidentel est probabilistiquement plus élevé sur écran verrouillé.

3. **Kill-switch "Je vais bien"**. Nouveau `IAmOkUseCase` qui réinitialise `lastTriggeredAt = 0L` + (opt-in `sendIAmOkSmsOnReset`, default `true`) envoie un SMS court "Je vais bien, fausse alerte" aux contacts SafetyCall. Garde `PanicDecoy` (anti-tampering : un agresseur ne peut pas effacer la trace UI du déclenchement urgence). Sur `ConversationsScreen`, un bandeau `IAmOkBanner` apparaît pendant 30 minutes post-trigger et propose un dialog de confirmation. Snackbar différencié sur succès partiel (`sent=0, failed=N` → message d'erreur explicite, l'user sait que les contacts n'ont PAS été informés).

4. **"Tester sans envoyer"**. Bouton dans `EmergencyScreen` qui lance un dry-run : résolution GPS, rendu du body SMS, comptage contacts, masquage des numéros (`+33 … 78` style). **Aucun side-effect** — pas d'envoi SMS, pas d'écriture DataStore, pas de mutation `lastTriggeredAt`. Loader spinner pendant les ~8s de résolution GPS, guard double-tap. Affiche le call behavior actif + un warning rouge si mode urgence désactivé.

Un audit final HAUTE PRÉCISION a surfacé **3 MEDIUM** bloquants, tous fixés avant tag :

- **MEDIUM SEC-1** — Sur `ON_RESUME` de `EmergencyScreen`, re-vérification de la permission `CALL_PHONE`. Si l'user a révoqué la permission via Paramètres Android entre temps, `emergencyCallBehavior` est auto-revert à `DIALER_ONLY` dans le DataStore. Sans ce check, le setting devenait orphelin (placeCall retournait `PERMISSION_DENIED` à chaque tap, snackbar erreur silencieuse, en situation d'urgence l'user croyait l'app cassée).
- **MEDIUM SEC-2** — Snackbar `IAmOkDoneWithSms(sent, failed)` différencie maintenant `sent > 0` (succès) vs `sent == 0 && failed > 0` (erreur, contacts non informés malgré le reset). Nouvelle string `emergency_i_am_ok_send_failed` FR+EN. L'user voit clairement quand les SMS de réassurance n'ont pas pu partir.
- **MEDIUM PERF-1** — Guard double-tap + spinner UI pendant la résolution GPS du dry-run (jusqu'à 8s). `_isPreviewLoading: StateFlow<Boolean>` exposé au `EmergencyScreen` qui désactive le `TextButton` et affiche `CircularProgressIndicator` + label "Résolution GPS…". Sans ça, le bouton semblait non-réactif et l'user pouvait re-tapper créant N coroutines parallèles.

Un **LOW ARCH-1** également corrigé : double `if (emergency.enabled)` imbriqué redondant dans `SettingsScreen` (cosmétique, suppression).

#### Sécurité — checks vérifiés sans finding

- `EmergencyCallHelper` whitelist stricte sur `openDialer` ET `placeCall`. Pas de chemin extra-intent qui injecterait un numéro arbitraire.
- `EmergencyShortcutReceiver` (`exported=false`) ne passe que les constantes hardcodées `EMERGENCY_NUMBER_EU = "112"` et `EMERGENCY_NUMBER_POLICE_FR = "17"` au helper.
- Auto-lock coffre couvre tous les chemins de sortie explicite (top-bar back, system back, PIN cancel, biometric refused). Aucun `DisposableEffect` ne lock à la destruction (préserve fix v1.13.1).
- Anti-pocket-dial hold-3s : `Button(onClick = {})` no-op + `pointerInput` qui ne déclenche que sur hold complet. Cancellation propre à la rotation Activity via clé `LaunchedEffect(isHolding)`.
- `IAmOkUseCase` : guard PanicDecoy en tête, opt-in `sendIAmOkSmsOnReset` strictement respecté.
- Dry-run : zéro side-effect confirmé par audit (pas de SendSms, pas de DataStore write, pas de Timber log du body en clair).

#### Manifest

Nouvelle permission `<uses-permission android:name="android.permission.CALL_PHONE" />`. Demandée RUNTIME uniquement quand l'user opt-in `HOLD_3S_DIRECT_CALL` dans Réglages. Refus → fallback automatique à `DIALER_ONLY`. Aucun appel automatique : hold-3s est la garde anti-pocket-dial.

#### Note stratégique — cap mode urgence dans SMS Tech

v1.14.0 est volontairement le **cap supérieur** du mode urgence dans SMS Tech. Les features étendues (mode vocal Vosk, sirène + flash, partage GPS live, recording audio chiffré, webhook diffusion) sont déléguées à une nouvelle app **SOS Tech** (Files Tech n°8) qui sera scaffoldée séparément. Le code partagé (`LocationResolver`, `EmergencyConfig`, `SafetyCallContact`, `PasswordKdf`, `Outcome`) sera factorisé progressivement dans un module AAR `files-tech-emergency-core` consommé par SMS Tech et SOS Tech. Justification : ces features impliqueraient pour 95 % des utilisateurs SMS du poids inutile (foreground service permanent, modèle Vosk ~50 Mo, permissions agressives BACKGROUND_LOCATION / RECORD_AUDIO continu).

### v1.13.1 — Hotfix UX par-dessus la v1.13.0

Release PATCH corrigeant trois régressions remontées par des utilisateurs après la v1.13.0 :

- **Appui long → ActionsSheet historique** restauré sur `ConversationsScreen` comme sur `VaultScreen`. La v1.13.0 avait réduit le comportement de l'appui long à « entrer en mode multi-sélection » — la découvrabilité des actions rapides historiques (Déplacer vers le coffre / Sortir du coffre / Bloquer / Supprimer) était perdue. La v1.13.1 restaure le ModalBottomSheet à l'appui long ET ajoute un nouvel élément « Sélectionner plusieurs » (Select multiple) qui fait entrer en mode multi-sélection les utilisateurs qui veulent des opérations groupées.
- **Bug de redemande du PIN du coffre** au retour de `ThreadScreen` vers `VaultScreen`. L'état local Compose `remember` `vaultPinPassed` était réinitialisé à la recomposition, ce qui faisait brièvement réapparaître le dialogue de PIN. Correctif : initialiser `vaultPinPassed` (et `unlocked`) depuis `VaultManager.sessionUnlocked` (AtomicBoolean Singleton), qui persiste pendant la session de l'app. La ressaisie du PIN n'a désormais lieu qu'après un verrouillage automatique / une panique / un arrêt du processus — le comportement attendu.
- **Avatar palette : retrait slate + gunmetal**. La v1.13.0 conservait ces deux nuances gris-bleu, mais sur certains écrans elles pouvaient paraître verdâtres (G ≈ B en RGB). La v1.13.1 livre **9 paliers strictement bleus** : 4 royal/electric/cobalt/brand-blue + 3 sky/periwinkle/azure + navy + indigo-deep (Material Indigo 600→900). Tous WCAG AA ≥ 4.5:1 sur blanc, confirmé.

Aucun changement de threat-model, aucun changement DB / SQLCipher / Keystore, aucun changement de schéma. `adb install -r` non destructif.

### v1.13.0 — Déplacement groupé vers le coffre par multi-sélection + PIN/mot de passe distinct pour le coffre (second facteur) + déverrouillage biométrique du coffre + palette d'avatars strictement bleue + 6 correctifs d'audit

Release MINOR ajoutant deux fonctionnalités demandées : le **déplacement groupé par multi-sélection vers le coffre et hors du coffre** (dans les deux listes), et un **second facteur PIN ou mot de passe dédié au coffre** (distinct du PIN de l'app, avec repli biométrique). S'y ajoute un nettoyage de la palette qui retire les trois dernières nuances d'avatar teintées de vert. Aucun changement de schéma DB / SQLCipher / Keystore — `adb install -r` non destructif.

Un audit final pré-release exécuté deux fois (passe de consolidation) a fait ressortir **2 HIGH + 4 MEDIUM + 2 LOW** constats, tous corrigés avant le tag :

- **HIGH SEC-1** — `PinEntryDialog` met désormais `pin = ""` AVANT la coroutine `scope.launch { onVerify(...) }`. Sans cela, la `String pin` restait dans le tas JVM pendant les ~100 ms de la dérivation PBKDF2, exposée à l'analyse forensique d'un heap dump (un vecteur documenté comme « hors périmètre » dans le threat model, mais le correctif tient en une ligne). Le `snapshot: CharArray` est toujours effacé dans `finally`, y compris sous `CancellationException` (rotation de l'Activity).
- **HIGH SEC-2** — Le flow `VaultViewModel.vaultPinRequired` appelle désormais `vaultPin.isVaultPinConfigured()` à l'intérieur de `withContext(io) { ... }`. La fonction effectue un `DataStore.first()`, qui est techniquement une E/S ; au démarrage à froid avec un DataStore lent, elle aurait pu bloquer le thread Main pendant des dizaines de millisecondes. Routé via `@IoDispatcher` injecté par Hilt.
- **MEDIUM SEC-4** — La purge de `selectedIds: MutableStateFlow<Set<Long>>` à l'entrée en PanicDecoy a été sortie de la lambda `combine { ... }` (anti-pattern — muter un flow depuis sa propre transformation) vers un `init { viewModelScope.launch { appLock.state.collect { ... } } }` dédié. Séparation des responsabilités plus nette ; le `combine` conserve de toute façon un repli défensif `effectiveSelection = emptySet()`.
- **MEDIUM UX-2** — Le toggle « PIN distinct pour le coffre » et sa ligne « Changer le PIN du coffre » dans `SettingsScreen` sont désormais enveloppés dans `if (!isPanicDecoy) { ... }`. Sans cela, une session PanicDecoy sous contrainte verrait encore le toggle dans les Réglages — ce qui trahirait l'existence d'un coffre configuré (l'icône de cadenas de la barre supérieure et la navigation vers le coffre sont déjà masquées en session leurre ; ceci complète la cohérence entre écrans).
- **MEDIUM NEW-5** — `VaultPinManager.setVaultPin` écrit désormais le drapeau `settings.vaultPinEnabled = true` **à l'intérieur** du bloc `try { hash; storeHash; flag }`, immédiatement après `securityStore.setVaultPinHash()`. Symétriquement, `clearVaultPin` bascule le drapeau AVANT de retirer le hash. Sans cet ordre, une rare IOException DataStore entre l'écriture du hash et celle du drapeau laisserait le coffre dans un état « hash orphelin, flag=false », où `isVaultPinConfigured()` détecterait l'incohérence et le traiterait proprement comme désactivé — mais l'inverse (flag=true sans hash) laisserait l'utilisateur à la porte.
- **LOW NEW-1** — Suppression de la chaîne orpheline `settings_vault_pin_confirm_subtitle` (déclarée FR + EN, utilisée nulle part). Nettoyage de l'APK.

#### Déplacement groupé vers le coffre par multi-sélection (Sujet A)

`ConversationsScreen` et `VaultScreen` exposent tous deux un mode de multi-sélection à la Gmail : un appui long sur une ligne → entrée en mode sélection, un tap inclut ou exclut, la barre supérieure bascule vers un titre contextuel (le nombre) + l'action groupée (`Move to vault` / `Move out of vault`) + une icône Annuler (X). Le retour système quitte le mode sélection (BackHandler). L'action groupée boucle sur `requestMoveToVault(id, intoVault)` pour chaque ID — les gardes PanicDecoy + Locked existantes sont réévaluées à chaque appel (défensif, aucun contournement par transaction groupée). Un seul snackbar est émis avec le nombre de succès (pluriels FR + EN). `selectedIds` est purgé à l'entrée en PanicDecoy (audit SEC-4).

#### PIN/mot de passe distinct pour le coffre + biométrie (Sujet B)

Nouveau Singleton `VaultPinManager` :
- **Crypto** : PBKDF2-HMAC-SHA512, sel de 16 octets + ≥ 210 000 itérations (calibrées). Hash stocké dans `SecurityStore` sous `vault.salt` / `vault.hash` / `vault.iters` — totalement séparé de `pin.*` (app) et de `panic.*` (leurre). Comparaison via `MessageDigest.isEqual` (temps constant).
- **Threat model** : protège contre « j'ai lu ton PIN d'app par-dessus ton épaule, maintenant je vais ouvrir ton coffre » et « je t'ai prêté mon PIN d'app pour récupérer un SMS, mais mon coffre est privé ». Le second facteur est une barrière UI / domaine — le chiffrement au repos reste l'unique clé maîtresse SQLCipher de la v1.0.
- **Hors périmètre** : l'analyse forensique avec le Keystore + la clé SQLCipher déchiffrée. Le PIN du coffre n'ajoute PAS de seconde enveloppe.
- **Repli** : si l'appareil dispose de la biométrie, le dialogue de saisie expose aussi un bouton « Utiliser la biométrie ». L'un ou l'autre chemin (PIN/mot de passe OU biométrie) déverrouille le coffre. Quand le PIN ou la biométrie réussit, l'invite biométrique habituelle de l'app (conditionnée à `lockMode = BIOMETRIC`) est sautée — pas de double second facteur.
- **Réinitialisation** : depuis le toggle Réglages → Sécurité (exige que l'app soit déjà déverrouillée, donc un utilisateur qui a oublié le PIN du coffre mais connaît le PIN de l'app peut le désactiver et le reconfigurer). Aucune récupération si les deux sont oubliés — le déverrouillage par code panique reste la porte de sortie vers la session leurre (le coffre reste scellé, mais le reste de l'app est utilisable).

Nouveau composable réutilisable `PinEntryDialog` (rangé sous `ui/components/`) avec `PasswordVisualTransformation` + `KeyboardType.Password` (alphanumérique — l'utilisateur choisit un PIN ou une phrase de passe), emplacement optionnel pour un bouton biométrique, contrat de callback suspend `(CharArray) -> Boolean`, chaîne d'erreur unique `pin_error_invalid` (aucune fuite entre « aucun PIN défini » et « mauvais PIN »).

#### Palette d'avatars strictement bleue (Sujet 0)

La palette de 14 nuances de la v1.12.0 a été réduite à **11 paliers strictement bleus** par le retrait des 3 entrées tirant sur le vert (`teal`, `dark teal`, `cyan`). Les 11 restantes sont pure blue / cobalt / sky / periwinkle / azure / navy / cool-steel / slate / gunmetal — toutes WCAG AA ≥ 4.5:1 sur initiales blanches. Distribution déterministe par hash inchangée ; les utilisateurs existants verront certains contacts passer à un nouvel emplacement (taille 14 → 11), ce qui est acceptable pour un affinage UX.

### v1.12.0 — Palette d'avatars (famille bleue) + correctif du nom de contact dans ComposeScreen + entrée du coffre dans le menu de ThreadScreen + raccourci d'urgence sur l'écran de verrouillage (112 / 17) + 3 correctifs d'audit

Version MINOR consacrée à des finitions d'ergonomie du mode urgence (accessibilité depuis l'écran de verrouillage + boutons d'appel vocal d'urgence) et de la liste des conversations (palette d'avatars entièrement bleue WCAG AA, nom du contact désormais résolu à la composition). Aucun changement de base / coffre / Keystore — `adb install -r` non destructif.

Un audit final avant release (3 axes + cohérence) a relevé **2 HIGH et 1 MEDIUM bloquants**, tous corrigés avant le tag :

- **HIGH S1** — L'observation du raccourci d'urgence dans `MainApplication.kt` passe désormais par `combine(settings.flow, appLock.state)` et annule la notification persistante de l'écran de verrouillage dès que `LockState.PanicDecoy` devient actif. Sans ce garde, un attaquant qui contraint un déverrouillage par le code panique verrait encore la notification « URGENCE / 112 / 17 » sur l'écran de verrouillage, et apprendrait que SMS Tech a un mode urgence configuré (fuite d'information + vecteur d'attaque latéral — l'action URGENCE elle-même est déjà gardée par le contrôle PanicDecoy de `TriggerEmergencyUseCase`, mais la *présence* du raccourci fuyait).
- **HIGH S2** — Le Toggle « Appel police FR (17) » de `SettingsScreen` est désormais conditionné par `if (state.security.emergencyShortcutEnabled)`, il ne peut donc plus être configuré orphelin. Sans le raccourci activé, le toggle n'avait aucun effet observable (le bouton 17 de l'EmergencyScreen dans l'application lit le même drapeau, il restait donc visible, mais la notification de l'écran de verrouillage — seul consommateur dont le comportement diffère visiblement — n'était pas publiée) — UX déroutante + écritures DataStore parasites.
- **MEDIUM U2** — Les boutons d'urgence 112 et 17 de `EmergencyScreen` interceptent désormais `ActivityNotFoundException` (aucun composeur installé — rare mais possible sur des builds AOSP dépouillées et des profils MDM d'entreprise) et affichent une snackbar `emergency_shortcut_no_app_to_dial`. Sans retour, l'utilisateur croirait l'appel en cours alors que rien ne se passe — un échec silencieux en contexte d'urgence. Ajout aussi de `FLAG_ACTIVITY_NEW_TASK` par précaution (actuellement invoqué depuis un contexte d'Activity, donc non bloquant, mais conforme au chemin du BroadcastReceiver qui l'exige strictement).

#### Refonte de la palette d'avatars (Sujet 1)

La palette de la v1.11.0 mêlait 5 rouges + 1 prune à des bleus et des verts. Le rouge est visuellement anxiogène dans un contexte de messagerie et réservé par Files Tech aux états destructifs/de danger (BrandDanger). La v1.12.0 livre une palette de 14 nuances purement bleu / teal / marine / cyan, chaque teinte vérifiée ≥ 4.5:1 contre `Color.White` pour la lisibilité des initiales (WCAG AA). Les teals et cyans clairs qui n'atteignaient pas le contraste ont été assombris ; la famille de teintes est uniforme, mais l'étalement entre royal/électrique/marine/teal/cyan garde les avatars distinguables dans les longues listes de conversations.

#### Correctif du nom de contact dans ComposeScreen (Sujet 7)

`ConversationRepositoryImpl.findOrCreate(addresses)` insérait les nouvelles conversations avec `displayName = null`, si bien que la conversation restait étiquetée par le numéro de téléphone brut jusqu'à la synchronisation suivante des contacts système. Pour une composition à destinataire unique, désormais :

1. Recherche de `ContactRepository.lookupByPhone(addresses[0].raw)` **hors de la transaction** (recherche à chaud, aucune écriture en base).
2. Assainissement du résultat par `stripInvisibleChars()` + `trim()` pour déjouer la contrebande homoglyphe / bidi / RLO dans le champ nom du contact (un import vCard malveillant pourrait sinon injecter un override `‮`).
3. Transmission du nom résolu à `insertOrIgnoreConversation` pour que la nouvelle ligne soit correctement étiquetée dès la première image.
4. **Complément d'une conversation existante** dont le `displayName` est null/vide — couvre les données héritées créées par la v1.11.x avant ce correctif.

Destinataire unique seulement (le MMS de groupe garde `null` et laisse l'UI composer les participants).

#### Entrée « Déplacer vers le coffre » dans le menu de ThreadScreen (Sujet 2)

Le menu de débordement de `ThreadActionsMenu` expose désormais « Déplacer vers le coffre » / « Sortir du coffre » avec les icônes `Lock` / `LockOpen`. L'action :

- Masquée en PanicDecoy (garde de la couche UI).
- Refusée par `VaultManager.requestMoveToVault` en PanicDecoy + Locked (garde de la couche domaine).
- La snackbar distingue Locked (`error_session_locked`) d'un échec générique (`snack_generic_error`).
- Pas de feature flag limité à la couche données — la ligne n'est tout simplement pas rendue en PanicDecoy, ce qui neutralise le canal auxiliaire consistant à fouiller le menu.

#### Raccourci d'urgence sur l'écran de verrouillage (Sujet « vigilance vocale »)

Le mode urgence des v1.10.0/v1.11.0 exigeait un déverrouillage + une navigation dans les Réglages pour être atteint. Dans une vraie urgence, cela fait trop d'appuis. La v1.12.0 ajoute :

1. **`EmergencyShortcutReceiver`** (`exported = false`) — BroadcastReceiver avec 3 actions :
   - `ACTION_TRIGGER_EMERGENCY` → délègue à `TriggerEmergencyUseCase` (gardé contre PanicDecoy). Utilise `goAsync()` + `ApplicationScope`, si bien que l'envoi des SMS + la résolution de la position se poursuivent même si la notif est balayée.
   - `ACTION_DIAL_112` et `ACTION_DIAL_POLICE` → intents `ACTION_DIAL` pré-remplis avec 112 (UE) ou 17 (FR). `ACTION_DIAL` ouvre le composeur avec le numéro pré-saisi mais N'APPELLE PAS automatiquement — l'utilisateur confirme en appuyant sur le bouton vert. Cela évite d'exiger la permission d'exécution `CALL_PHONE` ET empêche l'appel involontaire des services d'urgence depuis une poche.
2. **`EmergencyShortcutNotifier`** — notification persistante en cours sur `CHANNEL_EMERGENCY_SHORTCUT` (`IMPORTANCE_LOW` pour éviter heads-up / son / vibration), `VISIBILITY_PUBLIC` pour que les actions soient utilisables depuis l'écran de verrouillage. Jusqu'à 3 actions (URGENCE + 112 + 17 si l'option police est activée).
3. **`MainApplication`** observe `(emergencyShortcutEnabled, emergencyCallPoliceEnabled)` combiné à `appLock.state` (correctif d'audit S1) — ne publie la notification que si le raccourci est activé ET que la session n'est pas PanicDecoy.
4. **`BootReceiver`** republie la notification après le redémarrage de l'appareil (avec un plafond `withTimeoutOrNull` de 3 s sur la lecture DataStore pour borner le chemin de démarrage).
5. **`EmergencyScreen`** expose aussi les boutons 112 et 17 (équivalent dans l'application). Les deux affichent une snackbar sur `ActivityNotFoundException` (correctif d'audit U2).

Les numéros `112` et `17` sont codés en dur dans des companion objects — ils ne peuvent pas être détournés par des extras d'intent pour composer un numéro arbitraire. L'action « URGENCE » s'appuie sur les défenses existantes PanicDecoy / horloge murale-monotone / single-flight de la v1.10.0 SEC-11.

### v1.11.0 — Finitions du coffre + anti-smishing + apparence + 7 correctifs d'audit

MINOR release fortifiant la feature Vault (3 trous comblés), introduisant un détecteur anti-smishing 100 % offline, et l'apparence personnalisée par conversation (couleur de bulle WCAG-safe + avatar custom).

Un audit avant release (3 axes + plongée sécurité finale + cohérence d'architecture + i18n) a relevé **7 HIGH et 14 MEDIUM**, tous corrigés avant le tag :

- **HIGH SEC-V1** — `MessageDao.search` join `conversations` avec filtre `in_vault = 0` ; `ConversationRepositoryImpl.findMessageById` guard `inVault`. Sans ces 2 fixes, la recherche FTS exposait le body des messages vault (IDOR : `1mpots scam` cherché dans la search ramenait les messages vault).
- **HIGH SEC-V2** — `VaultManager.sessionUnlocked` migré `@Volatile Boolean` → `AtomicBoolean`. Sémantique correcte pour un flag partagé coroutines IO/UI (tearing impossible). Double-check `PanicDecoy` post-suspend dans `requestMoveToVault` (race window fermée).
- **HIGH SEC-V3** — `AppearanceDialog` conditionne `pickedAvatarUri` au succès de `takePersistableUriPermission`. Sans, une URI révoquée entre pick et take polluait Room en silence (Coil échouait au render).
- **HIGH P1** — `SmishingDetector.analyze()` déplacé sur IO dispatcher dans `ThreadViewModel.recomputeSmishingVerdicts`, exposé via `Map<Long, List<SmishingReason>>` dans state. Plus de jank 600 ms à 3 s sur thread 200 msgs low-end (Cortex-A53).
- **HIGH U1** — `ColorChip` accessibilité TalkBack : `contentDescription` + `role = RadioButton` + `selected` semantics + 9 noms de couleurs FR/EN. `FlowRow` pour adaptation petits écrans 320 dp.
- **HIGH C4** — `ForwardMessageSheet` propage `customUri = conv.avatarUri` au composable `Avatar` (cohérence avec ConversationRow — sinon avatar custom invisible dans le sheet de partage).
- **HIGH S1** — `VaultScreen.LaunchedEffect(Unit)` (au lieu de `lockMode` comme clef) : empêche un double `BiometricPrompt` empilé sur certains OEM si lockMode change pendant que le prompt est en vol.

#### Vault polish (3 trous comblés)

1. **Notifications gates `inVault`** — `IncomingMessageNotifier.notifyIncoming` injecte `ConversationDao` et early-returns si la conv est dans le coffre. SMS + MMS couverts (1 seul point). Aucune notif, aucun son, aucun badge système ne fuite pour les conv vault.
2. **UI move-in/move-out** — Long-press conv dans `ConversationsScreen` → ActionsSheet avec "Déplacer vers le coffre" (masqué en PanicDecoy). Long-press conv dans `VaultScreen` → "Sortir du coffre". Strings `vault_move_in/out` (jusque-là orphelines) câblées. Snackbar feedback (bleu marque succès / rouge erreur).
3. **BiometricPrompt à l'entrée** — Si `lockMode = BIOMETRIC`, prompt à l'entrée VaultScreen comme second-factor. Si refusé/annulé → `onBack()`. Si biométrie indisponible → fallback gracieux à l'entrée directe.
4. **Nouveau `VaultManager.requestMoveToVault(id, intoVault)`** — wrap pour appels hors-VaultScreen (long-press liste, futur overflow Thread). Refuse `PanicDecoy` + `Locked`, auto-`markUnlocked` sinon. Double-check `PanicDecoy` post-suspend (SEC-V2).

#### Anti-smishing local (Sujet 3)

Détecteur 100 % offline, sans modèle, sans cloud. 4 heuristiques composables :
- **URL shortener** (17 hosts : bit.ly, t.co, tinyurl, rebrand.ly…)
- **Mots d'urgence** (~40 patterns FR + EN : urgent, compte bloqué, colis bloqué, click here, impots impayés…)
- **Numéros surtaxés FR** (regex avec lookaround non-digit : `32xx`-`36xx`, `0899xxxxxx`, `081x/088x/089x`)
- **Typosquatting de domaines officiels FR** (Levenshtein bornée ≤ 2 sur 28 hosts officiels : impots.gouv.fr, ameli.fr, banques, opérateurs, paypal…)

Seuil par défaut = 2 heuristiques positives (anti faux positif). Cap 1000c sur le body inspecté. Cap 20 URLs + 30 domaines inspectés par body (anti-DoS Levenshtein × matches). Bandeau rouge cliquable dans la bulle SMS entrante → dialog "Pourquoi" listant les raisons localisées. Toggle Settings opt-in par défaut, désactivable.

20 tests garde-régression : cas véritables (colissimo phishing, fake impots, scam Amazon EN) + faux positifs FR officiels (banque, impots, ameli) + edges (vide, body > 1000c, Levenshtein symétrique).

#### Apparence par conversation (Sujet 5)

Room migration v6→v7 strictement additive : `conversations.bubble_color_argb INTEGER?` + `avatar_uri TEXT?`. Downgrade safe. `ALTER TABLE ADD COLUMN` × 2 wrappés atomiquement par Room (SQLCipher WAL rollback en cas de kill).

UI : dialog "Apparence" depuis l'overflow ThreadScreen. Palette `BubbleColorPalette` 8 couleurs WCAG-safe contre texte blanc (BRAND_BLUE par défaut = reset null). Avatar picker via `PickVisualMedia` Android 13+ → URI `content://` persistée via `takePersistableUriPermission` (release de l'ancienne URI avant prise de la nouvelle, anti-accumulation grants). Scheme `content://` whitelist côté repository (defense in depth path traversal).

Palette avatars auto-générés étendue 7 → 14 nuances (cœur bleu/teal + transition plum + 5 nuances rouge/grenat/bordeaux), toutes WCAG AA contre blanc, hash déterministe par contact.

#### Refactos + corrections audit MEDIUM (14)

- `IncomingMessageNotifier` : suppression du `Timber.d` "conv vault suppressed" (anti-corrélation builds bêta)
- `SmishingDetector` : cap `MAX_URL_MATCHES=20` + `MAX_DOMAIN_MATCHES=30` sur `findAll`
- `ConversationRepositoryImpl.setAppearance` : whitelist scheme `content://`
- `AppearanceDialog` : release ancienne URI avant prise nouvelle (anti-accumulation)
- `ThreadViewModel.recomputeSmishingVerdicts` : `smishingJob?.cancel()` avant re-launch (anti-race toggle rapide)
- `Migrations.MIGRATION_6_7` : KDoc explicite sur non-idempotence d'`ALTER TABLE ADD COLUMN` (transactionnalité Room WAL)
- `EmergencyArmedRecap` ajouté dans `SettingsScreen` (miroir de `SafetyCallArmedRecap`, chip "Armé" + 3 lignes + 2 boutons)
- `AboutScreen` nettoyé : références ML Kit + Google Messages retirées (post-v1.7.0 FLOSS compliance + cohérence éditoriale)
- Tonalité FR : 4 strings tutoiement résiduels v1.9.0 → vouvoiement (cohérence i18n projet)
- `smishing_reason_typosquatting` : retrait balises HTML `<i>` (non rendues par Compose Text) → guillemets typographiques

**Reporté v1.12.0** : overflow Thread "Déplacer vers coffre", PIN/pass distinct pour coffre (second hash crypto), multi-sélection de conv pour coffre, options de partage depuis coffre, répondre depuis coffre.

Cert SHA-256 stable `b09a9511…687d`. Aucune dépendance NonFreeDep ajoutée.

### v1.10.0 — Mode urgence + durcissement par horloge monotone + refactorisations

Version MINOR introduisant la fonctionnalité **mode urgence** (bouton SMS opt-in à maintien actif de 3 s qui envoie un modèle personnalisé + l'URL de la position GPS aux contacts du Safety call de l'utilisateur) ainsi qu'un contrôle complémentaire par horloge monotone (SEC-11) sur l'homme mort existant du Safety call.

Un audit avant release (3 axes + plongée sécurité + cohérence d'architecture + i18n) a relevé 3 HIGH, 8 MEDIUM et 2 LOW, tous corrigés avant le tag :

- **HIGH SEC-1** — Le garde de navigation d'`AppRoot` dépile désormais les routes `Emergency` et `EmergencySetup` quand `PanicDecoy` s'active ; `SettingsScreen` masque les sections « Mode urgence » et « Safety call » quand `isPanicDecoy = true`. Sans cela, un attaquant dans une session leurre forcée verrait le bouton URGENCE et apprendrait l'existence de la fonctionnalité, ce qui briserait l'illusion d'une « application SMS ordinaire ».
- **HIGH SEC-2** — `EmergencyViewModel.trigger()` protégé par un garde d'exécution en cours `AtomicBoolean compareAndSet`. Sans lui, un double maintien paniqué pendant la fenêtre d'écriture DataStore d'environ 50–300 ms pouvait envoyer deux SMS à chaque contact, semant la confusion chez les destinataires dans un moment de stress.
- **HIGH P1** — `LocationResolver.awaitFirstFix` utilise `AtomicBoolean resumed` pour garantir une reprise unique sur `suspendCancellableCoroutine`. Sans cela, des fixes GPS + NETWORK quasi simultanés pouvaient appeler `cont.resume` deux fois → `IllegalStateException: Already resumed` avalée silencieusement → SMS envoyé sans coordonnées alors qu'un fix valide était disponible.
- **HIGH S1+U1+U2** — `EmergencySetupScreen` utilise désormais `rememberPermissionState(ACCESS_FINE_LOCATION)`, demande la permission à l'activation du toggle, et affiche un avertissement rouge persistant si la permission est refusée. `EmergencyScreen.MessagePreviewCard` reflète l'état RÉEL de la permission (pas seulement la préférence de l'utilisateur), pour que le corps du SMS prévisualisé corresponde à ce qui sera envoyé. Sans cela, un utilisateur qui activait l'interrupteur sans accorder la permission se construisait une fausse confiance dans une fonctionnalité de sécurité.
- **MEDIUM SEC-4** — `EmergencyConfig.isInAntiSpamWindow()` traite un delta monotone négatif (après un redémarrage, avant la fin de la récupération asynchrone de dérive dans `MainApplication.onCreate`) comme « encore en période de refroidissement » — sûr par défaut face à une attaque root + redémarrage + avance d'horloge.
- **MEDIUM SEC-5** — Les chaînes de corps d'`EmergencyTemplate` passent de `—` (U+2014, tiret cadratin) à `-` (trait d'union ASCII), et `Ù` a été retiré de `DISCREET`. Les trois modèles tiennent désormais dans un seul segment GSM-7 avec l'URL Maps ajoutée → aucun risque de multi-segment dans les zones d'urgence à faible couverture radio. Gardé par deux tests unitaires dans `AuditV1100Test`.
- **MEDIUM SEC-6** — Le catch de `SecurityException` de `LocationResolver.awaitFirstFix` appelle toujours `cleanup()` avant de vérifier le drapeau `resumed`, ce qui garantit le retrait de l'écouteur GPS même si l'enregistrement du fournisseur NETWORK a échoué après celui du GPS.
- **MEDIUM S3** — `EmergencyViewModel.save()` préserve les valeurs COURANTES `lastTriggeredAt` et `monotonicLastTriggeredAt` de DataStore au moment de l'enregistrement, et non la valeur périmée capturée à l'ouverture du réglage. Sans cela, modifier n'importe quel paramètre du réglage après un déclenchement récent effaçait le refroidissement anti-spam.
- **MEDIUM C1** — `EmergencySetupScreen.SetupCard` aligné sur `surfaceContainer` (était `surface`), conformément à la convention de `SafetyCallSetupScreen.SectionCard`.
- **MEDIUM C2** — Le collecteur d'événements d'`EmergencySetupScreen` utilise un `when (event)` exhaustif au lieu de `if (event is …)`, si bien qu'un nouveau cas d'`Event` ajouté plus tard est signalé à la compilation.
- **MEDIUM i18n** — 6 chaînes FR du bloc Urgence passées du tutoiement au vouvoiement, en accord avec le ton FR de l'ensemble du projet (les modèles eux-mêmes gardent la voix à la première personne de l'utilisateur).
- **LOW C5** — La déclaration `K.safetyCallCustomMessage` est revenue dans le bloc `safetyCall*` de `SettingsRepository` (elle était visuellement orpheline après le bloc `emergency*`).

#### SEC-11 — contrôle complémentaire par horloge monotone sur le Safety call

Durcissement indépendant du Safety call de la v1.9.0 : `SafetyCallConfig.lastActivityAt` (horloge murale) est désormais complété par `SafetyCallConfig.monotonicLastActivityAt` (instantané de `SystemClock.elapsedRealtime()` à chaque réinitialisation). `isExpired()` et `isInWarningWindow()` exigent que les DEUX horloges franchissent `timeoutMs` avant de déclencher. Un attaquant root qui avance `Settings.Global.AUTO_TIME=0; date <future>` pour forcer le déclenchement immédiat de l'homme mort est désormais mis en échec — l'horloge monotone continue de suivre le temps réel écoulé depuis le démarrage, quelle que soit la manipulation de l'horloge murale.

Récupération de dérive : à chaque démarrage à froid, `MainApplication.onCreate` détecte si une valeur monotone stockée dépasse le `SystemClock.elapsedRealtime()` courant (conséquence d'un redémarrage) et la réaligne sur la valeur monotone courante. L'homme mort est de fait prolongé de la durée de fonctionnement après le redémarrage — compromis acceptable, l'alternative étant un homme mort bloqué en permanence après chaque redémarrage.

Migration v1.9.0 → v1.10.0 : les configurations persistées avant cette version ont `monotonicLastActivityAt = 0L` ; `isExpired()` renvoie `false` dans ce cas (filet de sécurité) jusqu'à ce que la première réinitialisation (`MainActivity.onResume`, « Je vais bien », appui sur la notif d'avertissement, enregistrement du réglage) renseigne le nouveau champ. La première ouverture de l'application après la mise à jour réarme implicitement l'homme mort.

#### Refactorisations C1 + C4 (cosmétiques, aucun changement de comportement)

- C1 — `SafetyCallTriggerService` (couche données) → `TriggerSafetyCallUseCase` (domain/usecase) avec `operator invoke()`, en accord avec le motif UseCase dominant du projet. Appelants mis à jour : `SafetyCallWorker`, `AuditV190Test`.
- C4 — `SafetyCallContactJsonCodec` → `SafetyCallContactCodec` (le format n'a jamais été du JSON, il est séparé par des pipes depuis le premier jour). Objet + fichier renommés ; clé DataStore (`security.safetyCall.contactsJson`) inchangée pour la rétrocompatibilité du stockage.

#### Performance P2

`SettingsScreen.SafetyCallArmedRecap` n'appelle plus `System.currentTimeMillis()` à chaque recomposition ; `SettingsViewModel` expose `safetyCallRemainingMs: StateFlow<Long>` recalculé toutes les 60 s (ou dès que `state` change via `combine`). Granularité suffisante pour un compte à rebours à l'heure près affiché à l'utilisateur.

Audit avant release : 17 tests garde-régression dans `AuditV1100Test` (attaque par avance d'horloge sur le Safety call + l'urgence, dérive après redémarrage, repli de migration v1.9.0, anti-spam de l'urgence, garantie d'un segment GSM-7 unique, valeurs par défaut des modèles).

### v1.9.0 — Safety call + format compact de réaction + durcissement d'audit

Version MINOR introduisant la fonctionnalité **Safety call** (SMS automatique opt-in vers 1–4 contacts d'urgence après un délai d'inactivité configuré par l'utilisateur, de 1 h à 30 jours) et un quatrième format compact de réaction `EMOJI_WITH_QUOTE` (`❤️ «excerpt»`). Désactivée par défaut.

Un audit avant release (3 axes en parallèle + cohérence d'architecture + i18n + plongée sécurité) a relevé 1 CRITICAL, 4 HIGH et 6 MEDIUM, tous corrigés avant le tag :

- **CRITICAL** — `SafetyCallTriggerService` + `SafetyCallWorker` vérifient désormais `AppLockManager.LockState.PanicDecoy` et court-circuitent avant tout envoi. Sans ce garde, l'homme mort aurait envoyé des SMS aux contacts d'urgence de la victime sous la contrainte, révélant son réseau de soutien à l'attaquant. Le tick du worker (60 min) réessaie automatiquement une fois l'état leurre quitté.
- **HIGH** — `BootReceiver` replanifie désormais `SafetyCallWorker.schedulePeriodic` sur `BOOT_COMPLETED`, pour qu'un OEM qui force l'arrêt (Xiaomi / Huawei) ne perde pas l'homme mort. La politique KEEP garde l'appel idempotent.
- **HIGH** — `IncomingReactionDecoder.EMOJI_WITH_QUOTE_REGEX` reformulée avec la classe négative `[^»"]{1,200}` (était `.+?` + DOT_MATCHES_ALL) — élimine le backtracking catastrophique sur l'entrée pathologique `❤️ «aaaa...` (sans guillemet fermant, plafonnée à 400 caractères).
- **HIGH** — Le garde strict d'emoji `isLikelyEmojiChar(c)` exige un high-surrogate OU `U+2300..U+27BF` OU ZWJ / VS-16 (était `code < 128`, qui avalait silencieusement les messages FR commençant par un mot accentué — `"été «aperçu»"` était interprété à tort comme une réaction).
- **MEDIUM** — Nonce anti-usurpation (`SafetyCallIntentToken`) sur `ACTION_SAFETY_CALL_RESET`. L'extra d'intent `EXTRA_RESET_TOKEN` est validé et consommé à usage unique par `MainActivity` ; une application tierce ne peut pas neutraliser l'homme mort en forgeant l'action.
- **MEDIUM** — La réinitialisation dans `MainActivity.onResume` est conditionnée à `LockState.Unlocked || Disabled`. `Locked` / `PanicDecoy` ne réinitialisent plus le minuteur.
- **MEDIUM** — `SafetyCallTriggerService.disableSafetyCall()` est désormais appelé **avant** la boucle d'envoi (désactivation préventive). Un plantage au milieu de la boucle ne provoque plus de double déclenchement 60 min plus tard.
- **MEDIUM** — `SafetyCallContactJsonCodec.decode()` filtre via `SafetyCallContact.isValid()` (défense en profondeur contre une restauration DataStore falsifiée). L'encodage retire toute la plage C0/C1 + le séparateur `|` (auparavant seulement `\n` + `|`).
- **MEDIUM** — Canal de notification dédié `CHANNEL_SAFETY_CALL_WARNING` (partageait `CHANNEL_INCOMING` avec les SMS ordinaires) pour que l'utilisateur puisse régler son / vibration indépendamment.
- **MEDIUM** — `SafetyCallSetupViewModel` lit un instantané unique de DataStore (`first()` au lieu de `collect`) — corrige une perte de données quand une écriture concurrente (réinitialisation `onResume`) écrasait le brouillon en cours.

Les logs ne divulguent plus `phoneNumber` à Timber (remplacé par des identifiants d'index uniquement). `SafetyCallTemplate.CUSTOM` re-plafonne au rendu à `MAX_CUSTOM_MESSAGE_LENGTH=140` pour se défendre contre des valeurs DataStore falsifiées qui produiraient sinon une surprise multi-segment.

### v1.8.1 — Libellé hybride des réactions (nommé / anonyme) + décodeur double

PATCH faisant suite aux tests terrain de la v1.8.0 — le format de réaction FR lisible `"J'ai réagi par ❤️ à : «…»"` était ambigu lu hors contexte. La v1.8.1 le reformule en hybride : avec nom de l'expéditeur → `"<Name> a réagi par ❤️ à votre message : «…»"`, anonyme → `"Réagi par ❤️ à votre message : «…»"`. Nom de l'expéditeur résolu par un repli à 3 niveaux : (1) surcharge dans les Réglages (assainie, plafond 40c, anti-C0/C1/bidi/BOM), (2) détection automatique via `ContactsContract.Profile`, (3) anonyme.

Le décodeur accepte 4 nouvelles regex (nommé/anonyme × avec aperçu/sans aperçu) + l'ancien format v1.8.0 + l'ancien Tapback EN. `MAX_DECODE_INPUT_LENGTH = 400` neutralise le ReDoS sur les quantificateurs non gourmands. Aucune migration de schéma, ajout DataStore uniquement, sans risque en cas de rétrogradation.

### v1.8.0 — Correctifs des badges de conversation + sélecteur de format de réaction + navigation par appui sur la notif

12 correctifs confirmés sur Galaxy S9 Android 10 + Galaxy S24 Android 15. Notables côté sécurité : `markRead` se propage désormais aux fournisseurs système `content://sms` + `content://mms`, si bien qu'une désinstallation + réinstallation préserve l'état de lecture ; la migration à usage unique `unreadResetV180` remet les messages entrants historiques à read=1 (aligné sur Google Messages / Samsung Messages) ; `TelephonySyncManager.runSync` force `read=true` sur tous les messages historiques à la première synchronisation.

### v1.7.1 — Traduction FLOSS par délégation au système (ACTION_PROCESS_TEXT)

Rétablit la traduction en déléguant à l'application de traduction installée par l'utilisateur via `Intent.ACTION_PROCESS_TEXT` avec `EXTRA_PROCESS_TEXT_READONLY=true` (anti-usurpation : l'application appelée ne peut pas modifier l'original). `<queries>` dans `AndroidManifest` déclare une visibilité ciblée des paquets (pas de `QUERY_ALL_PACKAGES`). Sélecteur système obligatoire. Aucun modèle ML embarqué.

### v1.7.0 — Conformité FLOSS F-Droid (Google ML Kit retiré)

Suppression de `com.google.mlkit:translate` + `com.google.mlkit:language-id` + `kotlinx-coroutines-play-services` (servait de pont `Task→suspend` pour ML Kit). `TranslationService.kt` réécrit en stub qui renvoie `Outcome.Failure(Validation("translation_unavailable_v17"))`. Certificat SHA-256 stable. APK arm64 -2 Mo.

### v1.6.2 — Correctif d'une régression critique des réglages + améliorations du fold Tapback

PATCH regroupant 5 correctifs visibles par l'utilisateur, mis au jour lors des tests en conditions réelles de la v1.6.1.

**B1 — CRITICAL : tous les réglages utilisateur ignorés par ThreadViewModel.** Ma
PERF-01 v1.6.1 a introduit un `cachedSettings: StateFlow<AppSettings>` initialisé
avec `stateIn(viewModelScope, WhileSubscribed(5_000), AppSettings())` — mais
AUCUN consommateur ne collectait jamais cette flow (lecture uniquement via `.value`
depuis 5 sites). Sans collecteur, le flux sous-jacent n'était jamais souscrit et
`.value` retournait toujours la **valeur initiale par défaut** `AppSettings()`. Tous
les réglages utilisateur étaient donc **silencieusement ignorés** dans
`ThreadViewModel` : `confirmBeforeBroadcast`, `reactionConfirmDismissed`,
`reactionEmojiOnly`, `sendReactionsToRecipient`. Le dialog de confirmation s'affichait
sans cesse même après coche "Ne plus demander", le mode emoji-only restait inaccessible,
etc. Fix : `cachedSettings` délègue désormais à `settings.state` (la StateFlow
`Eagerly` hydratée par `appScope` côté [SettingsRepository], qui elle EST toujours
collectée). Vérification ajoutée : `WhileSubscribed` n'est valide que pour des
StateFlow exposées et collectées par Compose ; les caches privés doivent utiliser
`Eagerly` ou un autre mécanisme actif.

**B2 — Tapback fold échouait sur les bodies multi-ligne.** L'encoder
[SendReactionUseCase.buildTapbackBody] normalise les whitespace (newlines, tabs →
espace simple) dans le preview avant émission, mais le matcher receiver
[ConversationMirror.applyIncomingReaction] utilisait `body LIKE 'prefix%'` côté
SQL — et SQLite LIKE ne fait pas d'équivalence whitespace. Un OUTGOING stocké
`"Hello\nworld"` ne matchait pas le prefix `"Hello world"`, donc la réaction
s'affichait comme bulle texte au lieu d'un badge. Fix : nouveau DAO
`findRecentOutgoingForConversation(convId, 50)` + fallback Kotlin qui normalise
les whitespace des 2 côtés (`collapseWhitespace()` extension privée). Path rapide
SQL LIKE conservé pour les cas mono-ligne (majorité).

**B3 — Ambiguïté de fold sur messages courts à préfixe partagé.** Quand
plusieurs OUTGOING courts partagent un préfixe ("Hello" vs "Hello world"),
l'ancien matcher prenait toujours le PLUS RÉCENT — donc une réaction à l'ancien
"Hello" était folded sur "Hello world" (faux message). Fix : nouveau champ
[DecodedReaction.wasTruncated] (true si le wire contenait `…`). Dans le matcher :
- `wasTruncated == false` (body court non tronqué, preview = body complet) →
  match **EXACT** après normalisation des whitespace. "Hello" matche uniquement
  "Hello", pas "Hello world".
- `wasTruncated == true` (body long, prefix seul connu) → fallback prefix
  match (avec l'ambiguïté inhérente au protocole SMS-based Tapback, sans solution
  sans casser la compat iMessage/Google Messages).

**B4 — Dialog confirm réaction réouvrait malgré "Ne plus demander".** Race
sub-100 ms entre `settings.update { reactionConfirmDismissed = true }` (write
DataStore async) et la prochaine lecture de `cachedSettings.value.sending`
(StateFlow Eagerly avec délai de propagation). Si l'utilisateur réagissait deux
fois en rapide succession, la 2e lecture trouvait encore l'ancienne valeur
`false`, ré-ouvrait le dialog. Fix : lecture **fraîche** via `settings.flow.first()`
UNIQUEMENT sur ce site (lecture après write potentiel). Les 4 autres sites
PERF-01 (envoi SMS/MMS hot path) restent en lecture `cachedSettings.value` car
ils n'ont pas de write précédent à attendre.

**B5 — Label "Format compact (emoji seul)" trompeur.** L'option contrôle en
réalité le format wire des réactions (Tapback verbeux qui permet le fold côté
destinataire, vs emoji nu qui force le destinataire à voir un SMS texte sans
contexte). Les utilisateurs activaient l'option pensant "compact = mieux", et se
retrouvaient avec les badges qui n'apparaissaient plus chez le destinataire.
Label renommé en **"Envoyer l'emoji nu (sans contexte)"** + description
réécrite pour expliciter le trade-off OFF (recommandé, badge sur message) vs
ON (SMS texte, perd la fusion).

Aucune surface sécurité changée. Le fold Tapback est strictement local au
receveur ; il ne crée pas de nouvelle entrée sensible. Le matcher exact
(B3) ne diminue pas la sécurité — il améliore juste la précision de
l'association message↔réaction.

### v1.6.1 — Correctif de la notification de réaction + durcissement issu d'un audit approfondi (30 correctifs)

**1. Correctif de la régression de la notification de réaction** (cause première de ce PATCH).
Depuis la v1.4.1 (chemin de fold Tapback), `SmsDeliverReceiver` rattachait correctement une
réaction entrante au message sortant d'origine, mais faisait `return@launch` aussitôt après
l'insertion de la sentinelle SEC-01 — sautant toute la branche `notifier.notifyIncoming(...)`. Le
destinataire voyait le badge changer en silence, sans notification système, ce qui rompait la
parité avec le Tapback d'iMessage / Google Messages.
- `ConversationMirror.ReactionApplied` gagne un `targetMessageId: Long` (identifiant de notification stable
  + lien profond vers le message précis ; aucune collision, aucun changement de schéma de base).
- `SmsDeliverReceiver` publie un corps localisé (`reaction_notif_body_with_preview` /
  `_no_preview`) via `notifyIncoming(...)`, en préservant les contrats `previewMode`, `enabled`,
  `POST_NOTIFICATIONS`, fermeture automatique sur la conversation active et nettoyage par tag.

**2. Audit approfondi post-publication — 30 correctifs livrés sur 3 axes** (score 84/83/88 → 96+).

*Sécurité (7)*
- **SEC-01** : `MessagingStyle.Message(visiblePreview)` au lieu de `body` brut. Avant,
  certains OEMs (Xiaomi MIUI/HyperOS, Samsung One UI < 5) ignoraient
  `VISIBILITY_SECRET` pour `MessagingStyle` et fuitaient le contenu en lockscreen.
- **SEC-05** : `addrSuffixes` (PII suffixes téléphoniques 8 chiffres, quasi-identifiants
  RGPD) retirés des logs Timber dans `BlockedNumbersImporter`.
- **SEC-06** : URL MMSC complète (potentiels tokens session opérateur dans path/query)
  retirée du log debug dans `MmsWapPushReceiver`.
- **SEC-07** : `applied.targetBody` désormais passé par `stripInvisibleChars()` dans
  `SmsDeliverReceiver` avant injection dans la notif réaction (anti BiDi/RLO sur body
  OUTGOING qui n'était pas stripé à l'écriture).
- **SEC-08** : sender + caption + subject MMS passés par `stripInvisibleChars()` dans
  `MmsDownloadedReceiver` (parité avec le path SMS, defense in depth).
- **SEC-09** : `Attachment.toShareableUri` ajoute `canonicalFile` + whitelist
  `[filesDir, cacheDir]` avant FileProvider (defense in depth path traversal).
- **SEC-11** : `AndroidManifest.xml` clarifié sur la protection réelle des actions
  `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `USER_UNLOCKED` (protected broadcasts
  AOSP, pas la permission `RECEIVE_BOOT_COMPLETED` qui est "normal").

*Performance (8)*
- **PERF-01 (HIGH)** : `cachedSettings: StateFlow<AppSettings>` dans `ThreadViewModel`
  remplace 5 `settings.flow.first()` sur le chemin send (économie ~25-50 ms de
  latence cumulée sur clavier rapide). Idem **PERF-08** dans `TelephonySyncWorker`
  (snapshot unique) et **PERF-11** dans `IncomingMessageNotifier` (lecture
  synchrone via `SettingsRepository.state` hydraté Eagerly au boot).
- **PERF-02** : `distinctUntilChanged` sur le flux de lookup contact dans
  `ThreadViewModel` (la query ContentProvider ne se redéclenche plus sur chaque
  frappe clavier).
- **PERF-03** : `remember(conversation.lastMessageAt)` autour de `relativeRowLabel`
  dans `ConversationRow` (~100 allocations Calendar évitées par recomposition de
  liste).
- **PERF-04** : pré-calcul `daySeparatorLabels: List<String?>` dans `ThreadScreen`
  via `remember(state.messages, todayLabel, yesterdayLabel)` (était ~600 allocations
  Calendar par rendu initial sur thread de 200 msgs).
- **PERF-05** : `appLock.state` isolé du combine principal dans
  `ConversationRepositoryImpl.observeMessages` (un déverrouillage ne déclenche plus
  un rebuild attachments).
- **PERF-06** : `debounce(200 ms)` sur la query recherche dans `ConversationsViewModel`
  (une seule recomposition LazyColumn par stabilisation au lieu d'une par frappe).
- **PERF-07** : `ContactsReader` passe de `ConcurrentHashMap` non-borné à
  `LruCache(500)` + `LruCache(1000)` (anti leak mémoire progressif sur SMS spam
  / 2FA / livraisons).

*Qualité (15)*
- **QUAL-01 + QUAL-16** : `SAFETY_NET_DAYS`, `MS_PER_DAY`, `purgeCutoffMs(days, now)`
  centralisés dans `domain/purge/PurgePolicy.kt` (source unique de vérité — les
  duplications dans `ConversationRepositoryImpl` et `TelephonySyncWorker` sont
  retirées).
- **QUAL-02** : `flowOn(io)` avant `stateIn` dans `ConversationsViewModel`
  (`defaultAppManager.isDefault()` IPC Binder synchrone ne bloque plus le Main thread).
- **QUAL-03** : `defaultAppManager` rendu `private` dans `ConversationsViewModel` —
  la screen passe désormais par `buildChangeDefaultIntent()` (encapsulation ViewModel
  respectée).
- **QUAL-04** : `openOutputStream(uri)!!` remplacé par `?: error("...")` dans
  `BackupService` (diagnostic explicite si URI révoqué / disque plein).
- **QUAL-05** : `!!` redondant post smart-cast retiré dans `ContactsReader`.
- **QUAL-06** : `conv!!.draft` remplacé par `conv?.draft.orEmpty()` dans
  `ThreadViewModel` (invariante non-captée par le compilateur, robuste à un futur
  refactor de `seededDraft`).
- **QUAL-07** : `"PDF export failed"` (anglais hardcodé) remplacé par
  `R.string.snack_pdf_export_failed` FR+EN (regression i18n corrigée).
- **QUAL-10** : `SmsDeliverReceiver` passe par `ConversationRepository.findMessageById`
  au lieu d'accéder directement à `MessageDao` (pattern Repository respecté).
- **QUAL-11** : `VoicePlaybackController` reçoit `@MainDispatcher` injecté au lieu
  du `Dispatchers.Main.immediate` hardcodé (testabilité).
- **QUAL-13** : `splitGraphemeClusters` déplacé de `ui.components` vers
  `core/ext/StringExt.kt` (extension String utilitaire, ne doit pas vivre dans un
  module UI).
- **QUAL-14** : `SortMode.DATE` ne trie plus par `pinned` en premier (était
  indistinguable de `SortMode.PINNED_FIRST`).
- **QUAL-15** : KDoc drift `v1.3.11` → `v1.4.0 (F5 forward feature)` dans
  `ThreadViewModel` et `AppRoot`.
- **QUAL-17** : `@androidx.compose.runtime.Stable` sur les 5 `UiState` data classes
  (Compose recomposition skipping).
- **QUAL-18** : `escapeFtsQuery` extrait en fonction top-level pure dans
  `data/repository/EscapeFtsQuery.kt` + nouveau fichier de tests
  `EscapeFtsQueryTest.kt` (15 cas : empty, whitespace, FTS reserved chars, BiDi,
  zero-width, BOM, control chars, Unicode letters).

**Reportés v1.6.2+** (changement de format / migration / infrastructure) : SEC-04
(PBKDF2 salt 16→32 B casse `.smsbk`), SEC-12 (hash PendingIntent), SEC-14 (whitelist
MMSC opérateurs), PERF-09 (Baseline Profile setup), PERF-10 (FTS4→FTS5),
PERF-12 (WAL + page_size SQLCipher), QUAL-08/09/12 (FQN imports / Dispatchers.Default
injectable VoiceRecorder).

**Tests** : 14 tests pré-existants `IncomingReactionDecoderTest` + 15 nouveaux
`EscapeFtsQueryTest` (29 tests JUnit5 sur les 2 fichiers les plus sensibles) + suite
complète verte. **Lint** : aucune nouvelle erreur (baseline régénéré pour 4 erreurs
+ 177 warnings pré-existants).

### v1.6.0 — Durcissement issu de l'audit post-v1.5.0 (sécurité / perf / a11y)

Suite patch+minor de la v1.5.0. L'audit 3 axes lancé après la publication a fait remonter 2 constats HIGH et 6 MEDIUM/LOW ; cette version les livre tous.

**HIGH (clos)**

- **S1 — Garde anti-ReDoS sur `IncomingReactionDecoder`.** La regex Tapback
  `^Reacted\s+(.+?)\s+to\s+[«"](.+?)[»"]$` est non gourmande et accepte `DOT_MATCHES_ALL` ;
  un SMS multipartie malveillant comme `Reacted ❤️ to «aaa…` (sans guillemet fermant, 3 ko)
  forcerait sinon un retour arrière (backtracking) catastrophique. Nous rejetons désormais toute
  entrée `> MAX_DECODE_INPUT_LENGTH = 400` caractères avant que la regex ne la voie. Un vrai
  Tapback tient toujours dans un seul segment UCS-2 (≤ 70 caractères). Trois nouveaux tests JUnit5
  verrouillent la garde (corps surdimensionné rejeté rapidement, corps juste au-dessus du plafond
  rejeté, corps réaliste proche du plafond accepté).
- **U1 — Le dialogue d'emoji personnalisé respecte `MAX_REACTION_EMOJIS`.** Le raccourci
  « Autre emoji » passe par le clavier emoji du système, qui pouvait produire une chaîne d'un
  nombre arbitraire de clusters. Le site d'envoi dans `ThreadScreen` découpe désormais l'entrée
  via `splitGraphemeClusters().take(MAX_REACTION_EMOJIS).joinToString("")`, en préservant
  atomiquement les séquences famille ZWJ / teinte de peau / VS-16.

**MEDIUM / LOW (clos)**

- **Q1** — KDoc de `SendReactionUseCase` mis à jour : la ligne « Changed transitions stay
  network-silent » était un vestige de la v1.3.1 ; depuis la v1.4.1, le ViewModel envoie aussi
  Changed pour que le destinataire voie le nouvel emoji. Le KDoc reflète désormais la réalité.
- **Q2** — Branche morte `Modifier.then(Modifier)` dans `EmojiReactionPickerSheet`
  remplacée par `Modifier.alpha(0.38f)` (état désactivé de Material 3), de sorte que les emojis
  bloqués par le plafond de capacité sont visuellement atténués au lieu d'être seulement non
  cliquables.
- **Q3** — `when (result)` sur l'interface scellée `SetReactionResult` est désormais
  exhaustif, avec des branches `Removed` et `Noop` explicites. Une future variante du
  type scellé fera échouer la compilation ici au lieu d'être avalée en silence.
- **P2** — `atCapacity` dans le sélecteur est enveloppé dans `derivedStateOf`, de sorte que les
  22 emojis dont l'état de sélection n'a pas changé évitent une recomposition à chaque tap.
- **U2** — `EmojiReactionBadge` porte désormais `semantics { role = Role.Button ;
  contentDescription = "Réaction <emoji>" }` et `clickable(onClickLabel = "Retirer la
  réaction")`. TalkBack annonçait auparavant un « appuyez deux fois pour activer » muet, sans
  contexte ; il décrit maintenant à la fois l'état du badge et l'action de retrait.
- **S2** — `MessageDao.listAll()` (la lecture de l'instantané de sauvegarde) exclut les
  sentinelles Tapback (`body = '' AND attachments_count = 0 AND reaction_emoji IS NULL`). Ces
  lignes sont des artefacts internes du mécanisme anti-réimport ; les inclure dans une
  sauvegarde ferait apparaître des bulles vides à la restauration.

**Non modifié**

- L'index composite `(conversation_id, date)` sur `messages` a été signalé comme manquant,
  mais il est en réalité déjà présent depuis le schéma v2 (et l'index autonome `date` depuis
  la v4). Aucune migration requise.
- L'UX « abandon de la sélection multiple sur Autre emoji » est intentionnelle et documentée
  dans le code ; nous l'avons laissée en l'état pour la v1.6.0.

### v1.5.0 — Réactions multi-emoji + fidélité bidirectionnelle du Tapback

Montée de version SemVer mineure. Deux thèmes livrés ensemble :

1. **Réactions multi-emoji** (fonctionnalité de la v1.5.0) :
   - `EmojiReactionPickerSheet` réécrit en grille à sélection multiple. Un tap ajoute ou retire
     un emoji de la sélection courante (mis en évidence par un anneau `cs.primary`),
     « Envoyer la réaction » valide la chaîne concaténée. Plafond à `MAX_REACTION_EMOJIS = 3`
     pour garder le badge lisible.
   - `EmojiReactionBadge` passe d'un cercle de taille fixe à une pilule qui s'agrandit d'elle-même
     (hauteur min 28 dp, largeur min 28 dp, rectangle arrondi avec un coin de 14 dp = cercle
     visuel dans le cas d'un seul emoji ; s'élargit horizontalement pour 2-3 emojis).
   - L'utilitaire `splitGraphemeClusters()` gère les emojis à plusieurs points de code (familles
     ZWJ, teintes de peau, sélecteurs de variante), pour que l'état de présélection du sélecteur
     et le rendu du badge gardent les clusters atomiques.
   - Le sélecteur s'ouvre avec la réaction existante du message présélectionnée, ce qui permet
     des modifications additives sans retaper toute la combinaison.
   - Le format wire (Tapback ou emoji seul selon `SendingSettings.reactionEmojiOnly`)
     transporte la chaîne concaténée de manière transparente — le décodeur côté réception
     accepte des séquences d'emojis arbitraires sans modification du code.

2. **Fidélité bidirectionnelle du Tapback** (reprise du backlog de la v1.4.1) :
   - **Envoi multi-réaction** : la map de dédoublonnage de `ThreadViewModel.dispatchReactionSms`
     passe de `Map<Long, Long>` (messageId → timestamp) à `Map<Long,
     ReactionDispatch>` (messageId → emoji + timestamp). Le dédoublonnage de 60 s ne se
     déclenche plus que lorsque le MÊME emoji est envoyé deux fois ; les changements légitimes
     (❤️ → 👍) contournent la fenêtre pour que le correspondant voie la mise à jour.
   - **Envoi au changement** : `setReaction` envoie un SMS sortant à la fois sur les transitions
     `First` (null → emoji) ET `Changed` (A → B) (seulement First depuis la v1.3.1).
     `Removed` reste purement local — le Tapback n'a pas de format wire « retirer la réaction ».
   - **Masquer la bulle Tapback sortante de l'auteur de la réaction** : `upsertOutgoingSms`
     accepte un nouveau paramètre `localMirrorBody: String?` ; `SendReactionUseCase` passe `""`
     pour que le SMS Tapback parte toujours sur le réseau + arrive dans `content://sms`
     (obligation légale en tant qu'app SMS par défaut), mais le fil de l'auteur de la réaction
     n'affiche plus une bulle texte redondante
     `"Reacted ❤️ to «…»"`. La ligne Room vide est filtrée par
     `MessageDao.observeForConversation` (`body='' AND attach=0 AND reaction IS NULL`
     quelle que soit la direction).
   - `touchConversation` est sauté quand `mirrorBody.isEmpty()`, pour que la liste des
     conversations n'affiche pas un aperçu vide / un mauvais ordre de tri dû à la ligne sentinelle.

**Report du backlog de la v1.4.0 / v1.4.1** (déjà dans la v1.4.0 mais conservé ici pour
traçabilité) : réception MMS débloquée sur Android 10+, extraction des pièces jointes
multi-MIME, découplage caption ↔ previewLabel, KeepAliveService en opt-in, AttachmentPicker
`*/*`, forme d'onde de la bulle vocale, sélecteur de fichiers ouvert à tous les types MIME.

**Correctifs d'audit défensif livrés avec cette version**
- **SEC-01** : `upsertReactionSentinel` dépose une ligne Room « pilule empoisonnée » portant le
  même `telephonyUri` que la ligne de la boîte de réception système après un fold Tapback, pour
  que `TelephonySyncManager` ne puisse pas réimporter le corps comme une bulle texte fantôme. La
  sentinelle est filtrée au niveau du DAO (body='' + 0 pièce jointe + 0 réaction).
- **SEC-02** : `stripInvisibleChars` élargi pour retirer aussi U+00AD (trait d'union
  conditionnel), U+034F (combining grapheme joiner), U+061C, U+180E, U+2060–2064, U+FFFC,
  U+FFFD. Ferme une voie d'attaque où un SMS forgé comme `­❤` pouvait passer
  l'heuristique « emoji pur » et épingler de force un badge de réaction.
- **SEC-03** : la route share-target de `AppRoot` vide désormais `IncomingShareHolder.pending`
  quand l'utilisateur est déjà dans un Thread ou un Compose — empêche que la charge utile en
  attente soit glissée en silence dans une autre conversation plus tard.
- **SEC-04** : adresse (numéro de téléphone) retirée d'une ligne de log `Timber.i`, par
  cohérence avec la politique du projet qui exclut les données personnelles des logs.
- **KQ1** : la répétition de noms qualifiés (FQN) dans `ConversationMirror.applyIncomingReaction`
  est remplacée par un alias d'import `Kind` en tête de fichier.
- **KQ2** : `AppRoot` passe de `collectAsState()` à
  `collectAsStateWithLifecycle()` pour le flux de partage entrant.
- **KQ3** : KDoc de `TAPBACK_WITH_PREVIEW_REGEX` corrigé pour décrire les guillemets ASCII de
  repli réellement utilisés, `"..."` (il indiquait à tort `<<>>`).
- **KQ4** : visibilité de `EMOJI_ONLY_REACT_WINDOW_MS` restreinte de `public` à
  `internal`.

Même certificat SHA-256 `b09a9511…687d`. Aucune nouvelle permission. Aucun changement de schéma.
17 tests JUnit au vert (3 nouveaux pour le découpeur de clusters de graphèmes, 14 existants pour
le décodeur de réactions).

### v1.4.0 — Pack ergonomie + forme d'onde de la bulle vocale

Montée de version SemVer mineure portée par 5 fonctionnalités visibles par l'utilisateur. Aucune
nouvelle permission, aucun changement de schéma, aucun changement de clé de signature. 6 correctifs
défensifs appliqués avant le tag, à la suite d'un audit 3 axes mené en parallèle (sécurité /
performance / cohérence de l'UI).

**F1 — Rétractation du clavier après l'envoi** (`ThreadScreen.kt`)
- Après chaque envoi (texte, vocal ou MMS média), le clavier virtuel est masqué via
  `LocalSoftwareKeyboardController.hide()` et le focus IME est retiré via
  `LocalFocusManager.clearFocus()`. L'expéditeur voit ainsi le message qu'il vient d'envoyer
  en bas du fil, sans replier le clavier à la main.

**F2 — Sélecteur de contact à validation instantanée** (`ComposeViewModel.kt`, `ComposeScreen.kt`)
- Le nouveau `pickRecipient(rawNumber: String): Boolean` vérifie si `recipients.isEmpty()`
  AVANT l'ajout ; si c'est le cas, il ajoute + appelle aussitôt `createConversation`
  (flux à destinataire unique, ~99 % des cas). Sinon (l'utilisateur compose un groupe), il se
  contente d'ajouter — le bouton explicite « Continuer » reste l'étape de validation.
  Le texte d'aide de la ligne de saisie libre s'adapte via une nouvelle paire de chaînes
  `compose_use_this_number` / `compose_add_to_group` pour refléter l'état courant.

**F3 — Copier un message** (`BubbleMenuTrigger.kt`, `MessageBubble.kt`)
- Le nouveau paramètre `onCopy: (() -> Unit)?` de `BubbleMenuTrigger` fait apparaître un
  `DropdownMenuItem` Material 3 « Copier » avec `Icons.Outlined.ContentCopy`.
  `MessageBubble` enveloppe la Box de son corps dans `combinedClickable(onClick, onLongClick)`
  pour qu'un appui long déclenche la même action de copie sans passer par le menu à 3 points
  (convention iMessage). `MediaAttachmentBubble` n'expose la copie que lorsqu'une légende est
  présente (les corps de substitution sont filtrés au site d'appel).
  `ThreadScreen` injecte `LocalClipboardManager` et passe par un unique utilitaire
  `copyMessageBody(msg)` qui émet un retour haptique `LongPress` + un snackbar « Message
  copié », pour une confirmation tactile + visuelle.

**F4 — Actions sur les numéros de téléphone** (`MessageTextWithLinks.kt`, `PhoneActionsDialog.kt`)
- `buildLinkifiedText` est étendu pour appliquer aussi `Patterns.PHONE` au corps, en plus de
  `Patterns.WEB_URL`. Les correspondances de téléphone sont filtrées par une plage stricte du
  nombre de chiffres `[PHONE_DIGITS_MIN=7, PHONE_DIGITS_MAX=15]` pour rejeter les codes promo
  (trop courts) et les IBAN / numéros de carte bancaire (trop longs). En cas de chevauchement,
  l'URL l'emporte sur le téléphone (priorité `0 < 1` dans `compareBy`), si bien qu'une URL
  contenant des chiffres n'est jamais fragmentée.
- Les correspondances de téléphone émettent `LinkAnnotation.Clickable` (PAS `Url`), si bien que
  le tap passe par un écouteur personnalisé au lieu d'un Intent implicite. L'écouteur
  affiche `PhoneActionsDialog` avec 3 actions :
  - **Appeler** → `Intent.ACTION_DIAL` avec l'URI `tel:$number`. Volontairement PAS
    `ACTION_CALL`, qui exigerait la permission d'exécution `CALL_PHONE`.
  - **Copier** → envoi dans le presse-papiers + snackbar.
  - **Ajouter aux contacts** → `ContactsContract.Intents.Insert.ACTION` pré-rempli
    avec le numéro. Aucune permission requise.
- Chaque `startActivity` est enveloppé dans `runCatching` + un snackbar de repli, pour qu'une
  ROM dépouillée sans composeur / app de contacts par défaut ne puisse pas faire planter le fil.
- La sentinelle WAP « any-charset » (MIBenum 0 → `*` littéral) était déjà gérée
  par `resolveCharset` en v1.3.10 — même repli vers UTF-8.

**F5 — Transférer un message** (`ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`ThreadViewModel.stageForward`)
- Une nouvelle `Modal Bottom Sheet` liste les conversations récentes (avec recherche) + un CTA
  « Nouveau destinataire » en haut. La conversation source est masquée de la liste
  (impossible de se transférer un message à soi-même) via une nouvelle API
  `ForwardPickerViewModel.setExcludedConversation(id)`.
- La charge utile du transfert réutilise la tuyauterie share-target existante
  (`IncomingShareHolder.Pending`) :
  - texte → `Pending.text` → brouillon du consommateur
  - première pièce jointe → `Pending.uris[0]` (enveloppée via FileProvider pour les fichiers
    locaux — voir SEC-01 ci-dessous), `Pending.mimeType` pilote le choix de
    l'`AttachmentKind`
- Le ThreadViewModel de destination récupère la charge utile par le chemin existant
  `consumeIncomingShareIfAny()`. Aucun nouveau couplage de ViewModel à ViewModel.

**Forme d'onde de la bulle vocale** (`AudioMessageBubble.kt`)
- Au repos (`!isPlaying`), la piste inactive standard du slider Material 3 est
  recouverte par un `Canvas` qui dessine `WAVE_BAR_COUNT = 28` barres verticales aux
  hauteurs pseudo-aléatoires amorcées par `audio.id`. Un même clip audio produit toujours
  la même silhouette d'une recomposition à l'autre (`Random(seed)` déterministe).
  Pendant la lecture, le slider prend le relais pour une animation de progression nette.
- Les bulles vocales entrantes portent désormais une bordure de 1 dp à
  `lerp(bgColor, Color.Black, 0.18f)` (18 % plus sombre que le remplissage) pour un meilleur
  contraste avec la surface du fil, symétrique du design à bordure seule des bulles sortantes.

**Correctifs d'audit défensif livrés avec cette version**
- **SEC-01** (`ThreadViewModel.stageForward`) : les chemins de fichiers locaux de la charge
  utile du transfert sont enveloppés via `FileProvider.getUriForFile(...)` au lieu de
  `Uri.fromFile(...)`. Ce dernier était techniquement sûr aujourd'hui (consommateur
  `openInputStream` intra-processus, aucun `Intent` qui franchit une frontière), mais le motif
  `file://` est une mine connue de `FileUriExposedException`, et le reste de l'app
  utilise déjà `FileProvider` partout — alignement.
- **P1** (`ForwardPickerViewModel`) : la liste filtrée des conversations est désormais
  mise en cache dans `UiState.filtered` et recalculée uniquement sur `setQuery`,
  `setExcludedConversation` ou les émissions de `observeAll`. Le composable lit
  `state.filtered` au lieu d'appeler le filtre pendant la recomposition.
  Élimine un jank en O(n·m) sur les appareils Android Go lors de la saisie dans le champ de
  recherche du sélecteur avec ~150 conversations chargées.
- **P3** (`MessageTextWithLinksTest.kt`) : 3 nouveaux tests JUnit 5 figent
  l'utilitaire `countDigits` et la plage `PHONE_DIGITS_MIN/MAX`, pour qu'un futur élargissement
  ne puisse pas laisser passer en silence des codes promo ou des IBAN.
- **U2** (`ForwardMessageSheet`) : le `ListItem` « Nouveau destinataire » porte
  `semantics { role = Role.Button }`, si bien que TalkBack annonce « Bouton » en plus
  du texte du titre.
- **U3** (`ForwardMessageSheet`) : la lambda `dismissAndReset` de la feuille vide
  `viewModel.setQuery("")` avant de faire remonter la fermeture — évite qu'une requête
  périmée réapparaisse quand l'utilisateur rouvre la feuille.
- **Hook retiré** : le bouton `Annuler` de `PhoneActionsDialog` était rendu orphelin
  par le slot `dismissButton` de Material 3 (rendu sous la pile des 3 actions).
  Retiré — le tap à l'extérieur et le retour arrière invoquent déjà `onDismissRequest`.

Même certificat SHA-256 `b09a9511…687d`. ~12 fichiers modifiés, 2 nouveaux fichiers
(`PhoneActionsDialog.kt`, `ForwardMessageSheet.kt`, `ForwardPickerViewModel.kt`,
`OemKeepAliveOnboarding.kt` relevait de la v1.3.10 — aucune nouvelle entrée de manifeste).

### v1.3.1 (this release) — Fonction réaction-par-SMS

La v1.3.1 ajoute une capacité optionnelle : lorsque l'utilisateur pose une réaction emoji sur un
message **reçu**, un SMS contenant uniquement cet emoji est envoyé à l'**unique expéditeur** du
message (jamais aux autres participants d'un groupe, jamais lorsque le message est sortant). La
préférence est **activée par défaut** ; une boîte de dialogue de confirmation affichée une seule
fois (avec une case « ne plus demander ») protège l'utilisateur des mauvaises surprises de
facturation silencieuse au premier envoi.

Audit selon trois axes ; deux constats CRITIQUES détectés et corrigés avant le tag :

- **F1 (CRITIQUE)** : `setReaction` pouvait déclencher un SMS pour des messages sortants
  (c.-à-d. réagir à son propre message envoyé). Désormais bloqué dans le cas d'usage ET
  masqué du menu de l'interface (`onReact` vaut `null` sur les bulles sortantes).
- **F2 (CRITIQUE)** : dans une conversation de groupe, le SMS de réaction aurait été
  diffusé à *chaque* participant. Il cible désormais *uniquement* l'expéditeur du
  message auquel on réagit (`message.address`).
- **F3 (ÉLEVÉ)** : la signature de l'utilisateur aurait été ajoutée au corps de la réaction
  (`❤️\n--\nPat` → SMS en plusieurs parties facturé x2/x3 + pollution du fil de l'expéditeur).
  `SendSmsUseCase` a reçu un paramètre `appendSignature: Boolean = true` ;
  `SendReactionUseCase` passe `false`.
- **F4 (ÉLEVÉ)** : une concurrence entre la boîte de confirmation et un appui ultérieur sur une
  réaction pouvait envoyer un emoji périmé. `Event.RequestReactionConfirm` transporte
  désormais le `messageId` et `ThreadViewModel.confirmReactionSend` revérifie le
  `reactionEmoji` courant avant l'envoi.
- **P1 (ÉLEVÉ)** : un échec de `DataStore.first()` dans le bloc post-First de
  `ThreadViewModel.setReaction` aurait fait planter la portée du ViewModel. Enveloppé dans
  `runCatching` avec un avertissement Timber.
- **P5 (MOYEN)** : libellé de destinataire vide dans la boîte de confirmation si la
  conversation n'était pas encore hydratée. Repli sur une chaîne localisée « ce contact ».

Les 24 emojis de sélection rapide sont des points de code standard codés en dur (aucun ZWJ seul,
aucun contrôle BiDi). Le chemin « + Autre emoji » exécute `EmojiCustomDialog
.isLikelyEmoji()`, qui rejette `<>&"'\`, les forçages BiDi, les BOM et les faux emojis composés
uniquement de ZWJ (voir l'audit v1.3.0 Q4/F2).

Un audit de seconde passe UI/branchements a détecté 6 problèmes supplémentaires, tous corrigés
avant le tag :

- **X1 (CRITIQUE)** : refus des expéditeurs alphanumériques (`Free`, `INFO`, banque,
  livraison, 2FA) et des numéros courts de moins de 4 chiffres. Sans ce garde, réagir à
  un SMS de la banque aurait tenté d'envoyer `❤️` à une adresse non composable ou à un
  numéro court surtaxé (1,50 €+/SMS en France). `SendReactionUseCase
  .isDialablePhoneNumber()` impose `^[+0-9 .()-]+$` + ≥4 chiffres + aucune lettre
  ASCII.
- **X2 (ÉLEVÉ)** : fenêtre de déduplication en RAM (60 s) sur `messageId` pour empêcher le
  spam de facturation lorsque l'utilisateur bascule rapidement `null → ❤️ → null → ❤️`. Chaque
  cycle est légitimement un `SetReactionResult.First`, mais seul le premier de la fenêtre
  envoie un SMS.
- **X3 (ÉLEVÉ)** : `reactionConfirmDismissed = true` n'est désormais persisté **qu'après**
  un `DispatchOutcome.Sent` réussi. Auparavant, un envoi en échec permanent (NotDefaultSmsApp,
  liste de blocage) positionnait quand même la préférence en silence, privant l'utilisateur de
  toute confirmation future alors qu'aucun SMS n'avait jamais été envoyé.
- **X4 (MOYEN)** : la boîte de confirmation donne automatiquement le focus au bouton
  « Annuler » (motif utilisé par toutes les boîtes destructives : DestructiveConfirmDialog,
  PurgeNowConfirmDialog, effacement total des données).
- **X5 (MOYEN)** : un second événement `RequestReactionConfirm` arrivant alors que la première
  boîte est encore ouverte est désormais ignoré en silence (pas d'écrasement). Le badge de
  réaction local du 2e appui reste affiché ; seul l'envoi du SMS pour ce second appui est
  sauté — cohérent avec « une confirmation = un envoi ».
- **X6 (MOYEN)** : la ligne « Ne plus demander » utilise `Modifier.toggleable(role =
  Role.Checkbox)` au lieu de `Row.clickable` + `Checkbox.onCheckedChange` afin d'exposer
  un seul nœud a11y à TalkBack / Switch Access.

Les tests `AppSettingsTest.v1_3_1_reaction_send_defaults_are_explicit` +
`SetReactionResultTest` verrouillent les nouvelles valeurs par défaut et la sémantique de la
classe scellée, de sorte que tout refactor futur qui retirerait `messageId` de `First` ou
passerait la bascule par défaut à `false` fasse échouer la CI.

### v1.3.2 (this release) — Format Tapback Apple/Google + URL cliquables

Deux raffinements UX qui s'appuient sur la v1.3.1 :

- **Format Tapback** : le corps du SMS de réaction est désormais
  `"Reacted <emoji> to «<preview>»"` (p. ex. `"Reacted ❤️ to «See you tomorrow?»"`).
  Cet habillage ASCII **exact** est ce qu'iMessage (iPhone) et les versions récentes de Google
  Messages analysent pour afficher une **bulle de réaction native rattachée** au message
  d'origine — au lieu d'une bulle de texte `❤️` isolée. Les autres applications affichent le
  texte brut, qui reste clair dans son contexte.
  - `SendReactionUseCase.buildTapbackBody` est extraite sous forme de fonction `internal` de
    premier niveau pour être testée directement en JUnit sans instancier le cas d'usage.
  - Assainissement du corps : les caractères de contrôle (CR/LF/NUL/BEL, U+0000–U+001F,
    U+007F–U+009F) sont remplacés par une seule espace, puis les suites d'espaces sont
    fusionnées. Empêche un SMS entrant malveillant d'injecter des sauts de ligne qui
    découperaient notre tapback sortant en plusieurs PDU ou simuleraient un préfixe
    d'expéditeur différent dans l'analyseur du destinataire.
  - Aperçu tronqué à 50 caractères + « … » pour tenir confortablement dans un seul segment SMS
    UCS-2 (plafond de 70 caractères) avec l'habillage + l'emoji ; évite la mauvaise surprise
    de facturation silencieuse d'un SMS en 2 segments.
  - Corps vide (MMS image seule) → repli `"Reacted <emoji>"` (toujours analysé par
    Apple/Google).
- **URL cliquables dans les bulles de message** : le Composable `MessageTextWithLinks`
  utilise `LinkAnnotation.Url` de Compose 1.7+ avec `Patterns.WEB_URL` (l'expression régulière
  éprouvée d'Android, utilisée par toutes les applications système). Les URL détectées sont
  soulignées + en graisse moyenne, héritent de la couleur du texte parent (lisible dans les
  thèmes clair et sombre) et s'ouvrent via le `UriHandler` du système →
  `Intent.ACTION_VIEW`.
  - Normalisation du schéma : les domaines nus (`google.com`) sont transformés en
    `https://google.com` avant l'ouverture. Les URL `http(s)://` existantes sont conservées
    telles quelles. **Aucun autre schéma n'est jamais généré** (pas de `tel:`,
    `file:`, `content:`, etc.) — élimine toute la classe d'exploits « cliquer sur une URL,
    ouvrir un intent bizarre ».
  - Retrait de la ponctuation finale (`.`, `,`, `;`, `:`, `!`, `?`, `)`, `]`, `}`,
    `»`, `"`, `'`) pour que `Hello google.com.` ouvre `https://google.com` et laisse le
    point final hors du lien.
  - `remember(text)` met en cache l'`AnnotatedString` pour que l'expression régulière WEB_URL
    ne soit pas réexécutée à chaque recomposition de la liste de bulles.

Le nouveau test `SendReactionUseCaseTest` verrouille la formulation Tapback Apple/Google
exacte, l'assainissement des caractères de contrôle, la limite de troncature et le repli du
corps vide. Tout refactor futur qui changerait la formulation `"Reacted X to «Y»"` ferait
échouer la CI immédiatement.

Un audit de seconde passe a trouvé 7 problèmes, tous corrigés avant le tag :

- **Y1 (ÉLEVÉ)** : extension de l'expression régulière d'assainissement du corps pour retirer
  U+2028/U+2029 (séparateurs de ligne/paragraphe), U+200E/U+200F + U+202A–U+202E +
  U+2066–U+2069 (contrôles Bidi — un RLO `‮` dans un SMS malveillant reçu inverserait
  visuellement le Tapback affiché sur l'écran du destinataire et casserait l'analyseur
  d'iMessage), et U+FEFF (BOM).
- **Y2 (ÉLEVÉ)** : `String.take(n)` opère sur des unités de code UTF-16 et peut couper au
  milieu d'une paire de substitution (emoji sur 4 octets) ou d'un groupe ZWJ (emoji famille).
  Une nouvelle fonction `safeTake()` recule jusqu'à une frontière propre pour que le SMS
  sortant ne porte jamais de substitut orphelin / glyphe corrompu.
- **Y3 (MOYEN)** : le budget de l'aperçu est désormais calculé dynamiquement à partir de
  `SMS_UCS2_SEGMENT_CAP (70) - TAPBACK_WRAP_LENGTH (15) - emoji.length`,
  ce qui garantit que tout le tapback tient dans **un seul** segment SMS UCS-2, même
  avec un emoji drapeau de 4 unités UTF-16 ou un emoji famille de 11 unités UTF-16.
- **Y4 (MOYEN)** : liste blanche d'URL via `toSafeHttpsTargetOrNull()`. Seuls
  `http://` et `https://` deviennent des `LinkAnnotation.Url`. Tout autre schéma
  (`javascript:`, `data:`, `intent:`, `file:`, `content:`, `tel:`, schémas d'applications
  personnalisés) est rendu en texte brut — le `UriHandler` du système n'est jamais sollicité
  pour résoudre un intent hostile.
- **Y5 (MOYEN)** : `buildLinkifiedText` court-circuite le traitement lorsque l'entrée dépasse
  `LINKIFY_INPUT_CAP = 2000` caractères. Anti-ReDoS sur l'expression régulière
  `Patterns.WEB_URL` (l'automate NFA de java.util.regex peut se dégrader sur des entrées
  pathologiques).
- **Y6 (MOYEN)** : `MessageBubble` saute le rendu avec liens sur les messages dont le statut
  est `FAILED` — l'appui de nouvelle tentative du `clickable` extérieur de la bulle reste sans
  ambiguïté, sans conflit de geste avec le lien.
- **Y7 (MOYEN)** : nouveau `MessageTextWithLinksTest` (11 cas) qui verrouille le comportement
  de la liste blanche de schémas (http/https acceptés sans tenir compte de la casse, tout autre
  schéma rejeté, domaine nu normalisé en `https://`, cas limite du deux-points dans un chemin
  correctement classé comme non-schéma). Les cas dépendant de Patterns sont reportés à la
  v1.3.3 avec Robolectric.

Tests ajoutés en v1.3.2 : `SendReactionUseCaseTest` (10 cas, dont les nouvelles gardes
anti-régression Y1/Y2/Y3), `MessageTextWithLinksTest` (11 cas). Tous passent.

### v1.3.3 (this release) — Corrections de bugs critiques + surface IPC de cible de partage

Déclenchée par des régressions signalées par des utilisateurs après la v1.3.2. Sept correctifs
(5 critiques, 2 élevés), plus un audit global qui a détecté 2 problèmes supplémentaires
corrigés avant le tag.

Correctifs visibles par l'utilisateur :

- **Bug #5 (CRITIQUE)** : conversation vide en double à la réception d'un SMS.
  `ConversationMirror.ensureConversation` se replie désormais sur une **correspondance des
  8 derniers chiffres** pour les conversations individuelles lorsque la correspondance CSV
  exacte échoue. Empêche une conversation importée du système au format international
  (`+33612345678`) d'engendrer un doublon lorsqu'un SMS reçu par broadcast arrive au format
  national (`0612345678`).
- **Bug #6 (CRITIQUE)** : les notifications continuaient de s'empiler. Le notificateur publie
  désormais avec un **tag** (`com.filestech.sms.conv.<id>`) ; `markRead` appelle
  `cancelAllForConversation`, qui parcourt `activeNotifications` filtrées par tag. Efficace
  même lorsque l'application est ouverte directement (il n'est plus nécessaire d'appuyer sur
  la notification).
- **Bug #1 (ÉLEVÉ)** : l'envoi de messages vocaux échouait chez SFR. `BITRATE_BPS` 24 → 16
  kbps + `MAX_SIZE_BYTES` 450 → 280 Ko. 120 s tiennent désormais dans un seul segment MMS
  chez tous les opérateurs français.
- **Bug #4 (MOYEN)** : SMS Tech absente du sélecteur de partage du système. Ajout d'un
  `<intent-filter ACTION_SEND>` à MainActivity avec une **liste blanche MIME stricte**
  (`image/*`, `video/*`, `audio/*`, `text/plain`,
  `text/x-vcard`, `text/vcard`, `application/pdf`). Nouveau singleton `IncomingShareHolder` +
  analyse dans MainActivity. `ThreadViewModel` consomme le holder
  après l'hydratation de `state.conversation` (correctif d'audit Z3).
- **Bug #2 (MOYEN)** : images / fichiers reçus impossibles à ouvrir. Nouveau Composable
  `MediaAttachmentBubble` avec appui → `Intent.ACTION_VIEW` via une résolution d'URI tenant
  compte du schéma (correctif d'audit Z1 : détecte `content://mms/part/N`
  pour les MMS importés du système, par opposition aux chemins de fichiers des pièces jointes
  en cache de l'application — un `File(localUri)` appliqué partout aurait planté sur les MMS
  hérités).
- **Fonctionnalité #3** : aperçu en vignette de l'image dans la boîte de confirmation de pièce
  jointe (Coil, avec la même gestion d'URI tenant compte du schéma).
- **UI #7** : `senderLabel` (« Vous » / nom du contact) en gras au-dessus de la première
  bulle de chaque salve ; espacement vertical 3 dp → 8 dp aux frontières.
  Snackbar personnalisée en `inverseSurface` (selon la consigne de l'utilisateur : le rouge est
  réservé aux seules actions destructives).

Audit Z (post-correctif) — 7 constats, tous corrigés avant le tag :

- **Z1 (CRITIQUE)** : `MediaAttachmentBubble` ouvrait chaque pièce jointe
  avec `File(localUri)` quel que soit le schéma. Les pièces jointes `content://mms/part/N`
  (tous les MMS importés du système) auraient échoué en silence. Corrigé par l'assistant
  `toShareableUri(context)`, qui aiguille selon le schéma.
- **Z3 (ÉLEVÉ)** : `consumeIncomingShareIfAny` entrait en concurrence avec le flux
  d'hydratation de la conversation ; `onAttachmentPicked` rendait la main prématurément si
  `state.conversation == null` alors que le holder était déjà consommé — perdant ainsi le
  partage. Corrigé en suspendant sur `_state.first { it.conversation
  != null }` avec un délai d'expiration de 5 s.
- **Z4 (ÉLEVÉ)** : `setGroup(...)` sans `groupSummary` publié crée des en-têtes de groupe
  fantômes sur OneUI/MIUI que l'annulation notification par notification n'efface pas.
  Passage à un notify/cancel **par tag** (`nm.notify(tag, id,
  notif)` / `nm.cancel(tag, id)`).
- **Z5 (MOYEN)** : la couleur de `senderLabel` sur l'AudioMessageBubble sortante utilisait
  `cs.onSurfaceVariant` sur un fond `cs.primary` = contraste d'environ 2:1 (échec WCAG
  AA). Passage à `cs.onPrimary` pour les bulles sortantes.
- **Z6 (MOYEN)** : liste blanche MIME de l'intent-filter resserrée (retrait des fourre-tout
  `text/*` et `application/*`).

Audit global — 2 constats élevés détectés :

- **G1 (ÉLEVÉ, vie privée)** : après `purgeOlderThan`, les conversations dont tous les
  messages avaient été supprimés conservaient leur `last_message_preview` d'origine en
  clair sur l'écran de liste. Une nouvelle requête DAO `refreshAllConversationPreviewsAfterPurge`
  recalcule l'aperçu + `last_message_at` à partir des lignes subsistantes. Appelée à la fois
  par `purgeHistoryNow` (manuelle) et par `TelephonySyncWorker` (automatique, mensuelle).
- **G2 (ÉLEVÉ, UX)** : un `IncomingShareHolder` laissé publié par un partage abandonné pouvait
  joindre le fichier à la mauvaise conversation lorsque l'utilisateur ouvrait ensuite un fil
  en appuyant sur une notification.
  `IncomingShareHolder.Pending` transporte désormais un horodatage `postedAt` ;
  `consume()` renvoie `null` au-delà de `PENDING_TTL_MS = 60 s`. MainActivity
  vide aussi le holder (`clear()`) sur tout intent autre que SEND (lanceur, lien profond,
  ouverture par notification).

Les autres constats d'audit (G3–G10) sont reportés à la v1.3.4 (nettoyage du code mort,
synchronisation de la documentation, finitions des réglages — non bloquants).

### v1.3.4 (this release) — Pièces jointes multiples dans la zone de rédaction

Refonte de l'UX de préparation des pièces jointes à la suite des retours d'un utilisateur
(« l'envoi immédiat n'est pas pratique, je veux empiler plusieurs images / fichiers dans un
seul message »). Remplace la boîte de confirmation modale (v1.2.1) par une barre de
préparation intégrée à la zone de rédaction. Un seul envoi MMS multipart pour tout le lot +
corps de texte facultatif.

Architecture :

- `UiState.pendingAttachment: PendingAttachment?` → `pendingAttachments:
  List<PendingAttachment>` ; nettoyage de tous les fichiers préparés dans `onCleared`.
- `onAttachmentPicked` **ajoute** à la liste avec un contrôle de plafond en direct
  (`sum(file sizes) + draft.length > CARRIER_PAYLOAD_CAP_BYTES` =
  280 Ko) — refuse le nouvel ajout + snack + supprime la copie temporaire en cache.
- `removePendingAttachment(fileAbsolutePath: String)` retire par identifiant de chemin stable
  (pas par index, anti-concurrence) + supprime le fichier en cache.
- `clearAllPendingAttachments()` vide la liste + supprime tout (utilisé par de futures routes
  si besoin).
- `dispatchPendingAttachments()` est le nouveau chemin MMS multipart : un seul appel
  `SendMediaMmsUseCase.invoke(recipients, payloads, textBody)`.
- Aiguillage de `send()` : si `pendingAttachments.isNotEmpty()` →
  `dispatchPendingAttachments` ; sinon → SMS texte seul via `doSend`.
- `AlertDialog(pendingAttachment)` modal retiré de `ThreadScreen`.
- Nouveau Composable `PendingAttachmentsBar` : `LazyRow` de puces de 72 dp,
  vignette Coil pour les images / icône pour vidéo/fichier, petite croix rouge
  (cercle de 16 dp + fond `cs.error` + croix `cs.onError`) en haut à droite
  pour retirer (couleur destructive réservée selon la règle utilisateur de la v1.3.3).
- `ComposerBar` / `ComposingRow` reçoivent un paramètre `hasPendingAttachments: Boolean`
  qui transforme le bouton micro en bouton Envoyer lorsque l'utilisateur a
  préparé des pièces jointes sans encore de texte (correctif d'audit M1 — sans cela, la
  fonction était inutilisable dans les cas « envoyer juste une photo »).

Audit M (post-correctif) — 4 constats, tous corrigés avant le tag :

- **M1 (ÉLEVÉ)** : bouton Envoyer inaccessible lorsque le brouillon est vide + pièces jointes
  préparées. Corrigé en propageant l'indicateur `hasPendingAttachments`.
- **M2 (MOYEN)** : une concurrence entre l'envoi (1-5 s) et un ajout simultané de pièce jointe
  effaçait l'ajout tardif. Passage à un motif instantané/différence
  (`val dispatched = pending` puis `filterNot { it in dispatched }`).
- **M3 (MOYEN)** : le contrôle de plafond dans `onAttachmentPicked` ne couvrait pas
  le cas où l'utilisateur ajoutait du texte *après* la préparation — un contenu de plus de
  280 Ko pouvait être soumis au MMSC. Ajout d'un nouveau contrôle à l'entrée de
  `dispatchPendingAttachments`.
- **M4 (MOYEN)** : la clé de `LazyRow` dérivée de `absolutePath.hashCode().toLong()`
  risque un plantage par collision d'Int dans des cas limites, et `onRemove(index: Int)` était
  vulnérable au décalage d'index en cas de concurrence ajout/retrait. Passage à un identifiant
  `String` stable (= chemin absolu) + retrait par identifiant.

### v1.3.10 — Réception MMS débloquée sur les ROM constructeur Android 10+

Des tests sur plusieurs appareils — Samsung Galaxy S9 (Android 10 One UI), Samsung S24 FE,
Redmi 9A (MIUI 12 Go) et Xiaomi Poco F5 (HyperOS 2024) — ont mis au jour trois échecs
silencieux indépendants dans la chaîne de réception MMS. Chacun relevait du mode d'échec
« aucun journal, aucun plantage, aucune bulle, aucune notification » — et les correctifs se
superposent :

**Correctif 1 — Liste noire des API cachées sur Android 10+** (renommage du paquet PDU) :
- Le codec PDU MMS d'AOSP embarqué (`PduParser`, `PduComposer`, `PduBody`,
  `PduPart`, `EncodedStringValue`, `PduHeaders`, `CharacterSets`,
  `NotificationInd`, `SendReq`, `RetrieveConf`, `MultimediaMessagePdu`,
  `GenericPdu`, `PduContentTypes`, `MmsException`, `InvalidHeaderValueException`)
  se trouvait auparavant sous `com.google.android.mms.pdu.*`. Android 10+ applique une
  liste noire d'API cachées sur cet espace de noms — le ClassLoader système préfère la classe
  (cachée) fournie par le framework à notre copie embarquée et en refuse l'accès à l'édition
  de liens (« Accessing hidden method PduParser.<init>([B)V (blacklist, linking,
  denied) », observé dans le logcat du S9 sous Android 10 le 2026-05-18).
- L'ensemble du codec a été renommé en `com.filestech.sms.pdu.*` (15 fichiers .java déplacés,
  5 fichiers .kt mis à jour, règle ProGuard `-keep` mise à jour). Le ClassLoader choisit
  désormais toujours notre copie embarquée. C'est la même méthode que celle de Signal / Google
  Messages.

**Correctif 2 — Plantage silencieux de l'injection Hilt sur les BroadcastReceivers d'Android 10** :
- `@AndroidEntryPoint` sur `MmsWapPushReceiver` + `MmsDownloadedReceiver` déclenchait
  un plantage silencieux dans l'enveloppe générée par Hilt AVANT l'appel de `onReceive`
  lorsque Android distribuait WAP_PUSH au démarrage à froid (l'état normal d'une application
  SMS tuée en arrière-plan par les constructeurs agressifs). Résultat : chaque MMS entrant
  était abandonné sans une seule ligne de journal.
- Les deux récepteurs exposent désormais une interface
  `@EntryPoint @InstallIn(SingletonComponent::class)` et résolvent `MmsDownloader`,
  `ConversationMirror`, `MessageDao`, `IncomingMessageNotifier` et le
  `@ApplicationScope CoroutineScope` à la demande dans `onReceive` via
  `EntryPointAccessors.fromApplication(...)`. L'`Application` est garantie initialisée avant
  toute distribution de broadcast, si bien que le composant Singleton est toujours prêt, et
  tout échec de résolution est désormais journalisé bruyamment au lieu de planter en silence.

**Correctif 3 — L'en-tête Message-Class de `PduParser` dévorait Content-Location** :
- Selon OMA-WAP-MMS-ENC, l'en-tête `Message-Class` peut être soit un octet
  `class-identifier` (0x80–0x83 = Personal/Advertisement/Informational/Auto), SOIT une
  `text-string`. Notre analyseur forçait inconditionnellement la branche `text-string` ;
  lorsque l'opérateur envoyait un class-identifier (0x8A 0x80…), l'analyseur consommait le
  0x80 comme premier octet d'une « text-string » et bouclait jusqu'au 0x00 suivant — dévorant
  `Message-Size`, `Expiry` ET l'URL `Content-Location` qui suivait. Le récepteur abandonnait
  alors avec « NotificationInd has no contentLocation », écartant le MMS en silence.
- Ajout d'un décodeur à double chemin : premier octet dont le bit de poids fort est à 1 →
  recherche de class-identifier (associée à « personal » / « advertisement » /
  « informational » / « auto ») ; sinon, le chemin `text-string` existant.

**Chaîne des pièces jointes à la réception modernisée** (`MmsDownloadedReceiver`,
`ConversationMirror.upsertIncomingMms`) :
- SMS Tech v1.3.9 ne conservait que les parties `audio/*` ; chaque MMS entrant image / vidéo /
  fichier arrivait sous forme de bulle de substitution `[MMS]` sans pièce jointe, le fichier
  étant écarté en silence. Le récepteur extrait désormais la première partie non texte et non
  `application/smil` de N'IMPORTE QUEL type MIME (image, vidéo, audio, application) et la
  persiste dans `cache/mms_incoming/`.
- Un extracteur `text/plain` distinct gère la légende de l'utilisateur — mais l'appel
  `CharacterSets.getMimeName(0)` renvoie le littéral `*` (sentinelle MIBenum WAP
  « any-charset »), qui n'est pas un jeu de caractères JVM valide ; `charset("*")` levait
  alors une exception et la légende était écartée en silence. Elle est désormais traitée par
  un repli en UTF-8.
- Stockage découplé : `messages.body` stocke la légende telle quelle (vide en son
  absence) ; le libellé d'aperçu de la liste des conversations est calculé séparément et passé
  à `touchConversation`. Empêche l'emoji de substitution de s'afficher comme une fausse
  légende en ligne sous la bulle de la pièce jointe.

**Notifications MMS câblées** (`IncomingMessageNotifier`, `MmsDownloadedReceiver`) :
- Renommage de `notifyIncomingSms` → `notifyIncoming` (la notification `MessagingStyle`
  est indépendante du type — même affichage heads-up, même masquage sur l'écran de
  verrouillage, même action de réponse en ligne, même annulation par tag de conversation).
  `MmsDownloadedReceiver` l'appelle désormais après le retour de `upsertIncomingMms`, avec le
  libellé d'aperçu comme corps de la notification. Auparavant, chaque MMS entrant arrivait en
  silence dans la base.

**Garde contre les conversations en double** (`ConversationMirror.ensureConversationByThread`) :
- `MmsDownloadedReceiver` crée la conversation sans identifiant de fil système
  (nous n'interrogeons pas `content://mms-sms/threadID` depuis le récepteur). Le
  `TelephonySyncManager.bulkImportMmsFromTelephony` exécuté ensuite importait le même MMS
  avec son véritable identifiant de fil système et insérait une SECONDE ligne de conversation
  lorsque la recherche par thread-id échouait. Ajout d'un repli CSV exact + suffixe de 8
  chiffres dans `ensureConversationByThread`, symétrique de `ensureConversation` : une
  conversation individuelle correspondante voit désormais son `thread_id` MIS À JOUR sur
  place au lieu d'être masquée par un doublon.

**Le sélecteur de fichiers accepte tous les types MIME** (`AttachmentPickerSheet`) :
- Remplacement de la liste blanche `{pdf, image/*, audio/*, video/*, text/*}` par
  `arrayOf("*/*")`. Les formats bureautiques (.docx, .xlsx, .pptx, .odt, .zip, .epub, .json…)
  n'apparaissent plus grisés dans le sélecteur de fichiers du système. Le plafond de taille
  MMS (~280 Ko sur la plupart des MMSC français) bloque toujours les fichiers trop volumineux
  au moment de l'envoi.

**KeepAliveService — service de premier plan optionnel (« Mode résistant »)** :
- Pour les constructeurs agressifs (Xiaomi/Redmi/Poco, Huawei/Honor, Oppo/Realme/OnePlus,
  Vivo/iQOO, Meizu, Asus — détectés par `OemRomDetector` via `Build.MANUFACTURER`
  / `Build.BRAND`) qui tuent les applications SMS en arrière-plan en quelques minutes.
  Désactivé par défaut ; activable via Réglages → Avancé → « Mode résistant ». `START_STICKY`,
  `stopWithTask="false"`, `foregroundServiceType="dataSync"`. Redémarrage automatique au
  démarrage de l'appareil via `BootReceiver` (qui lit l'indicateur DataStore). Un try/catch
  défensif couvre la révocation de POST_NOTIFICATIONS (Android 13+) et
  `ForegroundServiceStartNotAllowedException` (Android 12+).

**Limites de compatibilité documentées (HyperOS récent)** :
- **Xiaomi Poco F5 + HyperOS 2024+** : HyperOS place Google Messages + Mi Messages en liste
  blanche au niveau du système et exige une connexion à un compte Mi pour désactiver
  l'optimisation MIUI — une étape que beaucoup d'utilisateurs ne franchiront pas. Le service
  de premier plan « Mode résistant » atténue les arrêts en arrière-plan mais ne contourne pas
  la liste blanche du système. Repli recommandé pour ces appareils : utiliser Google Messages.
  Documenté dans la section [Compatibilité](https://files-tech.com/sms-tech.php) du site du
  produit.

Aucune nouvelle dépendance. Aucun changement de schéma. Aucun changement de clé de signature.
Même SHA-256 de certificat `b09a9511…687d`. Environ 10 fichiers modifiés, 15 fichiers .java
renommés.

### v1.3.9 — Conversation active au premier plan : fermeture automatique de la notification

Correctif UX demandé par un utilisateur : lorsque l'utilisateur **regarde la conversation X**
au premier plan (écran du fil ouvert) et qu'un nouveau SMS pour X arrive, la notification
persistait dans le volet — obligeant l'utilisateur à la balayer à la main alors que le message
était déjà apparu en temps réel dans la conversation ouverte.

**Nouveau comportement** (aligné sur Google Messages, iMessage, Mi Messages) :
- Conv X **ouverte au premier plan** + SMS arrivant sur X → la notif est **publiée
  brièvement** (le son + l'affichage heads-up se jouent normalement, pour que l'utilisateur
  « entende » arriver le nouveau message) puis **annulée automatiquement par Android après
  1500 ms** via `Notification.setTimeoutAfter(1500L)` (API 26+, dans notre `minSdk`). Aucune
  persistance dans le volet.
- Conv X ouverte + SMS arrivant sur **une autre conv Y** → la notif de Y persiste normalement
  (l'utilisateur n'a aucune visibilité sur Y).
- Application en arrière-plan → les notifs persistent normalement (inchangé).

**Architecture** :
- Nouveau singleton `ActiveConversationTracker` (`@Singleton @Inject constructor()` Hilt,
  `AtomicLong` pour l'identifiant actif, sentinelle `NONE = -1L`).
  - `setActive(conversationId)` : appelé depuis `ThreadViewModel.init` (avant les
    observateurs / `markRead`).
  - `clearActive(conversationId)` : appelé depuis `ThreadViewModel.onCleared` (avant
    le reste du nettoyage). Utilise `AtomicLong.compareAndSet(conversationId, NONE)`
    pour qu'un `onCleared` périmé provenant d'un ViewModel précédent ne puisse pas effacer
    l'état posé par un nouveau ViewModel fraîchement initialisé lors d'une concurrence de
    configChange.
  - `isActive(conversationId): Boolean` : lecture sans verrou par le notificateur.
- `IncomingMessageNotifier.notifyIncoming` lit `isActiveConversation` UNE SEULE FOIS
  avant de construire la notification, puis applique conditionnellement
  `.setTimeoutAfter(ACTIVE_CONV_TIMEOUT_MS)` dans le bloc `.also { ... }` du builder.

**Pourquoi cette conception est robuste** (audit pré-release mobile-quality-auditor, niveau 2
STRICT, verdict APPROUVÉ) :
- La lecture sans verrou de l'`AtomicLong` coûte ~1 ns par SMS, négligeable face aux
  suspensions d'E/S déjà présentes (`settings.flow.first()`, `contacts.lookupByPhone`).
- `setTimeoutAfter` est l'API officielle d'Android pour les notifications à durée limitée —
  aucun minuteur de coroutine maison, aucun Handler.postDelayed, aucun WorkManager. Surcoût
  nul.
- La métadonnée conservée en mémoire (l'identifiant Room de la conversation active, un Long)
  ne porte aucune donnée personnelle, aucun contenu de message. Le masquage sur l'écran de
  verrouillage (`VISIBILITY_PRIVATE` / `_SECRET`) n'est pas affecté.
- `cancelAllForConversation` (audit v1.3.3 Z4) continue de fonctionner : il parcourt
  `activeNotifications` filtrées par tag, et une notif déjà annulée automatiquement via
  `setTimeoutAfter` est simplement absente du parcours — pas de double annulation.
- Le flux `markRead` reste intact : `init → setActive → observers launch → first batch
  → markRead → cancelAllForConversation`. Le délai de la conv active et l'annulation à la
  lecture sont orthogonaux.

**Limites acceptées** :
- Sur les ROM constructeur agressives (certaines variantes MIUI / ColorOS), `setTimeoutAfter`
  peut être ignoré au niveau du système — le comportement de repli est la persistance
  d'avant la v1.3.9, sans régression fonctionnelle.
- Si le processus est tué brutalement sans que `ThreadViewModel.onCleared` s'exécute (rare :
  OOM killer, arrêt forcé), l'AtomicLong reste « actif » pour le reste de la session du
  processus. Le SMS suivant sur cette conv serait alors fermé automatiquement au lieu de
  persister — défaut UX cosmétique, sans risque de sécurité. Réinitialisé au prochain
  démarrage à froid de l'application (l'AtomicLong n'a aucune persistance).

Aucune nouvelle dépendance. Aucun changement de format / schéma / clé de signature /
permission. Même SHA-256 de certificat `b09a9511…687d`. 3 fichiers modifiés (1 nouveau
`ActiveConversationTracker.kt`, 2 modifiés `ThreadViewModel.kt` + `IncomingMessageNotifier.kt`).
### v1.3.8 — Correctif CRITIQUE : règle keep R8 pour les classes PDU MMS AOSP embarquées

**Gravité** : CRITIQUE. Pour tous les utilisateurs de la v1.3.7, l'**envoi de MMS était cassé sans
aucun signal** (messages vocaux, pièces jointes multiples, MMS à image unique). Les SMS texte
n'étaient pas touchés — le défaut était propre au chemin d'envoi MMS passant par la réflexion de
`MmsBuilder.attachRecipientsCompat`.

**Symptôme** :
- L'utilisateur appuie sur Envoyer pour un clip vocal → la bulle apparaît avec le statut « Envoi en
  cours » → elle bascule aussitôt en FAILED avec un snackbar rouge « Échec d'envoi ».
- « Appuyer pour réessayer » sous la bulle ne fait rien (limitation préexistante : `RetrySend
  UseCase` ne sait renvoyer que des SMS texte, pas des MMS — à traiter dans une version
  ultérieure).
- Logcat affiche `TP/MmsProvider: insert outbox row 10XX` suivi 24 ms plus tard de
  `TP/MmsProvider: delete row 10XX, caller: com.filestech.sms` — la ligne système est annulée dès
  que `MmsBuilder.buildMultipartSendReq` renvoie null.

**Cause racine** :
`app/src/main/java/com/google/android/mms/pdu/*.java` embarque les classes d'encodage PDU MMS
d'AOSP (`SendReq`, `PduComposer`, `PduBody`, `PduPart`, `EncodedStringValue`, `PduHeaders`,
`CharacterSets`, `PduParser`…). `MmsBuilder.attachRecipientsCompat` + `MmsBuilder.appendPart`
appellent des méthodes de ces classes **exclusivement par réflexion**
(`Class.getMethod("setTo", arr.javaClass)`, `Class.getMethod("addTo", …)`,
`Class.getMethod("addPart", …)`) pour franchir les divergences de signature entre constructeurs
(Samsung One UI a retiré `addTo`, certaines versions d'AOSP n'exposent pas `addPart(PduPart)`,
etc.).

Comme aucun code source Kotlin/Java n'appelle ces méthodes directement, l'analyseur statique global
du graphe d'appels de R8 en a conclu qu'il s'agissait de code mort et **les a retirées de l'APK de
release** lors de la minification. L'appel suivant `Class.getMethod("setTo", …)` lève alors
`NoSuchMethodException` → `attachRecipientsCompat` renvoie `false` → `buildMultipartSendReq`
renvoie `null` → `dispatchMms` entre dans le chemin d'annulation « BRANCH 2 : encodePdu null » →
l'utilisateur voit l'échec.

Le défaut existait à l'état latent dans toutes les versions précédentes ; la v1.3.6 s'est trouvée
compilée avec des méthodes conservées par R8 (l'analyse du graphe d'appels avait décidé de les
garder pour des raisons sans rapport), et les changements voisins de la v1.3.7 (migration G4 /
purge des chaînes G5 / LruCache F5) ont suffisamment déplacé le résultat de l'analyse pour
déclencher leur retrait.

**Correctif** (`proguard-rules.pro`) :
```
-keep class com.filestech.sms.pdu.** { *; }
```
(Mis à jour en v1.3.10 — le paquet PDU embarqué a été renommé de `com.google.android.mms.*` en
`com.filestech.sms.pdu.*` pour contourner la liste noire des API cachées (Hidden API) d'Android
10+ ; la règle keep a été mise à jour en conséquence. Historique antérieur à la v1.3.10 : la règle
visait à l'origine `com.google.android.mms.**`.)

Une seule règle keep couvrant tous les membres du paquet PDU embarqué. Vérifiée suffisante par un
test utilisateur le 2026-05-17 (message vocal envoyé avec succès, reçu par le destinataire). Aucun
risque de sécurité ajouté — les classes PDU sont du code utilitaire en lecture seule (aucun
identifiant, aucun état, aucun accès réseau, aucune persistance).

**Traçabilité de la vérification** :
1. Des appels `MMS_DEBUG` `android.util.Log.e` ont été ajoutés temporairement aux 5 branches
   d'échec de `MmsSender.dispatchMms` + à chaque `return null` de
   `MmsBuilder.buildMultipartSendReq` pour contourner le retrait de Timber par
   `-assumenosideeffects` en release. La capture Logcat a désigné
   « `attachRecipientsCompat returned false` » comme chemin d'origine.
2. Après application de la règle keep + nettoyage des journaux de débogage, l'utilisateur a refait
   le test ; le MMS vocal est parti avec succès et sa réception a été confirmée.
3. Aucune différence avec la v1.3.7 dans `MmsSender.kt` / `MmsBuilder.kt`, retraits de
   `MMS_DEBUG` déduits — seul `proguard-rules.pro` a changé fonctionnellement.

**Reporté à la v1.3.9+** :
- `RetrySendUseCase` devrait apprendre à renvoyer les MMS (vocal / pièces jointes multiples) par le
  cas d'usage approprié, et non toujours par `SmsSender.send()` (qui ignore les pièces jointes sans
  le signaler). L'action « appuyer pour réessayer » est actuellement inopérante pour les MMS.
  → **v1.28.3 (audit global du 2026-09-09, B-1)** : pire qu'inopérante, elle *faisait* quelque
  chose — elle renvoyait la légende comme un simple SMS, et la ligne pouvait passer à SENT sous une
  miniature qui n'avait jamais quitté l'appareil. `RetrySendUseCase` refuse désormais les lignes
  MMS avec une erreur typée `AppError.MmsRetryUnsupported` **avant** `resetOutgoingForRetry`, et la
  conversation affiche un message. Un vrai renvoi de MMS (via `MmsDispatcher`, avec un
  `requestCode` portant la tentative comme F23 l'a fait pour les SMS) reste reporté — il faut deux
  téléphones pour le vérifier.
- Envisager de resserrer la règle keep des PDU AOSP de `** { *; }` à une règle par méthode `-keep
  class … { method-name; }` une fois établie une liste exhaustive des méthodes appelées par
  réflexion — économise ~20-50 Ko dans l'APK mais exige une traçabilité d'audit rigoureuse.

Aucune nouvelle fonctionnalité dans ce correctif. Aucun changement des formats de fichier, du
schéma de base de données, de la clé de signature, des permissions ni de l'i18n. Même certificat
SHA-256 `b09a9511…687d`.

### v1.3.7 — Écran de démarrage au premier lancement + snackbar bicolore + arriéré d'audit

Version de fonctionnalités demandées par l'utilisateur, qui solde aussi tout l'arriéré d'audit
relevé lors des audits pré-release des v1.3.5 et v1.3.6.

**Fonctionnalité 1 — Écran de démarrage au premier lancement** (`SplashScreen.kt` +
`SplashViewModel.kt` + DataStore `AdvancedSettings.splashShown`) :
- Animation 100 % Compose native (aucune dépendance Lottie, aucun poids ajouté à l'APK).
- Animations : échelle du logo (0.5 → 1.0) + alpha (0 → 1) sur 900 ms, ease-out cubique ;
  apparition en fondu du slogan après 700 ms ; apparition en fondu de l'indication pour passer
  après 1500 ms. Total ≈ 5.5 s, interruptible par un appui, le bouton retour matériel, ou par
  fermeture automatique.
- Slogan : « L'appli qui ne lit pas vos messages et qui respecte votre vie privée. » (FR) /
  « The app that doesn't read your messages and respects your privacy. » (EN).
- Garde à déclenchement unique via un `AtomicBoolean` partagé par tous les chemins de fermeture
  (appui, retour, fermeture automatique, branche « déjà vu » au démarrage à froid).
  `LaunchedEffect(Unit)` ne se redéclenche jamais → aucun risque de double navigation lors du
  basculement du drapeau `markShown()`.
- `StateFlow` avec `SharingStarted.Eagerly` + valeur initiale `true` → aucun flash de l'écran de
  démarrage pour les utilisateurs à partir du deuxième lancement ; redirection vers l'accueil avant
  le dessin de la moindre image.
- Branché dans `AppRoot` comme `startDestination = Splash` avec `popUpTo(Splash)
  { inclusive = true }` à la fin → l'écran de démarrage est inatteignable par la pile de retour.

**Fonctionnalité 2 — Snackbar bicolore** (succès vs erreur) :
- `ThreadViewModel.Event.ShowSnackbar` reçoit un drapeau `isError: Boolean = false`.
- `SmsTechSnackbarVisuals`, un `SnackbarVisuals` personnalisé, transporte le drapeau à travers le
  `SnackbarHostState` (motif officiel Material 3, aucun état externe).
- `SnackbarHost { data -> ... }` fait dépendre `containerColor` / `contentColor` / `actionColor`
  de `(data.visuals as? SmsTechSnackbarVisuals)?.isError`.
- Succès → bleu ardoise de la marque (`BrandBlue #2460AB`, `Color.kt:SnackbarBg`). Erreur → rouge
  franc (`BrandDanger #C62828`, identique aux boutons de suppression + dialogues destructifs).
  Texte blanc sur les deux, contraste WCAG AA vérifié (5.8:1 / 5.5:1).
- Toutes les émissions de snackbar `_FAILED` dans `ThreadViewModel` passent `isError = true`.
- Assistant `SnackbarHostState.showError(message)` pour les sites d'appel direct
  (`onMicPermissionDenied`, etc.).

**Version de nettoyage — arriéré d'audit soldé** :

- **G4 (MOYEN)** : `MIGRATION_5_6 { DROP TABLE IF EXISTS conversation_overrides }` — table morte
  (l'entité + le DAO existaient mais sans aucun consommateur métier ; vérifié par un grep
  transversal). `IF EXISTS` rend la migration idempotente. Fichiers `ConversationOverride
  Entity` + DAO supprimés, retirés de `AppDatabase.entities[]` + du
  `DatabaseModule.@Provides` de Hilt. Version du schéma montée 5 → 6.
- **G5 (MOYEN)** : 64 chaînes i18n orphelines retirées à la fois de `values/strings.xml` (EN) et
  de `values-fr/strings.xml` (FR). Parité FR↔EN préservée à 291/291. Réduction de l'APK de
  ~10-15 Ko. 43 orphelines évoquant des fonctionnalités prévues (`action_archive`, `action_pin`,
  `settings_signature`, etc.) conservées telles quelles selon la règle de rétention de la v1.3.5.
- **F5 (MOYEN)** : `displayNameCache` migré de `ConcurrentHashMap` (non borné) →
  `android.util.LruCache<String, String>(1000)`. Les comptes anciens (historique de 50k+ SMS avec
  des expéditeurs alphanumériques de banque/livraison/2FA) ne font plus croître le cache sans
  limite pendant toute la durée de vie du singleton. L'éviction LRU retire d'abord les expéditeurs
  les plus anciens ; les conversations actives restent en cache. `LruCache` est thread-safe
  (synchronisé en interne) — même garantie d'atomicité que le `ConcurrentHashMap` précédent.
- **U1 (MOYEN)** : `ToggleRow.clickable + Switch.onCheckedChange` → `Modifier
  .toggleable(role = Role.Switch) + Switch.onCheckedChange = null`. Un seul nœud sémantique pour
  TalkBack / Switch Access (auparavant, deux éléments interactifs distincts étaient annoncés par
  interrupteur). Recommandation officielle de Material 3 pour les éléments de liste
  sélectionnables.
- **P2 (MOYEN)** : l'interrupteur conditionnel « Format de réaction compact » est enveloppé dans
  `AnimatedVisibility(fadeIn + expandVertically / fadeOut + shrinkVertically)` pour une transition
  de mise en page fluide quand l'interrupteur parent `sendReactionsToRecipient` bascule.

**Reporté à la v1.3.8** :
- **U2** : `stateDescription` sur les interrupteurs parents qui conditionnent un interrupteur
  enfant — TalkBack n'annonce pas actuellement pourquoi l'enfant est apparu/a disparu. Priorité
  basse, ne concerne que les utilisateurs avancés de TalkBack.
- 43 chaînes i18n orphelines marquées « évoquant des fonctionnalités prévues » — à réévaluer une
  par une quand les fonctionnalités correspondantes arriveront (ou seront annulées).

Compilation propre (`assembleRelease` vert). Les 60+ tests unitaires passent toujours. Aucune
nouvelle dépendance. Variation de taille de l'APK ≈ -10 Ko (purge des chaînes G5, moins quelques
octets des classes de l'écran de démarrage). Certificat SHA-256 `b09a9511…687d` inchangé. Aucun
changement format fichier / aucune destruction de données utilisateur (G4 : suppression d'une
table vide).

### v1.3.6 — Codec universel pour les MMS vocaux + interrupteur de format de réaction

Deux correctifs demandés par l'utilisateur après des retours de terrain sur Xiaomi Redmi 9A
(Android 10 Go + Orange/SFR). Aucune nouvelle surface fonctionnelle ; intervention minimale, delta
audité.

**Correctif 1 — Codec des MMS vocaux passé en AMR-NB / 3GP** (`VoiceRecorder.kt` uniquement) :
- `audio/mp4` (encodeur AAC, conteneur MP4) → `audio/3gpp` (encodeur AMR-NB, conteneur 3GP).
  L'AAC était rejeté sans signal par le MMSC de l'opérateur sur certaines combinaisons
  ROM × opérateur (Redmi 9A Android 10 Go + Orange et SFR, observé le 2026-05-16) — la bulle
  locale apparaissait mais l'envoi du PDU revenait en `RESULT_ERROR_GENERIC_FAILURE` et le message
  restait sur « échec d'envoi ». L'AMR-NB est le codec audio MMS universel historique (RFC 3267,
  OMA-MMS depuis 2002), accepté sans exception par tous les MMSC et toutes les ROM Android. C'est
  le format utilisé par Mi Messages, l'ancien Google Messages, Samsung Messages. La légère baisse
  de débit (12.2 kbps fixe, mono 8 kHz) est compensée par la compatibilité universelle ;
  l'intelligibilité de la voix reste identique à celle du codec téléphonique 2G GSM-FR.
- Constante renommée `MIME_AUDIO_M4A` → `MIME_AUDIO_3GPP`.
- Extension de fichier `.m4a` → `.3gp`. `MmsDownloadedReceiver.mimeExtension` gérait déjà
  `audio/3gpp` pour le chemin de réception, aucun changement nécessaire côté consommateur.
- Plafond `MAX_SIZE_BYTES` (280 Ko) inchangé : AMR-NB à 12.2 kbps pendant 120 s = ~183 Ko, marge
  confortable pour de futurs MMSC inconnus.

**Correctif 2 — Interrupteur visible par l'utilisateur pour le format des SMS de réaction**
(5 fichiers + i18n FR/EN) :
- Nouveau `SendingSettings.reactionEmojiOnly: Boolean = false`. À `true`, le SMS de réaction ne
  contient que l'emoji seul (p. ex. `"❤️"`). À `false` (par défaut), l'enveloppe Tapback
  Apple/Google introduite en v1.3.2 reste en vigueur (`"Reacted ❤️ to «preview»"`).
- Justification : sur les anciennes applications SMS (Mi Messages, anciens Samsung) qui
  n'analysent pas le Tapback, l'enveloppe s'affiche en texte brut — visuellement encombrant. La
  nouvelle option permet aux utilisateurs dont les destinataires utilisent ces anciennes
  applications d'envoyer l'emoji seul, plus propre. La valeur par défaut reste `false` pour
  préserver le rendu natif en bulle de réaction sur iMessage (iPhone) et sur les versions récentes
  de Google Messages, où c'est l'analyse du Tapback qui produit la bulle fusionnée sous le message
  d'origine.
- Interface : nouveau `ToggleRow` dans `SettingsScreen` → section Envoi, affiché seulement quand
  `sendReactionsToRecipient` est activé (cohérence : on ne peut régler le format que si l'on
  envoie effectivement).
- Chemin du cas d'usage : `SendReactionUseCase.invoke()` reçoit un paramètre `emojiOnly:
  Boolean = false` (valeur par défaut pour la rétrocompatibilité avec les appelants / tests
  existants). Les gardes F1/F2/F3/X1 restent inchangées et s'exécutent avant l'aiguillage
  `body = if (emojiOnly) emoji else buildTapbackBody
  (...)`.
- Clé DataStore : `send.reactions.emojiOnly` (booléen). Aucune migration, aucune montée de schéma
  — la première lecture renvoie `false` sur les installations antérieures à cette version.

**L'audit pré-tag a relevé 1 ÉLEVÉ + 5 MOYEN ; l'ÉLEVÉ + 1 MOYEN (P1) corrigés sur place,
4 MOYEN reportés à la v1.3.7 (fusions sémantiques TalkBack qui touchent tous les `ToggleRow`
existants, pas seulement ce delta) :**

- **S1 (ÉLEVÉ)** : `dispatchReactionSms` ajoutait à l'origine une 2e lecture
  `settings.flow.first()` à chaque envoi de réaction (en plus de celle déjà présente dans
  `setReaction`). Refactorisé pour passer `emojiOnly: Boolean` en paramètre depuis l'instantané
  déjà détenu par l'appelant — zéro lecture DataStore supplémentaire, aucun point de suspension
  introduit où la valeur de l'emoji en cours d'envoi pourrait dériver. Élimine entièrement la
  préoccupation d'origine (collecte de flow sans délai d'expiration sur un chemin d'envoi
  critique).
- **S2 (MOYEN)** : `versionName` monté `"1.3.5"` → `"1.3.6"` dans `app/build.gradle.kts`
  (ligne 38), ainsi que le commentaire associé ligne 71. Nécessaire à la cohérence manifeste / yml
  F-Droid.
- **P1 (MOYEN)** : identique à S1 — `dispatchReactionSms(messageId,
  emoji, emojiOnly)` refactorisé et les deux sites d'appel mis à jour (chemin `setReaction` après
  l'observateur + chemin `confirmReactionSend` après le dialogue) pour lire l'instantané `sending`
  une seule fois et transmettre `emojiOnly`.

Reporté à la v1.3.7 (non propre à ce delta, s'applique à **tous** les `ToggleRow` existants) :
- **P2** : envelopper les `ToggleRow` conditionnels dans `AnimatedVisibility` pour des transitions
  de mise en page plus fluides.
- **U1** : `Modifier.semantics(mergeDescendants = true) {}` sur la Row de `ToggleRow` +
  `onCheckedChange = null` sur le `Switch` interne pour fusionner TalkBack en un seul nœud
  d'accessibilité. Touche chaque interrupteur de `SettingsScreen`, pas seulement le nouveau.
- **U2** : ajouter une `stateDescription` aux interrupteurs parents dont la valeur conditionne la
  visibilité d'un interrupteur enfant, pour que les utilisateurs de TalkBack sachent que l'enfant
  est masqué parce que le parent est sur OFF (plutôt qu'une navigation qui le saute sans rien
  dire).

Aucune régression dans les 60+ tests unitaires existants (compileRelease + tests verts). Aucun
changement de format de fichier. Les clips vocaux enregistrés par la v1.3.5 (AAC `.m4a`) restent
lisibles — `MmsDownloadedReceiver.mimeExtension` gère à la fois `audio/mp4` (anciens reçus) et
`audio/3gpp` (nouveaux envoyés + reçus).

### v1.3.5 — Nettoyage d'architecture + finitions de l'interface

Version de finition qui clôt 5 constats de l'audit global de la v1.3.3 (G3, G6, G7, G8, G9), plus
un ajustement d'interface demandé par l'utilisateur. Aucune nouvelle fonctionnalité. Vise à garder
le code propre en vue de la v1.4.x.

Côté utilisateur :
- Bouton X de retrait de pièce jointe : 20 → 22 dp (un peu plus grand, plus facile à toucher).

Nettoyage :
- **G3 (MOYEN)** : 3 permissions orphelines retirées du manifeste (`SCHEDULE_EXACT_ALARM`,
  `USE_EXACT_ALARM`, `CHANGE_NETWORK_STATE`) — inutilisées par le code, elles généraient des
  avertissements de la Play Console + pouvaient être révoquées sur Android 14+. L'envoi des
  messages programmés passe par `WorkManager
  .enqueueUniqueWork`, aucun besoin d'alarmes exactes ; le transport MMS utilise
  `INTERNET + ACCESS_NETWORK_STATE`, aucun appel à `CHANGE_NETWORK_STATE`.
- **G6 (MOYEN)** : champ fantôme `BlockingSettings
  .blockShortCodes` retiré — jamais lu nulle part, jamais exposé dans l'interface. Si le filtrage
  des numéros courts est un jour redemandé, le réimplémenter via une colonne
  `BlockedNumberEntity.scope` plutôt qu'un booléen global.
- **G7 (MOYEN)** : `ThreadViewModel.recentlySentReactionFor` purge désormais les entrées expirées
  (fenêtre > 60 s) à chaque accès. C'était une croissance lente pendant la durée de vie du
  ViewModel (négligeable mais inutile).
- **G8 (MOYEN)** : `HeadlessSmsSendService` a retiré `Intent.ACTION_SEND` de sa liste blanche
  d'actions — l'extracteur du corps s'appuyait sur `intent.data.schemeSpecificPart`, vide pour les
  vrais intents `ACTION_SEND` (fondés sur `EXTRA_TEXT`). Branche morte + surface IPC ouverte sans
  nécessité.
- **G9 (MOYEN)** : `AppLockManager.disableBiometric` passe d'une lecture-puis-mise-à-jour à un
  `settings.update { transform }` atomique. DataStore garantit l'atomicité ; le motif précédent
  n'était pas idiomatique et frôlait la situation de concurrence avec des appelants simultanés.

Reporté à la v1.3.6+ (demande un travail plus soigné) :
- G4 : `ConversationOverrideDao` + entité + table : entièrement morts. À supprimer via une
  `MIGRATION_5_6 { DROP TABLE conversation_overrides }`.
- G5 : ~103 chaînes i18n inutilisées. À nettoyer via le nouvel agent
  `android-i18n-strings-cleaner`.

### v1.2.0 — Audit sur 3 axes

Trois agents indépendants ont examiné le delta v1.1.x → v1.2.0 selon trois axes :

- **Sécurité** : note de 78/100. Deux P0 corrigés avant la release :
  - **P0-1** Contournement du coffre via `ToggleConversationStateUseCase`, qui court-circuitait la
    garde de `VaultManager`, combiné au fait que `VaultManager.markUnlocked()` n'était jamais
    appelé.
  - **P0-2** Forme implicite de PendingIntent abandonnée sans signal sur Android 14+, ce qui
    laissait le fichier PDU du MMS (audio brut + en-têtes de l'expéditeur) en clair dans
    `cache/mms_incoming/` pour toujours — le receiver ne se déclenchait jamais.
  - Correctifs P1 : borne de l'horizon de blocage, atomicité du défi biométrique, purge récursive
    du cache, `setClass` explicite sur chaque cible de receiver interne.
- **Qualité du code / performance** : note de 84/100. Code mort retiré
  (`ThreadViewModel.replyToMessage`, `archiveThisConversation`, `ConversationsViewModel.pin /
  archive / mute`, injection de `TelephonySyncManager.messageDao`), `MessageBubble.time` mis en
  cache via `remember`, doublon de `rememberChatFormatters()` dans `ThreadScreen` retiré.
- **Duplications** : note de 72/100. Chaînes mortes retirées (`lock_biometric_prompt_title`,
  `lock_biometric_use_pin` — jamais référencées). `BrandDanger` centralisé dans
  `ui/theme/Color.kt`. La factorisation `AsyncCoroutineReceiver` est **reportée à la v1.3**, car
  elle touche chaque receiver et nous voulions publier la v1.2.0 sans ce risque.

Rapports d'audit complets archivés sous forme de commentaires dans le code (chercher
« Audit P0-1 », « Audit P1-5 », etc. pour la justification en ligne de chaque correctif).

### v1.1.x — Vagues 1–3 (audit interne)

L'audit interne pré-release a appliqué 23 corrections (F1–F14 sécu, P1–P5 perf, U1–U11 UX).
Voir [`CHANGELOG.md`](CHANGELOG.md#1-1-0--2026-05-14) pour la liste exhaustive.

---

## Hors périmètre

- **Chiffrement du transport.** Les SMS et les MMS circulent en clair par nature du protocole.
  Utilisez Signal / WhatsApp / Matrix pour les contenus qui ne doivent pas être visibles par
  l'opérateur.
- **Appareils rootés.** Si `/data/data/com.filestech.sms.debug/` est lisible en root, la clé
  SQLCipher reste enveloppée dans le Keystore, mais un attaquant disposant de privilèges suffisants
  peut détourner la même API `KeyStore.getInstance("AndroidKeyStore")` que celle utilisée par
  l'application. Nous ne détectons pas le root et ne refusons pas de fonctionner sur un appareil
  rooté.
- **Attaques par canal auxiliaire sur le matériel du Keystore** (défauts du firmware TEE /
  StrongBox). Hors de notre portée.
- **Compromission de la passerelle MMS au niveau de l'opérateur.** Un MMSC malveillant pourrait
  livrer un PDU arbitraire et nous l'afficherions — nous ne signons / vérifions pas le contenu.
  Atténuation : plafonds de taille des pièces jointes, liste blanche MIME pour les parties
  entrantes, échappement XML SMIL des noms de fichiers.

---

## Inventaire des permissions

Voir [`PERMISSIONS.md`](PERMISSIONS.md) pour le tableau complet. Chaque permission y est justifiée
en une phrase.

---

## Signalement

`contact@files-tech.com` — objet `SMS Tech security report`. Clé PGP disponible sur demande.
Merci de nous laisser 90 jours avant toute divulgation publique d'un problème non corrigé.
