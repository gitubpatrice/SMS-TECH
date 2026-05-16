package com.filestech.sms.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "sms_tech_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toAppSettings()
            val next = transform(current)
            prefs.write(next)
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val p = this
        return AppSettings(
            appearance = Appearance(
                themeMode = enumOr(p, K.themeMode, ThemeMode.SYSTEM, ThemeMode::valueOf),
                dynamicColors = p[K.dynamicColors] ?: true,
                customAccentArgb = p[K.customAccentArgb],
                textScale = enumOr(p, K.textScale, TextScale.MEDIUM, TextScale::valueOf),
                density = enumOr(p, K.density, ListDensity.STANDARD, ListDensity::valueOf),
                amoledTrueBlack = p[K.amoled] ?: false,
            ),
            locale = LocaleSettings(
                languageTag = p[K.languageTag],
                firstDayOfWeek = enumOr(p, K.firstDayOfWeek, FirstDayOfWeek.SYSTEM, FirstDayOfWeek::valueOf),
            ),
            conversations = ConversationSettings(
                sortMode = enumOr(p, K.sortMode, SortMode.DATE, SortMode::valueOf),
                previewLines = p[K.previewLines] ?: 1,
                showAvatars = p[K.showAvatars] ?: true,
                groupArchived = p[K.groupArchived] ?: true,
                signature = p[K.signature],
            ),
            sending = SendingSettings(
                confirmBeforeBroadcast = p[K.confirmBroadcast] ?: true,
                convertToMmsAfterSegments = p[K.convertMmsAfter] ?: 3,
                mmsImageQuality = enumOr(p, K.mmsQuality, MmsImageQuality.BALANCED, MmsImageQuality::valueOf),
                deliveryReports = p[K.deliveryReports] ?: false,
                retryFailedAutomatically = p[K.retryFailed] ?: true,
                defaultSubId = p[K.defaultSubId],
                userMsisdn = p[K.userMsisdn],
            ),
            notifications = NotificationSettings(
                enabled = p[K.notifEnabled] ?: true,
                style = enumOr(p, K.notifStyle, NotificationStyle.HEADS_UP, NotificationStyle::valueOf),
                previewMode = enumOr(p, K.notifPreview, PreviewMode.ALWAYS, PreviewMode::valueOf),
                inlineReply = p[K.inlineReply] ?: true,
                defaultSoundUri = p[K.notifSoundUri],
                vibrate = p[K.notifVibrate] ?: false,
                vibratePattern = enumOr(p, K.notifVibPattern, VibratePattern.DEFAULT, VibratePattern::valueOf),
                ledColorArgb = p[K.notifLed],
                bubbles = p[K.notifBubbles] ?: false,
            ),
            security = SecuritySettings(
                lockMode = enumOr(p, K.lockMode, LockMode.OFF, LockMode::valueOf),
                autoLockDelay = enumOr(p, K.autoLockDelay, AutoLockDelay.ONE_MINUTE, AutoLockDelay::valueOf),
                flagSecure = p[K.flagSecure] ?: true,
                lockVaultOnLeave = p[K.lockVault] ?: true,
                panicCodeEnabled = p[K.panicCode] ?: false,
                autoDeleteOlderThanDays = p[K.autoDeleteDays],
            ),
            blocking = BlockingSettings(
                blockUnknown = p[K.blockUnknown] ?: false,
                blockShortCodes = p[K.blockShort] ?: false,
            ),
            backup = BackupSettings(
                autoBackup = enumOr(p, K.autoBackup, AutoBackupFrequency.OFF, AutoBackupFrequency::valueOf),
                destinationUri = p[K.backupUri],
                encrypt = p[K.backupEncrypt] ?: true,
                keepLast = p[K.backupKeep] ?: 5,
                format = enumOr(p, K.backupFormat, BackupFormat.SMSBK, BackupFormat::valueOf),
            ),
            advanced = AdvancedSettings(
                isDefaultSmsApp = p[K.isDefault] ?: false,
                mmsRoamingAutoDownload = p[K.mmsRoaming] ?: false,
                lastSyncedSmsId = p[K.lastSyncedSmsId] ?: 0L,
            ),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.themeMode] = s.appearance.themeMode.name
        this[K.dynamicColors] = s.appearance.dynamicColors
        s.appearance.customAccentArgb?.let { this[K.customAccentArgb] = it } ?: remove(K.customAccentArgb)
        this[K.textScale] = s.appearance.textScale.name
        this[K.density] = s.appearance.density.name
        this[K.amoled] = s.appearance.amoledTrueBlack

        s.locale.languageTag?.let { this[K.languageTag] = it } ?: remove(K.languageTag)
        this[K.firstDayOfWeek] = s.locale.firstDayOfWeek.name

        this[K.sortMode] = s.conversations.sortMode.name
        this[K.previewLines] = s.conversations.previewLines
        this[K.showAvatars] = s.conversations.showAvatars
        this[K.groupArchived] = s.conversations.groupArchived
        s.conversations.signature?.let { this[K.signature] = it } ?: remove(K.signature)

        this[K.confirmBroadcast] = s.sending.confirmBeforeBroadcast
        this[K.convertMmsAfter] = s.sending.convertToMmsAfterSegments
        this[K.mmsQuality] = s.sending.mmsImageQuality.name
        this[K.deliveryReports] = s.sending.deliveryReports
        this[K.retryFailed] = s.sending.retryFailedAutomatically
        s.sending.defaultSubId?.let { this[K.defaultSubId] = it } ?: remove(K.defaultSubId)
        s.sending.userMsisdn?.takeIf { it.isNotBlank() }?.let { this[K.userMsisdn] = it } ?: remove(K.userMsisdn)

        this[K.notifEnabled] = s.notifications.enabled
        this[K.notifStyle] = s.notifications.style.name
        this[K.notifPreview] = s.notifications.previewMode.name
        this[K.inlineReply] = s.notifications.inlineReply
        s.notifications.defaultSoundUri?.let { this[K.notifSoundUri] = it } ?: remove(K.notifSoundUri)
        this[K.notifVibrate] = s.notifications.vibrate
        this[K.notifVibPattern] = s.notifications.vibratePattern.name
        s.notifications.ledColorArgb?.let { this[K.notifLed] = it } ?: remove(K.notifLed)
        this[K.notifBubbles] = s.notifications.bubbles

        this[K.lockMode] = s.security.lockMode.name
        this[K.autoLockDelay] = s.security.autoLockDelay.name
        this[K.flagSecure] = s.security.flagSecure
        this[K.lockVault] = s.security.lockVaultOnLeave
        this[K.panicCode] = s.security.panicCodeEnabled
        s.security.autoDeleteOlderThanDays?.let { this[K.autoDeleteDays] = it } ?: remove(K.autoDeleteDays)

        this[K.blockUnknown] = s.blocking.blockUnknown
        this[K.blockShort] = s.blocking.blockShortCodes

        this[K.autoBackup] = s.backup.autoBackup.name
        s.backup.destinationUri?.let { this[K.backupUri] = it } ?: remove(K.backupUri)
        this[K.backupEncrypt] = s.backup.encrypt
        this[K.backupKeep] = s.backup.keepLast
        this[K.backupFormat] = s.backup.format.name

        this[K.isDefault] = s.advanced.isDefaultSmsApp
        this[K.mmsRoaming] = s.advanced.mmsRoamingAutoDownload
        this[K.lastSyncedSmsId] = s.advanced.lastSyncedSmsId
    }

    private inline fun <reified E : Enum<E>> enumOr(p: Preferences, key: Preferences.Key<String>, def: E, valueOf: (String) -> E): E =
        p[key]?.let { runCatching { valueOf(it) }.getOrNull() } ?: def

    private object K {
        val themeMode = stringPreferencesKey("appearance.themeMode")
        val dynamicColors = booleanPreferencesKey("appearance.dynamic")
        val customAccentArgb = intPreferencesKey("appearance.customAccent")
        val textScale = stringPreferencesKey("appearance.textScale")
        val density = stringPreferencesKey("appearance.density")
        val amoled = booleanPreferencesKey("appearance.amoled")
        val languageTag = stringPreferencesKey("locale.tag")
        val firstDayOfWeek = stringPreferencesKey("locale.firstDay")
        val sortMode = stringPreferencesKey("conv.sort")
        val previewLines = intPreferencesKey("conv.previewLines")
        val showAvatars = booleanPreferencesKey("conv.avatars")
        val groupArchived = booleanPreferencesKey("conv.groupArchived")
        val signature = stringPreferencesKey("conv.signature")
        val confirmBroadcast = booleanPreferencesKey("send.confirmBroadcast")
        val convertMmsAfter = intPreferencesKey("send.convertMmsAfter")
        val mmsQuality = stringPreferencesKey("send.mmsQuality")
        val deliveryReports = booleanPreferencesKey("send.delivery")
        val retryFailed = booleanPreferencesKey("send.retry")
        val defaultSubId = intPreferencesKey("send.subId")
        val userMsisdn = stringPreferencesKey("send.userMsisdn")
        val notifEnabled = booleanPreferencesKey("notif.enabled")
        val notifStyle = stringPreferencesKey("notif.style")
        val notifPreview = stringPreferencesKey("notif.preview")
        val inlineReply = booleanPreferencesKey("notif.inline")
        val notifSoundUri = stringPreferencesKey("notif.sound")
        val notifVibrate = booleanPreferencesKey("notif.vibrate")
        val notifVibPattern = stringPreferencesKey("notif.vibPattern")
        val notifLed = intPreferencesKey("notif.led")
        val notifBubbles = booleanPreferencesKey("notif.bubbles")
        val lockMode = stringPreferencesKey("security.lockMode")
        val autoLockDelay = stringPreferencesKey("security.autoLock")
        val flagSecure = booleanPreferencesKey("security.flagSecure")
        val lockVault = booleanPreferencesKey("security.lockVault")
        val panicCode = booleanPreferencesKey("security.panic")
        val autoDeleteDays = intPreferencesKey("security.autoDeleteDays")
        val blockUnknown = booleanPreferencesKey("block.unknown")
        val blockShort = booleanPreferencesKey("block.short")
        val autoBackup = stringPreferencesKey("backup.auto")
        val backupUri = stringPreferencesKey("backup.uri")
        val backupEncrypt = booleanPreferencesKey("backup.encrypt")
        val backupKeep = intPreferencesKey("backup.keep")
        val backupFormat = stringPreferencesKey("backup.format")
        val isDefault = booleanPreferencesKey("advanced.isDefault")
        val mmsRoaming = booleanPreferencesKey("advanced.mmsRoaming")
        // Bumped from a boolean ("didInitialSmsImport") to a long cursor: the latter encodes the
        // same first-run signal (0 vs > 0) AND tells the sync manager where to resume from.
        val lastSyncedSmsId = longPreferencesKey("advanced.lastSyncedSmsId")
    }
}
