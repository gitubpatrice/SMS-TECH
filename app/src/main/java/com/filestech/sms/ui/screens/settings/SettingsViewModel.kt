package com.filestech.sms.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.local.datastore.AppSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.PanicService
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val defaultAppManager: DefaultSmsAppManager,
    private val panic: PanicService,
    private val appLock: AppLockManager,
    private val blockedImporter: BlockedNumbersImporter,
    private val conversationRepo: ConversationRepository,
    private val vaultPin: com.filestech.sms.security.VaultPinManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** One-shot UI events (snackbar après purge bloqués + after history cleanup). Buffered so a rapid tap pair is OK. */
    private val _events = Channel<Event>(capacity = Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    sealed interface Event {
        data class BlockedPurged(val count: Int) : Event
        /** v1.3.0 — résultat du nettoyage manuel de l'historique (bouton "Effacer maintenant"). */
        data class HistoryPurged(val count: Int) : Event
        /** v1.3.0 — re-sync from content://sms a été enquêtée. Le snack confirme à l'utilisateur. */
        data object ResyncRequested : Event
    }

    val state: StateFlow<AppSettings> = settings.flow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        AppSettings(),
    )

    /**
     * v1.10.0 audit SEC-1 — exposé pour que [SettingsScreen] puisse masquer
     * la section Mode urgence en session [AppLockManager.LockState.PanicDecoy].
     * Un agresseur en decoy ne doit pas voir qu'un mode urgence existe
     * (l'illusion "app SMS ordinaire" doit tenir).
     */
    val isPanicDecoy: StateFlow<Boolean> = appLock.state
        .map { it is AppLockManager.LockState.PanicDecoy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * v1.10.0 perf P2 — temps restant avant déclenchement du Safety call (en ms).
     * Recomputé à chaque tick 60s (granularité suffisante pour un compteur d'heures
     * affiché en h/j) OU à chaque changement de [state] (reset "Je vais bien",
     * modification timeout, désactivation…). Évite l'appel à
     * `System.currentTimeMillis()` à chaque recomposition de [SettingsScreen].
     *
     * Valeur sentinelle [REMAINING_NOT_ARMED] quand le deadman est désactivé ou
     * pas encore initialisé — l'UI ne lit cette flow que dans la branche `armed`,
     * mais la sentinelle évite toute lecture stale entre deux ticks.
     */
    val safetyCallRemainingMs: StateFlow<Long> = combine(
        state,
        flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        },
    ) { snapshot, _ ->
        val cfg = snapshot.security.safetyCall
        // v1.10.0 SEC-11 — affichage cohérent avec [SafetyCallConfig.isExpired] :
        // si la mono clock n'est pas posée (config v1.9.0 héritée), on traite
        // comme "non armé" — l'UI ne fait pas miroiter un compte à rebours qui
        // ne déclencherait pas.
        if (!cfg.enabled || cfg.lastActivityAt == 0L || cfg.monotonicLastActivityAt == 0L) {
            REMAINING_NOT_ARMED
        } else {
            (cfg.lastActivityAt + cfg.timeoutMs) - System.currentTimeMillis()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        REMAINING_NOT_ARMED,
    )

    companion object {
        /** Sentinelle : Safety call inactif (désactivé ou non initialisé). */
        const val REMAINING_NOT_ARMED: Long = Long.MIN_VALUE
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(transform) }

    fun resetAll() = viewModelScope.launch { settings.update { AppSettings() } }

    fun nukeData() = viewModelScope.launch { panic.nukeEverything() }

    /**
     * Sets the user's PIN/passphrase via [AppLockManager.setPin]. The `CharArray` is wiped
     * inside the manager so the secret never lingers on the JVM heap. Caller (UI) must hand
     * over a fresh `CharArray` — we never accept `String` to avoid the implicit intern table.
     */
    fun setPin(pin: CharArray) = viewModelScope.launch { appLock.setPin(pin) }

    /** Disables the lock entirely (back to [com.filestech.sms.data.local.datastore.LockMode.OFF]). */
    fun clearLock() = viewModelScope.launch { appLock.clearPin() }

    /**
     * Forces the blocked-conversation purge synchronously and reports the count via [events].
     * Uses the same logic that runs at boot — but here the user explicitly opted in, so we
     * don't second-guess them with a confirmation dialog (the operation only deletes rows the
     * system blocklist already considers undesired).
     */
    fun purgeBlockedConversations() = viewModelScope.launch {
        val count = runCatching { blockedImporter.purgeMatchingConversations() }.getOrDefault(0)
        _events.send(Event.BlockedPurged(count))
    }

    /**
     * Switches to biometric unlock **on top of an existing PIN**. Caller (UI) must ensure a PIN
     * is configured first — returns `false` if not (in which case the UI should keep the picker
     * open and route the user through PIN setup).
     */
    suspend fun enableBiometricOverPin(): Boolean = appLock.enableBiometric()

    /** Reverts to PIN-only mode. */
    fun disableBiometric() = viewModelScope.launch { appLock.disableBiometric() }

    /**
     * v1.3.0 — compte combien de messages seraient effacés par un nettoyage manuel à la
     * profondeur [olderThanDays]. Suspend pour que le dialog "Effacer maintenant" puisse
     * afficher le total avant confirmation et permettre à l'utilisateur d'annuler si le
     * volume est inattendu (sécurité). Retourne 0 si la sélection est désactivée ou si
     * aucun message ne correspond.
     */
    suspend fun countHistoryToPurge(olderThanDays: Int?): Int {
        val days = olderThanDays ?: return 0
        if (days <= 0) return 0
        return runCatching { conversationRepo.countMessagesToPurge(days) }.getOrDefault(0)
    }

    /**
     * v1.3.0 — déclenche un nettoyage manuel immédiat à la profondeur [olderThanDays] et
     * émet un [Event.HistoryPurged] avec le nombre de rows effacées. Ne touche pas au
     * `lastAutoPurgeAt` (le cycle mensuel auto reste indépendant). No-op si désactivé.
     */
    fun purgeHistoryNow(olderThanDays: Int?) = viewModelScope.launch {
        val days = olderThanDays ?: return@launch
        if (days <= 0) return@launch
        val count = runCatching { conversationRepo.purgeHistoryNow(days) }.getOrDefault(0)
        _events.send(Event.HistoryPurged(count))
    }

    /**
     * v1.3.0 — force une resynchronisation complète depuis `content://sms` (le system
     * provider Android). Reset le curseur `lastSyncedSmsId = 0` puis enqueue un OneTime
     * worker pour relancer immédiatement le scan complet. L'index UNIQUE `telephony_uri`
     * + `OnConflictStrategy.IGNORE` garantissent l'absence de doublons : seuls les
     * messages absents de Room sont réinsérés. Utilisé pour récupérer un historique
     * purgé par erreur (auto-purge ou nettoyage manuel) tant que les rows sont encore
     * dans le system provider.
     */
    fun forceResyncFromTelephony() = viewModelScope.launch {
        settings.update { it.copy(advanced = it.advanced.copy(lastSyncedSmsId = 0L)) }
        TelephonySyncWorker.enqueueOneShot(context)
        _events.send(Event.ResyncRequested)
    }

    /**
     * v1.13.0 — set/change le PIN/pass coffre. [pin] est wipé par
     * [com.filestech.sms.security.VaultPinManager]. Le flag `vaultPinEnabled`
     * est posé `true` côté manager après hash réussi.
     */
    fun setVaultPin(pin: CharArray) = viewModelScope.launch {
        vaultPin.setVaultPin(pin)
    }

    /**
     * v1.13.0 — retire le PIN/pass coffre + flip le flag à `false`. Idempotent.
     */
    fun clearVaultPin() = viewModelScope.launch {
        vaultPin.clearVaultPin()
    }
}
