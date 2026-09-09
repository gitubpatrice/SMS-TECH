# Audit global et approfondi — quatre passes parallèles, puis vérification

**Date** : 2026-09-09, soir — après la fermeture côté code de la 4ᵉ passe d'Andrew Pozdnakov
(`!38458`), sur la branche `fix/relecture-externe-38458` (26 commits devant `main`, non poussée).
**Méthode** : la fiche `audit_global_apps.md` (skill `developpeur-apps`), amendée sur quatre points
— pas de note sur 10 ; pas de backend imaginaire (zéro Retrofit/OkHttp/Firebase/analytics, mesuré) ;
aucun constat sans `fichier:ligne` lu ; le registre des 33 + 7 findings fermés donné en entrée pour
ne pas re-signaler du clos. Quatre agents en lecture pure (`android-code-quality-deep-dive`), un par
groupe de sections de la fiche, puis **chaque constat retenu vérifié par moi dans le code**. Rien n'a
été modifié : audit seulement.

**Résultat brut** : 16 constats rendus par les agents. **Après vérification : 9 retenus, 2 réfutés,
1 requalifié, 1 découvert en vérifiant** — et c'est celui qui passe en tête.

---

## A. Résumé exécutif

L'application est solide là où les sept audits précédents ont porté : verrou, PIN, session leurre,
Keystore, sauvegarde, migrations, réception MMS, manifeste, FileProvider, dépendances. Les quatre
passes le disent chacune à leur façon, et je le confirme : **aucun constat critique au sens
« exploitable par un tiers »**.

Ce qui reste relève de trois familles, toutes déjà nommées dans le registre de la relecture :

1. **Une règle posée sur un chemin, absente de son jumeau** — `setVisibility` sur un notificateur sur
   trois ; garde leurre sur l'export mais pas sur la restauration ; numéro de tentative dans le
   `PendingIntent` SMS mais pas MMS.
2. **Une fonctionnalité annoncée mais non câblée** — le toggle « Vibrer » ne contrôle rien ; six
   réglages persistés que rien ne lit.
3. **Un chemin qui traverse une frontière sans la voir** — le renvoi d'un MMS par le chemin SMS ; la
   réponse depuis un groupe du coffre qui écrit hors du coffre.

