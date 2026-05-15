package com.filestech.sms.ui.screens.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.ui.components.ConversationRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    archived: Boolean = false,
    onOpenThread: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    val defaultAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshDefaultStatus() }

    // Android still treats SMS perms as runtime even for the default app. Request the bundle
    // once we become default; the running TelephonySyncManager picks up new permissions on its
    // next observer fire (or via the periodic worker, whichever comes first).
    val context = androidx.compose.ui.platform.LocalContext.current
    val smsPermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.requestSyncNow() }
    androidx.compose.runtime.LaunchedEffect(state.isDefaultSmsApp) {
        if (state.isDefaultSmsApp) {
            val needed = listOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.RECEIVE_MMS,
                android.Manifest.permission.READ_CONTACTS,
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) smsPermsLauncher.launch(needed.toTypedArray())
        }
    }

    // Re-evaluate the default-SMS-app banner whenever the screen comes back to foreground.
    // Covers the case where the user sets the default from Android Settings outside our app.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDefaultStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Brand logo in front of the title. Hidden on the archived sub-page so the
                        // crumb stays unambiguous (logo = home). cacheWidth equivalent: the image
                        // is fixed at 28 dp on screen, Coil/painterResource decodes once.
                        if (!archived) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_app_logo),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(end = 10.dp),
                            )
                        }
                        Text(
                            text = if (archived) stringResource(R.string.tab_archived)
                            else stringResource(R.string.app_name),
                        )
                    }
                },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (!archived) {
                        // Audit S-P0-1: the panic-decoy session must not expose any vault entry
                        // point. We hide the icon entirely (rather than disable it) so the feature
                        // looks like it doesn't exist to a coerced observer — matches the decoy
                        // illusion. Defense in depth: the AppRoot navigation guard and the
                        // repository-level filter back-stop this UI gate.
                        if (!state.isPanicDecoy) {
                            IconButton(onClick = onOpenVault) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = stringResource(R.string.tab_vault),
                                )
                            }
                        }
                        IconButton(onClick = onOpenArchived) {
                            Icon(
                                Icons.Outlined.Inventory2,
                                contentDescription = stringResource(R.string.tab_archived),
                            )
                        }
                        IconButton(onClick = onOpenBlocked) {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = stringResource(R.string.tab_blocked),
                            )
                        }
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            text = { Text(stringResource(R.string.settings_title)) },
                            onClick = { overflowOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            text = { Text(stringResource(R.string.about_title)) },
                            onClick = { overflowOpen = false; onOpenAbout() },
                        )
                        // Sort section. Header is a non-interactive label rendered with
                        // `enabled = false` so the user can read it without it looking like
                        // an action item. The 3 sort options follow with a trailing check on
                        // the active mode.
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.sort_section),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { },
                            enabled = false,
                        )
                        SortMenuItem(
                            label = stringResource(R.string.sort_date),
                            selected = state.settings.conversations.sortMode == com.filestech.sms.data.local.datastore.SortMode.DATE,
                            onClick = {
                                overflowOpen = false
                                viewModel.setSortMode(com.filestech.sms.data.local.datastore.SortMode.DATE)
                            },
                        )
                        SortMenuItem(
                            label = stringResource(R.string.sort_unread),
                            selected = state.settings.conversations.sortMode == com.filestech.sms.data.local.datastore.SortMode.UNREAD_FIRST,
                            onClick = {
                                overflowOpen = false
                                viewModel.setSortMode(com.filestech.sms.data.local.datastore.SortMode.UNREAD_FIRST)
                            },
                        )
                        SortMenuItem(
                            label = stringResource(R.string.sort_pinned),
                            selected = state.settings.conversations.sortMode == com.filestech.sms.data.local.datastore.SortMode.PINNED_FIRST,
                            onClick = {
                                overflowOpen = false
                                viewModel.setSortMode(com.filestech.sms.data.local.datastore.SortMode.PINNED_FIRST)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCompose,
                containerColor = androidx.compose.ui.graphics.Color(0xFF1565C0),
                contentColor = androidx.compose.ui.graphics.Color.White,
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_new_message),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onClear = viewModel::clearQuery,
            )
            if (!state.isDefaultSmsApp && !archived) {
                DefaultAppBanner(onSetDefault = {
                    viewModel.defaultAppManager.buildChangeDefaultIntent()?.let { intent ->
                        defaultAppLauncher.launch(intent)
                    }
                })
                HorizontalDivider()
            }
            when {
                state.isImporting -> ImportingPlaceholder(count = state.importedCount)
                state.isLoading -> Unit
                state.conversations.isEmpty() -> EmptyState(archived = archived, filtered = state.filtered)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(state.conversations, key = { it.id }) { conv ->
                        SwipeableConversationRow(
                            conversation = conv,
                            showAvatars = state.settings.conversations.showAvatars,
                            previewLines = state.settings.conversations.previewLines.coerceIn(1, 2),
                            onOpenThread = { onOpenThread(conv.id) },
                            onDelete = { viewModel.delete(conv.id) },
                            onBlock = { viewModel.block(conv) },
                        )
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.conversations_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * Soft "set me as default" banner rendered above the list when the app is not yet the system
 * default SMS handler. Information nudge — not a failure — so we paint it in the brand-blue
 * family (`primaryContainer` + `primary`) instead of error red. The lighter container alpha
 * keeps the line visible without competing with the conversation list below.
 */
@Composable
private fun DefaultAppBanner(onSetDefault: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = cs.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.error_not_default_app),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = onSetDefault,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
            ) { Text(stringResource(R.string.settings_set_default)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableConversationRow(
    conversation: com.filestech.sms.domain.model.Conversation,
    showAvatars: Boolean,
    previewLines: Int,
    onOpenThread: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf(false) }
    var pendingBlock by remember { mutableStateOf(false) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right → ask for confirmation, do not dismiss yet.
                    pendingDelete = true
                    false
                }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → open thread (= reply). Snap back.
                    onOpenThread()
                    false
                }
                androidx.compose.material3.SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val (bg, icon, align) = when (dir) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ->
                    Triple(androidx.compose.ui.graphics.Color(0xFFC62828), Icons.Outlined.Delete, Alignment.CenterStart)
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart ->
                    Triple(androidx.compose.ui.graphics.Color(0xFF1565C0), Icons.AutoMirrored.Outlined.Reply, Alignment.CenterEnd)
                else -> Triple(cs.surface, Icons.Outlined.Delete, Alignment.Center)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 24.dp),
                contentAlignment = align,
            ) {
                if (dir != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        },
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.background(cs.surface)) {
            ConversationRow(
                conversation = conversation,
                onClick = onOpenThread,
                showAvatars = showAvatars,
                previewLines = previewLines,
                // Long-press opens a contextual sheet with Block / Delete. The previous flow
                // (long-press → straight delete dialog) didn't expose the block action that
                // already lived deeper in the menu hierarchy.
                onLongClick = { actionsSheetOpen = true },
            )
        }
    }

    if (actionsSheetOpen) {
        ConversationActionsSheet(
            onDismiss = { actionsSheetOpen = false },
            onBlockRequested = {
                actionsSheetOpen = false
                pendingBlock = true
            },
            onDeleteRequested = {
                actionsSheetOpen = false
                pendingDelete = true
            },
        )
    }

    if (pendingDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.conversations_delete_confirm_title)) },
            text = { Text(stringResource(R.string.conversations_delete_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFC62828),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { pendingDelete = false },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (pendingBlock) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingBlock = false },
            title = { Text(stringResource(R.string.conversation_block_confirm_title)) },
            text = { Text(stringResource(R.string.conversation_block_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        pendingBlock = false
                        onBlock()
                    },
                ) { Text(stringResource(R.string.action_block)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { pendingBlock = false },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Snap back to settled whenever the dialog closes or after a left-swipe-to-reply.
    androidx.compose.runtime.LaunchedEffect(pendingDelete, pendingBlock, dismissState.currentValue) {
        if (!pendingDelete && !pendingBlock &&
            dismissState.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled
        ) {
            dismissState.reset()
        }
    }
}

/**
 * Bottom-sheet menu shown on long-press of a conversation row. Surfaces the actions that were
 * previously buried in nested dialogs (Block) alongside the destructive one (Delete). Each
 * choice routes back through the parent's confirm flow so destructive intents still go through
 * a 2-step confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionsSheet(
    onDismiss: () -> Unit,
    onBlockRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.conversation_actions_title),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            androidx.compose.material3.ListItem(
                leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_block)) },
                modifier = Modifier.clickable(onClick = onBlockRequested),
            )
            androidx.compose.material3.ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = cs.error,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.action_delete), color = cs.error)
                },
                modifier = Modifier.clickable(onClick = onDeleteRequested),
            )
        }
    }
}

@Composable
private fun ImportingPlaceholder(count: Int) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = cs.primary,
            strokeWidth = 3.dp,
        )
        Text(
            text = stringResource(R.string.conversations_importing_title),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = if (count > 0) {
                stringResource(R.string.conversations_importing_count, count)
            } else {
                stringResource(R.string.conversations_importing_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Empty-state hero: a halo (soft 6 % primary disc) wrapping a primaryContainer-tinted emblem
 * with an outlined icon picked from the user's context (search, archive, chat). Centered title
 * + supportive body underneath. Replaces the previous two-line text dump.
 */
@Composable
private fun EmptyState(archived: Boolean, filtered: Boolean) {
    val cs = MaterialTheme.colorScheme
    val emblemIcon = when {
        filtered -> Icons.Outlined.SearchOff
        archived -> Icons.Outlined.Inventory2
        else -> Icons.Outlined.ChatBubbleOutline
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer halo — soft glow ring.
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.06f)),
            )
            // Inner emblem — primary container disc holding the icon.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = emblemIcon,
                    contentDescription = null,
                    tint = cs.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.size(24.dp))
        Text(
            text = when {
                filtered -> stringResource(R.string.conversations_no_match)
                archived -> stringResource(R.string.conversations_archived_empty)
                else -> stringResource(R.string.conversations_empty_title)
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!archived && !filtered) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.conversations_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Sort-picker entry inside the overflow menu. Renders a leading check mark on the currently
 * active mode so the user sees the current state at a glance — no separate dialog, no extra tap.
 */
@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        text = { Text(label) },
        onClick = onClick,
    )
}
