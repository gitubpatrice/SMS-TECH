package com.filestech.sms.data.local.datastore

/** User-facing immutable snapshot of all preferences. */
data class AppSettings(
    val appearance: Appearance = Appearance(),
    val locale: LocaleSettings = LocaleSettings(),
    val conversations: ConversationSettings = ConversationSettings(),
    val sending: SendingSettings = SendingSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val blocking: BlockingSettings = BlockingSettings(),
    val backup: BackupSettings = BackupSettings(),
    val advanced: AdvancedSettings = AdvancedSettings(),
)

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColors: Boolean = true,
    val customAccentArgb: Int? = null,
    val textScale: TextScale = TextScale.MEDIUM,
    val density: ListDensity = ListDensity.STANDARD,
    val amoledTrueBlack: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK, DARK_TECH }
enum class TextScale { XS, S, MEDIUM, L, XL }
enum class ListDensity { COMFORT, STANDARD, COMPACT }

data class LocaleSettings(
    /** null = follow system. ISO-639-1 tag otherwise. */
    val languageTag: String? = null,
    val firstDayOfWeek: FirstDayOfWeek = FirstDayOfWeek.SYSTEM,
)

enum class FirstDayOfWeek { SYSTEM, MONDAY, SUNDAY }

data class ConversationSettings(
    val sortMode: SortMode = SortMode.DATE,
    val previewLines: Int = 1,
    val showAvatars: Boolean = true,
    val groupArchived: Boolean = true,
    val signature: String? = null,
)

enum class SortMode { DATE, UNREAD_FIRST, PINNED_FIRST }

