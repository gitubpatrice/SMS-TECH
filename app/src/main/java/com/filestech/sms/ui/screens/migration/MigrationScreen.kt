package com.filestech.sms.ui.screens.migration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.TelephonyReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MigrationViewModel @Inject constructor(
    private val reader: TelephonyReader,
    private val mirror: ConversationMirror,
    private val settings: SettingsRepository,
) : ViewModel() {
    data class UiState(val isRunning: Boolean = false, val imported: Int = 0, val done: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /**
     * v1.1.1: switched from per-message `upsertIncoming/OutgoingSms` to the bulk path. The old
     * loop opened **one Room transaction per SMS** (so 2000 historical messages = 2000 tx
     * + 2000 conversation touches), which:
     *   - took minutes on Samsung One UI mid-range devices,
     *   - flooded the conversations list `Flow` with 2000 invalidations,
     *   - made the user think the import was looping ("ça s'arrete pas, 2000 messs etc").
     *
     * `bulkImportFromTelephony` groups inserts by system thread_id and commits one tx per page,
     * with `OnConflictStrategy.IGNORE` on the `telephony_uri` UNIQUE index → safe to re-run.
     *
     * Also persists `lastSyncedSmsId` at the end so the periodic `TelephonySyncWorker` does not
     * re-scan the historical set on its next tick.
     */
    fun run() {
        if (_state.value.isRunning) return
        _state.value = UiState(isRunning = true)
        viewModelScope.launch {
            var count = 0
            reader.readSmsBatched(pageSize = 500) { batch ->
                mirror.bulkImportFromTelephony(batch)
                count += batch.size
                _state.value = _state.value.copy(imported = count)
            }
            runCatching {
                val fp = reader.snapshotSmsFingerprint()
                if (fp.maxId > 0L) {
                    settings.update { it.copy(advanced = it.advanced.copy(lastSyncedSmsId = fp.maxId)) }
                    Timber.i("Migration: cursor advanced to id=%d", fp.maxId)
                }
            }.onFailure { Timber.w(it, "Migration: failed to persist sync cursor") }
            _state.value = _state.value.copy(isRunning = false, done = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(onBack: () -> Unit, viewModel: MigrationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.migration_title)) },
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
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = stringResource(R.string.migration_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.migration_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = viewModel::run, enabled = !state.isRunning) {
                Text(
                    if (state.done) stringResource(R.string.migration_rerun)
                    else stringResource(R.string.migration_run),
                )
            }
            if (state.isRunning || state.done) {
                Spacer(Modifier.size(16.dp))
                LinearProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.migration_imported, state.imported),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
