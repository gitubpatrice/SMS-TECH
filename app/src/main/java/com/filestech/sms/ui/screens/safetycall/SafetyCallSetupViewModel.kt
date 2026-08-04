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

    /**
     * v1.25.5 — vrai dès la première modification de l'utilisateur.
     *
     * L'hydratation depuis le DataStore est asynchrone : entre l'ouverture de l'écran et la
     * réponse du disque, un ajout de contact rapide était **écrasé** par `_draft.value = initial`.
     * Même motif que le Coffre en v1.25.4 — une initialisation tardive qui annule une décision
     * déjà prise. On ne réhydrate donc plus par-dessus une saisie.
     */
    private var userEdited = false

    /**
     * v1.25.5 — vrai quand le brouillon diverge de ce qui est enregistré.
     *
     * Quitter l'écran jetait le brouillon **en silence** : le dialogue d'ajout de contact,
     * dont le bouton s'intitulait « Enregistrer », donnait toute raison de croire que le contact
     * était acquis. Il ne l'était pas — seul le bouton du bas écrit sur le disque. L'écran s'en
     * sert désormais pour prévenir avant de sortir.
     */
    val hasUnsavedChanges: Boolean get() = _draft.value != snapshotInitial

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
            // v1.25.5 — ne jamais écraser une saisie plus rapide que le disque.
            if (!userEdited) _draft.value = initial
        }
    }

    fun setEnabled(enabled: Boolean) {
        userEdited = true
        _draft.value = _draft.value.copy(enabled = enabled)
    }

    fun setTimeoutMs(timeoutMs: Long) {
        userEdited = true
        val capped = timeoutMs.coerceIn(
            SafetyCallConfig.TIMEOUT_MIN_MS,
            SafetyCallConfig.TIMEOUT_MAX_MS,
        )
        _draft.value = _draft.value.copy(timeoutMs = capped)
    }

    fun setTemplate(template: SafetyCallTemplate) {
        userEdited = true
        _draft.value = _draft.value.copy(template = template)
    }

    fun setCustomMessage(message: String) {
        userEdited = true
        val capped = message.take(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH)
        _draft.value = _draft.value.copy(customMessage = capped)
    }

    /**
     * Ajoute un contact au brouillon. **Rend `false` si rien n'a été ajouté.**
     *
     * v1.25.5 — le retour est le correctif : l'écran fermait le dialogue quel que soit le
     * résultat, si bien qu'un numéro refusé disparaissait sans que l'utilisateur puisse le
     * corriger — l'erreur passait dans un bandeau, derrière un dialogue déjà refermé.
     */
    fun addContact(name: String?, phoneNumber: String): Boolean {
        val current = _draft.value
        if (current.contacts.size >= SafetyCallConfig.MAX_CONTACTS) {
            _events.trySend(Event.ValidationError(ValidationReason.MaxContactsReached))
            return false
        }
        val candidate = SafetyCallContact(displayName = name, phoneNumber = phoneNumber.trim())
        if (!candidate.isValid()) {
            _events.trySend(Event.ValidationError(ValidationReason.InvalidPhone))
            return false
        }
        userEdited = true
        _draft.value = current.copy(contacts = current.contacts + candidate)
        return true
    }

    fun removeContact(index: Int) {
        val current = _draft.value
        if (index !in current.contacts.indices) return
        userEdited = true
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
            // v1.26.1 (audit M5) — le carnet de contacts est PARTAGÉ avec le Mode urgence, qui
            // lit `security.safetyCall.contacts`. La validation ci-dessus ne s'applique que si
            // le Safety call lui-même est activé : un utilisateur qui le désarmait et retirait
            // ses contacts au passage vidait donc le carnet, et le Mode urgence — resté armé —
            // perdait TOUS ses destinataires. Il ne l'apprenait qu'au déclenchement, c'est-à-dire
            // au moment précis où il en avait besoin.
            if (current.contacts.isEmpty() &&
                settings.state.value.security.emergency.enabled
            ) {
                _events.trySend(Event.ValidationError(ValidationReason.SharedWithEmergency))
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
                    // v1.27.2 — un armement part forcément d'un compteur vide : sans cette
                    // remise à zéro, un temps capitalisé lors d'une activation précédente
                    // aurait été rejoué et le deadman serait parti en avance.
                    monotonicAccumulatedMs = 0L,
                )
            } else {
                current
            }
            settings.update { s ->
                s.copy(security = s.security.copy(safetyCall = toPersist))
            }
            // v1.25.5 — le brouillon vient d'être écrit : il n'y a plus rien à perdre, donc plus
            // de garde de sortie à déclencher.
            snapshotInitial = toPersist
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

        /**
         * v1.26.1 (audit M5) — le carnet est partagé avec le Mode urgence, qui est encore armé :
         * le vider le priverait de tous ses destinataires, en silence.
         */
        SharedWithEmergency,
    }
}
