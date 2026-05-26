package com.filestech.sms.ui.screens.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * v1.15.1 — Liste des messages programmés en attente d'envoi.
 *
 * Avant cette version, l'infrastructure (ScheduleMessageUseCase + ScheduledMessageWorker + DAO)
 * existait mais sans UI exposée — un user ayant programmé un message via un chemin théorique
 * n'avait aucun moyen de le voir ou de l'annuler. Cet écran ferme ce cycle.
 *
 * Source : route accessible depuis Settings → "Messages programmés". Affiche les messages
 * `PENDING` triés par scheduledAt ASC. Tap sur une ligne → dialog confirmation annulation.
 */
@HiltViewModel
class ScheduledMessagesViewModel @Inject constructor(
    repo: ScheduledMessageRepository,
    private val cancel: CancelScheduledMessageUseCase,
) : ViewModel() {

    val pending: StateFlow<List<ScheduledMessage>> = repo.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun cancelMessage(id: Long) {
        viewModelScope.launch { cancel(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesScreen(
    onBack: () -> Unit,
    viewModel: ScheduledMessagesViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var confirmCancelFor by remember { mutableStateOf<ScheduledMessage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_title)) },
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
        if (pending.isEmpty()) {
            // v1.15.1 — Empty state enrichi : icône + titre + mode d'emploi détaillé.
            // L'user qui ouvre cette page sans message programmé doit comprendre comment
            // en créer un — sinon il referme et la feature reste invisible.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.scheduled_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.scheduled_empty_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Top,
            ) {
                items(pending, key = { it.id }) { msg ->
                    val recipients = msg.addresses.joinToString(", ") { it.toString() }
                    val whenLabel = remember(msg.scheduledAt) {
                        // Format date+time locale system — pas de lib externe, DateFormat AOSP.
                        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        df.format(Date(msg.scheduledAt))
                    }
                    ListItem(
                        headlineContent = { Text(msg.body, maxLines = 2) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = stringResource(R.string.scheduled_to, recipients),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.scheduled_when, whenLabel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        trailingContent = {
                            TextButton(onClick = { confirmCancelFor = msg }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    confirmCancelFor?.let { msg ->
        AlertDialog(
            onDismissRequest = { confirmCancelFor = null },
            title = { Text(stringResource(R.string.scheduled_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.scheduled_cancel_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = msg.id
                    confirmCancelFor = null
                    viewModel.cancelMessage(id)
                }) { Text(stringResource(R.string.scheduled_cancel_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelFor = null }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}
