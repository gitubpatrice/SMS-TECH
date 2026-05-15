package com.filestech.sms.ui.screens.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.VaultManager
import com.filestech.sms.ui.components.ConversationRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    repo: ConversationRepository,
    private val vault: VaultManager,
) : ViewModel() {
    val state: StateFlow<List<Conversation>> =
        repo.observeVault().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Audit P0-1 (v1.2.0): the vault is gated by [VaultManager.sessionUnlocked] which arms the
     * `moveToVault` / `moveOutOfVault` write paths. Until this VM was wired, the flag was never
     * raised, so write operations either silently failed (when properly routed) OR succeeded
     * through the legacy bypass — depending on which path the caller took. We mark the session
     * unlocked the moment the user reaches this screen because reaching it implies they already
     * passed the AppLock (PIN / biometric / disabled lock), so it is a fair second-factor.
     */
    fun markUnlocked() = vault.markUnlocked()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.markUnlocked() }
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
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                        ConversationRow(conversation = c, onClick = { onOpenThread(c.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}
