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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.LockMode
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.security.VaultManager
import com.filestech.sms.ui.components.ConversationRow
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
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
    // v1.13.0 audit SEC-2 — vaultPinRequired flow appelle `isVaultPinConfigured`
    // qui fait un DataStore.first() (I/O). Routé via `withContext(io)` pour ne
    // pas bloquer le Main thread pendant le cold-start.
    @com.filestech.sms.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<List<Conversation>> =
        repo.observeVault().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * v1.11.0 — Trou #3 Vault polish : lockMode courant, utilisé par
     * [VaultScreen] pour décider d'afficher un BiometricPrompt à l'entrée
     * (si l'user a `lockMode = BIOMETRIC`, on re-prompte au coffre comme
     * second-factor distinct du déverrouillage initial de l'app).
     */
    val lockMode: StateFlow<LockMode> = settings.flow
        .map { it.security.lockMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LockMode.OFF)

    /**
     * v1.13.0 — état effectif du second-factor PIN coffre. `true` si le flag
     * Settings est ON ET un hash est posé en SecurityStore. Si le flag est ON
     * mais le hash absent (état incohérent post-restore ou bug), on traite
     * comme OFF — l'user pourra reconfigurer depuis Réglages.
     */
    val vaultPinRequired: StateFlow<Boolean> = settings.flow
        .map { s ->
            if (!s.security.vaultPinEnabled) false
            else kotlinx.coroutines.withContext(io) { vaultPin.isVaultPinConfigured() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

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
     */
    fun bulkMoveSelectedOut() = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        var success = 0
        var failure = 0
        for (id in ids) {
            when (toggle.requestMoveToVault(id, intoVault = false)) {
                is Outcome.Success -> success++
                is Outcome.Failure -> failure++
            }
        }
        clearSelection()
        _events.trySend(
            if (success > 0) Event.MovedOut(count = success)
            else Event.MoveOutFailed(count = failure),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    val lockMode by viewModel.lockMode.collectAsStateWithLifecycle()
    val vaultPinRequired by viewModel.vaultPinRequired.collectAsStateWithLifecycle()
    // v1.13.0 — sélection multiple bulk move-out.
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    // v1.14.0 — wrap onBack pour verrouiller le coffre à CHAQUE sortie
    // explicite. PAS sur push ThreadScreen (composable ne quitte pas la nav
    // stack, juste mise en pause par le push d'écran au-dessus). Le wrapping
    // est centralisé pour ne pas oublier un call site (top-bar back, system
    // back, PIN cancel, biometric refused).
    val lockedOnBack: () -> Unit = remember(onBack) {
        {
            viewModel.lockVaultSession()
            onBack()
        }
    }

    // v1.13.0 — système back en mode sélection ⇒ sortir du mode sélection.
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        viewModel.clearSelection()
    }
    // v1.14.0 — système back HORS mode sélection ⇒ lock + onBack.
    androidx.activity.compose.BackHandler(enabled = !selectionMode) {
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
    val pinGateOpen = vaultPinRequired && !vaultPinPassed

    // v1.13.0 — détecte la présence d'un capteur biométrique pour proposer le
    // bouton "Utiliser la biométrie" dans le PinEntryDialog. Lecture pure côté
    // BiometricManager, pas d'effet de bord.
    val biometricAvailable = remember(ctx) {
        val bmgr = androidx.biometric.BiometricManager.from(ctx)
        bmgr.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    // v1.13.0 — helper biométrique factorisé. Appelé soit par le LaunchedEffect
    // (lockMode=BIOMETRIC) soit par le bouton "Utiliser la biométrie" du
    // PinEntryDialog. onError = ce qu'on fait si l'user annule ou si une erreur
    // matérielle se produit (différencié : LaunchedEffect → onBack ; bouton
    // dialog → garder le dialog ouvert pour retentative PIN/pass).
    val triggerBiometric: (onError: () -> Unit) -> Unit = remember {
        { onError ->
            val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
            val activity = ctx.findVaultActivity()
            if (activity == null) {
                onError()
            } else {
                val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                val prompt = androidx.biometric.BiometricPrompt(
                    activity,
                    executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                        ) {
                            vaultPinPassed = true
                            viewModel.markUnlocked()
                            unlocked = true
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            onError()
                        }
                    },
                )
                val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle(ctx.getString(R.string.vault_biometric_title))
                    .setSubtitle(ctx.getString(R.string.vault_biometric_subtitle))
                    .setNegativeButtonText(ctx.getString(R.string.action_cancel))
                    .setAllowedAuthenticators(authenticators)
                    .setConfirmationRequired(false)
                    .build()
                prompt.authenticate(info)
            }
        }
    }

    // v1.11.0 audit S1 — key = Unit (pas lockMode) pour éviter qu'un changement
    // de lockMode pendant que le prompt est en vol re-déclenche un second
    // BiometricPrompt (UX cassée sur certains OEMs Samsung/Xiaomi qui empilent
    // deux prompts simultanés). Le snapshot lockMode est lu UNE seule fois à
    // l'entrée de l'écran ; si l'user change son lockMode après être entré, ça
    // n'a pas d'effet sur cette session — comportement attendu.
    // v1.13.0 — clé composite (Unit, pinGateOpen) : à la 1ère composition le
    // pinGateOpen=true skip le bloc auth (return@LaunchedEffect immédiat).
    // Quand le user valide le PIN (vaultPinPassed=true), pinGateOpen passe à
    // false ET la key du LaunchedEffect change → relance qui passe le gate.
    LaunchedEffect(Unit, pinGateOpen) {
        if (pinGateOpen) return@LaunchedEffect
        val currentLockMode = lockMode
        if (unlocked == true) return@LaunchedEffect
        when (currentLockMode) {
            LockMode.BIOMETRIC -> {
                val bmgr = androidx.biometric.BiometricManager.from(ctx)
                val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                val canBio = bmgr.canAuthenticate(authenticators) ==
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                if (!canBio) {
                    // Biométrie indisponible (perdue, hardware off) → on accepte
                    // car l'user a déjà passé le verrouillage principal de l'app.
                    viewModel.markUnlocked()
                    unlocked = true
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
            when (unlocked) {
                null -> {
                    // Auth en cours : neutre, pas de contenu vault dévoilé.
                    Text(
                        text = stringResource(R.string.vault_biometric_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                false -> {
                    // v1.11.0 audit U2 — auth refusée : `onBack()` est déjà
                    // appelé dans le callback `onAuthenticationError`. On
                    // affiche le même placeholder que pendant l'auth pour
                    // éviter un flash blanc entre la recomposition
                    // `unlocked=false` et le popBackStack effectif (1-2
                    // frames sur slow device).
                    Text(
                        text = stringResource(R.string.vault_biometric_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                true -> {
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
    if (pinGateOpen) {
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
