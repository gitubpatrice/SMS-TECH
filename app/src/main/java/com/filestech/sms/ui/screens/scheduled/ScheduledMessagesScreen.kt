package com.filestech.sms.ui.screens.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase
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
 *
 * v1.25.3 (audit H6) — seconde section « Échecs ». L'écran ne montrait que `PENDING` : dès
 * qu'un envoi était abandonné il quittait la liste sans laisser de trace, et l'utilisateur
 * n'avait ni l'information, ni le moyen de relancer. Les échecs sont désormais visibles, avec
 * relance et retrait explicites.
 */
@HiltViewModel
class ScheduledMessagesViewModel @Inject constructor(
    repo: ScheduledMessageRepository,
    private val cancel: CancelScheduledMessageUseCase,
    private val retry: RetryScheduledMessageUseCase,
    private val delete: DeleteScheduledMessageUseCase,
) : ViewModel() {

    val pending: StateFlow<List<ScheduledMessage>> = repo.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val failed: StateFlow<List<ScheduledMessage>> = repo.observeFailed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun cancelMessage(id: Long) {
        viewModelScope.launch { cancel(id) }
    }

    fun retryMessage(id: Long) {
        viewModelScope.launch { retry(id) }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch { delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesScreen(
    onBack: () -> Unit,
    viewModel: ScheduledMessagesViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var confirmCancelFor by remember { mutableStateOf<ScheduledMessage?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<ScheduledMessage?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Résolue au niveau composable et non via `LocalContext.getString` dans le clic : le
    // `Context` capturé dans une lambda non-composable ne suit pas les changements de
    // configuration (`LocalContextGetResourceValueCall`).
    val retriedMessage = stringResource(R.string.scheduled_retried)

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
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        if (pending.isEmpty() && failed.isEmpty()) {
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
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Top,
            ) {
                // L'en-tête « En attente » n'a de sens que s'il y a une seconde section à en
                // distinguer : sans échec, la liste reste exactement celle d'avant.
                if (pending.isNotEmpty() && failed.isNotEmpty()) {
                    item(key = "header-pending") {
                        SectionHeader(title = stringResource(R.string.scheduled_section_pending))
                    }
                }
                items(pending, key = { "pending-${it.id}" }) { msg ->
                    ScheduledRow(
                        message = msg,
                        whenLabelRes = R.string.scheduled_when,
                        whenColor = MaterialTheme.colorScheme.primary,
                        trailing = {
                            TextButton(onClick = { confirmCancelFor = msg }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
                if (failed.isNotEmpty()) {
                    item(key = "header-failed") {
                        SectionHeader(
                            title = stringResource(R.string.scheduled_section_failed),
                            explainer = stringResource(R.string.scheduled_failed_explainer),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    items(failed, key = { "failed-${it.id}" }) { msg ->
                        ScheduledRow(
                            message = msg,
                            whenLabelRes = R.string.scheduled_failed_when,
                            whenColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            viewModel.retryMessage(msg.id)
                                            scope.launch { snackbarHost.showSnackbar(retriedMessage) }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Refresh,
                                            contentDescription = stringResource(R.string.action_retry),
                                        )
                                    }
                                    IconButton(onClick = { confirmDeleteFor = msg }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.action_delete),
                                        )
                                    }
                                }
                            },
                        )
                    }
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

    // Retrait définitif : même exigence de confirmation que l'annulation ci-dessus — un tap
    // sur une icône ne doit jamais suffire à effacer un message que l'utilisateur a écrit.
    confirmDeleteFor?.let { msg ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text(stringResource(R.string.scheduled_delete_confirm_title)) },
            text = { Text(stringResource(R.string.scheduled_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = msg.id
                    confirmDeleteFor = null
                    viewModel.deleteMessage(id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** En-tête de section, avec un explicatif optionnel sous le titre. */
@Composable
private fun SectionHeader(
    title: String,
    explainer: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = color)
        if (explainer != null) {
            Text(
                text = explainer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ligne commune aux deux sections : même corps, mêmes destinataires, même date. Seuls le libellé
 * de date et les actions de fin varient. Factorisée pour qu'une évolution du rendu n'ait pas à
 * être répliquée — donc à terme désynchronisée — entre attente et échec.
 */
@Composable
private fun ScheduledRow(
    message: ScheduledMessage,
    whenLabelRes: Int,
    whenColor: Color,
    trailing: @Composable () -> Unit,
) {
    val recipients = message.addresses.joinToString(", ") { it.toString() }
    val whenLabel = remember(message.scheduledAt) {
        // Format date+time locale system — pas de lib externe, DateFormat AOSP.
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        df.format(Date(message.scheduledAt))
    }
    ListItem(
        headlineContent = { Text(message.body, maxLines = 2) },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.scheduled_to, recipients),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(whenLabelRes, whenLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = whenColor,
                )
            }
        },
        trailingContent = trailing,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