data class SendingSettings(
    val confirmBeforeBroadcast: Boolean = true,
    val convertToMmsAfterSegments: Int = 3,
    val mmsImageQuality: MmsImageQuality = MmsImageQuality.BALANCED,
    val deliveryReports: Boolean = false,
    val retryFailedAutomatically: Boolean = true,
    val defaultSubId: Int? = null,
    /**
     * v1.2.6 audit F4 — MSISDN saisi par l'utilisateur quand la détection automatique via
     * `SubscriptionManager.getActiveSubscriptionInfoForSubscriptionId(subId).number` retourne
     * `null` (Free Mobile FR, MVNO, certaines configs Samsung One UI). Utilisé par
     * `MmsSentReceiver.finalizeFromAddress` pour remplacer le placeholder `"insert-address-token"`
     * de la row FROM dans `content://mms` après dispatch réussi.
     *
     * `null` = pas configuré ; on tente la détection auto puis on laisse le placeholder.
     */
    val userMsisdn: String? = null,
    /**
     * v1.3.1 — quand `true` (défaut), poser une réaction emoji sur un message reçu envoie
     * automatiquement un SMS au correspondant contenant uniquement l'emoji (ex. "❤️"), afin
     * que le correspondant puisse savoir que vous avez réagi. Quand `false`, la réaction
     * reste strictement locale (badge visible uniquement dans SMS Tech).
     *
     * Le **changement** d'une réaction (A → B) et le **retrait** ne génèrent jamais d'envoi,
     * pour éviter le spam SMS si l'utilisateur hésite.
     */
    val sendReactionsToRecipient: Boolean = true,
    /**
     * v1.3.1 — `false` tant que l'utilisateur n'a pas validé le dialog de confirmation
     * du premier envoi (avec case "Ne plus demander"). Tant que `false`, chaque premier
     * envoi de réaction ouvre le dialog ; quand `true`, l'envoi est silencieux et direct.
     */
    val reactionConfirmDismissed: Boolean = false,
    /**
     * v1.3.6 (legacy) — flag boolean conservé pour rétro-compat DataStore. Supplanté
     * en v1.8.0 par [reactionFormat] qui propose 3 options. Migration côté
     * [com.filestech.sms.data.local.datastore.SettingsRepository] : pour un user
     * existant, `reactionEmojiOnly=true` → `reactionFormat=EMOJI_ONLY`, `false` →
     * `reactionFormat=TAPBACK_EN` (l'ancien défaut). Pour les nouveaux users, le
     * défaut est désormais `READABLE_FR`. La valeur reste écrite à chaque persist
     * pour ne pas perdre la rétro-compat si un downgrade vers v1.7.x se produit.
     */
    val reactionEmojiOnly: Boolean = false,
    /**
     * v1.8.0 (bug 5 fix) — format du SMS envoyé au correspondant lors d'une réaction
     * emoji. Quatre options exposées dans Settings → Envoi → "Format des réactions" :
     *
     *   - [ReactionFormat.EMOJI_WITH_QUOTE] (défaut v1.14.4) : `"❤️ «aperçu»"`.
     *     Compact + contexte préservé. Le destinataire voit l'emoji ET le
     *     bout de message d'origine. Pas de phrase parasite. Pattern proche
     *     de l'expérience Tapback mais plus court et indépendant de la langue.
     *   - [ReactionFormat.READABLE_FR] : `"J'ai réagi par ❤️ à : «aperçu»"`.
     *     Format français lisible que tout destinataire Android francophone
     *     comprend immédiatement. Décodé par SMS Tech (regex FR) pour afficher
     *     la bulle de réaction native. Default v1.8.0–v1.14.3.
     *   - [ReactionFormat.TAPBACK_EN] : `"Reacted ❤️ to «aperçu»"`. Format
     *     Apple/Google Tapback détecté nativement par iMessage (iPhone) et
     *     Google Messages récent qui l'affichent comme une bulle réaction
     *     visuelle attachée au message d'origine. Conserve la compat iPhone.
     *   - [ReactionFormat.EMOJI_ONLY] : `"❤️"` seul. Plus propre sur apps SMS
     *     legacy mais le destinataire perd tout contexte du message d'origine.
     *
     * v1.14.4 — défaut changé `READABLE_FR` → `EMOJI_WITH_QUOTE` (demande user).
     * Les users existants conservent leur choix (DataStore persiste). Seuls les
     * NOUVEAUX installs voient le nouveau défaut.
     */
    val reactionFormat: ReactionFormat = ReactionFormat.EMOJI_WITH_QUOTE,
    /**
     * v1.8.1 — nom personnel à inclure dans les SMS de réaction sortants
     * (format `"<Nom> a réagi par ❤️ à votre message : «…»"`). Pour aider
     * le destinataire à identifier l'auteur de la réaction de manière
     * explicite, en plus du nom de contact que son app SMS affiche déjà.
     *
     *  - `null` ou chaîne vide → résolution automatique via
     *    `ContactsContract.Profile` (le "moi" Android, si l'utilisateur a
     *    renseigné son profil dans Contacts). Si Profile est absent / vide,
     *    fallback au format anonyme `"Réagi par ❤️ à votre message : «…»"`.
     *  - chaîne non-vide → override explicite, utilisé tel quel (priorité
     *    sur Profile).
     *
     *  Saisissable depuis Settings → Envoi → "Mon nom dans les réactions".
     *  Cappé à 40 caractères au save pour rester dans 1 segment UCS-2 même
     *  avec un emoji multi-codepoint et un preview de message d'origine.
     */
    val senderDisplayName: String? = null,
    /**
     * v1.21.0 — indicatif pays par défaut (ISO 3166-1 alpha-2, ex. `"FR"`, `"LU"`) utilisé
     * pour convertir les numéros **nationaux** (`06…`) au format international E.164 **avant
     * l'envoi**, via [com.filestech.sms.data.sms.PhoneNumberWireFormatter].
     *
     *  - `null` / vide = **Auto** : on utilise le pays de la SIM (comportement par défaut).
     *  - valeur explicite = **override** : indispensable quand la SIM est étrangère mais que
     *    les contacts sont enregistrés en national d'un autre pays. Ex. amie avec une SIM 🇱🇺
     *    qui écrit à des contacts français `06…` : régler `FR` fait envoyer `+33…` et les SMS
     *    arrivent enfin. Sans override, `06…` sur SIM 🇱🇺 est ininterprétable → non routable.
     *
     * N'affecte QUE l'adresse sur le fil ; le stockage, le fil de discussion et l'affichage
     * gardent la forme brute saisie par l'utilisateur.
     */
    val defaultRegionIso: String? = null,
)

