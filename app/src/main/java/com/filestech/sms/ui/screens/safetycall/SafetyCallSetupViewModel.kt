package com.filestech.sms.ui.screens.safetycall

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.filestech.sms.system.scheduler.SafetyCallWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * v1.9.0 — State holder pour [SafetyCallSetupScreen].
 *
 * **Working draft** : l'écran édite une copie locale (`draft`) de la config
 * deadman avant validation. Le `Save` matérialise atomiquement la
 * configuration dans DataStore et reschedule le worker. Tant que l'user
 * n'a pas tapé `Save`, ses modifications restent en RAM et sont perdues
 * en cas de back / kill — comportement attendu pour un formulaire de
 * sécurité (éviter de sauver une config incomplète).
 *
 * **Validation au save** :
 *  - `enabled = true` requiert ≥ 1 contact valide
 *  - Template CUSTOM requiert `customMessage` non-vide
 *  - Sinon, retourne un `Event.ValidationError(reason)` que l'écran
 *    affiche via SnackBar.
 *
 * **Effet de bord du save** :
 *  - Atomic write DataStore
 *  - Si nouvellement enabled : reset `lastActivityAt = now()` pour démarrer
 *    le timer à partir de maintenant (pas du dernier reset éventuel)
 *  - Schedule du [SafetyCallWorker] (idempotent, KEEP policy)
 */
@HiltViewModel
class SafetyCallSetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Draft local édité par l'écran. Initialisé depuis DataStore au `init`
     * via un `first()` ONE-SHOT (audit fix P1) — l'ancienne version collectait
     * en continu et écrasait le draft à chaque émission DataStore concurrente,
     * faisant perdre les modifications en cours si une autre source (ex:
     * `onResume` qui écrit `lastActivityAt`) modifiait `settings` pendant
     * que l'utilisateur éditait le formulaire.
     */
    private val _draft = MutableStateFlow(SafetyCallConfig())
    val draft: StateFlow<SafetyCallConfig> = _draft.asStateFlow()

    /**
     * Snapshot du config persisté au moment où l'écran a été ouvert. Utilisé
     * dans [save] pour décider si on doit reset `lastActivityAt` (passage
     * disabled → enabled). Hydraté lazy via [snapshotInitial].
     */
    private var snapshotInitial: SafetyCallConfig = SafetyCallConfig()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    init {
        // v1.9.0 audit fix P1 — hydrate one-shot depuis DataStore au lieu
        // de `collect` permanent. Si l'user édite et qu'une autre coroutine
        // écrit dans DataStore (ex: onResume reset), le draft local reste
        // intact.
        viewModelScope.launch {
            val initial = settings.flow.first().security.safetyCall
            snapshotInitial = initial
            _draft.value = initial
        }
    }

    fun setEnabled(enabled: Boolean) {
        _draft.value = _draft.value.copy(enabled = enabled)
    }

    fun setTimeoutMs(timeoutMs: Long) {
        val capped = timeoutMs.coerceIn(
            SafetyCallConfig.TIMEOUT_MIN_MS,
            SafetyCallConfig.TIMEOUT_MAX_MS,
        )
        _draft.value = _draft.value.copy(timeoutMs = capped)
    }

    fun setTemplate(template: SafetyCallTemplate) {
        _draft.value = _draft.value.copy(template = template)
    }

    fun setCustomMessage(message: String) {
        val capped = message.take(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH)
        _draft.value = _draft.value.copy(customMessage = capped)
    }

    fun addContact(name: String?, phoneNumber: String) {
        val current = _draft.value
        if (current.contacts.size >= SafetyCallConfig.MAX_CONTACTS) {
            _events.trySend(Event.ValidationError(ValidationReason.MaxContactsReached))
            return
        }
        val candidate = SafetyCallContact(displayName = name, phoneNumber = phoneNumber.trim())
        if (!candidate.isValid()) {
            _events.trySend(Event.ValidationError(ValidationReason.InvalidPhone))
            return
        }
        _draft.value = current.copy(contacts = current.contacts + candidate)
    }

    fun removeContact(index: Int) {
        val current = _draft.value
        if (index !in current.contacts.indices) return
        _draft.value = current.copy(
            contacts = current.contacts.toMutableList().also { it.removeAt(index) },
        )
    }

    /**
     * Sauvegarde la config draft dans DataStore après validation. Si la
     * sauvegarde aboutit avec `enabled = true`, le worker est schedulé
     * (idempotent) et le timer initialisé à `now()`.
     *
     * Émet [Event.Saved] sur succès, [Event.ValidationError] sinon.
     */
    fun save() {
        viewModelScope.launch {
            val current = _draft.value
            // Validation au save.
            if (current.enabled && current.contacts.isEmpty()) {
                _events.trySend(Event.ValidationError(ValidationReason.NoContacts))
                return@launch
            }
            if (current.enabled &&
                current.template == SafetyCallTemplate.CUSTOM &&
                current.customMessage.isBlank()
            ) {
                _events.trySend(Event.ValidationError(ValidationReason.EmptyCustomMessage))
                return@launch
            }
            // Si on active fraîchement (passage de disabled → enabled), reset
            // le timer à maintenant. Si on était déjà enabled, on garde le
            // lastActivityAt existant (pas de reset implicite via un simple
            // change de template).
            val wasDisabled = !snapshotInitial.enabled
            val toPersist = if (current.enabled && wasDisabled) {
                // v1.10.0 SEC-11 — couple mono+wall au premier arming.
                current.copy(
                    lastActivityAt = System.currentTimeMillis(),
                    monotonicLastActivityAt = SystemClock.elapsedRealtime(),
                )
            } else {
                current
            }
            settings.update { s ->
                s.copy(security = s.security.copy(safetyCall = toPersist))
            }
            // Reschedule le worker — idempotent (KEEP policy). Même si enabled
            // est false, on schedule (no-op ticks) pour qu'un futur enable
            // n'ait pas besoin de cold-start pour démarrer.
            SafetyCallWorker.schedulePeriodic(context)
            _events.trySend(Event.Saved)
        }
    }

    sealed interface Event {
        data object Saved : Event
        data class ValidationError(val reason: ValidationReason) : Event
    }

    enum class ValidationReason {
        NoContacts,
        InvalidPhone,
        MaxContactsReached,
        EmptyCustomMessage,
    }
}
