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
import androidx.compose.material.icons.outlined.LockOpen
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
import com.filestech.sms.ui.components.showError
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
            if (needed.isNotEmpty()) {
                smsPermsLauncher.launch(needed.toTypedArray())
            } else {
                // v1.8.0 (fix bug S9 fresh install) — permissions déjà toutes
                // accordées (cas du `GRANTED_BY_ROLE` automatique Android qui
                // marche sur Pixel mais aussi sur Samsung One UI quand le rôle
                // SMS-default vient juste d'être accordé). Sans ce `else`, le
                // `smsPermsLauncher` n'était jamais déclenché et donc
                // `requestSyncNow()` n'était JAMAIS appelé → l'utilisateur
                // devait fermer/rouvrir l'app manuellement (au prochain cold-
                // start, `MainApplication.onCreate` re-tentait `start()` avec
                // `READ_SMS` désormais granted, et l'import démarrait enfin).
                //
                // En appelant `requestSyncNow()` directement ici, l'import
                // démarre dans la foulée du grant, sans nécessiter un 2ème
                // lancement de l'app. Mirror blocklist + filter inclus via
                // `TelephonySyncManager.runSync` (cf. v1.8.0 fix).
                viewModel.requestSyncNow()
            }
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
    // v1.8.0 — dialog de confirmation pour "Tout marquer comme lu".
    var showMarkAllReadConfirm by remember { mutableStateOf(false) }
    // v1.11.0 — Snackbar feedback pour les déplacements Vault. Utilise
    // SmsTechSnackbarHost (bleu marque succès / rouge erreur).
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationsViewModel.Event.MovedToVault -> {
                    val msg = if (event.count <= 1) ctx.getString(R.string.vault_move_in_done)
                    else ctx.resources.getQuantityString(
                        R.plurals.vault_bulk_move_in_done, event.count, event.count,
                    )
                    snackbarHost.showSnackbar(msg)
                }
                is ConversationsViewModel.Event.MoveToVaultFailed ->
                    snackbarHost.showError(ctx.getString(R.string.vault_move_in_failed))
            }
        }
    }

    // v1.13.0 — système back en mode sélection ⇒ sortir du mode sélection,
    // pas quitter l'écran. Pattern Gmail / Google Messages : préserve l'écran.
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        snackbarHost = { com.filestech.sms.ui.components.SmsTechSnackbarHost(snackbarHost) },
        topBar = topBar@{
            if (state.selectionMode && !state.isPanicDecoy) {
                // v1.13.0 — TopAppBar contextuelle en mode sélection : count + action
                // "Déplacer N vers le coffre" + croix annule. Masquée en PanicDecoy
                // (re-entry via le `combine` purge la sélection ; on dé-render aussi
                // côté UI pour ne pas laisser un état zombie pendant la transition).
                TopAppBar(
                    title = {
                        Text(
                            text = androidx.compose.ui.res.pluralStringResource(
                                id = R.plurals.conversations_selection_count,
                                count = state.selectedIds.size,
                                state.selectedIds.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.bulkMoveSelectedToVault() }) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = stringResource(R.string.bulk_vault_move_in),
                            )
                        }
                    },
                )
                return@topBar
            }
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
                        // v1.2.6 polish : icône Numéros bloqués retirée du topbar (redondante).
                        // L'accès se fait via Réglages → Numéros bloqués (NavigationRow déjà câblée).
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
                        // v1.8.0 — action "Tout marquer comme lu". Utile quand l'user a
                        // lu ses messages dans une autre app SMS et que les badges SMS
                        // Tech persistent (cache `messages.read` désynchronisé du système).
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                            text = { Text(stringResource(R.string.action_mark_all_read)) },
                            onClick = {
                                overflowOpen = false
                                showMarkAllReadConfirm = true
                            },
                        )
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
            // v1.2.3 audit U6: use the theme's primary (which auto-adapts to Dark Tech /
            // Material You / Light) rather than a hardcoded blue that desaturated wrong on
            // the deep-slate Dark Tech palette.
            FloatingActionButton(
                onClick = onCompose,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
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
                    viewModel.buildChangeDefaultIntent()?.let { intent ->
                        defaultAppLauncher.launch(intent)
                    }
                })
                HorizontalDivider()
            }
            when {
                state.isImporting -> ImportingPlaceholder(count = state.importedCount)
                state.isLoading -> Unit
                state.conversations.isEmpty() -> EmptyState(
                    archived = archived,
                    filtered = state.filtered,
                    onCompose = onCompose,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(state.conversations, key = { it.id }) { conv ->
                        val isSelected = state.selectedIds.contains(conv.id)
                        SwipeableConversationRow(
                            conversation = conv,
                            showAvatars = state.settings.conversations.showAvatars,
                            previewLines = state.settings.conversations.previewLines.coerceIn(1, 2),
                            // v1.13.0 — en mode sélection, tap = toggle de l'item, pas
                            // ouverture du thread. Hors mode sélection, comportement
                            // normal. Le swipe est désactivé en mode sélection pour
                            // éviter les conflits de gestes.
                            onOpenThread = {
                                if (state.selectionMode) viewModel.toggleSelection(conv.id)
                                else onOpenThread(conv.id)
                            },
                            onDelete = { viewModel.delete(conv.id) },
                            onBlock = { viewModel.block(conv) },
                            // v1.11.0 — Trou #2 Vault polish : callback passé
                            // UNIQUEMENT hors PanicDecoy. L'item de menu n'apparaît
                            // donc pas en session decoy (cohérent avec masquage
                            // de l'icône cadenas top-bar). Désactivé aussi en mode
                            // sélection (l'action passe par la TopAppBar bulk).
                            onMoveToVault = if (state.isPanicDecoy || state.selectionMode) null else {
                                { viewModel.moveConversationToVault(conv.id) }
                            },
                            // v1.13.0 — long-press hors sélection = entrer en sélection
                            // avec ce conv comme 1ᵉʳ item. Long-press en sélection =
                            // toggle (cohérent avec Gmail / Google Messages).
                            onLongClickOverride = if (state.isPanicDecoy) null else {
                                { viewModel.toggleSelection(conv.id) }
                            },
                            selected = isSelected,
                            selectionMode = state.selectionMode,
                        )
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    // v1.8.0 — Dialog de confirmation pour "Tout marquer comme lu".
    // Action non destructive, dialog simple (pas d'autofocus complexe — c'est
    // de l'UX, pas une action irreversible).
    if (showMarkAllReadConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMarkAllReadConfirm = false },
            title = { Text(stringResource(R.string.action_mark_all_read)) },
            text = { Text(stringResource(R.string.mark_all_read_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        viewModel.markAllAsRead()
                        showMarkAllReadConfirm = false
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showMarkAllReadConfirm = false },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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
    // v1.2.3 audit U13: dropping the 0.55 alpha — composited over the dark surface of Dark
    // Tech it could fall below 4.5:1 contrast for the body text. Material 3 `surfaceContainer`
    // is the native "subtle tint" surface and stays consistent across schemes.
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = cs.surfaceContainer,
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
                tint = cs.primary,
                modifier = Modifier.size(22.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.error_not_default_app),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
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
    onMoveToVault: (() -> Unit)? = null,
    /**
     * v1.13.0 — surcharge du long-press par défaut (qui ouvre `ActionsSheet`).
     * Non-null en mode normal pour entrer en sélection ; null en PanicDecoy.
     */
    onLongClickOverride: (() -> Unit)? = null,
    /** v1.13.0 — visuel sélectionné (background tinted + check icon). */
    selected: Boolean = false,
    /** v1.13.0 — désactive le swipe et l'ActionsSheet pour éviter conflits. */
    selectionMode: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
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
        // v1.13.0 — swipe désactivé en mode sélection (cohérent avec absence
        // d'ActionsSheet sur long-press et avec le pattern Gmail / Messages).
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            // v1.2.3 audit U6: use the brand/error tokens so swipe backgrounds adapt to the
            // current theme. Hardcoded 0xFFC62828 / 0xFF1565C0 broke on Dark Tech.
            val dir = dismissState.dismissDirection
            val (bg, icon, align) = when (dir) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ->
                    Triple(com.filestech.sms.ui.theme.BrandDanger, Icons.Outlined.Delete, Alignment.CenterStart)
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart ->
                    Triple(cs.primary, Icons.AutoMirrored.Outlined.Reply, Alignment.CenterEnd)
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
        // v1.13.0 — background `primaryContainer.copy(alpha=.35f)` quand
        // sélectionné. Subtil mais lisible WCAG AA face au `onSurface` du
        // texte (le `primaryContainer` est déjà un ton clair sur thème clair
        // et un ton sombre sur thème sombre ; α=0.35 préserve la lisibilité).
        val rowBg = if (selected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surface
        androidx.compose.foundation.layout.Box(modifier = Modifier.background(rowBg)) {
            ConversationRow(
                conversation = conversation,
                onClick = onOpenThread,
                showAvatars = showAvatars,
                previewLines = previewLines,
                // v1.13.0 — long-press route :
                //  - `onLongClickOverride` non-null (mode normal) → entrer en
                //    sélection (1ᵉʳ item) ou toggle (sélection en cours).
                //  - sinon (PanicDecoy) → ActionsSheet legacy.
                // Haptic pulse dans les deux cas (Material guideline).
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (onLongClickOverride != null) onLongClickOverride() else actionsSheetOpen = true
                },
            )
            // v1.13.0 — check icon en overlay quand sélectionné. Placé en bas-
            // droite pour ne pas masquer l'avatar ; tinté `primary` pour lisibilité
            // sur le background sélectionné.
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = cs.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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
            // v1.11.0 — null en PanicDecoy (le parent ne passe pas le callback)
            // → l'item de menu "Déplacer vers le coffre" n'apparaît pas.
            onMoveToVaultRequested = onMoveToVault?.let { handler ->
                {
                    actionsSheetOpen = false
                    handler()
                }
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
                    // v1.2.5: solid BrandDanger fill + white text — `errorContainer` is pastel
                    // pink in light theme and didn't read as a destructive action.
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = com.filestech.sms.ui.theme.BrandDanger,
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
    onMoveToVaultRequested: (() -> Unit)? = null,
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
            // v1.11.0 — Trou #2 Vault polish : action "Déplacer vers le coffre"
            // exposée dans le menu long-press. Visible UNIQUEMENT hors PanicDecoy
            // (le caller passe `null` en decoy pour ne pas révéler l'existence du
            // coffre à un agresseur — défense en profondeur sur le guard VaultManager
            // qui refuse aussi côté domain).
            if (onMoveToVaultRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.vault_move_in)) },
                    modifier = Modifier.clickable(onClick = onMoveToVaultRequested),
                )
            }
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
private fun EmptyState(archived: Boolean, filtered: Boolean, onCompose: () -> Unit) {
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
            // v1.2.3 audit U7: empty state previously had no action button, leaving a fresh
            // user with nothing to click. The bottom-right FAB exists but is easy to miss on a
            // mostly-empty screen. Inline CTA mirrors what Notes Tech v1.0.9 shipped (U9).
            Spacer(Modifier.size(20.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onCompose) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_new_message))
            }
        }
    }
}

/**
 * Sort-picker entry inside the overflow menu. Renders a leading check mark on the currently
 * active mode so the user sees the current state at a glance — no separate dialog, no extra tap.
 */
@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // v1.2.3 audit U22: Semantics expose the radio-button role + selected state so TalkBack
    // reads "Date, sélectionné" instead of just "Date". U14: short haptic when the user
    // changes the sort mode (Material guideline for state-change feedback).
    DropdownMenuItem(
        leadingIcon = {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        text = { Text(label) },
        onClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
            contentDescription = label
        },
    )
}
