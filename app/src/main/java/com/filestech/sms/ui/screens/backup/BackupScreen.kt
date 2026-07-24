package com.filestech.sms.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
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
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val restoreBackup: RestoreBackupUseCase,
) : ViewModel() {

    sealed interface Event {
        data class ExportDone(val uri: android.net.Uri, val pages: Int = 0) : Event
        data object ExportFailed : Event
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
     * Triggers an encrypted `.smsbk` export. The [passphrase] CharArray is consumed (wiped) by
     * [BackupService.writeSmsbk]. The UI is responsible for asking the user the passphrase and
     * passing a fresh CharArray every time.
     */
    fun exportEncrypted(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            val r = backupService.writeSmsbk(uri, passphrase)
            _events.tryEmit(if (r is Outcome.Success) Event.ExportDone(uri) else Event.ExportFailed)
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is BackupViewModel.Event.ExportDone -> snackbarHost.showSnackbar(
                    context.getString(R.string.backup_export_success),
                )
                BackupViewModel.Event.ExportFailed -> snackbarHost.showError(
                    context.getString(R.string.backup_export_failed),
                )
                // v1.15.2 — Événements restore : snackbar avec récap chiffré succès, OU
                // erreur typée mappée vers la bonne string localisée.
                is BackupViewModel.Event.RestoreDone -> snackbarHost.showSnackbar(
                    context.getString(
                        R.string.backup_restore_success,
                        ev.result.totalConversationsInBackup,
                        ev.result.conversationsCreated,
                        ev.result.messagesImported,
                        ev.result.messagesSkipped,
                    ),
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
            Button(onClick = { launcher.launch("smstech_${System.currentTimeMillis()}.smsbk") }) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_passphrase_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                    // Devient rouge tant que la longueur n'est pas atteinte.
                    supportingText = {
                        val tooShort = pw.isNotEmpty() && pw.length < MIN_PASSPHRASE_LEN
                        Text(
                            text = stringResource(R.string.backup_passphrase_min_length, MIN_PASSPHRASE_LEN),
                            color = if (tooShort) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isError = pw.isNotEmpty() && pw.length < MIN_PASSPHRASE_LEN,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pw2,
                    onValueChange = { pw2 = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pw.isNotEmpty() && pw != pw2) {
                    Text(
                        text = stringResource(R.string.backup_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = matches,
                onClick = {
                    val chars = pw.toCharArray()
                    pw = ""
                    pw2 = ""
                    onConfirm(chars)
                },
            ) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_passphrase_title)) },
        text = {
            Column {
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            TextButton(
                enabled = pw.isNotEmpty(),
                onClick = {
                    val chars = pw.toCharArray()
                    pw = ""
                    onConfirm(chars)
                },
            ) {
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