/**
 * v1.8.0 (bug 5 fix) — format du SMS de réaction envoyé au correspondant.
 *
 * Pour le décodage entrant (réception d'une réaction de l'autre côté), voir
 * [com.filestech.sms.domain.reaction.IncomingReactionDecoder] qui supporte les
 * 3 formats en parallèle (rétro-compatibilité totale avec les v1.7.x et avec
 * les Tapbacks iMessage entrants).
 */
/**
 * v1.8.0 (bug 5 fix) — format du SMS de réaction envoyé au correspondant.
 *  - READABLE_FR : "Réagi par ❤️ à votre message : «…»"
 *  - TAPBACK_EN  : "Reacted ❤️ to «…»" (Tapback iMessage / Google Messages récent)
 *  - EMOJI_ONLY  : "❤️" seul (compact, perd le contexte)
 *  - **v1.9.0** EMOJI_WITH_QUOTE : "❤️ «aperçu du message»" (compact + contexte
 *    visuel ; pas de mot "Réagi", l'emoji + citation typographique parlent
 *    d'elles-mêmes côté destinataire)
 */
enum class ReactionFormat { READABLE_FR, TAPBACK_EN, EMOJI_ONLY, EMOJI_WITH_QUOTE }

enum class MmsImageQuality { HIGH, BALANCED, ECONOMY }

data class NotificationSettings(
    val enabled: Boolean = true,
    val style: NotificationStyle = NotificationStyle.HEADS_UP,
    val previewMode: PreviewMode = PreviewMode.ALWAYS,
    val inlineReply: Boolean = true,
    val defaultSoundUri: String? = null,
    // Default to false: most users on Samsung One UI already get a system-level haptic for the
    // notification sound itself, and a second buzz on top is intrusive. The user can re-enable
    // it in Settings → Notifications.
    val vibrate: Boolean = false,
    val vibratePattern: VibratePattern = VibratePattern.DEFAULT,
    val ledColorArgb: Int? = 0xFF2460AB.toInt(),
    val bubbles: Boolean = false,
)

enum class NotificationStyle { BANNER, HEADS_UP, SILENT }
enum class PreviewMode { ALWAYS, WHEN_UNLOCKED, NEVER }
enum class VibratePattern { DEFAULT, SHORT, LONG, NONE }

