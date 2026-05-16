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
)

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
    val autoDeleteOlderThanDays: Int? = null,
)

enum class LockMode { OFF, PIN, PATTERN, BIOMETRIC }
enum class AutoLockDelay { IMMEDIATE, FIFTEEN_SECONDS, ONE_MINUTE, FIVE_MINUTES, NEXT_LAUNCH }

data class BlockingSettings(
    val blockUnknown: Boolean = false,
    val blockShortCodes: Boolean = false,
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
)