La troisième famille porte les deux constats les plus lourds, et **aucun des deux n'est nouveau dans
le code** : l'un est documenté dans `SECURITY.md` depuis la v1.3.9, l'autre découle d'un choix de
conception (« le SMS n'a pas de vrai groupe ») qui n'a jamais été confronté au coffre.

## B. Ce que l'audit ne peut pas voir

Rappel de méthode, avant les constats : les deux défauts préexistants de la v1.27.11 (forme du
`telephony_uri`) étaient **invisibles à la lecture** et trouvés en mesurant sur appareil. Le silence
de cet audit sur un chemin ne vaut donc pas absence de défaut. Les mesures qui manquent sont
listées au §G.

---

## C. Constats, par gravité

### X-01 · Élevée · CONFIRMÉ par lecture (rendu à mesurer) — Répondre depuis un groupe du coffre écrit hors du coffre

**Découvert en vérifiant A-01**, dont la prémisse était fausse (cf. §D).

- [ThreadViewModel.kt:707](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadViewModel.kt#L707) —
  `sendSms.invoke(conv.addresses, body, …)` : un fil de groupe envoie aux adresses du groupe.
- [SendSmsUseCase.kt](../../domain/src/main/java/com/filestech/sms/domain/usecase/SendSmsUseCase.kt) —
  une ligne **par destinataire**, `mirror.upsertOutgoingSms(address = r.raw, …)`.
- [ConversationMirror.kt:364](../../data/src/main/java/com/filestech/sms/data/repository/ConversationMirror.kt#L364) —
  `ensureConversation(listOf(PhoneAddress.of(address)))` : la ligne est rattachée à la conversation
  **1-à-1** du destinataire ; si elle n'existe pas, elle est **créée** (ligne ~975) avec
  [`inVault = false`](../../data/src/main/java/com/filestech/sms/data/local/db/entity/ConversationEntity.kt#L56)
  par défaut, puis `touchConversation` y pose l'**aperçu**.
- [ConversationMirror.kt:505](../../data/src/main/java/com/filestech/sms/data/repository/ConversationMirror.kt#L505) —
  `upsertOutgoingMediaMms` : **même mécanisme** ; et
  [SendMediaMmsUseCase.kt:93-130](../../domain/src/main/java/com/filestech/sms/domain/usecase/SendMediaMmsUseCase.kt#L93)
  envoie `recipients = listOf(r.raw)` — un PDU par destinataire, miroir dans sa 1-à-1.
- [ConversationRepositoryImpl.kt:476](../../data/src/main/java/com/filestech/sms/data/repository/ConversationRepositoryImpl.kt#L476) —
  `moveToVault` est un `setInVault` nu : **le coffre accepte les groupes**.
- Aucune garde `isGroup`/`inVault` au composeur : `grep isGroup` dans `ThreadViewModel`/`ThreadScreen`
  ne rend que l'affichage du nom ([ThreadScreen.kt:457](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadScreen.kt#L457)).

**Ce que ça fait.** L'utilisateur place une conversation de groupe dans le coffre. Il y reçoit des
MMS de groupe — masqués, comme promis. Il **répond** depuis ce fil : chaque réponse est écrite dans
la conversation individuelle de chaque membre, **hors coffre**, avec son texte en aperçu dans la
liste principale. Si aucune conversation individuelle n'existait, elle apparaît — nouvelle, visible,
nommée — dans la liste. Le contenu que le coffre devait protéger est dans la liste ouverte,
**par l'usage le plus ordinaire** du fil.

Le choix de conception est assumé et documenté
([ThreadViewModel.kt:716-721](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadViewModel.kt#L716) :
« les bulles … SMS n'ayant pas de vrai groupe, vivent chacune dans la conversation individuelle de
son destinataire ») ; il n'a simplement jamais été confronté au coffre. Hors coffre, il a une
conséquence déjà étrange mais inoffensive : un fil de groupe ne montre **jamais** ce qu'on y a
envoyé.

**Correction recommandée** — sa propre branche, appareil requis. Deux options honnêtes :
(a) rattacher les lignes sortantes à la conversation **d'où l'on a envoyé** (un `conversationId`
d'indice passé au miroir), ce qui ferme la fuite et répare au passage le fil de groupe muet — c'est
la bonne correction, mais elle change un comportement documenté et touche les trois use cases et
`ScheduledSendAttempt` : à faire **dans** la factorisation de la boucle d'envoi déjà décidée, pas à
côté ; (b) refuser les groupes au coffre avec un message clair — un cran plus bas, immédiat, honnête.
Dans les deux cas, **A-01 doit être corrigé en même temps** (cf. §D) : l'option (a) le rend réel.

**À mesurer sur appareil** : confirmer que le composeur est bien actif dans un fil de groupe du
coffre et qu'une réponse crée/alimente une 1-à-1 visible — la lecture ne laisse pas de doute sur le
code, mais c'est le rendu qui fait la gravité.

### B-1 · Élevée · CONFIRMÉ — Relancer un MMS en échec l'envoie comme SMS texte, sans la pièce jointe

- [ThreadScreen.kt:2168](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadScreen.kt#L2168)
  `onTap = { if (msg.status == FAILED) onTapFailed(msg) }` — aucun test de `msg.type` ;
  [ThreadScreen.kt:698-706](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadScreen.kt#L698) →
  [ThreadViewModel.kt:1317](../../app/src/main/java/com/filestech/sms/ui/screens/thread/ThreadViewModel.kt#L1317)
  `retry` → [RetrySendUseCase.kt](../../domain/src/main/java/com/filestech/sms/domain/usecase/RetrySendUseCase.kt),
  qui ne connaît que `SmsSender` et renvoie `msg.body`.
- `SECURITY.md:1643-1646` le documente depuis la v1.3.9 : *« The "tap to retry" affordance is
  currently dead for MMS »*. Jamais fermé.

**Scénario** : photo en MMS, échec, tap sur la bulle rouge. Avec légende : la légende part en SMS,
la ligne passe `SENT` sous une vignette qui n'est jamais partie — **« affiché envoyé, ne l'est
pas »**, la question directrice de la fiche. Sans légende : `divideMessage("")` échoue très
probablement de façon synchrone (à mesurer) — bulle rouge à nouveau, sans explication.

**Hors de cause** : la notification « Renvoyer » ajoutée ce soir n'est postée que par
`SmsSentReceiver` ([SmsSentReceiver.kt:79](../../app/src/main/java/com/filestech/sms/system/receiver/SmsSentReceiver.kt#L79)),
donc jamais sur une ligne MMS. Jumeaux : MMS média KO, MMS vocal KO (même chemin), SMS OK.

**Correction** : à court terme, une garde de type dans `retry()` avec un message (« relancez depuis
le composeur ») — S, sans risque. Le vrai renvoi MMS via `MmsDispatcher` demande de **traiter B-3
en même temps** (le `requestCode` de
[MmsSender.kt:282-297](../../data/src/main/java/com/filestech/sms/data/mms/MmsSender.kt#L282) ne porte
pas de numéro de tentative, contrairement à `smsTrackingRequestCode` depuis F23 — inoffensif
aujourd'hui **parce qu'**aucun chemin ne re-dispatche un MMS sur le même id ; un vrai renvoi MMS
rouvrirait F23 côté MMS).

### D-01 · Élevée (promesse UX fausse) · CONFIRMÉ — Le toggle « Vibrer » ne contrôle rien

- Déclaré [AppSettings.kt:170](../../domain/src/main/java/com/filestech/sms/domain/settings/AppSettings.kt#L170),
  exposé [SettingsScreen.kt:3237-3241](../../app/src/main/java/com/filestech/sms/ui/screens/settings/SettingsScreen.kt#L3237),
  persisté [SettingsRepository.kt:386](../../data/src/main/java/com/filestech/sms/data/local/datastore/SettingsRepository.kt#L386).
- **Lecteurs hors UI et persistance : zéro** (`grep '\.vibrate\b'`). Les canaux sont créés une fois
  avec `enableVibration(true|false)` **en dur**
  ([NotificationChannelInitializer.kt:27,45,63,…](../../app/src/main/java/com/filestech/sms/system/notifications/NotificationChannelInitializer.kt#L27)).
  Sur minSdk 26, le comportement d'un canal est figé à sa création : le réglage ne pouvait agir
  qu'en choisissant un canal — le motif existe déjà pour `NotificationStyle.SILENT`, il n'a pas
  été répliqué.

C'est **exactement F26** (`blockShortCodes`, `retryFailedAutomatically`), sur un champ qui n'était
pas dans la liste d'Andrew. **Correction** : câbler (second canal `INCOMING_NO_VIBRATE`, ~30 lignes,
régression faible) **ou** retirer le toggle selon la doctrine de F26. Décision produit.

### B-2 · Moyenne · mécanisme CONFIRMÉ, déclencheur À VÉRIFIER — Un envoi programmé qui échoue de façon synchrone et retente duplique sa ligne, en Room **et** dans `content://sms`

- [ScheduledSendAttempt.kt:108-112](../../app/src/main/java/com/filestech/sms/system/scheduler/ScheduledSendAttempt.kt#L108)
  rappelle `sendSms.invoke`/`sendMediaMms.invoke` **en entier** à chaque `Verdict.RETRY` ;
  [ScheduledMessageWorker.kt:48-56](../../app/src/main/java/com/filestech/sms/system/scheduler/ScheduledMessageWorker.kt#L48)
  `Result.retry()` jusqu'à `MAX_ATTEMPTS = 5`, backoff 30 s exponentiel.
- Chaque appel **insère** : `sentSmsRecorder.insertSentSms` (fournisseur système) puis
  `upsertOutgoingSms` — un `insert` pur malgré son nom
  ([ConversationMirror.kt:384](../../data/src/main/java/com/filestech/sms/data/repository/ConversationMirror.kt#L384)) —
  puis `FAILED/SYNCHRONOUS` ([SendSmsUseCase.kt:166-172](../../domain/src/main/java/com/filestech/sms/domain/usecase/SendSmsUseCase.kt#L166)).
  L'agent avait vu Room ; **la copie système est dupliquée aussi**.

**Nuance qui change la gravité** : `RETRY` n'est atteint que sur une exception **synchrone** de
`SmsManager` ([SmsSenderImpl.kt:76-78](../../data/src/main/java/com/filestech/sms/data/sms/SmsSenderImpl.kt#L76)).
L'absence de réseau ou de SIM se signale le plus souvent **en asynchrone** (`RESULT_ERROR_NO_SERVICE`),
chemin qui ne duplique pas. Pour le MMS programmé, un fichier disparu est refusé **avant** insertion
(`refusPrealable`, [SendMediaMmsUseCase.kt:46](../../domain/src/main/java/com/filestech/sms/domain/usecase/SendMediaMmsUseCase.kt#L46)) :
pas de doublon sur ce cas. Reste : `subId` périmé après changement de SIM, rejet OEM — fréquence
inconnue, **à mesurer**.

**Correction** : créer la ligne miroir **une fois** et retenter via `resetOutgoingForRetry` — c'est
le travail de la factorisation de la boucle d'envoi *et de son appelant*, déjà décidée : B-2 en est
un argument de plus, pas un chantier à part.

### D-02 · Moyenne · CONFIRMÉ — Deux notificateurs sur trois n'appellent pas `setVisibility`

- [IncomingMessageNotifier.kt:210-214](../../app/src/main/java/com/filestech/sms/system/notifications/IncomingMessageNotifier.kt#L210)
  mappe `PreviewMode` → `VISIBILITY_PUBLIC/PRIVATE/SECRET`.
- [MmsFailureNotifier.kt](../../app/src/main/java/com/filestech/sms/system/notifications/MmsFailureNotifier.kt) et
  [OutgoingFailureNotifier.kt](../../app/src/main/java/com/filestech/sms/system/notifications/OutgoingFailureNotifier.kt)
  (**ajouté ce soir, commit `3eed7f6`**) : aucune occurrence (`grep setVisibility`). Défaut du
  framework : `PRIVATE`.

`CorrespondentVisibilityPolicy` a unifié **qui** l'on nomme, pas **si l'événement paraît** sur
l'écran verrouillé. Avec `PreviewMode.NEVER`, un message reçu ne laisse rien paraître ; un échec
d'envoi ou de téléchargement, si — anonymisé, mais visible. Fuite d'existence, pas d'identité.
**Quatrième occurrence du motif dominant, dans du code écrit en sachant le motif.**

**Correction** : exposer le mapping dans la politique (`visibilitePour(PreviewMode)`) et l'appliquer
aux deux — deux lignes par notificateur, test unitaire à étendre. **La seule correction que je
recommande sur la branche en cours** : c'est un trou dans une classe de ce soir, même famille que F08.

### A-03 · Faible · CONFIRMÉ, non atteignable — La restauration n'a pas la garde leurre de l'export

- [BackupService.kt:148](../../data/src/main/java/com/filestech/sms/data/backup/BackupService.kt#L148)
  `writeSmsbk` refuse en `PanicDecoy` ; [`readSmsbk` (:382)](../../data/src/main/java/com/filestech/sms/data/backup/BackupService.kt#L382)
  et `restore()` (:63) n'ont rien ; `grep PanicDecoy` dans le fichier : lignes 84 (facteur
  d'export) et 148 seulement.
- L'agent a tracé la navigation : `AppRoot` vide la pile au verrouillage, l'entrée est masquée en
  leurre ([SettingsScreen.kt:665](../../app/src/main/java/com/filestech/sms/ui/screens/settings/SettingsScreen.kt#L665)),
  aucun deep link vers `Backup`. **Pas de chemin aujourd'hui.** Mais la doctrine de ce dépôt est
  « l'UI masque, le service refuse » — et ici seul l'UI masque. Une ligne. À faire quand on touche
  `BackupService`.

### D-03 · Faible · CONFIRMÉ — Six réglages persistés que rien ne lit

`defaultSoundUri`, `ledColorArgb`, `vibratePattern`, `bubbles`, `groupArchived`,
`mmsRoamingAutoDownload` — chacun n'apparaît que dans `AppSettings.kt` et `SettingsRepository.kt`
(grep par champ, vérifié). Aucun n'a d'UI, donc aucune promesse rompue — dette de modèle. **Retirer**
selon F26, sauf `mmsRoamingAutoDownload` qui a une vraie valeur produit (frais d'itinérance) s'il
est un jour câblé dans `MmsWapPushReceiver`.

### C-03 · Faible · CONFIRMÉ — Un `groupingBy` sur toutes les conversations, sur le fil principal

[ConversationsScreen.kt:484-490](../../app/src/main/java/com/filestech/sms/ui/screens/conversations/ConversationsScreen.kt#L484) :
`remember(state.conversations) { … groupingBy … }` à chaque émission de la liste, alors que le
filtre et le tri du même pipeline ont été déplacés sur IO pour cette raison précise
([ConversationsViewModel.kt:252-257](../../app/src/main/java/com/filestech/sms/ui/screens/conversations/ConversationsViewModel.kt#L252)).
Quelques millisecondes à 10 000 fils, rien en usage courant. À déplacer dans le ViewModel quand on
y touche.

### C-02 · À VÉRIFIER — Recalcul filtre + tri de toute la liste à chaque écriture de `conversations`

Mécanisme exact (invalidation Room par table, import paginé à 500 → une réémission par page),
coût **non mesuré**. Pas d'action sans profil sur un jeu de 10 000 conversations. Limite
structurelle à connaître avant toute promesse de montée en charge ; pas un défaut.

---

## D. Réfutés et requalifiés — ce que les agents ont dit et qui ne tient pas

**A-01 — « Le nom d'un correspondant d'un groupe du coffre est révélé sur échec d'envoi » : prémisse
fausse.** L'agent supposait que les lignes d'un envoi groupé portent le `conversationId` du groupe.
Elles portent celui de la **1-à-1** de chaque destinataire (X-01). Si cette 1-à-1 est au coffre,
`snapshotVaultOneToOne` la voit → `TAIRE`, correct. Si elle n'y est pas, la ligne est **déjà visible
dans la liste** : la notification ne révèle rien de plus. **Pas un défaut aujourd'hui.** Mais il le
**deviendra** si X-01 est corrigé par l'option (a) — les lignes vivraient alors dans le groupe, que
la requête 1-à-1 ignore. D'où : **corriger ensemble**, en faisant lire à la politique l'`inVault` de
la conversation réelle quand un `conversationId` est connu, comme `IncomingMessageNotifier` le fait.

**A-02 — même chose côté MMS entrant en échec** : le notificateur ne connaît que l'expéditeur, et
l'expéditeur n'est pas une conversation protégée si sa 1-à-1 ne l'est pas. Limite de conception,
Faible. Pas d'action.

**C-01 — « Le cache disque Coil garde des vignettes du coffre après verrouillage » : très
probablement réfuté.** Il n'y a pas d'`ImageLoader` personnalisé (grep vide, exact), mais le cache
**disque** de Coil 2.x ne sert qu'aux réponses **réseau** ; les fichiers locaux (`File`, `content://`)
sont décodés sans écriture disque. Il reste le cache **mémoire** (bitmaps jusqu'à la mort du
processus) — hors de portée d'une extraction de fichiers. **À confirmer par un `ls cacheDir` sur
appareil** après affichage d'une image : si un `image_cache/` existe, l'ajouter à
`purgeTransientCaches()` ([AutoLockObserver.kt:156-178](../../data/src/main/java/com/filestech/sms/security/AutoLockObserver.kt#L156)).

**C-04, C-05** — pic mémoire de la sauvegarde (borné par F32), taille de `ThreadViewModel`
(baselinée, connue). Pas de nouveauté.

**Note CI (passe D) — la mémoire est périmée, pas le dépôt** :
[android.yml:5](../../.github/workflows/android.yml#L5) déclenche sur `main, develop, 'fix/**',
'feat/**'` **et** sur les tags `v*`. Le `CLAUDE.md` global §9.4 affirme encore que `sms_tech` ne
couvre que `main, develop`. Corrigé dans `CLAUDE.md` à la suite de cet audit.

---

## E. Les 5 que je refuserais de laisser publier

1. **X-01** — une réponse depuis un groupe du coffre écrit hors du coffre. La promesse centrale,
   rompue par un usage ordinaire.
2. **B-1** — un MMS relancé part en SMS texte et peut s'afficher « envoyé ». Connu depuis la v1.3.9.
3. **D-01** — un interrupteur qui ne fait rien. F26, encore.
4. **D-02** — deux notificateurs sur trois ignorent `PreviewMode` sur l'écran verrouillé, dont un
   écrit ce soir.
5. Il n'y a pas de cinquième. B-2 est réel mais son déclencheur est rare et sa correction appartient
   à la factorisation déjà décidée ; le reste est de la dette tenue.

## F. Plan d'action

| Niveau | Quoi | Où | Appareil |
|---|---|---|---|
| **Sur la branche en cours** | D-02 : `visibilitePour()` dans la politique, appliqué aux deux notificateurs, test étendu | 2 fichiers + 1 test | non |
| **Décision produit** | D-01 : câbler (second canal) ou retirer le toggle | — | non |
| **Branche dédiée, avant l'audit suivant** | X-01 + A-01 ensemble, **dans** la factorisation de la boucle d'envoi et de son appelant (qui absorbe aussi B-2) | 3 use cases, `ScheduledSendAttempt`, `ConversationMirror`, politique de notification | **oui** |
| **Branche dédiée** | B-1 : garde de type immédiate ; vrai renvoi MMS avec B-3 | `ThreadViewModel`, `RetrySendUseCase`, `MmsSender` | **oui** |
| **Au passage** | A-03 (une ligne), D-03 (retrait), C-03 (déplacement) | — | non |
| **Mesure avant action** | C-01 (`ls cacheDir`), C-02 (profil 10 k) | — | **oui** |

## G. Ce que seul un appareil peut dire

- X-01 : le composeur est-il actif dans un fil de groupe du coffre, et la réponse crée-t-elle une
  1-à-1 visible ?
- B-1 : `divideMessage("")` — exception synchrone ou SMS vide, selon l'OEM ?
- B-2 : quelles causes réelles de rejet **synchrone** de `SmsManager` sur S9 (Android 10) et S24 ?
- C-01 : existe-t-il un `cacheDir/image_cache/` après affichage d'une image locale ?
- D-02 : rendu réel d'une notification `PRIVATE` sur écran verrouillé, avec et sans le réglage
  système « masquer le contenu sensible ».
- Et tout ce que le registre liste déjà : campagne instrumentée complète, MMS réels, PDF long, purge.

## H. Ce que cet audit aura appris sur la méthode

- **Vérifier la prémisse avant le constat.** A-01 était faux sur son hypothèse de départ et
  cachait X-01, plus grave. Un agent cite ce qu'il a lu ; il ne vérifie pas ce qu'il a supposé.
- **Un constat d'agent sur deux est juste, précis et incomplet** : B-2 sans la copie système, A-03
  sans `restore()`, C-01 sans le modèle de cache de Coil. La passe de vérification n'est pas un
  luxe, c'est la moitié du travail.
- **Le motif dominant s'est reproduit une quatrième fois dans du code écrit en le connaissant**
  (D-02, `OutgoingFailureNotifier`). Savoir le motif ne suffit pas ; il faut une liste des jumeaux à
  cocher à chaque nouveau notificateur — c'est ce que `CorrespondentVisibilityPolicy` devait être,
  et qu'elle n'est qu'à moitié.
