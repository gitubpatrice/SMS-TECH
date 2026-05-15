package com.filestech.sms.ui.screens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.ui.components.Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    initialAddress: String?,
    onBack: () -> Unit,
    onConversationCreated: (Long) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ComposeViewModel.Event.ConversationCreated -> onConversationCreated(e.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_new_message)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::createConversation,
                        enabled = state.recipients.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.action_continue))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.compose_to_label), style = MaterialTheme.typography.titleSmall)
            }
            if (state.recipients.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.recipients.forEach { addr ->
                        AssistChip(
                            onClick = { viewModel.removeRecipient(addr) },
                            label = { Text(addr.raw) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.compose_search_contact)) },
                singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val filtered = viewModel.filteredContacts()
                // Free-entry row: lets the user pick a raw number (or anything they typed) when
                // it doesn't match any saved contact. Audit Q-BUG-1: previously the modifier ran
                // `.let { mod -> mod.also { /* clickable */ } }` — a no-op that left the row
                // visually clickable-looking but inert, breaking the whole new-conversation flow.
                if (state.query.isNotBlank() && filtered.none { c -> c.firstPhone?.raw == state.query }) {
                    item {
                        ListItem(
                            headlineContent = { Text(state.query) },
                            supportingContent = { Text(stringResource(R.string.action_continue)) },
                            leadingContent = { Avatar(label = state.query) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clickable {
                                    viewModel.addRecipient(state.query)
                                    viewModel.setQuery("")
                                },
                        )
                    }
                }
                items(filtered, key = { it.id ?: it.hashCode().toLong() }) { contact ->
                    val number = contact.firstPhone?.raw.orEmpty()
                    ListItem(
                        headlineContent = { Text(contact.displayName ?: number) },
                        supportingContent = { Text(number) },
                        leadingContent = { Avatar(label = contact.displayName ?: number) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = number.isNotBlank()) {
                                viewModel.addRecipient(number)
                                viewModel.setQuery("")
                            },
                    )
                }
            }
        }
    }
}
