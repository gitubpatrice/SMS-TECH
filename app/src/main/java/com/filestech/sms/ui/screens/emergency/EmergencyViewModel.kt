package com.filestech.sms.ui.screens.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.emergency.EmergencyConfig
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.domain.usecase.TriggerEmergencyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * v1.10.0 — State holder pour [EmergencyScreen] et [EmergencySetupScreen].
 *
 * Combine 2 responsabilités :
 *  - **Setup** (édition draft puis save) — mêmes patterns que
 *    [com.filestech.sms.ui.screens.safetycall.SafetyCallSetupViewModel] :
 *    hydratation `first()` one-shot, mutations sur `draft`, validation
 *    au save, events one-shot via [Channel].
 *  - **Trigger** (déclenchement urgence depuis l'écran principal) —
 *    appelle [TriggerEmergencyUseCase] et émet le résultat en event.
 *
 * **Pourquoi un seul VM** : la surface est petite (~6 mutations + 1
 * trigger + 1 save) et l'écran setup + screen partagent l'état config.
 * Garde la cohérence d'un seul flux source (DataStore → VM → UI).
 */
@HiltViewModel
class EmergencyViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val triggerEmergency: TriggerEmergencyUseCase,
    private val locationResolver: com.filestech.sms.data.location.LocationResolver,
) : ViewModel() {

    /** Config persistée — lue en continu pour refléter les changements live. */
    val state: StateFlow<EmergencyConfig> = settings.flow
        .map { it.security.emergency }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            EmergencyConfig(),
        )

    /**
     * Nombre de contacts d'urgence configurés (réutilise la liste Safety
     * call). Exposé en StateFlow pour que l'UI grise le bouton URGENCE
     * quand il n'y a pas de contacts (cas d'usage : user vient d'activer
     * Mode urgence mais n'a jamais configuré Safety call).
     */
    val safetyCallContactsCount: StateFlow<Int> = settings.flow
        .map { it.security.safetyCall.contacts.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    /**
     * v1.12.0 — exposé pour afficher le bouton "Appeler 17" dans
     * [EmergencyScreen] uniquement quand l'user a opt-in dans Settings.
     * Le 112 reste toujours visible (SOS européen, pas de toggle).
     */
    val callPoliceEnabled: StateFlow<Boolean> = settings.flow
        .map { it.security.emergencyCallPoliceEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * v1.14.0 — comportement boutons 112/17 :
     *  - [com.filestech.sms.data.local.datastore.EmergencyCallBehavior.DIALER_ONLY] (default) : tap → composeur pré-rempli.
     *  - [com.filestech.sms.data.local.datastore.EmergencyCallBehavior.HOLD_3S_DIRECT_CALL] : hold-3s → appel direct CALL_PHONE.
     */
    val callBehavior: StateFlow<com.filestech.sms.data.local.datastore.EmergencyCallBehavior> = settings.flow
        .map { it.security.emergencyCallBehavior }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            com.filestech.sms.data.local.datastore.EmergencyCallBehavior.DIALER_ONLY,
        )

    /**
     * v1.14.0 audit SEC-1 — appelé par l'UI au ON_RESUME pour détecter qu'un
     * user a révoqué CALL_PHONE depuis Paramètres Android. Si la permission
     * est absente alors que `emergencyCallBehavior = HOLD_3S_DIRECT_CALL`,
     * on revert le setting à DIALER_ONLY pour aligner état app + OS.
     * Sans ça, chaque tap sur 112/17 retournerait `PERMISSION_DENIED` →
     * l'user croit que l'app est cassée en situation d'urgence.
     */
    fun revertCallBehaviorIfPermissionRevoked(hasCallPhonePermission: Boolean) {
        if (hasCallPhonePermission) return
        val current = callBehavior.value
        if (current == com.filestech.sms.data.local.datastore.EmergencyCallBehavior.HOLD_3S_DIRECT_CALL) {
            viewModelScope.launch {
                settings.update { s ->
                    s.copy(
                        security = s.security.copy(
                            emergencyCallBehavior = com.filestech.sms.data.local.datastore.EmergencyCallBehavior.DIALER_ONLY,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Draft local édité par l'écran setup. Hydraté one-shot via `first()`
     * (même pattern que SafetyCallSetupViewModel — évite l'écrasement
     * concurrent par un trigger qui pose `lastTriggeredAt`).
     */
    private val _draft = MutableStateFlow(EmergencyConfig())
    val draft: StateFlow<EmergencyConfig> = _draft.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    /**
     * v1.10.0 audit SEC-2 — flag in-flight pour empêcher un double-trigger.
     * La fenêtre de race vient du fait que `isInAntiSpamWindow()` lit le
     * StateFlow `state` qui n'est mis à jour qu'après l'écriture DataStore
     * (~50-300 ms). Pendant ce gap, un 2e hold 3s pourrait passer la garde
     * UI et appeler `trigger()` une 2e fois → double SMS aux contacts. Ce
     * flag bloque concurrent dans le VM avant même de lancer le UseCase.
     */
    private val _isTriggerInFlight = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            _draft.value = settings.flow.first().security.emergency
        }
    }

    // ──────────── Draft mutations (setup screen) ────────────

    fun setEnabled(enabled: Boolean) {
        _draft.value = _draft.value.copy(enabled = enabled)
    }

    fun setTemplate(template: EmergencyTemplate) {
        _draft.value = _draft.value.copy(template = template)
    }

    fun setIncludeLocation(include: Boolean) {
        _draft.value = _draft.value.copy(includeLocation = include)
    }

    /**
     * Sauvegarde la config draft. v1.10.0 audit S3 — préserve
     * `lastTriggeredAt` + `monotonicLastTriggeredAt` live (le draft
     * capturé à l'ouverture du setup peut être stale si un trigger a
     * eu lieu entre temps). Sans ça, un user qui déclenche puis modifie
     * un paramètre setup ferait sauter son propre cooldown anti-spam.
     */
    fun save() {
        viewModelScope.launch {
            val current = _draft.value
            settings.update { s ->
                val live = s.security.emergency
                s.copy(
                    security = s.security.copy(
                        emergency = current.copy(
                            lastTriggeredAt = live.lastTriggeredAt,
                            monotonicLastTriggeredAt = live.monotonicLastTriggeredAt,
                        ),
                    ),
                )
            }
            _events.trySend(Event.Saved)
        }
    }

    // ──────────── Trigger (main screen) ────────────

    /**
     * Déclenche l'envoi d'urgence — appelé depuis [EmergencyScreen] après
     * que le bouton ait été maintenu 3 secondes. Diffuse le résultat en
     * event pour que l'UI puisse afficher le snackbar approprié
     * (succès / pas de location / pas de contacts / panic suppressed).
     *
     * v1.10.0 audit SEC-2 — protégé contre concurrent via [_isTriggerInFlight].
     * Un second appel pendant qu'un trigger est en cours est ignoré (return).
     * Le flag est libéré dans `finally`, garantissant la libération même en
     * cas d'exception inattendue côté UseCase.
     */
    fun trigger() {
        if (!_isTriggerInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = triggerEmergency()
                _events.trySend(Event.Triggered(result))
            } finally {
                _isTriggerInFlight.set(false)
            }
        }
    }

    sealed interface Event {
        data object Saved : Event
        data class Triggered(val result: TriggerEmergencyUseCase.Result) : Event
    }

    /**
     * v1.14.0 — état du dry-run "Tester sans envoyer". `null` = pas de preview
     * en cours. Non-null = preview à afficher (le dialog est rendu côté
     * EmergencyScreen).
     */
    private val _previewState = MutableStateFlow<DryRunPreview?>(null)
    val previewState: StateFlow<DryRunPreview?> = _previewState.asStateFlow()

    /**
     * v1.14.0 audit PERF-1 — `true` pendant la résolution GPS (jusqu'à 8s).
     * UI désactive le bouton "Tester sans envoyer" + affiche un loader pour
     * éviter double-tap et UX gelée silencieuse.
     */
    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    /**
     * v1.14.0 — simule un déclenchement urgence pour QUE L'USER VOIT exactement
     * ce qui partirait (body SMS rendu + count contacts + location resolved
     * oui/non + call behavior actif). **AUCUN SMS, AUCUN APPEL, AUCUNE
     * MUTATION DataStore**. Lit le snapshot config, tente GPS si opt-in
     * (réutilise [LocationResolver]), rend le body.
     *
     * Permet à l'user de configurer son mode urgence et vérifier la sortie
     * sans flinger ses contacts. Très important UX : un user qui tente
     * `trigger()` "pour voir" envoie de vrais SMS. Le dry-run élimine cette
     * peur. Utilisable à n'importe quel moment, jamais bloqué par cooldown.
     *
     * v1.14.0 audit PERF-1 — guard double-tap via `_isPreviewLoading` :
     * re-tap pendant le GPS resolve (8s timeout) = no-op. UI désactive le
     * bouton + affiche un loader pour la transparence UX.
     */
    fun previewTrigger() {
        if (_isPreviewLoading.value) return
        viewModelScope.launch {
            _isPreviewLoading.value = true
            try {
                val snapshot = settings.flow.first()
                val emergency = snapshot.security.emergency
                val contacts = snapshot.security.safetyCall.contacts
                val locationUrl: String? = if (emergency.includeLocation) {
                    runCatching { locationResolver.getCurrentLocation() }
                        .getOrNull()
                        ?.let { loc -> "https://maps.google.com/?q=%.5f,%.5f".format(loc.latitude, loc.longitude) }
                } else null
                val body = emergency.template.renderBody(locationUrl).trim()
                _previewState.value = DryRunPreview(
                    enabled = emergency.enabled,
                    template = emergency.template,
                    includeLocation = emergency.includeLocation,
                    locationResolved = locationUrl != null,
                    body = body,
                    contactsCount = contacts.size,
                    redactedContacts = contacts.map { redactPhoneNumber(it.phoneNumber) },
                    callBehavior = snapshot.security.emergencyCallBehavior,
                )
            } finally {
                _isPreviewLoading.value = false
            }
        }
    }

    /** Ferme le dialog dry-run. */
    fun dismissPreview() {
        _previewState.value = null
    }

    /**
     * v1.14.0 — masque un numéro de téléphone pour le preview UI : conserve
     * les 2 derniers chiffres pour reconnaissance + le préfixe pays s'il
     * existe. "+33 6 12 34 56 78" → "+33 ... 78". Pas du logging — c'est
     * un display UX (l'user connaît ses propres contacts), juste un
     * non-leak en cas de screenshot accidentel partagé.
     */
    private fun redactPhoneNumber(raw: String): String {
        val cleaned = raw.replace("\\s".toRegex(), "")
        if (cleaned.length <= 4) return "•••"
        val prefix = if (cleaned.startsWith("+")) cleaned.take(3) else cleaned.take(2)
        val suffix = cleaned.takeLast(2)
        return "$prefix … $suffix"
    }

    /**
     * v1.14.0 — snapshot du dry-run "Tester sans envoyer" pour affichage UI.
     * IMMUTABLE : créé une fois côté ViewModel, lu par le dialog Compose.
     * Aucune action côté UI ne modifie cet objet.
     */
    data class DryRunPreview(
        val enabled: Boolean,
        val template: EmergencyTemplate,
        val includeLocation: Boolean,
        val locationResolved: Boolean,
        val body: String,
        val contactsCount: Int,
        val redactedContacts: List<String>,
        val callBehavior: com.filestech.sms.data.local.datastore.EmergencyCallBehavior,
    )
}