data class SecuritySettings(
    val lockMode: LockMode = LockMode.OFF,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.ONE_MINUTE,
    val flagSecure: Boolean = true,
    val lockVaultOnLeave: Boolean = true,
    val panicCodeEnabled: Boolean = false,
    /**
     * v1.9.0 — config du Safety call (opt-in, désactivé par défaut).
     * Voir [com.filestech.sms.domain.safetycall.SafetyCallConfig] pour le
     * détail des champs et de la sémantique. Stocké dans DataStore via 5
     * clés indépendantes (cf. K.safetyCall*) plutôt qu'un blob JSON sérialisé,
     * pour rester lisible/debugable et permettre des migrations partielles
     * sans schéma. La liste [SafetyCallContact] est stockée en format
     * pipe-separated via [SafetyCallContactCodec] (seule structure complexe).
     */
    val safetyCall: com.filestech.sms.domain.safetycall.SafetyCallConfig =
        com.filestech.sms.domain.safetycall.SafetyCallConfig(),
    /**
     * v1.10.0 — config du Mode urgence (opt-in, désactivé par défaut).
     * Voir [com.filestech.sms.domain.emergency.EmergencyConfig]. Réutilise
     * les contacts de [safetyCall] (pas de liste séparée). Stocké dans
     * DataStore via 4 clés flat (cf. K.emergency*).
     */
    val emergency: com.filestech.sms.domain.emergency.EmergencyConfig =
        com.filestech.sms.domain.emergency.EmergencyConfig(),
    /**
     * v1.12.0 — Raccourci urgence en notification persistante. Quand `true`,
     * SMS Tech pose une notif `IMPORTANCE_LOW` (silencieuse, persistante)
     * visible sur l'écran verrouillé avec 2 actions :
     *  - "URGENCE" → déclenche [TriggerEmergencyUseCase] directement
     *  - "112" → ouvre le composeur téléphone pré-rempli (ACTION_DIAL)
     *
     * Désactivé par défaut. Opt-in via Settings → Mode urgence. Nécessite
     * `emergency.enabled = true` pour avoir un sens (sinon le bouton URGENCE
     * tape dans le vide). Le canal `CHANNEL_EMERGENCY_SHORTCUT` est posé
     * avec `VISIBILITY_PUBLIC` pour l'affichage lock-screen.
     */
    val emergencyShortcutEnabled: Boolean = false,
    /**
     * v1.12.0 — Bouton "Appeler 17 (Police FR)" dans EmergencyScreen ET
     * action 17 dans la notification raccourci lock-screen. Opt-in spécifique
     * France — désactivé par défaut car le 112 (SOS européen) couvre déjà
     * police + SAMU + pompiers pour tous les pays UE. Activer 17 = accès
     * direct à la police nationale FR depuis l'écran verrouillé.
     */
    val emergencyCallPoliceEnabled: Boolean = false,
    /**
     * v1.13.0 — PIN distinct pour le coffre (second-factor). Quand ON, l'entrée
     * dans `VaultScreen` est gated derrière la saisie d'un PIN dédié (hash
     * séparé du PIN d'app), même si l'app principale est déjà déverrouillée.
     * Pratique pour ceux qui partagent leur PIN d'app à un proche mais veulent
     * garder le coffre privé.
     *
     * Default `false` : opt-in pour ne pas changer la UX existante. Le hash
     * vit dans [com.filestech.sms.data.local.datastore.SecurityStore], cette
     * clé n'est que le flag déclaratif "feature active oui/non".
     */
    val vaultPinEnabled: Boolean = false,
    /**
     * v1.14.0 — comportement des boutons 112 / 17 (EmergencyScreen + notif
     * lock-screen raccourci). Deux niveaux :
     *   - [EmergencyCallBehavior.DIALER_ONLY] (DEFAULT) : ACTION_DIAL ouvre le
     *     composeur pré-rempli, l'user confirme l'appel manuellement.
     *     Aucune permission, zéro risque de pocket-dial.
     *   - [EmergencyCallBehavior.HOLD_3S_DIRECT_CALL] : maintenir le bouton
     *     3 secondes déclenche un appel direct via CALL_PHONE permission.
     *     Pour situations vraiment critiques où chaque seconde compte.
     *     Anneau de progression visible (anti-déclenchement accidentel).
     *
     * Niveau 2 (tap unique → call direct) volontairement non implémenté
     * v1.14.0 : risque pocket-dial trop élevé pour gain marginal.
     */
    val emergencyCallBehavior: EmergencyCallBehavior = EmergencyCallBehavior.DIALER_ONLY,
    /**
     * v1.14.0 — opt-in du SMS "Je vais bien" envoyé aux contacts SafetyCall
     * quand l'user appuie sur le bouton kill-switch. Default `true` (assume
     * que l'user veut rassurer ses contacts), désactivable.
     */
    val sendIAmOkSmsOnReset: Boolean = true,
    /**
     * v1.11.0 — Sujet 3 anti-smishing : active la détection locale d'arnaque
     * (URL raccourcies, mots d'urgence, numéros premium, typosquatting) et
     * l'affichage d'un bandeau rouge "⚠️ Possiblement frauduleux" sous les
     * messages reçus suspects. Opt-in par défaut (valeur ajoutée significative
     * face à la vague de smishing FR 2024-2025), désactivable dans Settings →
     * Sécurité si l'user trouve les faux positifs gênants.
     */
    val smishingDetectionEnabled: Boolean = true,
    /**
     * Profondeur de l'auto-nettoyage : NULL ou 0 = désactivé, sinon nombre de jours
     * au-delà duquel les messages sont effacés. L'auto-nettoyage tourne au plus une fois
     * par mois (voir [lastAutoPurgeAt]) ; le bouton "Effacer maintenant" du dialog
     * applique la même profondeur sans toucher au cycle auto.
     */
    val autoDeleteOlderThanDays: Int? = null,
    /**
     * Timestamp epoch ms du dernier passage de l'auto-nettoyage (manuel ou auto). NULL =
     * jamais. Le worker compare `now - lastAutoPurgeAt >= AUTO_PURGE_INTERVAL_MS`
     * (30 jours) avant de re-purger, pour éviter le spam toutes les 12 h.
     */
    val lastAutoPurgeAt: Long? = null,
)

