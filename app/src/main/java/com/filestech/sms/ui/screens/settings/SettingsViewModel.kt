package com.filestech.sms.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.local.datastore.AppSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.PanicService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    /** One-shot UI events (snackbar after blocking purge). Buffered so a rapid tap pair is OK. */
    private val _events = Channel<Event>(capacity = Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    sealed interface Event {
        data class BlockedPurged(val count: Int) : Event
    }

    val state: StateFlow<AppSettings> = settings.flow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        AppSettings(),
    )

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
}
