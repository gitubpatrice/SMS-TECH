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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.backup.BackupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
) : ViewModel() {

    sealed interface Event {
        data class ExportDone(val uri: android.net.Uri, val pages: Int = 0) : Event
        data object ExportFailed : Event
    }

    private val _events = oneShotEvents<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

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

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is BackupViewModel.Event.ExportDone -> snackbarHost.showSnackbar(
                    context.getString(R.string.backup_export_success),
                )
                BackupViewModel.Event.ExportFailed -> snackbarHost.showSnackbar(
                    context.getString(R.string.backup_export_failed),
                )
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
        snackbarHost = { SnackbarHost(snackbarHost) },
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
            Text(
                text = stringResource(R.string.backup_restore_coming_v11),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pw2,
                    onValueChange = { pw2 = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
