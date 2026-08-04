package com.filestech.sms.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withResumed
import com.filestech.sms.R
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.LockMode
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.security.VaultManager
import com.filestech.sms.ui.components.ConversationRow
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.security.StrongBiometrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    repo: ConversationRepository,
    private val vault: VaultManager,
    private val toggle: ToggleConversationStateUseCase,
    settings: SettingsRepository,
    private val vaultPin: com.filestech.sms.security.VaultPinManager,
    private val biometricGate: com.filestech.sms.ui.security.BiometricGate,
    // v1.13.0 audit SEC-2 — vaultPinRequired flow appelle `isVaultPinConfigured`
    // qui fait un DataStore.first() (I/O). Routé via `withContext(io)` pour ne
    // pas bloquer le Main thread pendant le cold-start.
    @com.filestech.sms.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<List<Conversation>> =
        repo.observeVault().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * v1.25.4 — conditions d'entrée du Coffre, **ou `null` tant qu'elles ne sont pas lues**.
     *
     * Elles étaient exposées en deux `StateFlow` distincts, chacun avec une valeur de repli
     * (`LockMode.OFF`, `false`) servie tant que le DataStore n'avait pas répondu. L'effet d'entrée
     * partait sur ces valeurs de repli, tombait dans la branche « aucun verrou configuré » et
     * appelait `markUnlocked()` : **le Coffre s'ouvrait tout seul, avant tout second facteur**, et
     * son contenu s'affichait derrière le dialogue de PIN qui arrivait ensuite.
     *
     * Un repli qui signifie « pas de verrou » est un choix qui échoue du mauvais côté. Ici
     * l'absence de réponse se dit `null`, et l'appelant n'a alors rien à décider : il attend.
     *
     * Regroupées en un seul état parce qu'elles se lisent ensemble : deux flux séparés se
     * résolvent à deux instants différents, et c'est exactement l'intervalle entre les deux qui
     * ouvrait la faille.
     */
    data class EntryGate(val lockMode: LockMode, val pinRequired: Boolean)

    val entryGate: StateFlow<EntryGate?> = settings.flow
        .map { s ->
            EntryGate(
                lockMode = s.security.lockMode,
                // v1.13.0 audit SEC-2 — `isVaultPinConfigured` fait un DataStore.first() (I/O),
                // routé via `withContext(io)` pour ne pas bloquer le Main pendant le cold-start.
                // Flag ON mais hash absent (restauration incohérente) ⇒ traité comme OFF :
                // l'utilisateur pourra reconfigurer depuis les Réglages.
                pinRequired = if (!s.security.vaultPinEnabled) {
                    false
                } else {
                    kotlinx.coroutines.withContext(io) { vaultPin.isVaultPinConfigured() }
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** v1.13.0 — verify suspend pour le `PinEntryDialog`. */
    suspend fun verifyVaultPin(candidate: CharArray): Boolean = vaultPin.verifyVaultPin(candidate)

    /**
     * v1.13.1 — `true` si l'user a déjà déverrouillé le coffre dans la session
     * courante (via PIN coffre OU biométrie OU `lockMode != BIOMETRIC`). Le
     * flag vit dans [VaultManager.sessionUnlocked] (Singleton, AtomicBoolean),
     * donc persiste à travers les navigations ThreadScreen ↔ VaultScreen et
     * les recompositions Compose. Évite le re-prompt PIN à chaque retour
     * arrière sur le VaultScreen.
     *
     * `lock()` est appelé sur autoLock / panic / process kill → reset à false
     * → l'user re-saisira son PIN coffre au prochain accès, comportement
     * attendu.
     */
    fun isVaultSessionUnlocked(): Boolean = vault.isVaultUnlockedInSession

    /**
     * v1.14.0 — verrouille explicitement le coffre. Appelé à chaque sortie
     * EXPLICITE de [VaultScreen] (tap back, system back, cancel PIN dialog,
     * biometric refused). PAS appelé lors d'une navigation vers une conv
     * vault (ThreadScreen) — le retour `ON_PAUSE` Compose ne déclenche RIEN,
     * `sessionUnlocked` persiste à travers l'aller-retour ThreadScreen
     * ↔ VaultScreen (cf. fix v1.13.1).
     *
     * Idempotent. Si déjà locked, no-op.
     */
    fun lockVaultSession() = vault.lock()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * v1.13.0 — sélection multiple. Symétrique de
     * [com.filestech.sms.ui.screens.conversations.ConversationsViewModel] mais
     * pour l'action "Sortir N conv du coffre". Pas de protection PanicDecoy ici
     * car l'écran lui-même est inatteignable en PanicDecoy (gated dans AppRoot).
     */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    val selectionMode: StateFlow<Boolean> = _selectedIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    sealed interface Event {
        data class MovedOut(val count: Int) : Event
        data class MoveOutFailed(val count: Int) : Event
    }

    /**
     * Audit P0-1 (v1.2.0): the vault is gated by [VaultManager.sessionUnlocked] which arms the
     * `moveToVault` / `moveOutOfVault` write paths. Until this VM was wired, the flag was never
     * raised, so write operations either silently failed (when properly routed) OR succeeded
     * through the legacy bypass — depending on which path the caller took. We mark the session
     * unlocked the moment the user reaches this screen because reaching it implies they already
     * passed the AppLock (PIN / biometric / disabled lock), so it is a fair second-factor.
     */
    fun markUnlocked() = vault.markUnlocked()

    /** v1.25.3 — voir [com.filestech.sms.ui.security.BiometricGate]. Le coffre exige la même
     *  preuve cryptographique que l'écran de verrouillage : il garde des conversations. */
    suspend fun prepareBiometricGate() = biometricGate.prepare()

    fun confirmBiometricGate(cipher: javax.crypto.Cipher?): Boolean = biometricGate.confirm(cipher)

    fun toggleSelection(id: Long) {
        _selectedIds.update { current -> if (current.contains(id)) current - id else current + id }
    }

    fun clearSelection() {
        _selectedIds.update { emptySet() }
    }

    /**
     * v1.11.0 — Trou #2 Vault polish : sortir une conv du coffre depuis le
     * long-press dans [VaultScreen]. La session est déjà unlocked (markUnlocked
     * appelé au LaunchedEffect d'entrée), donc le guard
     * [VaultManager.moveOutOfVault] accepte. L'opération émet un Event
     * pour le snackbar (succès / échec).
     */
    fun moveOutOfVault(conversationId: Long) {
        viewModelScope.launch {
            val outcome = toggle.requestMoveToVault(conversationId, intoVault = false)
            _events.trySend(
                when (outcome) {
                    is Outcome.Success -> Event.MovedOut(count = 1)
                    is Outcome.Failure -> Event.MoveOutFailed(count = 1)
                },
            )
        }
    }

    /**
     * v1.13.0 — bulk move-out symétrique de
     * [com.filestech.sms.ui.screens.conversations.ConversationsViewModel.bulkMoveSelectedToVault].
     *
     * v1.14.8 R8 — Remplace la boucle itérative N×requestMoveToVault par UN appel
     * [VaultManager.requestBulkMoveToVault] qui wrap les N updates dans une transaction Room
     * atomique. Garantit : soit tout passe, soit rien (rollback en cas de process-kill / erreur).
     */
    fun bulkMoveSelectedOut() = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        val outcome = toggle.requestBulkMoveToVault(ids = ids, intoVault = false)
        clearSelection()
        _events.trySend(
            when (outcome) {
                is Outcome.Success<Int> -> {
                    if (outcome.value > 0) Event.MovedOut(count = outcome.value)
                    else Event.MoveOutFailed(count = ids.size)
                }
                is Outcome.Failure -> Event.MoveOutFailed(count = ids.size)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    // v1.25.4 — `null` tant que les réglages n'ont pas répondu. Voir [VaultViewModel.EntryGate].
    val entryGate by viewModel.entryGate.collectAsStateWithLifecycle()
    val gateResolved = entryGate != null
    // v1.13.0 — sélection multiple bulk move-out.
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    /*
     * v1.25.4 — le Coffre ne réagit qu'une fois sa destination réellement au premier plan.
     *
     * Sous un `NavHost`, l'écran qu'on dépile et celui qu'on découvre sont composés **en même
     * temps** le temps de la transition. Le Coffre y enregistrait donc ses `BackHandler` alors que
     * le geste de retour parti depuis la conversation n'était pas encore terminé — et comme
     * `OnBackPressedDispatcher` donne la priorité au callback enregistré en dernier, c'est le
     * Coffre qui en recevait la fin. Un seul retour produisait deux dépilements : la conversation,
     * puis le Coffre. D'où le dialogue de second facteur qui « essaie d'apparaître et disparaît
     * aussitôt », et le retour direct à la liste.
     *
     * Le garde de la v1.25.2 rendait `lockedOnBack` idempotent, ce qui empêchait le Coffre d'être
     * dépilé DEUX fois — pas d'être dépilé pour un retour qui ne lui était pas destiné.
     *
     * `LocalLifecycleOwner` est ici la `NavBackStackEntry` du Coffre : elle n'atteint `RESUMED`
     * qu'une fois la transition finie. Tant qu'on n'y est pas, ni les `BackHandler` ni le dialogue
     * de PIN ne sont armés, donc rien ne peut capter un geste destiné à l'écran précédent.
     */
    val navEntryOwner = LocalLifecycleOwner.current
    val navEntryState by navEntryOwner.lifecycle.currentStateAsState()
    val navEntryResumed = navEntryState.isAtLeast(Lifecycle.State.RESUMED)

    // v1.14.0 — wrap onBack pour verrouiller le coffre à CHAQUE sortie
    // explicite. PAS sur push ThreadScreen (composable ne quitte pas la nav
    // stack, juste mise en pause par le push d'écran au-dessus). Le wrapping
    // est centralisé pour ne pas oublier un call site (top-bar back, system
    // back, PIN cancel, biometric refused).
    // v1.25.2 — garde d'idempotence : sortir du Coffre ne doit se produire qu'UNE fois. Sans ça,
    // pendant l'animation de pop (VaultScreen glisse en sortie ~160 ms), un second Retour rapide
    // atteignait encore le BackHandler de VaultScreen (toujours composé le temps de la transition)
    // et rappelait `lockedOnBack` → double `popBackStack` → dépilement de Conversations → NavHost
    // vide = page blanche bloquée. L'AtomicBoolean (remember sans clé, stable sur toute la vie du
    // composable) rend tout appel suivant no-op. Une nouvelle entrée dans le Coffre = nouveau garde.
    val backGuard = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val lockedOnBack: () -> Unit = remember(onBack, backGuard) {
        {
            if (backGuard.compareAndSet(false, true)) {
                viewModel.lockVaultSession()
                onBack()
            }
        }
    }

    // v1.13.0 — système back en mode sélection ⇒ sortir du mode sélection.
    // v1.25.4 — armé seulement une fois la destination au premier plan (cf. [navEntryResumed]).
    androidx.activity.compose.BackHandler(enabled = selectionMode && navEntryResumed) {
        viewModel.clearSelection()
    }
    // v1.14.0 — système back HORS mode sélection ⇒ lock + onBack.
    androidx.activity.compose.BackHandler(enabled = !selectionMode && navEntryResumed) {
        lockedOnBack()
    }

    // v1.11.0 — Trou #3 Vault polish : BiometricPrompt à l'entrée si l'user
    // a `lockMode = BIOMETRIC`. Second-factor distinct du déverrouillage
    // initial de l'app (protège contre l'épaule curieuse pendant que l'app
    // est ouverte). Pour les autres lockMode, on entre direct car l'user
    // a déjà passé son PIN/pattern principal (ou aucun lock).
    //
    // `null` = pas encore évalué. `true` = autorisé, liste visible.
    // `false` = refusé, on revient en arrière sans rien dévoiler.
    // v1.13.1 — init depuis `VaultManager.sessionUnlocked` pour ne pas re-déclencher
    // BiometricPrompt sur un retour ThreadScreen → VaultScreen. Le Singleton state
    // est l'autorité pour "déjà déverrouillé dans la session courante".
    var unlocked by remember {
        mutableStateOf<Boolean?>(if (viewModel.isVaultSessionUnlocked()) true else null)
    }
    // v1.27.2 (audit externe Gemini 2026-08-04) — la biométrie est le SEUL second facteur de ce
    // coffre (aucun code coffre configuré) et elle est indisponible. On ne peut donc pas
    // authentifier : on l'explique au lieu d'ouvrir. Cf. l'effet d'entrée plus bas.
    var biometricUnavailable by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // v1.13.0 — si le PIN/pass distinct coffre est ON, on attend la validation
    // du PinEntryDialog avant d'enchaîner sur le flow biométrique. Le PinDialog
    // est rendu plus bas, son onSuccess flippe `vaultPinPassed=true` puis le
    // LaunchedEffect ci-dessous se relance via la clé composite et procède.
    // v1.13.1 — initialisation depuis `VaultManager.sessionUnlocked` (Singleton,
    // AtomicBoolean) pour préserver le déverrouillage à travers les navigations
    // ThreadScreen ↔ VaultScreen. Sans ça, le retour arrière depuis ThreadScreen
    // recompose VaultScreen, le `remember` revient à `false`, et le dialog PIN
    // ré-apparaît furtivement (bug v1.13.0 remonté user). Le sessionUnlocked
    // est reset par autoLock / panic / process kill → re-prompt attendu.
    var vaultPinPassed by remember {
        mutableStateOf(viewModel.isVaultSessionUnlocked())
    }
    // v1.25.4 — tant que [gateResolved] est faux on ne sait pas si un PIN est exigé : on ne
    // prétend donc pas que la porte est ouverte, et [revealContent] plus bas garde le contenu
    // masqué pendant ce temps.
    val pinGateOpen = entryGate?.pinRequired == true && !vaultPinPassed

    // v1.13.0 — détecte la présence d'un capteur biométrique pour proposer le
    // bouton "Utiliser la biométrie" dans le PinEntryDialog. Lecture pure côté
    // BiometricManager, pas d'effet de bord.
    // v1.25.3 (audit H2) — Classe 3 exigée, via l'unique politique [StrongBiometrics] : le coffre
    // acceptait jusqu'ici la Classe 2 (reconnaissance faciale 2D), soit une photo du visage.
    val biometricAvailable = remember(ctx) { StrongBiometrics.isAvailable(ctx) }

    // v1.13.0 — helper biométrique factorisé. Appelé soit par le LaunchedEffect
    // (lockMode=BIOMETRIC) soit par le bouton "Utiliser la biométrie" du
    // PinEntryDialog. onError = ce qu'on fait si l'user annule ou si une erreur
    // matérielle se produit (différencié : LaunchedEffect → onBack ; bouton
    // dialog → garder le dialog ouvert pour retentative PIN/pass).
    val gateScope = androidx.compose.runtime.rememberCoroutineScope()
    // v1.25.4 — mêmes deux gardes que [com.filestech.sms.ui.screens.lock.LockScreen], absents ici
    // alors que la v1.25.3 y a introduit la même fenêtre asynchrone (`prepareBiometricGate` est
    // suspend et touche le matériel sécurisé avant que le prompt ne parte). Le coffre garde des
    // conversations : il n'a aucune raison d'être moins protégé que l'écran de verrouillage.
    val promptInFlight = remember { mutableStateOf(false) }
    val triggerBiometric: (onError: () -> Unit) -> Unit = remember(gateScope, navEntryOwner) {
        fun(onError: () -> Unit) {
            val activity = ctx.findVaultActivity()
            if (activity == null) {
                onError()
            } else if (!promptInFlight.value) {
                // Un prompt déjà en vol fait tomber l'appel dans l'absence de branche `else` :
                // on l'ignore en silence, sans `onError`. Celui-ci ramène en arrière ou rouvre le
                // dialogue PIN, ce qui saborderait le prompt effectivement affiché.
                //
                // Le drapeau se lève **ici**, avant le `launch` : `prepareBiometricGate()` est
                // suspend, donc le lever à l'intérieur laisserait deux appels rapprochés franchir
                // le garde pendant la préparation. Contrairement au `LaunchedEffect` de
                // [com.filestech.sms.ui.screens.lock.LockScreen], que le changement de clé annule
                // avant de le relancer, ce lambda est appelable depuis deux endroits (l'effet
                // d'entrée et le bouton du dialogue PIN) et rien ne sérialise ses invocations.
                promptInFlight.value = true
                gateScope.launch {
                    // `handedOff` distingue « le prompt est parti, les callbacks ont la main » de
                    // « on a renoncé avant » — seul ce second cas doit rabaisser le drapeau.
                    var handedOff = false
                    try {
                        // v1.25.3 — clé Keystore adossée à la biométrie, préparée hors thread
                        // principal. `Invalidated` est traité comme une indisponibilité : c'est
                        // [LockScreen] qui désarme le réglage au prochain déverrouillage de l'app,
                        // le coffre n'ayant pas autorité sur le mode de verrouillage global.
                        val prepared = viewModel.prepareBiometricGate()
                        val gateCipher = when (prepared) {
                            is com.filestech.sms.ui.security.BiometricGate.Prepared.Ready -> prepared.cipher
                            else -> {
                                onError()
                                return@launch
                            }
                        }
                        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                        val prompt = androidx.biometric.BiometricPrompt(
                            activity,
                            executor,
                            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(
                                    result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                                ) {
                                    promptInFlight.value = false
                                    // Sans `doFinal` accepté par le Keystore, le succès annoncé
                                    // n'est adossé à aucune clé : on refuse l'ouverture du coffre.
                                    if (viewModel.confirmBiometricGate(result.cryptoObject?.cipher)) {
                                        vaultPinPassed = true
                                        viewModel.markUnlocked()
                                        unlocked = true
                                    } else {
                                        onError()
                                    }
                                }

                                override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence,
                                ) {
                                    promptInFlight.value = false
                                    onError()
                                }
                            },
                        )
                        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                            .setTitle(ctx.getString(R.string.vault_biometric_title))
                            .setSubtitle(ctx.getString(R.string.vault_biometric_subtitle))
                            .setNegativeButtonText(ctx.getString(R.string.action_cancel))
                            .setAllowedAuthenticators(StrongBiometrics.AUTHENTICATORS)
                            .setConfirmationRequired(false)
                            .build()
                        // v1.25.4 — `withResumed` : passé `onSaveInstanceState`, androidx.biometric
                        // 1.1.0 renonce silencieusement (test `isStateSaved()`, journalisation puis
                        // retour) et n'appelle **aucun** callback. Le coffre restait alors sans
                        // prompt et sans repli, `onError` n'étant jamais atteint — il fallait sortir
                        // au bouton retour. On attend le premier plan au lieu de renoncer.
                        navEntryOwner.lifecycle.withResumed {
                            prompt.authenticate(
                                info,
                                androidx.biometric.BiometricPrompt.CryptoObject(gateCipher),
                            )
                            handedOff = true
                        }
                    } finally {
                        // Renoncement avant l'envoi (clé indisponible, annulation pendant
                        // l'attente) : aucun callback ne viendra rabaisser le drapeau, et le
                        // laisser levé condamnerait toute tentative ultérieure.
                        if (!handedOff) promptInFlight.value = false
                    }
                }
            }
        }
    }

    // v1.11.0 audit S1 — key = Unit (pas lockMode) pour éviter qu'un changement
    // de lockMode pendant que le prompt est en vol re-déclenche un second
    // BiometricPrompt (UX cassée sur certains OEMs Samsung/Xiaomi qui empilent
    // deux prompts simultanés). Le snapshot lockMode est lu UNE seule fois à
    // l'entrée de l'écran ; si l'user change son lockMode après être entré, ça
    // n'a pas d'effet sur cette session — comportement attendu.
    // v1.13.0 — clé composite (…, pinGateOpen) : à la 1ère composition le
    // pinGateOpen=true skip le bloc auth (return@LaunchedEffect immédiat).
    // Quand le user valide le PIN (vaultPinPassed=true), pinGateOpen passe à
    // false ET la key du LaunchedEffect change → relance qui passe le gate.
    //
    // v1.25.4 — la clé `Unit` devient [gateResolved]. Avec `Unit`, l'effet partait dès la
    // première composition, quand les réglages n'étaient pas encore lus : `lockMode` valait
    // encore son repli `OFF`, la branche `else` concluait « aucun verrou » et appelait
    // `markUnlocked()`. Le Coffre s'ouvrait donc **avant** le second facteur, et son contenu
    // restait affiché derrière le dialogue de PIN qui apparaissait juste après.
    //
    // Passer de faux à vrai relance l'effet une fois, avec les vraies valeurs. Une modification
    // ultérieure des réglages ne le relance pas : [gateResolved] reste vrai, ce qui préserve
    // l'instantané voulu ci-dessus.
    LaunchedEffect(gateResolved, pinGateOpen) {
        val gate = entryGate ?: return@LaunchedEffect
        if (pinGateOpen) return@LaunchedEffect
        val currentLockMode = gate.lockMode
        if (unlocked == true) return@LaunchedEffect
        when (currentLockMode) {
            LockMode.BIOMETRIC -> {
                if (!StrongBiometrics.isAvailable(ctx)) {
                    // v1.27.2 (audit externe Gemini 2026-08-04) — on N'OUVRE PLUS.
                    //
                    // Avant : « l'utilisateur a déjà passé le verrouillage principal, on
                    // accepte ». Le repli échouait du mauvais côté. Sur ce chemin — mode
                    // biométrique ET aucun code coffre configuré — la biométrie est le SEUL
                    // second facteur du Coffre : la rendre indisponible le supprimait
                    // entièrement. Retirer ses empreintes, ou un capteur en panne, et le Coffre
                    // s'ouvrait tout seul pour quiconque tient le téléphone déverrouillé.
                    //
                    // L'asymétrie était parlante : [com.filestech.sms.ui.screens.lock.LockScreen]
                    // retombe sur le PIN quand la biométrie manque ; ici on retombait sur RIEN.
                    //
                    // Pourquoi refuser plutôt que retomber sur le PIN principal : sur ce chemin
                    // il l'a DÉJÀ saisi pour ouvrir l'application (LockScreen bascule sur le PIN
                    // dès que la biométrie manque). Le redemander ne prouverait rien de plus et
                    // ne serait qu'une mise en scène. Le second facteur du Coffre doit être un
                    // secret DISTINCT — c'est le code coffre, et il se configure dans les
                    // Réglages, hors du Coffre : personne n'est enfermé dehors.
                    biometricUnavailable = true
                    return@LaunchedEffect
                }
                triggerBiometric {
                    // User refused or hardware error → on quitte sans dévoiler la
                    // liste. Le markUnlocked n'est PAS appelé → le filtre repo
                    // continue à retourner emptyList() si PanicDecoy + safe fallback.
                    // v1.14.0 — lockVault + back pour cohérence "session non
                    // ouverte" (le user n'est jamais entré, pas besoin de lock
                    // explicite mais defensive — sessionUnlocked est déjà false).
                    unlocked = false
                    lockedOnBack()
                }
            }
            else -> {
                // Pas de biométrie configurée comme lock principal → entrée directe.
                // L'user a déjà fait son authentification primaire (PIN, pattern,
                // ou pas de lock du tout) pour ouvrir SMS Tech.
                viewModel.markUnlocked()
                unlocked = true
            }
        }
    }

    // v1.13.1 — long-press hors sélection ouvre un ActionsSheet (single-conv
    // quick action "Sortir du coffre" + entrée mode sélection multiple). Le
    // legacy ModalBottomSheet v1.11.0 est ressuscité pour préserver la UX
    // "appui long → action rapide" attendue par les utilisateurs.
    var vaultSheetTarget by remember { mutableStateOf<Long?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultViewModel.Event.MovedOut -> {
                    val msg = if (event.count <= 1) ctx.getString(R.string.vault_move_out_done)
                    else ctx.resources.getQuantityString(
                        R.plurals.vault_bulk_move_out_done, event.count, event.count,
                    )
                    snackbarHost.showSnackbar(msg)
                }
                is VaultViewModel.Event.MoveOutFailed ->
                    snackbarHost.showError(ctx.getString(R.string.vault_move_out_failed))
            }
        }
    }

    Scaffold(
        topBar = topBar@{
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = androidx.compose.ui.res.pluralStringResource(
                                id = R.plurals.conversations_selection_count,
                                count = selectedIds.size,
                                selectedIds.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.bulkMoveSelectedOut() }) {
                            Icon(
                                Icons.Outlined.LockOpen,
                                contentDescription = stringResource(R.string.bulk_vault_move_out),
                            )
                        }
                    },
                )
                return@topBar
            }
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title)) },
                navigationIcon = {
                    IconButton(onClick = lockedOnBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // v1.25.4 — le dévoilement exige les TROIS conditions, et plus le seul `unlocked`.
            //
            // `pinGateOpen` ne commandait jusqu'ici que l'affichage du dialogue de PIN, pas le
            // contenu : un `unlocked` passé à vrai par ailleurs laissait donc la liste du Coffre
            // lisible **derrière** ce dialogue. Et tant que les réglages ne sont pas lus, on ne
            // sait pas encore si un PIN sera exigé — afficher pendant cet intervalle reviendrait
            // à parier que non.
            //
            // Deuxième barrière, volontairement redondante avec la garde de l'effet d'entrée :
            // celle-ci décide de ne pas ouvrir, celle-là de ne rien montrer. Il faut que les deux
            // cèdent pour que le contenu fuie.
            val revealContent = gateResolved && !pinGateOpen && unlocked == true
            when {
                !revealContent -> {
                    // Auth en cours, refusée, ou réglages pas encore lus : neutre, aucun contenu
                    // du Coffre dévoilé. Même placeholder dans les trois cas — l'écran ne doit pas
                    // laisser deviner laquelle des trois situations il traverse.
                    //
                    // v1.11.0 audit U2 — sur refus, `onBack()` est déjà appelé depuis le callback
                    // `onAuthenticationError` ; ce placeholder évite le flash blanc entre la
                    // recomposition et le `popBackStack` effectif (1-2 frames sur appareil lent).
                    Text(
                        text = stringResource(R.string.vault_biometric_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> {
                    if (rows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.vault_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(rows, key = { it.id }) { c ->
                                val isSelected = selectedIds.contains(c.id)
                                // v1.13.0 — background tinted en selected + check overlay
                                val rowBg = if (isSelected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surface
                                Box(modifier = Modifier.background(rowBg)) {
                                    ConversationRow(
                                        conversation = c,
                                        // v1.13.0 — tap en sélection = toggle, sinon ouvre
                                        // le thread comme avant.
                                        onClick = {
                                            if (selectionMode) viewModel.toggleSelection(c.id)
                                            else onOpenThread(c.id)
                                        },
                                        // v1.13.1 — long-press : si en sélection, toggle
                                        // (cohérent avec Gmail). Sinon, ouvre l'ActionsSheet
                                        // legacy qui offre (a) "Sortir du coffre" quick action
                                        // single-conv, (b) "Sélection multiple..." pour
                                        // entrer en mode batch. Restaure le flow v1.12 que
                                        // l'user attend, sans perdre le multi-select v1.13.
                                        onLongClick = {
                                            if (selectionMode) viewModel.toggleSelection(c.id)
                                            else vaultSheetTarget = c.id
                                        },
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 16.dp)
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(cs.primary),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = stringResource(R.string.selected),
                                                tint = cs.onPrimary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }
    }

    // v1.13.0 — PinEntryDialog gate quand vaultPinRequired ET pas encore validé.
    // Rendu HORS du Scaffold pour qu'il flotte au-dessus du fond neutre.
    // Succès PIN/pass → on considère le second-factor VALIDÉ et on entre direct
    // (markUnlocked + unlocked=true). On NE re-prompt PAS la biométrie d'app
    // au-dessus (double second-factor = friction inutile : l'user a déjà
    // démontré qu'il connaît le secret distinct du coffre).
    // Annulation → onBack() (sortie de l'écran).
    // v1.25.4 — pas avant que la destination ne soit au premier plan : présenté pendant la
    // transition, ce dialogue captait lui aussi la fin du geste de retour venu de la conversation
    // et se refermait dans la foulée en appelant `onCancel` — donc `lockedOnBack`.
    // v1.27.2 (audit externe Gemini 2026-08-04) — biométrie indisponible et aucun code coffre :
    // on explique et on ressort, au lieu d'ouvrir. Un dialogue plutôt qu'un message éphémère,
    // qui ne s'afficherait pas puisqu'on quitte l'écran dans la foulée.
    // ⚠️ `navEntryResumed` OBLIGATOIRE, même raison que le dialogue de PIN juste en dessous :
    // présenté pendant l'animation de retour, ce dialogue capterait la fin du geste venu de
    // l'écran précédent et se refermerait aussitôt en appelant `lockedOnBack` — le dépilement
    // parasite corrigé en v1.25.4. Omise à la première écriture de ce correctif, rattrapée à la
    // relecture.
    if (biometricUnavailable && navEntryResumed) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { biometricUnavailable = false; lockedOnBack() },
            title = { Text(stringResource(R.string.vault_biometric_unavailable_title)) },
            text = { Text(stringResource(R.string.vault_biometric_unavailable_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { biometricUnavailable = false; lockedOnBack() },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
        )
    }

    if (pinGateOpen && navEntryResumed) {
        com.filestech.sms.ui.components.PinEntryDialog(
            title = stringResource(R.string.vault_pin_dialog_title),
            description = stringResource(R.string.vault_pin_dialog_subtitle),
            confirmLabel = stringResource(R.string.vault_pin_dialog_unlock),
            onVerify = { candidate -> viewModel.verifyVaultPin(candidate) },
            onVerified = {
                vaultPinPassed = true
                viewModel.markUnlocked()
                unlocked = true
            },
            onCancel = { lockedOnBack() },
            onUseBiometric = if (biometricAvailable) {
                {
                    triggerBiometric {
                        // Annulation biométrique → on RESTE sur le dialog PIN
                        // (vaultPinPassed inchangé). L'user peut retenter
                        // PIN/pass ou cancel pour sortir.
                    }
                }
            } else null,
        )
    }

    // v1.13.1 — ActionsSheet single-conv : long-press sur un row vault ouvre
    // ce sheet avec (a) action rapide "Sortir du coffre" pour la conv ciblée,
    // (b) entrée en mode sélection multiple si l'user veut batch. Restaure le
    // pattern v1.12 attendu (appui long = action rapide) sans perdre le multi.
    val pendingSheet = vaultSheetTarget
    if (pendingSheet != null) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { vaultSheetTarget = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.vault_actions_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.vault_move_out)) },
                    modifier = Modifier.clickable {
                        viewModel.moveOutOfVault(pendingSheet)
                        vaultSheetTarget = null
                    },
                )
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.Check, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.bulk_select_multiple)) },
                    modifier = Modifier.clickable {
                        viewModel.toggleSelection(pendingSheet)
                        vaultSheetTarget = null
                    },
                )
            }
        }
    }
}

/**
 * v1.11.0 — helper local pour remonter le `FragmentActivity` à partir du
 * `LocalContext` Compose. Copie volontaire du même helper privé de
 * [com.filestech.sms.ui.screens.lock.LockScreen] : pas exposé en util
 * partagé pour limiter la surface API (3 lignes triviales). Si un 3ᵉ
 * call site apparait, factoriser dans `core.ui.findFragmentActivity()`.
 */
private tailrec fun android.content.Context.findVaultActivity(): androidx.fragment.app.FragmentActivity? = when (this) {
    is androidx.fragment.app.FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findVaultActivity()
    is android.app.Activity -> null
    else -> null
}