enum class LockMode { OFF, PIN, PATTERN, BIOMETRIC }
enum class AutoLockDelay { IMMEDIATE, FIFTEEN_SECONDS, ONE_MINUTE, FIVE_MINUTES, NEXT_LAUNCH }

/**
 * v1.14.0 — comportement des boutons 112 / 17 dans EmergencyScreen.
 * Voir [SecuritySettings.emergencyCallBehavior] pour le KDoc.
 */
enum class EmergencyCallBehavior { DIALER_ONLY, HOLD_3S_DIRECT_CALL }

data class BlockingSettings(
    val blockUnknown: Boolean = false,
    // v1.3.5 G6 audit fix — `blockShortCodes` retiré (champ fantôme : aucun caller
    // ne le lisait pour filtrer les short codes côté SmsDeliverReceiver, et l'UI
    // ne l'exposait pas. Si la feature est re-demandée plus tard, ré-implémenter
    // proprement via une colonne `BlockedNumberEntity.scope` plutôt qu'un boolean
    // global. La clé DataStore reste lue pour migration (sera supprimée v1.3.6+).
)

data class BackupSettings(
    val autoBackup: AutoBackupFrequency = AutoBackupFrequency.OFF,
    val destinationUri: String? = null,
    val encrypt: Boolean = true,
    val keepLast: Int = 5,
    val format: BackupFormat = BackupFormat.SMSBK,
)

enum class AutoBackupFrequency { OFF, DAILY, WEEKLY }
enum class BackupFormat { SMSBK, XML_COMPAT }

