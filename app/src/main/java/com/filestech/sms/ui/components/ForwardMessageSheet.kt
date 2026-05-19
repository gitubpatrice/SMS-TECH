package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R

/**
 * v1.3.11 (F5) — bottom sheet shown when the user picks "Transférer" in a bubble's
 * overflow menu. Two paths:
 *
 *   - **Conversation existante** — picks a target thread from the recent conversation
 *     list (filterable). [onPickConversation] fires with the target `conversationId`;
 *     the caller is responsible for navigating + posting the payload to
 *     [com.filestech.sms.system.share.IncomingShareHolder] beforehand so the dest
 *     `ThreadViewModel.consumeIncomingShareIfAny` picks it up at open.
 *
 *   - **Nouveau destinataire** — opens [com.filestech.sms.ui.screens.compose.ComposeScreen]
 *     for picker → create new conv flow. Same `IncomingShareHolder` mechanism carries
 *     the payload through to the final thread.
 *
 * UI choices:
 *   - The "Nouveau destinataire" CTA pinned at the top so the user can always reach it
 *     without scrolling past long histories.
 *   - The source conversation is hidden from the existing-conv list (see
 *     [ForwardPickerViewModel.filtered]) so the user cannot forward to themselves.
 *   - `imePadding` so the search field stays above the keyboard on small screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMessageSheet(
    currentConversationId: Long,
    onDismiss: () -> Unit,
    onPickConversation: (Long) -> Unit,
    onPickNewContact: () -> Unit,
    viewModel: ForwardPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.3.11 (P1) — hand the source conversation id to the ViewModel so the filter
    // (and its mémoïsation) is owned end-to-end by the VM. Keyed on `currentConversationId`
    // so a configuration change that swaps the source id re-syncs the exclusion list.
    LaunchedEffect(currentConversationId) {
        viewModel.setExcludedConversation(currentConversationId)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // v1.3.11 (U3) — single dismiss path that resets the search query before bubbling
    // up to the caller. Without it the `ForwardPickerViewModel` (scoped to the source
    // ThreadScreen back-stack entry) would replay the previous query the next time the
    // sheet opens, surprising the user.
    val dismissAndReset: () -> Unit = {
        viewModel.setQuery("")
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = dismissAndReset,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.forward_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.forward_search_placeholder)) },
                singleLine = true,
            )
            Spacer(Modifier.size(12.dp))
            // Always-on shortcut: pick a new recipient (opens ComposeScreen).
            // v1.3.11 (U2) — explicit Role.Button so TalkBack announces the action role
            // ("Bouton" / "Button") in addition to the headline text. Standard a11y for
            // a [ListItem] whose only purpose is to fire an action.
            ListItem(
                headlineContent = { Text(stringResource(R.string.forward_new_contact)) },
                leadingContent = {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickNewContact() }
                    .semantics { role = Role.Button },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.forward_existing_conversation),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            val filtered = state.filtered
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.forward_no_conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { conv ->
                        val firstAddress = conv.addresses.firstOrNull()?.raw.orEmpty()
                        val label = conv.displayName?.takeIf { it.isNotBlank() } ?: firstAddress
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = conv.lastMessagePreview
                                ?.takeIf { it.isNotBlank() }
                                ?.let { preview -> { Text(preview, maxLines = 1) } },
                            leadingContent = { Avatar(label = label) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickConversation(conv.id) },
                        )
                    }
                }
            }
        }
    }
}

