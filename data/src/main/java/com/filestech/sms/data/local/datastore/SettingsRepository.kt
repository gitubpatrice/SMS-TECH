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
import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.settings.AdvancedSettings
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.Appearance
import com.filestech.sms.domain.settings.AutoLockDelay
import com.filestech.sms.domain.settings.BackupSettings
import com.filestech.sms.domain.settings.BlockingSettings
import com.filestech.sms.domain.settings.ConversationSettings
import com.filestech.sms.domain.settings.EmergencyCallBehavior
import com.filestech.sms.domain.settings.FirstDayOfWeek
import com.filestech.sms.domain.settings.ListDensity
import com.filestech.sms.domain.settings.LocaleSettings
import com.filestech.sms.domain.settings.LockMode
import com.filestech.sms.domain.settings.MmsImageQuality
import com.filestech.sms.domain.settings.NotificationSettings
import com.filestech.sms.domain.settings.NotificationStyle
import com.filestech.sms.domain.settings.PreviewMode
import com.filestech.sms.domain.settings.SecuritySettings
import com.filestech.sms.domain.settings.SendingSettings
import com.filestech.sms.domain.settings.SortMode
import com.filestech.sms.domain.settings.TextScale
import com.filestech.sms.domain.settings.ThemeMode
import com.filestech.sms.domain.settings.VibratePattern
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "sms_tech_settings")

/**
 * v1.26.1 (audit H14) — nombre de reprises sur une lecture DataStore en échec avant d'abandonner
 * l'émission. Trois : assez pour absorber une erreur d'E/S transitoire, assez peu pour ne pas
 * boucler indéfiniment sur un fichier réellement corrompu.
 */
