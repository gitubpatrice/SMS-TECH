package com.filestech.sms.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "sms_tech_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toAppSettings() }

    /**
     * v1.6.1 (audit PERF-01 / PERF-11) — snapshot chaud partagé via [StateFlow]. Tous
     * les call sites qui n'ont besoin que de la valeur courante des settings (notif
     * incoming, dispatch SMS, worker auto-purge) devraient lire `state.value` au lieu
     * de `flow.first()` qui ouvre/lit/ferme le fichier DataStore à chaque appel
     * (~5-10 ms × N call sites).
     *
     * `SharingStarted.Eagerly` car on est `@Singleton` scoped à l'app : on garde un
     * unique collect actif pour toute la durée de vie du processus, ce qui est exactement
     * le comportement attendu (les settings sont consultés en permanence par les
     * receivers, workers, viewmodels). Le seul coût est la première hydration au boot.
     */
    val state: StateFlow<AppSettings> = flow.stateIn(
        appScope, SharingStarted.Eagerly, AppSettings(),
    )

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
                sendReactionsToRecipient = p[K.sendReactionsToRecipient] ?: true,
                reactionConfirmDismissed = p[K.reactionConfirmDismissed] ?: false,
                reactionEmojiOnly = p[K.reactionEmojiOnly] ?: false,
                // v1.8.0 (bug 5 fix) — migration douce. Si la nouvelle clé existe,
                // on l'utilise. Sinon (user qui upgrade depuis v1.7.x) :
                //  - reactionEmojiOnly=true → EMOJI_ONLY (préserve son choix)
                //  - reactionEmojiOnly=false → TAPBACK_EN (préserve l'ancien défaut
                //    "Reacted X to «…»" — l'user avait peut-être beaucoup de contacts
                //    iPhone et compte sur le parsing Tapback)
                //  - aucune clé présente (fresh install) → READABLE_FR (nouveau défaut)
                reactionFormat = p[K.reactionFormat]?.let {
                    runCatching { ReactionFormat.valueOf(it) }.getOrNull()
                } ?: when {
                    p[K.reactionEmojiOnly] == true -> ReactionFormat.EMOJI_ONLY
                    p[K.reactionEmojiOnly] == false -> ReactionFormat.TAPBACK_EN
                    else -> ReactionFormat.READABLE_FR
                },
                senderDisplayName = p[K.senderDisplayName]?.takeIf { it.isNotBlank() },
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
                lastAutoPurgeAt = p[K.lastAutoPurgeAt],
                safetyCall = com.filestech.sms.domain.safetycall.SafetyCallConfig(
                    enabled = p[K.safetyCallEnabled] ?: false,
                    timeoutMs = p[K.safetyCallTimeoutMs]
                        ?: com.filestech.sms.domain.safetycall.SafetyCallConfig.TIMEOUT_48H_MS,
                    lastActivityAt = p[K.safetyCallLastActivityAt] ?: 0L,
                    // v1.10.0 SEC-11 — défaut 0L si absent (config v1.9.0
                    // héritée) → isExpired retournera false jusqu'au premier
                    // reset (cf. KDoc SafetyCallConfig.monotonicLastActivityAt).
                    monotonicLastActivityAt = p[K.safetyCallMonotonicLastActivityAt] ?: 0L,
                    contacts = SafetyCallContactCodec.decode(p[K.safetyCallContactsJson]),
                    template = enumOr(
                        p,
                        K.safetyCallTemplate,
                        com.filestech.sms.domain.safetycall.SafetyCallTemplate.CHECK_IN,
                        com.filestech.sms.domain.safetycall.SafetyCallTemplate::valueOf,
                    ),
                    customMessage = p[K.safetyCallCustomMessage].orEmpty(),
                ),
                // v1.10.0 — Mode urgence.
                emergency = com.filestech.sms.domain.emergency.EmergencyConfig(
                    enabled = p[K.emergencyEnabled] ?: false,
                    template = enumOr(
                        p,
                        K.emergencyTemplate,
                        com.filestech.sms.domain.emergency.EmergencyTemplate.NEED_HELP,
                        com.filestech.sms.domain.emergency.EmergencyTemplate::valueOf,
                    ),
                    includeLocation = p[K.emergencyIncludeLocation] ?: true,
                    lastTriggeredAt = p[K.emergencyLastTriggeredAt] ?: 0L,
                    monotonicLastTriggeredAt = p[K.emergencyMonotonicLastTriggeredAt] ?: 0L,
                ),
                // v1.11.0 — Sujet 3 anti-smishing. Défaut `true` (opt-in
                // sécurité, désactivable par l'user dans Settings).
                smishingDetectionEnabled = p[K.smishingDetectionEnabled] ?: true,
                // v1.12.0 — raccourci urgence. Défaut false (opt-in strict).
                emergencyShortcutEnabled = p[K.emergencyShortcutEnabled] ?: false,
                emergencyCallPoliceEnabled = p[K.emergencyCallPoliceEnabled] ?: false,
                // v1.13.0 — PIN distinct coffre. Défaut false (opt-in strict).
                vaultPinEnabled = p[K.vaultPinEnabled] ?: false,
                // v1.14.0 — comportement boutons 112/17. Défaut DIALER_ONLY
                // (zero-risk pocket-dial, behavior v1.12 préservé).
                emergencyCallBehavior = p[K.emergencyCallBehavior]
                    ?.let { runCatching { com.filestech.sms.data.local.datastore.EmergencyCallBehavior.valueOf(it) }.getOrNull() }
                    ?: com.filestech.sms.data.local.datastore.EmergencyCallBehavior.DIALER_ONLY,
                // v1.14.0 — SMS "Je vais bien" sur kill-switch. Default true.
                sendIAmOkSmsOnReset = p[K.sendIAmOkSmsOnReset] ?: true,
            ),
            blocking = BlockingSettings(
                blockUnknown = p[K.blockUnknown] ?: false,
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
                splashShown = p[K.splashShown] ?: false,
                keepAliveService = p[K.keepAliveService] ?: false,
                unreadResetV180 = p[K.unreadResetV180] ?: false,
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
        this[K.sendReactionsToRecipient] = s.sending.sendReactionsToRecipient
        this[K.reactionConfirmDismissed] = s.sending.reactionConfirmDismissed
        this[K.reactionEmojiOnly] = s.sending.reactionEmojiOnly
        // v1.8.0 (bug 5 fix) — persiste le nouveau format. La clé legacy
        // `reactionEmojiOnly` continue à être écrite au-dessus pour ne pas
        // casser un éventuel downgrade vers v1.7.x.
        this[K.reactionFormat] = s.sending.reactionFormat.name
        s.sending.senderDisplayName?.takeIf { it.isNotBlank() }?.let { this[K.senderDisplayName] = it }
            ?: remove(K.senderDisplayName)

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
        s.security.lastAutoPurgeAt?.let { this[K.lastAutoPurgeAt] = it } ?: remove(K.lastAutoPurgeAt)
        // v1.9.0 — Safety call. La config est éclatée en 5 clés flat (cf.
        // doc [SecuritySettings.safetyCall]). Seuls [contacts] passent par un
        // codec pipe-separated, le reste est trivialement scalaire.
        this[K.safetyCallEnabled] = s.security.safetyCall.enabled
        this[K.safetyCallTimeoutMs] = s.security.safetyCall.timeoutMs
        this[K.safetyCallLastActivityAt] = s.security.safetyCall.lastActivityAt
        this[K.safetyCallMonotonicLastActivityAt] = s.security.safetyCall.monotonicLastActivityAt
        this[K.safetyCallContactsJson] = SafetyCallContactCodec.encode(s.security.safetyCall.contacts)
        this[K.safetyCallTemplate] = s.security.safetyCall.template.name
        this[K.safetyCallCustomMessage] = s.security.safetyCall.customMessage
        // v1.10.0 — Mode urgence.
        this[K.emergencyEnabled] = s.security.emergency.enabled
        this[K.emergencyTemplate] = s.security.emergency.template.name
        this[K.emergencyIncludeLocation] = s.security.emergency.includeLocation
        this[K.emergencyLastTriggeredAt] = s.security.emergency.lastTriggeredAt
        this[K.emergencyMonotonicLastTriggeredAt] = s.security.emergency.monotonicLastTriggeredAt
        // v1.11.0 — Sujet 3 anti-smishing.
        this[K.smishingDetectionEnabled] = s.security.smishingDetectionEnabled
        // v1.12.0 — raccourci urgence.
        this[K.emergencyShortcutEnabled] = s.security.emergencyShortcutEnabled
        this[K.emergencyCallPoliceEnabled] = s.security.emergencyCallPoliceEnabled
        // v1.13.0 — PIN distinct coffre.
        this[K.vaultPinEnabled] = s.security.vaultPinEnabled
        // v1.14.0 — call behavior 112/17.
        this[K.emergencyCallBehavior] = s.security.emergencyCallBehavior.name
        // v1.14.0 — SMS "Je vais bien" opt-in.
        this[K.sendIAmOkSmsOnReset] = s.security.sendIAmOkSmsOnReset

        this[K.blockUnknown] = s.blocking.blockUnknown
        // v1.3.5 G6 + audit F3 — `blockShortCodes` retiré (champ fantôme, voir
        // [BlockingSettings]). On purge ACTIVEMENT la clé orpheline pour ne pas
        // laisser la valeur user persister à jamais sur disque sans consommateur.
        // Le `remove` est idempotent ; appelé à chaque write c'est négligeable.
        remove(K.blockShort)

        this[K.autoBackup] = s.backup.autoBackup.name
        s.backup.destinationUri?.let { this[K.backupUri] = it } ?: remove(K.backupUri)
        this[K.backupEncrypt] = s.backup.encrypt
        this[K.backupKeep] = s.backup.keepLast
        this[K.backupFormat] = s.backup.format.name

        this[K.isDefault] = s.advanced.isDefaultSmsApp
        this[K.mmsRoaming] = s.advanced.mmsRoamingAutoDownload
        this[K.lastSyncedSmsId] = s.advanced.lastSyncedSmsId
        this[K.splashShown] = s.advanced.splashShown
        this[K.keepAliveService] = s.advanced.keepAliveService
        this[K.unreadResetV180] = s.advanced.unreadResetV180
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
        val sendReactionsToRecipient = booleanPreferencesKey("send.reactions.toRecipient")
        val reactionConfirmDismissed = booleanPreferencesKey("send.reactions.confirmDismissed")
        val reactionEmojiOnly = booleanPreferencesKey("send.reactions.emojiOnly")
        // v1.8.0 (bug 5 fix) — nouveau format avec 3 valeurs (READABLE_FR / TAPBACK_EN
        // / EMOJI_ONLY). La clé `reactionEmojiOnly` ci-dessus reste écrite pour la
        // rétro-compat v1.7.x si un downgrade se produit.
        val reactionFormat = stringPreferencesKey("send.reactions.format")
        // v1.8.1 — override personnel du nom inclus dans les SMS de réaction
        // sortants. `null` = résolution auto via `ContactsContract.Profile`.
        val senderDisplayName = stringPreferencesKey("send.senderDisplayName")

        // v1.9.0 — Safety call (opt-in, désactivé par défaut). 6 clés flat
        // pour rester lisible/debugable, la liste des contacts est sérialisée
        // en format pipe-separated via SafetyCallContactCodec. Le suffixe
        // `Json` dans la clé est conservé pour rétro-compatibilité de stockage.
        val safetyCallEnabled = booleanPreferencesKey("security.safetyCall.enabled")
        val safetyCallTimeoutMs = longPreferencesKey("security.safetyCall.timeoutMs")
        val safetyCallLastActivityAt = longPreferencesKey("security.safetyCall.lastActivityAt")
        // v1.10.0 SEC-11 — snapshot monotonic du dernier reset, anti clock-forward.
        val safetyCallMonotonicLastActivityAt =
            longPreferencesKey("security.safetyCall.monotonicLastActivityAt")
        val safetyCallContactsJson = stringPreferencesKey("security.safetyCall.contactsJson")
        val safetyCallTemplate = stringPreferencesKey("security.safetyCall.template")
        val safetyCallCustomMessage = stringPreferencesKey("security.safetyCall.customMessage")
        // v1.10.0 — Mode urgence (réutilise les contacts Safety call).
        val emergencyEnabled = booleanPreferencesKey("security.emergency.enabled")
        val emergencyTemplate = stringPreferencesKey("security.emergency.template")
        val emergencyIncludeLocation = booleanPreferencesKey("security.emergency.includeLocation")
        val emergencyLastTriggeredAt = longPreferencesKey("security.emergency.lastTriggeredAt")
        // v1.10.0 audit S2 — snapshot monotonic du dernier trigger urgence.
        val emergencyMonotonicLastTriggeredAt =
            longPreferencesKey("security.emergency.monotonicLastTriggeredAt")
        // v1.11.0 — Sujet 3 anti-smishing.
        val smishingDetectionEnabled = booleanPreferencesKey("security.smishingDetectionEnabled")
        // v1.12.0 — raccourci urgence (notif persistante lock-screen).
        val emergencyShortcutEnabled = booleanPreferencesKey("security.emergencyShortcutEnabled")
        // v1.12.0 — bouton Appeler Police 17 (FR uniquement, opt-in).
        val emergencyCallPoliceEnabled = booleanPreferencesKey("security.emergencyCallPoliceEnabled")
        // v1.13.0 — PIN distinct coffre (second-factor opt-in).
        val vaultPinEnabled = booleanPreferencesKey("security.vaultPinEnabled")
        // v1.14.0 — comportement boutons 112/17 (DIALER_ONLY ou HOLD_3S_DIRECT_CALL).
        val emergencyCallBehavior = stringPreferencesKey("security.emergencyCallBehavior")
        // v1.14.0 — SMS "Je vais bien" sur kill-switch.
        val sendIAmOkSmsOnReset = booleanPreferencesKey("security.sendIAmOkSmsOnReset")
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
        val lastAutoPurgeAt = longPreferencesKey("security.lastAutoPurgeAt")
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
        val splashShown = booleanPreferencesKey("advanced.splashShown")
        val keepAliveService = booleanPreferencesKey("advanced.keepAliveService")
        // v1.8.0 — flag one-shot pour la migration de purge des badges hérités v1.7.1.
        val unreadResetV180 = booleanPreferencesKey("advanced.unreadResetV180")
    }
}
