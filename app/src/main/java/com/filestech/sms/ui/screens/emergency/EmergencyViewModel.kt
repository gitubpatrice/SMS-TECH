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
     * v1.14.1 — liste complète des contacts SafetyCall pour le bouton
     * "Appeler un proche" : si 1 contact → call direct, si ≥2 → picker
     * dialog. Réutilise la même source que `safetyCallContactsCount`.
     */
    val safetyCallContacts: StateFlow<List<com.filestech.sms.domain.safetycall.SafetyCallContact>> =
        settings.flow
            .map { it.security.safetyCall.contacts }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * v1.12.0 — exposé pour afficher le bouton "Appeler 17" dans
     * [EmergencyScreen] uniquement quand l'user a opt-in dans Settings.
     * Le 112 reste toujours visible (SOS européen, pas de toggle).
     */
    val callPoliceEnabled: StateFlow<Boolean> = settings.flow
        .map { it.security.emergencyCallPoliceEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    // v1.14.0 `callBehavior` + `revertCallBehaviorIfPermissionRevoked` retirés
    // v1.14.1 : la refonte EmergencyScreen full-page utilise direct-call +
    // fallback automatique au composeur, le setting `emergencyCallBehavior`
    // est dead (clé DataStore préservée pour compat ascendante).

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

    // v1.14.5 — `previewTrigger` / `dismissPreview` / `_previewState` /
    // `_isPreviewLoading` / `DryRunPreview` / `redactPhoneNumber` retirés :
    // le bouton "Tester sans envoyer" a été supprimé de la page urgence
    // sur demande user (encombrait l'UI). Aucun caller restant — pas de
    // surface API conservée à l'extérieur du VM.

    /**
     * v1.14.1 — bouton "Désactiver le mode urgence" sur EmergencyScreen.
     * Pose `emergency.enabled = false` dans DataStore. Effets :
     *  - le gros bouton URGENCE devient `enabled = false` (grisé)
     *  - le `MainApplication` flow combine cancel la notif persistante
     *    lock-screen (raccourci 112/17 disparaît)
     *  - les sections Settings → Mode urgence montrent "désactivé"
     *  - le `BootReceiver` ne re-poste plus la notif au boot
     *
     * L'user peut re-activer en allant dans Réglages → Mode urgence ou
     * en re-cliquant sur l'icône Edit en haut de cette page (qui ouvre
     * EmergencySetupScreen). Le reset est immédiat, sans envoi SMS.
     *
     * PanicDecoy guard non nécessaire ici : l'écran lui-même est gated
     * en PanicDecoy via `AppRoot` (cf. v1.10.0 SEC-1).
     */
    fun disableEmergencyMode() = viewModelScope.launch {
        settings.update { s ->
            s.copy(
                security = s.security.copy(
                    // v1.14.5 hotfix — clear AUSSI `lastTriggeredAt` +
                    // `monotonicLastTriggeredAt` pour éviter le banner
                    // "Je vais bien" orphelin sur ConversationsScreen
                    // qui s'affichait 30 min post-trigger même après
                    // désactivation du mode urgence (user remonté
                    // 2026-05-22 : "j'ai désactivé mais j'ai toujours
                    // une alerte"). Désactiver = reset complet.
                    emergency = s.security.emergency.copy(
                        enabled = false,
                        lastTriggeredAt = 0L,
                        monotonicLastTriggeredAt = 0L,
                    ),
                    // v1.14.2 hotfix — cascade-disable du raccourci notif
                    // lock-screen. Avant : `emergencyShortcutEnabled` restait à
                    // `true` après disable du mode urgence → notif persistante
                    // ré-apparaissait à chaque lancement de l'app + quick action
                    // URGENCE encore tappable. Maintenant : désactivation
                    // complète en un clic.
                    emergencyShortcutEnabled = false,
                    emergencyCallPoliceEnabled = false,
                ),
            )
        }
    }

}
