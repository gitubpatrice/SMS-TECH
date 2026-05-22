package com.filestech.sms.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    repo: ConversationRepository,
    private val vault: VaultManager,
    private val toggle: ToggleConversationStateUseCase,
    settings: SettingsRepository,
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

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    sealed interface Event {
        data object MovedOut : Event
        data object MoveOutFailed : Event
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
                    is Outcome.Success -> Event.MovedOut
                    is Outcome.Failure -> Event.MoveOutFailed
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    val lockMode by viewModel.lockMode.collectAsStateWithLifecycle()

    // v1.11.0 — Trou #3 Vault polish : BiometricPrompt à l'entrée si l'user
    // a `lockMode = BIOMETRIC`. Second-factor distinct du déverrouillage
    // initial de l'app (protège contre l'épaule curieuse pendant que l'app
    // est ouverte). Pour les autres lockMode, on entre direct car l'user
    // a déjà passé son PIN/pattern principal (ou aucun lock).
    //
    // `null` = pas encore évalué. `true` = autorisé, liste visible.
    // `false` = refusé, on revient en arrière sans rien dévoiler.
    var unlocked by remember { mutableStateOf<Boolean?>(null) }
    val ctx = LocalContext.current

    // v1.11.0 audit S1 — key = Unit (pas lockMode) pour éviter qu'un changement
    // de lockMode pendant que le prompt est en vol re-déclenche un second
    // BiometricPrompt (UX cassée sur certains OEMs Samsung/Xiaomi qui empilent
    // deux prompts simultanés). Le snapshot lockMode est lu UNE seule fois à
    // l'entrée de l'écran ; si l'user change son lockMode après être entré, ça
    // n'a pas d'effet sur cette session — comportement attendu.
    LaunchedEffect(Unit) {
        val currentLockMode = lockMode
        if (unlocked == true) return@LaunchedEffect
        when (currentLockMode) {
            LockMode.BIOMETRIC -> {
                val bmgr = androidx.biometric.BiometricManager.from(ctx)
                val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                val canBio = bmgr.canAuthenticate(authenticators) ==
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                val activity = ctx.findVaultActivity()
                if (!canBio || activity == null) {
                    // Biométrie indisponible (perdue, hardware off) → on accepte
                    // car l'user a déjà passé le verrouillage principal de l'app.
                    viewModel.markUnlocked()
                    unlocked = true
                    return@LaunchedEffect
                }
                val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                val prompt = androidx.biometric.BiometricPrompt(
                    activity,
                    executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                        ) {
                            viewModel.markUnlocked()
                            unlocked = true
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            // User refused or hardware error → on quitte sans
                            // dévoiler la liste. Le markUnlocked n'est PAS appelé
                            // → le filtre repo continue à retourner emptyList()
                            // si PanicDecoy + safe fallback ailleurs.
                            unlocked = false
                            onBack()
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
            else -> {
                // Pas de biométrie configurée comme lock principal → entrée directe.
                // L'user a déjà fait son authentification primaire (PIN, pattern,
                // ou pas de lock du tout) pour ouvrir SMS Tech.
                viewModel.markUnlocked()
                unlocked = true
            }
        }
    }

    // v1.11.0 — long-press → ModalBottomSheet "Sortir du coffre".
    var sheetTarget by remember { mutableStateOf<Long?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                VaultViewModel.Event.MovedOut ->
                    snackbarHost.showSnackbar(ctx.getString(R.string.vault_move_out_done))
                VaultViewModel.Event.MoveOutFailed ->
                    snackbarHost.showError(ctx.getString(R.string.vault_move_out_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                                ConversationRow(
                                    conversation = c,
                                    onClick = { onOpenThread(c.id) },
                                    onLongClick = { sheetTarget = c.id },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }
    }

    val pendingTarget = sheetTarget
    if (pendingTarget != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.vault_move_out)) },
                    modifier = Modifier.clickable {
                        viewModel.moveOutOfVault(pendingTarget)
                        sheetTarget = null
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
