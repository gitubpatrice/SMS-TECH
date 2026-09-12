package com.filestech.sms.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.backup.BackupService
import com.filestech.sms.domain.backup.RestoreResult
import com.filestech.sms.domain.usecase.RestoreBackupUseCase
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.security.ProtectSecretInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val restoreBackup: RestoreBackupUseCase,
    // v1.27.2 (audit externe 2026-08-04 #4) — distingue le refus « second facteur coffre »
    // du refus « session leurre » : les deux reviennent en AppError.Locked, mais seul le
    // premier peut être nommé à l'écran. Cf. [exportEncrypted].
    private val appLock: com.filestech.sms.security.AppLockManager,
    // v1.27.13 — le second facteur du coffre se prouve DANS ce flux, cf. [exportSecondFactor].
    private val vaultPin: com.filestech.sms.security.VaultPinManager,
    private val vaultSession: com.filestech.sms.security.VaultSessionState,
) : ViewModel() {

    sealed interface Event {
        data class ExportDone(val uri: android.net.Uri, val pages: Int = 0) : Event
        data object ExportFailed : Event

        // v1.27.2 (audit externe 2026-08-04 #4) — l'export exige la session coffre
        // déverrouillée quand le coffre n'est pas vide ; l'UI invite à l'ouvrir d'abord.
        data object ExportVaultLocked : Event

        // v1.15.2 — Événements restore. Le succès porte le récap chiffré pour l'affichage,
        // l'échec porte un kind typé qui mappe vers une string d'erreur localisée côté UI.
        data class RestoreDone(val result: RestoreResult) : Event
        data class RestoreFailed(val kind: RestoreFailureKind) : Event
    }

    /** v1.15.2 — Catégorise les échecs de restore pour message UI ciblé. */
    enum class RestoreFailureKind { WRONG_PASSPHRASE_OR_CORRUPTED, INVALID_FORMAT, STORAGE }

    private val _events = oneShotEvents<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // v1.15.2 — Flag observable pour disabler le bouton "Restaurer" et afficher un indicateur
    // pendant le travail crypto + import (peut prendre quelques secondes sur grosse archive).
    // v1.17.0 audit KOTLIN-L1 — `MutableStateFlow` expose déjà `compareAndSet` atomique. Le
    // guard anti-réentrance utilise CAS(false→true) côté `restoreFromUri` plutôt qu'un
    // read-then-write non-atomique. Sûr même si appelé depuis un dispatcher non-Main.
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring

    /**
     * v1.27.13 — ce que l'utilisateur doit prouver avant que l'export puisse aboutir.
     *
     * Consulte AVANT d'ouvrir le selecteur de fichier : le refus arrivait jusqu'ici apres le
     * choix d'une destination et la saisie d'une passphrase, sur une condition connue des le
     * depart — et `CreateDocument` ayant deja cree le fichier, un `.smsbk` vide restait sur le
     * stockage.
     */
    suspend fun exportSecondFactor(): com.filestech.sms.security.VaultSecondFactor =
        backupService.exportSecondFactor()

    /** v1.27.13 — meme verification que la porte du coffre, temporisation comprise. */
    suspend fun verifyVaultPin(candidate: CharArray): com.filestech.sms.security.PinVerdict =
        vaultPin.verifyVaultPin(candidate)

    /** v1.27.13 — millisecondes de temporisation restantes, pour le compte a rebours. */
    suspend fun vaultLockoutRemainingMs(): Long = vaultPin.vaultLockoutRemainingMs()

    /**
     * v1.27.13 — le second facteur vient d'etre prouve pour CET export.
     *
     * La preuve n'ouvre pas le coffre pour la suite : [exportEncrypted] ouvre la session juste
     * avant d'ecrire et la referme aussitot apres, y compris en cas d'echec. Un export ne doit
     * pas laisser derriere lui un coffre consultable — l'utilisateur a prouve son facteur pour
     * sauvegarder, pas pour ouvrir.
     */
    private var vaultProofGranted = false

    fun grantVaultProof() { vaultProofGranted = true }

    /**
     * Triggers an encrypted `.smsbk` export. The [passphrase] CharArray is consumed (wiped) by
     * [BackupService.writeSmsbk]. The UI is responsible for asking the user the passphrase and
     * passing a fresh CharArray every time.
     */
    fun exportEncrypted(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            // v1.27.13 — la session n'est ouverte que le temps de l'ecriture. Le `finally`
            // la referme meme si `writeSmsbk` echoue ou leve : sans lui, un export rate
            // laisserait le coffre ouvert, ce que l'utilisateur n'a pas demande.
            val borrowed = vaultProofGranted
            if (borrowed) vaultSession.markUnlocked()
            val r = try {
                backupService.writeSmsbk(uri, passphrase)
            } finally {
                if (borrowed) {
                    vaultSession.lock()
                    vaultProofGranted = false
                }
            }
            _events.tryEmit(
                when {
                    r is Outcome.Success -> Event.ExportDone(uri)
                    // v1.27.2 (audit externe 2026-08-04 #4) — AppError.Locked = « le coffre
                    // exige son second facteur avant l'export »… SAUF en session leurre, où
                    // writeSmsbk refuse aussi avec Locked : là, message GÉNÉRIQUE. Un libellé
                    // « déverrouillez le coffre » trahirait l'existence d'un contenu caché au
                    // détenteur du code panique — la ligne qui trahit le leurre a déjà été un
                    // vrai défaut de ce dépôt.
                    r is Outcome.Failure && r.error is AppError.Locked &&
                        appLock.state.value !is
                        com.filestech.sms.security.AppLockManager.LockState.PanicDecoy ->
                        Event.ExportVaultLocked
                    else -> Event.ExportFailed
                },
            )
        }
    }

    /**
     * v1.15.2 — Triggers a restore from an encrypted `.smsbk` URI. The [passphrase] CharArray is
     * consumed (wiped) by [BackupService.readSmsbk]. Caller is responsible for handing a fresh
     * CharArray each call. `_isRestoring` est posé à true pendant le travail pour griser le
     * bouton UI et empêcher un double-tap qui produirait 2 imports concurrents.
     */
    fun restoreFromUri(uri: android.net.Uri, passphrase: CharArray) {
        // v1.17.0 audit KOTLIN-L1 — Anti-réentrance ATOMIQUE via compareAndSet. Avant : `if
        // (_isRestoring.value) ... else _isRestoring.value = true` n'était pas atomique côté
        // contrat — un futur refacto qui appellerait restoreFromUri hors Main thread aurait pu
        // produire une race (deux callers passent le check avant que l'un ne mette à true).
        // CAS(false → true) garantit qu'un seul caller continue, même hors Main.
        if (!_isRestoring.compareAndSet(false, true)) {
            // Restore déjà en cours — refus + wipe passphrase reçue (pas de fuite mémoire).
            passphrase.fill(' ')
            return
        }
        viewModelScope.launch {
            try {
                val outcome = restoreBackup(uri.toString(), passphrase)
                when (outcome) {
                    is Outcome.Success -> _events.tryEmit(Event.RestoreDone(outcome.value))
                    is Outcome.Failure -> {
                        // Map l'AppError vers un kind UI-friendly. Le BackupService catégorise
                        // déjà via le message (cf. readSmsbk errorMapper).
                        val error = outcome.error
                        val kind = when {
                            error is AppError.Validation -> {
                                val msg = error.message.orEmpty()
                                when {
                                    msg.contains("decrypt failed") -> RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED
                                    else -> RestoreFailureKind.INVALID_FORMAT
                                }
                            }
                            else -> RestoreFailureKind.STORAGE
                        }
                        _events.tryEmit(Event.RestoreFailed(kind))
                    }
                }
            } finally {
                _isRestoring.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var askingPassphrase by remember { mutableStateOf(false) }
    // v1.27.13 — la sauvegarde ne peut pas aboutir tant que le coffre est ferme ; on le dit
    // avant d'ouvrir le selecteur de fichier, pas apres la saisie de la passphrase.
    var askingVaultPin by remember { mutableStateOf(false) }
    var vaultBiometricOnlyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            askingPassphrase = true
        }
    }

    // v1.15.2 — État du flow restore. `restoreUri` = URI .smsbk pické via SAF ; non-null
    // déclenche le dialog de passphrase. `restoreFlowActive` empêche le double-pick.
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val isRestoring by viewModel.isRestoring.collectAsState()
    val restoreLauncher = rememberLauncherForActivityResult(
        // OpenDocument plutôt que GetContent : on garde une URI persistable / re-readable.
        // Mime `*/*` car certains pickers FS ne reconnaissent pas le mime SMS Tech custom ;
        // le service revalide via la magic bytes du fichier, donc safe.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) restoreUri = uri
    }

    // v1.27.2 (audit externe 2026-08-04 #4) — résolue à la composition via stringResource :
    // un `context.getString` DANS le collect ajouterait une instance de
    // LocalContextGetResourceValueCall au-delà de la baseline lint (on n'enterre rien dedans).
    val vaultLockedMsg = stringResource(R.string.backup_export_vault_locked)
    // v1.28.3 — gabarit resolu au niveau composable, formate a l'emission : un `Context` capture
    // dans une lambda non composable ne suit pas les changements de configuration.
    val sansPiecesJointesFmt = stringResource(R.string.backup_restore_without_attachments)
    val coffreSansFacteurMsg = stringResource(R.string.backup_restore_vault_without_factor)
    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is BackupViewModel.Event.ExportDone -> snackbarHost.showSnackbar(
                    context.getString(R.string.backup_export_success),
                )
                BackupViewModel.Event.ExportFailed -> snackbarHost.showError(
                    context.getString(R.string.backup_export_failed),
                )
                // v1.27.2 (audit externe 2026-08-04 #4) — le coffre n'est pas vide et sa
                // session est verrouillée : inviter à l'ouvrir plutôt qu'un échec opaque.
                BackupViewModel.Event.ExportVaultLocked -> snackbarHost.showError(vaultLockedMsg)
                // v1.15.2 — Événements restore : snackbar avec récap chiffré succès, OU
                // erreur typée mappée vers la bonne string localisée.
                // v1.28.3 — le bilan dit desormais combien de messages sont revenus SANS leurs
                // pieces jointes. Le format `.smsbk` n'en transporte aucune, et rien ne le disait :
                // un MMS sans legende restaure n'apparait meme dans aucun fil. Une perte enoncee
                // vaut infiniment mieux qu'une perte muette.
                is BackupViewModel.Event.RestoreDone -> snackbarHost.showSnackbar(
                    buildString {
                        append(
                            context.getString(
                                R.string.backup_restore_success,
                                ev.result.totalConversationsInBackup,
                                ev.result.conversationsCreated,
                                ev.result.messagesImported,
                                ev.result.messagesSkipped,
                            ),
                        )
                        if (ev.result.messagesWithoutAttachments > 0) {
                            append(' ')
                            append(sansPiecesJointesFmt.format(ev.result.messagesWithoutAttachments))
                        }
                        // v1.28.5 — un coffre restaure sans second facteur ici : dit, pas tu.
                        if (ev.result.vaultRestoredWithoutSecondFactor) {
                            append(' ')
                            append(coffreSansFacteurMsg)
                        }
                    },
                )
                is BackupViewModel.Event.RestoreFailed -> {
                    val msg = when (ev.kind) {
                        BackupViewModel.RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED ->
                            context.getString(R.string.backup_restore_failed_wrong_pass)
                        BackupViewModel.RestoreFailureKind.INVALID_FORMAT ->
                            context.getString(R.string.backup_restore_failed_format)
                        BackupViewModel.RestoreFailureKind.STORAGE ->
                            context.getString(R.string.backup_restore_failed_storage)
                    }
                    snackbarHost.showError(msg)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_backup)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_backup_now),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.backup_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = {
                // v1.27.13 — on demande AVANT de faire travailler l'utilisateur. Le selecteur
                // n'est meme pas ouvert si la sauvegarde ne peut pas aboutir : il aurait cree
                // un fichier vide qu'on aurait ensuite refuse de remplir.
                scope.launch {
                    when (viewModel.exportSecondFactor()) {
                        com.filestech.sms.security.VaultSecondFactor.NONE ->
                            launcher.launch("smstech_${System.currentTimeMillis()}.smsbk")
                        // Le facteur se prouve ICI, pas dans un ecran qu'il faudrait quitter
                        // pour revenir — le quitter refermerait justement la session.
                        com.filestech.sms.security.VaultSecondFactor.PIN ->
                            askingVaultPin = true
                        // Seul cas non couvert : coffre garde par la biometrie SEULE. La porte
                        // biometrique est adossee au Keystore et vit dans l'ecran du coffre ; la
                        // recopier ici creerait deux implementations d'une meme garde, et c'est
                        // ce motif qui a produit les vrais defauts de ce depot. On le dit
                        // franchement, avec la seule action qui debloque.
                        com.filestech.sms.security.VaultSecondFactor.BIOMETRIC ->
                            vaultBiometricOnlyDialog = true
                    }
                }
            }) {
                Text(stringResource(R.string.settings_backup_now))
            }
            Spacer(Modifier.size(24.dp))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.settings_restore),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.backup_restore_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Button(
                // v1.15.2 — Disable le bouton pendant l'opération crypto + import. Évite un
                // 2ᵉ pick file qui produirait 2 restores concurrents (le VM a déjà un guard
                // anti-réentrance, mais on défend en profondeur côté UI).
                enabled = !isRestoring,
                onClick = {
                    // mime `*/*` car certains pickers (Samsung My Files) ne reconnaissent pas
                    // le mime SMS Tech custom — la validation se fait via magic bytes côté
                    // BackupService.readSmsbk.
                    restoreLauncher.launch(arrayOf("*/*"))
                },
            ) {
                Text(
                    text = if (isRestoring) {
                        stringResource(R.string.backup_restore_in_progress)
                    } else {
                        stringResource(R.string.backup_restore_pick_file)
                    },
                )
            }
        }
    }

    // v1.15.2 — Dialog passphrase de restore (single field, pas de confirm — l'user tape une
    // passphrase qu'il connaît, pas une qu'il pose). Sur confirm : convert en CharArray fresh,
    // wipe la String UI, déléger au VM (qui wipera le CharArray côté service).
    restoreUri?.let { uri ->
        RestorePassphraseDialog(
            onConfirm = { passphrase ->
                val safeUri = uri
                restoreUri = null
                viewModel.restoreFromUri(safeUri, passphrase)
            },
            onDismiss = { restoreUri = null },
        )
    }

    // v1.27.13 — le second facteur se prouve ici meme, avec le composant et la temporisation
    // de la porte du coffre. Le prouver dans l'ecran du coffre ne servait a rien : en sortir
    // referme la session, donc la condition redevenait fausse avant qu'on puisse l'utiliser.
    if (askingVaultPin) {
        com.filestech.sms.ui.components.PinEntryDialog(
            title = stringResource(R.string.backup_vault_pin_title),
            description = stringResource(R.string.backup_vault_pin_body),
            confirmLabel = stringResource(R.string.action_confirm),
            onVerify = { candidate -> viewModel.verifyVaultPin(candidate) },
            probeLockout = { viewModel.vaultLockoutRemainingMs() },
            onVerified = {
                askingVaultPin = false
                viewModel.grantVaultProof()
                launcher.launch("smstech_${System.currentTimeMillis()}.smsbk")
            },
            onCancel = { askingVaultPin = false },
        )
    }

    if (vaultBiometricOnlyDialog) {
        AlertDialog(
            onDismissRequest = { vaultBiometricOnlyDialog = false },
            title = { Text(stringResource(R.string.backup_vault_biometric_title)) },
            text = { Text(stringResource(R.string.backup_vault_biometric_body)) },
            confirmButton = {
                TextButton(onClick = { vaultBiometricOnlyDialog = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }

    if (askingPassphrase) {
        PassphraseDialog(
            onConfirm = { passphrase ->
                val uri = pendingUri
                askingPassphrase = false
                pendingUri = null
                if (uri != null) viewModel.exportEncrypted(uri, passphrase)
            },
            onDismiss = {
                askingPassphrase = false
                pendingUri = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    // v1.15.2 — Toggle visibilité passphrase (icône œil). Permet de vérifier la saisie pour
    // éviter une erreur de frappe qui rendrait le backup irrestaurable. Désactivé par défaut.
    var pwVisible by remember { mutableStateOf(false) }
    val matches = pw.length >= MIN_PASSPHRASE_LEN && pw == pw2
    val tooShort = pw.isNotEmpty() && pw.length < MIN_PASSPHRASE_LEN
    val mismatch = pw2.isNotEmpty() && pw != pw2

    // v1.28.6 — LE CLAVIER CACHAIT LE BOUTON « Enregistrer », signale par Patrice : il fallait
    // refermer le clavier pour valider. Deux corrections, et non une seule :
    //
    //  1. la touche de validation du clavier enregistre — c'est le geste naturel une fois la
    //     confirmation tapee, et c'est deja le motif de [PinEntryDialog] ;
    //  2. le contenu du dialogue DEFILE, pour que les boutons restent atteignables meme quand la
    //     fenetre reduite par le clavier ne peut plus tout afficher. Sans cela, la premiere
    //     correction masquerait le defaut sans le fermer : quelqu'un qui ne trouve pas la touche
    //     de validation resterait coince.
    //
    // L'action de confirmation vit dans UNE lambda, partagee par le bouton et le clavier. Deux
    // copies auraient divergé — c'est le motif d'asymetrie le plus frequent de ce depot — et
    // celle-ci efface des secrets.
    val enregistrer = {
        val chars = pw.toCharArray()
        pw = ""
        pw2 = ""
        onConfirm(chars)
    }

    AlertDialog(
        // v1.28.6 — LE DEFILEMENT SEUL NE SUFFISAIT PAS, et Patrice l'a constate : un
        // `verticalScroll` ne sert a rien tant que la FENETRE du dialogue garde toute la hauteur
        // de l'ecran. Une `Dialog` Compose a sa propre fenetre ; avec `decorFitsSystemWindows`
        // a `true` — le defaut — elle ignore l'encart du clavier, et les boutons se retrouvent
        // simplement dessous, hors de l'ecran. Il n'y avait donc rien a faire defiler.
        //
        // En passant a `false`, l'application prend la main sur les encarts : `imePadding` remonte
        // le dialogue au-dessus du clavier, `safeDrawingPadding` le garde hors de la barre d'etat
        // et de la barre de navigation, et le contenu defile pour le cas ou meme cette hauteur
        // reduite ne suffirait pas. Les trois sont necessaires : le premier resout le defaut, le
        // deuxieme empeche de le remplacer par un dialogue sous la barre d'etat, le troisieme
        // couvre les tres petits ecrans et les polices agrandies.
        modifier = Modifier
            .safeDrawingPadding()
            .imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase_title)) },
        text = {
            // v1.27.1 (N3) — DANS le contenu du dialogue : phrase secrète d'export.
            ProtectSecretInput()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.backup_passphrase_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(
                                imageVector = if (pwVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (pwVisible) R.string.action_hide_password else R.string.action_show_password,
                                ),
                            )
                        }
                    },
                    // v1.15.2 — Indicateur visuel du minimum requis (sous le champ).
                    // v1.28.6 — il ne s'affiche PLUS EN PERMANENCE. Le texte d'explication juste
                    // au-dessus dit déjà « Au minimum 8 caractères » : la même phrase sous le champ
                    // était un doublon, et elle coûtait une ligne — celle qui, clavier ouvert,
                    // faisait sortir le second champ et les boutons de l'écran. Elle ne paraît donc
                    // plus que quand elle APPREND quelque chose : la saisie est trop courte.
                    // `supportingText = null` ne réserve aucune place, contrairement à un `Text` vide.
                    supportingText = if (tooShort) {
                        {
                            Text(stringResource(R.string.backup_passphrase_min_length, MIN_PASSPHRASE_LEN))
                        }
                    } else {
                        null
                    },
                    isError = tooShort,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pw2,
                    onValueChange = { pw2 = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    // La touche de validation n'enregistre QUE si le bouton l'aurait fait : meme
                    // predicat `matches`, pas un second jugement ecrit a cote.
                    keyboardActions = KeyboardActions(onDone = { if (matches) enregistrer() }),
                    // v1.15.2 — Œil aussi sur le champ confirmation : le state `pwVisible` est
                    // partagé donc cliquer ici ou sur le 1er champ produit le même effet
                    // (les deux champs se masquent/affichent ensemble). Permet à l'user de
                    // toggler depuis le champ qu'il est en train de remplir.
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(
                                imageVector = if (pwVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (pwVisible) R.string.action_hide_password else R.string.action_show_password,
                                ),
                            )
                        }
                    },
                    // v1.28.6 — L'ÉCART EST DIT PAR LE CHAMP CONCERNÉ, et non par un `Text` posé
                    // dessous en `labelSmall` : cette taille-là était illisible (constaté par
                    // Patrice), et le message flottait sans être rattaché à un champ. En
                    // `supportingText`, Material lui donne sa taille, sa couleur d'erreur et sa
                    // place, et le champ se souligne en rouge avec lui.
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.backup_passphrase_mismatch)) }
                    } else {
                        null
                    },
                    isError = mismatch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = matches, onClick = enregistrer) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { pw = ""; pw2 = ""; onDismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private const val MIN_PASSPHRASE_LEN = 8

/**
 * v1.15.2 — Dialog passphrase pour restore. Asymétrie avec [PassphraseDialog] (export) :
 *  - 1 seul champ (pas de "repeat") : l'user TAPE une passphrase qu'il connaît, pas qu'il pose.
 *  - Aucune validation côté UI (longueur etc.) — c'est la passphrase originale, on l'accepte
 *    telle quelle. La validation a lieu côté crypto : si elle est fausse → erreur typée
 *    `WRONG_PASSPHRASE_OR_CORRUPTED` → snackbar localisée.
 *  - CharArray construite à la confirmation, wipée par [BackupService.readSmsbk] côté service.
 *  - String UI wipée immédiatement après conversion en CharArray pour réduire l'exposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestorePassphraseDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    // v1.15.2 — Toggle visibilité passphrase. Critique en restore : si l'user se trompe, le
    // déchiffrement échoue avec une erreur générique → il ne saura pas si c'est une faute de
    // frappe ou la mauvaise passphrase. L'œil lui permet de vérifier visuellement.
    var pwVisible by remember { mutableStateOf(false) }
    // v1.28.6 — le jumeau du dialogue d'export, corrigé en même temps que lui : le clavier y
    // cachait le bouton de la même façon. Corriger un seul des deux aurait reproduit l'asymétrie
    // que ce dépôt rencontre le plus souvent — et celui-ci se présente au pire moment, quand on
    // restaure après avoir perdu ses données.
    val restaurer = {
        val chars = pw.toCharArray()
        pw = ""
        onConfirm(chars)
    }
    AlertDialog(
        // v1.28.6 — meme correction que [PassphraseDialog], son jumeau : cf. le commentaire y
        // figurant pour le raisonnement complet.
        modifier = Modifier
            .safeDrawingPadding()
            .imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_passphrase_title)) },
        text = {
            // v1.27.1 (N3) — DANS le contenu du dialogue : phrase secrète de restauration.
            ProtectSecretInput()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.backup_restore_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_restore_passphrase_field)) },
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) restaurer() }),
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(
                                imageVector = if (pwVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (pwVisible) R.string.action_hide_password else R.string.action_show_password,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty(), onClick = restaurer) {
                Text(stringResource(R.string.backup_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { pw = ""; onDismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
