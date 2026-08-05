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
    /**
     * v1.26.1 (audit H10) — le déclenchement d'urgence tourne sur la portée de l'APPLICATION,
     * pas sur celle du ViewModel. Voir [trigger] : `viewModelScope` était annulé dès que
     * l'utilisateur quittait l'écran, ce qui pouvait faire échouer l'envoi de vrais SMS
     * d'urgence, en silence. `EmergencyShortcutReceiver` utilisait déjà cette portée pour le
     * même travail — c'était une asymétrie entre deux chemins censés faire la même chose.
     */
    @com.filestech.sms.di.ApplicationScope
    private val appScope: kotlinx.coroutines.CoroutineScope,
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

    // Audit ARCH-L4 (v1.14.8) — `callPoliceEnabled` zombie retiré. Plus aucun consommateur
    // depuis v1.14.1 (refonte EmergencyScreen direct-call). Le StateFlow restait actif en
    // mémoire (collect DataStore continu) sans utilité.

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

    /**
     * v1.27.2 — instantane du config PERSISTE au moment ou l ecran s est ouvert.
     *
     * Sert a [hasUnsavedChanges]. Le Safety call en dispose depuis la v1.25.5 ; le mode urgence
     * etait reste en arriere, et quitter son ecran jetait le brouillon EN SILENCE.
     */
    private var snapshotInitial: EmergencyConfig = EmergencyConfig()

    /**
     * v1.27.2 — vrai quand le brouillon diverge de ce qui est enregistre.
     *
     * Constate sur appareil par Patrice le 2026-08-05 : « si je fais retour ca m indique rien ».
     * Activer le mode urgence puis sortir sans enregistrer perdait l intention sans un mot —
     * sur une fonction qui envoie de vrais SMS avec la position, croire l avoir activee alors
     * qu elle ne l est pas est exactement le mauvais sens d echec.
     */
    val hasUnsavedChanges: Boolean get() = _draft.value != snapshotInitial

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
            val initial = settings.flow.first().security.emergency
            snapshotInitial = initial
            _draft.value = initial
        }
    }

    // ──────────── Draft mutations (setup screen) ────────────

    fun setEnabled(enabled: Boolean) {
        _draft.value = _draft.value.copy(enabled = enabled)
        // v1.27.2 — 🔴 L'INTERRUPTEUR ÉTEINT IMMÉDIATEMENT. Constaté sur appareil par Patrice le
        // 2026-08-05 : « si je désactive avec le switch seul ça ne désactive pas, il faut taper
        // sur enregistrer ».
        //
        // Le Safety call règle ce cas depuis `d3f4f92`, avec un raisonnement qui vaut mot pour
        // mot ici — et le mode urgence était resté en arrière. Le jumeau asymétrique, encore.
        //
        // L'asymétrie interne, elle, est VOULUE et penche du côté sûr :
        //
        //  - **Éteindre** ne demande aucune validation et l'intention ne souffre aucune ambiguïté.
        //    Croire avoir coupé une fonction qui envoie de vrais SMS avec sa position, et la
        //    laisser active, est inacceptable.
        //  - **Allumer** reste soumis à [save], qui vérifie qu'il existe au moins un contact. Un
        //    mode urgence activé sans destinataire ne ferait rien, en silence.
        //
        // `appScope` et non `viewModelScope` : même raison que [save] et [disableEmergencyMode] —
        // l'écriture ne doit pas mourir avec l'écran. La cascade est identique à celle de [save],
        // raccourci d'écran verrouillé et appel police compris.
        if (!enabled) {
            appScope.launch {
                settings.update { s ->
                    s.copy(
                        security = s.security.copy(
                            emergency = s.security.emergency.copy(
                                enabled = false,
                                lastTriggeredAt = 0L,
                                monotonicLastTriggeredAt = 0L,
                            ),
                            emergencyShortcutEnabled = false,
                            emergencyCallPoliceEnabled = false,
                        ),
                    )
                }
                snapshotInitial = snapshotInitial.copy(
                    enabled = false,
                    lastTriggeredAt = 0L,
                    monotonicLastTriggeredAt = 0L,
                )
                _draft.value = _draft.value.copy(
                    lastTriggeredAt = 0L,
                    monotonicLastTriggeredAt = 0L,
                )
                _events.trySend(Event.DisabledImmediately)
            }
        }
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
        // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 `appScope`, PAS `viewModelScope`.
        //
        // Le jumeau asymétrique dans sa forme la plus pure. [disableEmergencyMode] a été passé sur
        // `appScope` précisément parce que l'écran se referme dès l'événement émis : le ViewModel
        // est détruit et l'écriture DataStore annulée en plein vol. Ce chemin-ci fait exactement la
        // MÊME cascade de désactivation — et il était resté sur `viewModelScope`.
        //
        // Conséquence : décocher « Activer le mode urgence » puis « Enregistrer » émettait
        // `Event.Saved`, l'écran se fermait, la coroutine mourait avant l'écriture. **L'utilisateur
        // était persuadé d'avoir éteint le mode urgence, et le raccourci d'écran verrouillé restait
        // posé et tappable.** Un réglage de sécurité qui ment sur son propre état.
        appScope.launch {
            val current = _draft.value
            settings.update { s ->
                val live = s.security.emergency
                // v1.27.2 — la MÊME cascade que [disableEmergencyMode] quand l'enregistrement
                // désactive le mode urgence.
                //
                // Le bouton dédié « Désactiver » de [EmergencyScreen] éteint le raccourci
                // d'écran verrouillé et l'appel police (cascade v1.14.2) ; ce chemin-ci —
                // décocher « Activer le mode urgence » puis « Enregistrer » — ne le faisait
                // pas. Le raccourci restait donc posé sur l'écran verrouillé, encore tappable,
                // alors que le mode était éteint. Le défaut était masqué : la migration de
                // réparation de [com.filestech.sms.MainApplication] le corrigeait en silence…
                // au COLD-START SUIVANT seulement.
                //
                // On efface aussi les horodatages de déclenchement, pour la même raison qu'en
                // v1.14.5 sur la jumelle : sinon le bandeau « alerte déclenchée récemment »
                // survit 30 min à une désactivation.
                val disabling = !current.enabled
                s.copy(
                    security = s.security.copy(
                        emergency = current.copy(
                            lastTriggeredAt = if (disabling) 0L else live.lastTriggeredAt,
                            monotonicLastTriggeredAt =
                            if (disabling) 0L else live.monotonicLastTriggeredAt,
                        ),
                        emergencyShortcutEnabled =
                        if (disabling) false else s.security.emergencyShortcutEnabled,
                        emergencyCallPoliceEnabled =
                        if (disabling) false else s.security.emergencyCallPoliceEnabled,
                    ),
                )
            }
            snapshotInitial = settings.flow.first().security.emergency
            _events.trySend(Event.Saved(enabled = current.enabled))
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
        // v1.26.1 (audit H10) — `appScope` et non `viewModelScope`.
        //
        // `TriggerEmergencyUseCase` résout la position AVANT la boucle d'envoi, ce qui peut
        // prendre jusqu'à 8 secondes. Pendant ce temps, un appui sur Retour, une bascule
        // d'application ou l'écran qui s'éteint annulait `viewModelScope` : AUCUN SMS ne partait,
        // et rien ne le disait. Une annulation en cours de boucle ne prévenait qu'une partie des
        // contacts, sans trace. C'est inacceptable pour un envoi d'urgence : une fois le geste
        // fait — appui maintenu 3 secondes — l'envoi doit aller au bout.
        appScope.launch {
            try {
                val result = triggerEmergency()
                _events.trySend(Event.Triggered(result))
            } finally {
                _isTriggerInFlight.set(false)
            }
        }
    }

    sealed interface Event {
        /**
         * v1.27.2 — porte l'état enregistré pour que l'écran nomme ce qui vient de se passer
         * (« Mode urgence activé » / « désactivé ») au lieu d'un « Enregistré » muet.
         */
        data class Saved(val enabled: Boolean) : Event

        /**
         * v1.27.2 — la désactivation est ÉCRITE. L'écran s'en sert pour revenir en arrière :
         * une fois le mode éteint il n'a plus d'objet, et son gros bouton rouge devient inerte.
         */
        /**
         * v1.27.2 — l'interrupteur a ETEINT le mode urgence sur-le-champ, sans passer par
         * « Enregistrer ». L'ecran s'en sert pour confirmer, sans quoi rien ne distinguerait
         * une desactivation effective d'un simple mouvement d'interrupteur.
         */
        data object DisabledImmediately : Event

        data object Disabled : Event
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
    fun disableEmergencyMode() = appScope.launch {
        // v1.27.2 — `appScope` et non `viewModelScope`, même raison qu'en v1.26.1 (audit H10)
        // pour [trigger].
        //
        // L'écran revient désormais en arrière dès que la désactivation est écrite. Avec
        // `viewModelScope`, ce retour détruisait le ViewModel et pouvait annuler l'écriture
        // DataStore en vol : le mode urgence serait resté ACTIF alors que l'utilisateur venait
        // de le couper et que l'application le lui avait confirmé. Le pire des deux sens.
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
        // v1.27.2 — émis APRÈS l'écriture : l'écran ne revient en arrière qu'une fois la
        // désactivation réellement enregistrée, jamais sur une intention.
        _events.trySend(Event.Disabled)
    }

}