private const val SETTINGS_READ_RETRIES = 3L

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : AppSettingsSource {
    /**
     * v1.26.1 (audit H14) — le flux SURVIT à une lecture qui échoue.
     *
     * `preferencesDataStore` n'a pas de `corruptionHandler` : une corruption fait lever
     * `dataStore.data` à CHAQUE lecture. Sans ce `retry`/`catch`, la coroutine de partage de
     * [state] mourait, le `StateFlow` restait figé sur `AppSettings()` — tous les réglages à leur
     * valeur par défaut — DÉFINITIVEMENT et en silence. Conséquences observées à la lecture :
     * `SafetyCallWorker` voyait `enabled = false` et le deadman ne se déclenchait plus jamais,
     * alors que l'utilisateur le croyait armé ; et les aperçus de notification réapparaissaient
     * chez quelqu'un qui les avait désactivés.
     *
     * On réessaie trois fois (une corruption transitoire d'E/S se résorbe), puis on cesse
     * d'émettre plutôt que de servir des défauts : le repli est ainsi « pas de nouvelle valeur »
     * et non « valeurs par défaut », ce qui laisse le dernier instantané VALIDE en place.
     */
    override val flow: Flow<AppSettings> = context.dataStore.data
        .retry(SETTINGS_READ_RETRIES) { t ->
            Timber.w(t, "SettingsRepository: DataStore read failed — retrying")
            true
        }
        .catch { t -> Timber.e(t, "SettingsRepository: DataStore unreadable — keeping last snapshot") }
        .map { prefs -> prefs.toAppSettings() }

    /**
     * v1.27.2 — `true` dès que [_state] a reçu au moins une valeur VENUE DU STOCKAGE.
     *
     * Écrit **après** la publication dans [_state] et lu **avant** [_state] : les deux champs
     * étant volatils (`MutableStateFlow.value` l'est par construction), quiconque observe
     * `hydrated == true` voit forcément l'instantané qui l'a précédé. L'inverse — annoncer puis
     * publier — aurait laissé une fenêtre où l'on affirme « hydraté » en servant encore les
     * défauts, ce qui est exactement le défaut qu'on ferme ici.
     */
    @Volatile
    private var hydrated: Boolean = false

    private val _state = MutableStateFlow(AppSettings())

    /**
     * v1.6.1 (audit PERF-01 / PERF-11) — snapshot chaud partagé via [StateFlow]. Tous
     * les call sites qui n'ont besoin que de la valeur courante des settings (notif
     * incoming, dispatch SMS, worker auto-purge) devraient lire `state.value` au lieu
     * de `flow.first()` qui ouvre/lit/ferme le fichier DataStore à chaque appel
     * (~5-10 ms × N call sites).
     *
     * Collecte unique lancée dans [appScope] (`@Singleton` scoped à l'app), équivalente au
     * `stateIn(..., SharingStarted.Eagerly, ...)` d'origine. La collecte est explicite ici pour
     * une seule raison : pouvoir lever [hydrated] APRÈS la publication (cf. ci-dessus), ce que
     * `stateIn` ne permet pas — un `onEach` en amont l'aurait levé AVANT.
     *
     * ⚠️ La valeur initiale reste `AppSettings()`, donc les DÉFAUTS. Lire `state.value` sur un
     * processus démarré à froid ne rend pas les réglages de l'utilisateur : utiliser
     * [hydratedOrNull] sur tout chemin réveillé par le système.
     */
    override val state: StateFlow<AppSettings> = _state.asStateFlow()

    /**
     * v1.27.2 (relecture Codex 2026-08-05, finding 2) — **barrière d'hydratation unique**.
     *
     * `true` = la collecte ci-dessous a publié au moins un instantané dans [_state] ;
     * `false` = le flux s'est terminé sans jamais émettre (fichier illisible après ses reprises).
     *
     * Elle existe pour que [hydratedOrNull] n'ait plus sa propre lecture indépendante. Avec deux
     * lectures concurrentes, l'appelant obtenait le bon instantané pendant que [_state] servait
     * encore les défauts à tout lecteur synchrone — au premier rang duquel
     * [com.filestech.sms.data.sms.PhoneNumberWireFormatter], appelé quelques instructions plus
     * loin sur le MÊME envoi. Un Safety call parti d'un processus froid pouvait ainsi résoudre la
     * bonne SIM et perdre l'indicatif pays choisi, donc composer un numéro étranger.
     *
     * Le contrat est désormais : après le retour de [hydratedOrNull], [state] connaît la même
     * valeur. Aucun consommateur synchrone ne peut plus voir de défauts derrière une lecture
     * hydratée réussie.
     */
    private val firstHydration = CompletableDeferred<Boolean>()

    init {
        val job = appScope.launch {
            flow.collect { snapshot ->
                _state.value = snapshot
                hydrated = true
                firstHydration.complete(true)
            }
        }
        // Débloque les attentes même si la collecte se termine sans avoir rien émis — fichier
        // illisible, portée annulée, ou coroutine annulée avant d'avoir démarré. Sans ce filet,
        // `hydratedOrNull()` resterait suspendu POUR TOUJOURS sur le chemin d'un SMS entrant.
        // `complete` est idempotent : après une hydratation réussie, cet appel est sans effet.
        job.invokeOnCompletion { firstHydration.complete(false) }
    }

    /**
     * v1.27.2 — cf. [AppSettingsSource.hydratedOrNull].
     *
     * Chemin chaud : aucun I/O, une lecture volatile et une lecture de `StateFlow`.
     * Chemin froid : on **attend la collecte partagée**, on ne lance pas la sienne.
     *
     * C'est le correctif du finding 2 de la relecture du 2026-08-05. La version précédente
     * appelait `flow.first()` de son côté : elle rendait le bon instantané, mais [_state] pouvait
     * encore servir les défauts à un lecteur synchrone exécuté juste après, sur le même envoi.
     * Attendre la barrière garantit que [state] est à jour **avant** le retour, ce qui répare du
     * même coup tous les consommateurs non convertis et non convertissables — au premier rang
     * [com.filestech.sms.data.sms.PhoneNumberWireFormatter], qui n'est pas suspendable.
     *
     * Effet de bord bienvenu : une seule lecture DataStore au lieu de deux sur un processus froid.
     *
     * `null` = le flux s'est terminé sans jamais émettre. `flow` absorbe déjà les erreurs
     * ([SETTINGS_READ_RETRIES] reprises puis `catch`), donc ce cas signifie « fichier durablement
     * illisible ». On rend `null` plutôt que `AppSettings()` : servir des défauts silencieux est
     * précisément ce qu'on cherche à empêcher.
     *
     * Ne peut pas se suspendre indéfiniment : la barrière est complétée soit par la première
     * émission, soit par la fin de la coroutine de collecte (`invokeOnCompletion`).
     * `CancellationException` traverse `await()` sans être avalée — une annulation n'est pas un
     * échec de lecture.
     */
    override suspend fun hydratedOrNull(): AppSettings? {
        if (hydrated) return _state.value
        return if (firstHydration.await()) _state.value else null
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
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
                defaultRegionIso = p[K.defaultRegion]?.takeIf { it.isNotBlank() },
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
                    // v1.27.2 — absent d'une config antérieure ⇒ `0L`, soit exactement le
                    // comportement précédent jusqu'au premier jalon du worker. Pas de saut.
                    monotonicAccumulatedMs = p[K.safetyCallMonotonicAccumulatedMs] ?: 0L,
                    // v1.27.2 — sequence de relances. Absents d'une config anterieure => 0,
                    // c'est-a-dire « jamais declenche » : le comportement d'avant, sans saut.
                    triggeredAt = p[K.safetyCallTriggeredAt] ?: 0L,
                    messagesSent = p[K.safetyCallMessagesSent] ?: 0,
                    claimedAt = p[K.safetyCallClaimedAt] ?: 0L,
                    claimId = p[K.safetyCallClaimId] ?: 0L,
                    generation = p[K.safetyCallGeneration] ?: 0L,
                    contacts = SafetyCallContactCodec.decode(p[K.safetyCallContactsJson]),
                    // v1.27.3 — absent d'une config anterieure => liste vide, c'est-a-dire
                    // « aucun declenchement connu ». Les cycles anterieurs a cette version ne
                    // seront donc pas dans l'historique : ils n'ont jamais ete archives.
                    history = SafetyCallHistoryCodec.decode(p[K.safetyCallHistory]),
                    // v1.28.0 — journal technique. Les deux replis valent « éteint » : une clé
                    // absente donne `0L` et `""`, et `isJournalActive` est faux dans les deux cas.
                    journalUntilMs = p[K.safetyCallJournalUntilMs] ?: 0L,
                    journalSalt = p[K.safetyCallJournalSalt].orEmpty(),
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
                    ?.let { runCatching { com.filestech.sms.domain.settings.EmergencyCallBehavior.valueOf(it) }.getOrNull() }
                    ?: com.filestech.sms.domain.settings.EmergencyCallBehavior.DIALER_ONLY,
                // v1.14.0 — SMS "Je vais bien" sur kill-switch. Default true.
                sendIAmOkSmsOnReset = p[K.sendIAmOkSmsOnReset] ?: true,
            ),
            blocking = BlockingSettings(
                blockUnknown = p[K.blockUnknown] ?: false,
            ),
            backup = BackupSettings(
                encrypt = p[K.backupEncrypt] ?: true,
            ),
            advanced = AdvancedSettings(
                isDefaultSmsApp = p[K.isDefault] ?: false,
                mmsRoamingAutoDownload = p[K.mmsRoaming] ?: false,
                lastSyncedSmsId = p[K.lastSyncedSmsId] ?: 0L,
                mmsImportCompleted = p[K.mmsImportCompleted] ?: false,
                splashShown = p[K.splashShown] ?: false,
                keepAliveService = p[K.keepAliveService] ?: false,
                unreadResetV180 = p[K.unreadResetV180] ?: false,
                dedupSameNumberV1230 = p[K.dedupSameNumberV1230] ?: false,
                attachmentsMovedToFilesDirV147 = p[K.attachmentsMovedToFilesDirV147] ?: false,
                startupDbMigrationsDone = p[K.startupDbMigrationsDone] ?: false,
                staleConversationPreviewsRepairedV1240 = p[K.staleConversationPreviewsRepairedV1240] ?: false,
                identityDedupRepairedV1272 = p[K.identityDedupRepairedV1272] ?: false,
                emptyConversationsPurgedV1272 = p[K.emptyConversationsPurgedV1272] ?: false,
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
        s.sending.defaultRegionIso?.takeIf { it.isNotBlank() }?.let { this[K.defaultRegion] = it }
            ?: remove(K.defaultRegion)

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
        this[K.safetyCallMonotonicAccumulatedMs] = s.security.safetyCall.monotonicAccumulatedMs
        this[K.safetyCallTriggeredAt] = s.security.safetyCall.triggeredAt
        this[K.safetyCallMessagesSent] = s.security.safetyCall.messagesSent
        this[K.safetyCallClaimedAt] = s.security.safetyCall.claimedAt
        this[K.safetyCallClaimId] = s.security.safetyCall.claimId
        this[K.safetyCallGeneration] = s.security.safetyCall.generation
        this[K.safetyCallContactsJson] = SafetyCallContactCodec.encode(s.security.safetyCall.contacts)
        this[K.safetyCallHistory] = SafetyCallHistoryCodec.encode(s.security.safetyCall.history)
        // v1.28.0 — journal technique. ⚠️ Ces deux lignes sont le 2ᵉ des TROIS points de câblage
        // DataStore ; en oublier un fait un champ qui se lit mais ne se persiste pas, ou l'inverse.
        this[K.safetyCallJournalUntilMs] = s.security.safetyCall.journalUntilMs
        this[K.safetyCallJournalSalt] = s.security.safetyCall.journalSalt
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

        // v1.26.1 (audit F5) — `autoBackup`, `backupUri`, `backupKeep` et `backupFormat` ont été
        // retirés avec la sauvegarde automatique. On les EFFACE explicitement du magasin : les
        // valeurs persistées chez les utilisateurs existants deviendraient sinon des orphelines
        // silencieuses, et `backup.uri` en particulier conserverait une permission d'URI vers un
        // dossier de l'utilisateur sans que plus rien ne la justifie.
        remove(K.autoBackup)
        remove(K.backupUri)
        remove(K.backupKeep)
        remove(K.backupFormat)
        this[K.backupEncrypt] = s.backup.encrypt

        this[K.isDefault] = s.advanced.isDefaultSmsApp
        this[K.mmsRoaming] = s.advanced.mmsRoamingAutoDownload
        this[K.lastSyncedSmsId] = s.advanced.lastSyncedSmsId
        this[K.mmsImportCompleted] = s.advanced.mmsImportCompleted
        this[K.splashShown] = s.advanced.splashShown
        this[K.keepAliveService] = s.advanced.keepAliveService
        this[K.unreadResetV180] = s.advanced.unreadResetV180
        this[K.dedupSameNumberV1230] = s.advanced.dedupSameNumberV1230
        this[K.attachmentsMovedToFilesDirV147] = s.advanced.attachmentsMovedToFilesDirV147
        this[K.startupDbMigrationsDone] = s.advanced.startupDbMigrationsDone
        this[K.staleConversationPreviewsRepairedV1240] = s.advanced.staleConversationPreviewsRepairedV1240
        this[K.identityDedupRepairedV1272] = s.advanced.identityDedupRepairedV1272
        this[K.emptyConversationsPurgedV1272] = s.advanced.emptyConversationsPurgedV1272
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
        // v1.21.0 — indicatif pays par défaut pour la conversion E.164 des numéros
        // nationaux à l'envoi. null/absent = Auto (pays de la SIM).
        val defaultRegion = stringPreferencesKey("send.defaultRegion")

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

        // v1.27.2 — temps monotone capitalisé, pour que le deadman survive aux redémarrages.
        val safetyCallMonotonicAccumulatedMs =
            longPreferencesKey("security.safetyCall.monotonicAccumulatedMs")

        // v1.27.2 — sequence de relances : instant du premier envoi reussi, et compteur de
        // messages deja partis. Cf. [SafetyCallConfig.triggeredAt].
        val safetyCallTriggeredAt = longPreferencesKey("security.safetyCall.triggeredAt")

        /** v1.27.2 — bail sur le créneau réservé. Voir [SafetyCallConfig.claimedAt]. */
        val safetyCallClaimedAt = longPreferencesKey("security.safetyCall.claimedAt")

        /** v1.27.2 (audit Codex, C-03) — proprietaire du creneau reserve. */
        val safetyCallClaimId = longPreferencesKey("security.safetyCall.claimId")

        /** v1.27.2 (audit Codex, C-04) — generation du cycle, incrementee a chaque reset. */
        val safetyCallGeneration = longPreferencesKey("security.safetyCall.generation")
        val safetyCallMessagesSent = intPreferencesKey("security.safetyCall.messagesSent")
        val safetyCallContactsJson = stringPreferencesKey("security.safetyCall.contactsJson")

        /** v1.27.3 — historique des declenchements, encode par [SafetyCallHistoryCodec]. */
        val safetyCallHistory = stringPreferencesKey("security.safetyCall.history")
        /**
         * v1.28.0 — échéance du journal technique de diagnostic, en horloge murale. `0` = éteint.
         *
         * ⚠️ Écrit **en clair** dans le DataStore, comme tout ce fichier — seule la base Room est
         * chiffrée (SQLCipher). Ce n'est pas un secret : c'est une date. Le sel voisin, lui, n'a de
         * valeur que couplé au contenu du journal, qui vit dans le même bac à sable.
         */
        val safetyCallJournalUntilMs = longPreferencesKey("security.safetyCall.journalUntilMs")

        /** v1.28.0 — sel d'installation pour réduire les destinataires du journal. Vide = éteint. */
        val safetyCallJournalSalt = stringPreferencesKey("security.safetyCall.journalSalt")
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

        // v1.26.1 (audit F5) — ces quatre clés ne sont plus JAMAIS lues : la sauvegarde
        // automatique a été retirée. Elles sont conservées uniquement pour pouvoir EFFACER les
        // valeurs déjà persistées chez les utilisateurs existants (cf. `write`). Sans elles, les
        // anciennes valeurs resteraient indéfiniment dans le magasin — et `backup.uri`
        // continuerait de désigner un dossier de l'utilisateur sans aucune raison.
        val autoBackup = stringPreferencesKey("backup.auto")
        val backupUri = stringPreferencesKey("backup.uri")
        val backupKeep = intPreferencesKey("backup.keep")
        val backupFormat = stringPreferencesKey("backup.format")
        val backupEncrypt = booleanPreferencesKey("backup.encrypt")
        val isDefault = booleanPreferencesKey("advanced.isDefault")
        val mmsRoaming = booleanPreferencesKey("advanced.mmsRoaming")
        // Bumped from a boolean ("didInitialSmsImport") to a long cursor: the latter encodes the
        // same first-run signal (0 vs > 0) AND tells the sync manager where to resume from.
        val lastSyncedSmsId = longPreferencesKey("advanced.lastSyncedSmsId")

        /**
         * v1.27.2 (audit Codex du 2026-08-05, P-10) — preuve PERSISTEE qu'un import MMS est alle
         * jusqu'a sa derniere page. `hasAnyMms` ne prouvait qu'une chose : une page avait ete
         * ecrite. Voir [com.filestech.sms.domain.settings.AdvancedSettings.mmsImportCompleted].
         */
        val mmsImportCompleted = booleanPreferencesKey("advanced.mmsImportCompleted")
        val splashShown = booleanPreferencesKey("advanced.splashShown")
        val keepAliveService = booleanPreferencesKey("advanced.keepAliveService")
        // v1.8.0 — flag one-shot pour la migration de purge des badges hérités v1.7.1.
        val unreadResetV180 = booleanPreferencesKey("advanced.unreadResetV180")
        // v1.22.x — flag de complétion de la dédup des conversations du même numéro.
        val dedupSameNumberV1230 = booleanPreferencesKey("advanced.dedupSameNumberV1230")
        // v1.14.7 — flag one-shot pour la migration des attachments MMS cacheDir → filesDir.
        val attachmentsMovedToFilesDirV147 = booleanPreferencesKey("advanced.attachmentsMovedToFilesDirV147")
        val startupDbMigrationsDone = booleanPreferencesKey("advanced.startupDbMigrationsDone")
        val staleConversationPreviewsRepairedV1240 =
            booleanPreferencesKey("advanced.staleConversationPreviewsRepairedV1240")

        /** v1.27.2 (audit Codex, LP-05) — rejeu de la dedup avec l identite region-aware. */
        val identityDedupRepairedV1272 =
            booleanPreferencesKey("advanced.identityDedupRepairedV1272")

        /** v1.27.2 (audit Codex, LP-07) — purge des coquilles deja creees. */
        val emptyConversationsPurgedV1272 =
            booleanPreferencesKey("advanced.emptyConversationsPurgedV1272")
    }
}