data class AdvancedSettings(
    val isDefaultSmsApp: Boolean = false,
    val mmsRoamingAutoDownload: Boolean = false,
    /**
     * Highest `Telephony.Sms._ID` we have already mirrored into our Room DB. Maintained by the
     * [com.filestech.sms.data.sync.TelephonySyncManager] — it queries `content://sms` with
     * `_ID > lastSyncedSmsId` to read only new rows, which keeps every open instant (no full
     * scan after the first run). `0L` means "never synced yet", so the first sync after a
     * fresh install copies everything.
     */
    val lastSyncedSmsId: Long = 0L,
    /**
     * v1.3.7 — `true` une fois que le splash de présentation a été vu (à la première
     * ouverture après install). Tant que `false`, l'ouverture de l'app passe par
     * [com.filestech.sms.ui.screens.splash.SplashScreen] (logo scale+fade + tagline,
     * 3 s ou tap-to-skip), puis le flag est persisté et l'utilisateur n'est plus jamais
     * redirigé vers ce splash, y compris après une mise à jour. `Effacer données` depuis
     * les paramètres système ré-affiche le splash (comportement attendu : nouvelle "1ère
     * ouverture" du point de vue de l'utilisateur).
     */
    val splashShown: Boolean = false,
    /**
     * v1.8.0 (post-audit fix unread badges) — migration one-shot pour purger
     * l'état legacy v1.7.1 : au 1er cold-start v1.8.0, on RESET tous les
     * `conversations.unread_count` à 0 ET on marque tous les `messages.read`
     * INCOMING à 1 (pour aligner Room sur la réalité — le user a très
     * probablement déjà lu ces messages dans une autre app ou les considère
     * comme lus). Sans cette purge, les badges hérités de v1.7.1 (compteurs
     * inflated + flag `read=0` désynchronisé du système) persistent
     * indéfiniment et ne se cleanent jamais — le simple recompute SQL ne
     * suffit pas car il s'appuie sur `messages.read` qui est lui-même
     * désynchronisé.
     *
     * `true` une fois la migration faite. Les futurs SMS live arrivent
     * ensuite via `SmsDeliverReceiver` avec `read=false` + `unread_count++`
     * normalement — comportement préservé pour la vraie nouveauté.
     */
    val unreadResetV180: Boolean = false,
    /**
     * v1.22.x — flag de complétion de la dédup des conversations du même numéro
     * ([com.filestech.sms.data.repository.ConversationMirror.dedupeSameNumberConversations]).
     * Posé à `true` une fois la base propre (aucun doublon restant) pour ne plus re-scanner à
     * chaque cold-start. Même pattern que `unreadResetV180`.
     */
    val dedupSameNumberV1230: Boolean = false,
    /**
     * v1.14.7 — flag de migration one-shot pour rapatrier les attachments MMS reçus
     * de `cacheDir/mms_incoming/` (volatile, peut être purgé par Android Storage
     * Manager ou par "Effacer le cache" Réglages → Apps) vers
     * `filesDir/mms_attachments/` (persistant, ne disparaît qu'avec un wipe app).
     * Avant ce fix, les MMS audio/image/vidéo reçus disparaissaient silencieusement
     * quand Android nettoyait le cache, laissant des `AttachmentEntity.localUri`
     * Room pointant vers des fichiers absents → bulle audio cassée affichant le
     * nom de fichier en fallback. Migration cold-start idempotente.
     */
    val attachmentsMovedToFilesDirV147: Boolean = false,
    /**
     * v1.24.0 — court-circuit global des migrations one-shot qui ouvrent la base
     * ([com.filestech.sms.system.startup.StartupMigrations]).
     *
     * Chaque migration garde son propre flag comme **source de vérité** ([unreadResetV180],
     * [attachmentsMovedToFilesDirV147], [dedupSameNumberV1230]). Ce flag n'est qu'une optimisation :
     * il est posé UNIQUEMENT lorsque les trois sont déjà à `true`, et il permet alors au cold-start
     * suivant de sortir sans ouvrir SQLCipher du tout — le vrai gain, puisqu'une install à jour ne
     * fait plus aucun travail de migration. Sûr par construction : il ne peut jamais sauter une
     * migration dont le flag individuel n'est pas encore posé.
     */
    val startupDbMigrationsDone: Boolean = false,
    /**
     * v1.3.10 — **opt-in** : démarre [com.filestech.sms.system.service.KeepAliveService]
     * qui maintient le processus SMS Tech vivant via une notification persistante
     * discrète (canal `IMPORTANCE_MIN`, ne fait ni son ni vibration). Indispensable pour
     * que SMS Tech reçoive correctement les SMS / MMS / notifications sur :
     *
     *   - **Xiaomi MIUI / HyperOS 2024+** (Redmi 9A Android 10 Go, Poco F5 HyperOS,
     *     Redmi Note 14 Pro 5G `2410FPCC5G`…)
     *   - **Huawei EMUI / HarmonyOS** (sans Google Mobile Services)
     *   - **Honor MagicOS** (descendant EMUI)
     *   - Certains **OnePlus** anciens (OxygenOS < 12) et **realme realmeUI**
     *
     * Ces ROMs whitelistent uniquement les apps SMS du constructeur ; toute app SMS
     * tierce (SMS Tech, QKSMS, Signal, Silence, etc.) est silencieusement killée en
     * background, ce qui empêche la réception MMS et l'affichage des notifications.
     *
     * Sur ROMs non-agressives (Pixel Android stock, Samsung One UI, Sony Xperia,
     * Fairphone, Nothing OS, OnePlus récent OxygenOS, Motorola), ce mode est INUTILE
     * — la notification persistante encombre le shade pour rien. Laisser `false` par
     * défaut respecte les utilisateurs sur des ROMs propres.
     *
     * **Trade-off** : ~5-10 Mo RAM permanents (processus gardé en heap), batterie
     * négligeable (service idle). La notif persistante est masquable depuis les
     * réglages OS si l'utilisateur la trouve gênante.
     */
    val keepAliveService: Boolean = false,
)
