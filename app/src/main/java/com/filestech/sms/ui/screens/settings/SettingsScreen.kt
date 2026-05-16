package com.filestech.sms.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenMigration: () -> Unit,
    onOpenBlocked: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNuke by remember { mutableStateOf(false) }
    var lockModePickerOpen by remember { mutableStateOf(false) }
    var pinSetupOpen by remember { mutableStateOf(false) }

    val defaultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* no-op */ }

    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is SettingsViewModel.Event.BlockedPurged -> {
                    val msg = if (e.count > 0) {
                        ctx.getString(R.string.settings_purge_blocked_result, e.count)
                    } else ctx.getString(R.string.settings_purge_blocked_none)
                    snackbarHost.showSnackbar(msg)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(hostState = snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.size(4.dp))

            SectionCard(
                title = stringResource(R.string.settings_section_appearance),
                icon = Icons.Outlined.Palette,
            ) {
                ThemeSwatchPicker(
                    current = state.appearance.themeMode,
                    onSelect = { mode -> viewModel.update { it.copy(appearance = it.appearance.copy(themeMode = mode)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_dynamic_colors),
                    value = state.appearance.dynamicColors,
                    onChange = { v -> viewModel.update { it.copy(appearance = it.appearance.copy(dynamicColors = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_amoled),
                    value = state.appearance.amoledTrueBlack,
                    onChange = { v -> viewModel.update { it.copy(appearance = it.appearance.copy(amoledTrueBlack = v)) } },
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_section_conversations),
                icon = Icons.Outlined.Forum,
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_show_avatars),
                    value = state.conversations.showAvatars,
                    onChange = { v -> viewModel.update { it.copy(conversations = it.conversations.copy(showAvatars = v)) } },
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_section_sending),
                icon = Icons.AutoMirrored.Outlined.Send,
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_delivery_reports),
                    value = state.sending.deliveryReports,
                    onChange = { v -> viewModel.update { it.copy(sending = it.sending.copy(deliveryReports = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_retry_failed),
                    value = state.sending.retryFailedAutomatically,
                    onChange = { v -> viewModel.update { it.copy(sending = it.sending.copy(retryFailedAutomatically = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_confirm_broadcast),
                    value = state.sending.confirmBeforeBroadcast,
                    onChange = { v -> viewModel.update { it.copy(sending = it.sending.copy(confirmBeforeBroadcast = v)) } },
                )
                // v1.2.6 audit F4 — saisie facultative du MSISDN. Sans cette valeur, les MMS
                // envoyés peuvent afficher "insert-address-token" comme expéditeur dans
                // d'autres apps SMS sur certaines ROM Samsung One UI.
                MyNumberRow(
                    current = state.sending.userMsisdn,
                    onChange = { v ->
                        viewModel.update { it.copy(sending = it.sending.copy(userMsisdn = v?.takeIf { s -> s.isNotBlank() })) }
                    },
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_section_notifications),
                icon = Icons.Outlined.Notifications,
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_notifications_enabled),
                    value = state.notifications.enabled,
                    onChange = { v -> viewModel.update { it.copy(notifications = it.notifications.copy(enabled = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_inline_reply),
                    value = state.notifications.inlineReply,
                    onChange = { v -> viewModel.update { it.copy(notifications = it.notifications.copy(inlineReply = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_vibrate),
                    value = state.notifications.vibrate,
                    onChange = { v -> viewModel.update { it.copy(notifications = it.notifications.copy(vibrate = v)) } },
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_section_security),
                icon = Icons.Outlined.Shield,
            ) {
                // App lock entry — opens the lock-mode picker (None / PIN). Subtitle reflects the
                // current state so the user sees at a glance whether the lock is armed.
                val currentLockLabel = when (state.security.lockMode) {
                    com.filestech.sms.data.local.datastore.LockMode.PIN ->
                        stringResource(R.string.lock_mode_pin)
                    else -> stringResource(R.string.lock_mode_off)
                }
                NavigationRow(
                    title = stringResource(R.string.settings_app_lock),
                    description = currentLockLabel,
                    onClick = { lockModePickerOpen = true },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_flag_secure),
                    description = stringResource(R.string.settings_flag_secure_desc),
                    value = state.security.flagSecure,
                    onChange = { v -> viewModel.update { it.copy(security = it.security.copy(flagSecure = v)) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_lock_vault_on_leave),
                    description = stringResource(R.string.settings_lock_vault_on_leave_desc),
                    value = state.security.lockVaultOnLeave,
                    onChange = { v -> viewModel.update { it.copy(security = it.security.copy(lockVaultOnLeave = v)) } },
                )
                NavigationRow(
                    title = stringResource(R.string.settings_purge_blocked),
                    description = stringResource(R.string.settings_purge_blocked_desc),
                    onClick = { viewModel.purgeBlockedConversations() },
                )
            }

            SectionCard(
                title = stringResource(R.string.settings_section_blocking),
                icon = Icons.Outlined.Block,
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_block_unknown),
                    value = state.blocking.blockUnknown,
                    onChange = { v -> viewModel.update { it.copy(blocking = it.blocking.copy(blockUnknown = v)) } },
                )
                NavigationRow(stringResource(R.string.settings_manage_blocked), onClick = onOpenBlocked)
            }

            SectionCard(
                title = stringResource(R.string.settings_section_backup),
                icon = Icons.Outlined.Backup,
            ) {
                NavigationRow(stringResource(R.string.settings_backup_now), onClick = onOpenBackup)
                NavigationRow(stringResource(R.string.settings_restore), onClick = onOpenBackup)
                NavigationRow(stringResource(R.string.migration_title), onClick = onOpenMigration)
            }

            SectionCard(
                title = stringResource(R.string.settings_section_advanced),
                icon = Icons.Outlined.Tune,
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_default_sms_app)) },
                    supportingContent = {
                        Text(
                            text = if (viewModel.defaultAppManager.isDefault())
                                stringResource(R.string.settings_is_default)
                            else stringResource(R.string.error_not_default_app),
                        )
                    },
                    trailingContent = {
                        if (!viewModel.defaultAppManager.isDefault()) {
                            Button(onClick = {
                                viewModel.defaultAppManager.buildChangeDefaultIntent()?.let { defaultLauncher.launch(it) }
                            }) { Text(stringResource(R.string.settings_set_default)) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                // Audit U15: label said "Archived" but the row opened the Blocked screen. Removed —
                // archived conversations are reached from the Conversations top bar (icon Inventory2),
                // and the Blocked list has its own dedicated row right above.
                NavigationRow(stringResource(R.string.settings_reset_all), onClick = { viewModel.resetAll() })
                NavigationRow(
                    stringResource(R.string.settings_nuke_data),
                    onClick = { showNuke = true },
                    destructive = true,
                )
            }

            Spacer(Modifier.size(8.dp))
            // About lives outside the card stack — it's a meta link, not a settings group.
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NavigationRow(stringResource(R.string.about_title), onClick = onOpenAbout)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (showNuke) {
        // Audit U16: destructive dialog — Cancel really autofocused (FocusRequester wired below)
        // + confirm action surfaced in errorContainer so accidental taps require visual intent.
        // The "nuke" call is irreversible.
        val cancelFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { showNuke = false },
            title = { Text(stringResource(R.string.settings_nuke_data)) },
            text = { Text(stringResource(R.string.settings_nuke_confirm)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { viewModel.nukeData(); showNuke = false },
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFC62828),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNuke = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (lockModePickerOpen) {
        val biometricCtx = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        LockModePickerDialog(
            currentMode = state.security.lockMode,
            onPick = { mode ->
                lockModePickerOpen = false
                when (mode) {
                    com.filestech.sms.data.local.datastore.LockMode.OFF -> viewModel.clearLock()
                    com.filestech.sms.data.local.datastore.LockMode.PIN -> {
                        // PIN-only: opens the 2-field setup. If already on BIOMETRIC, this also
                        // strips the biometric flag back down.
                        if (state.security.lockMode == com.filestech.sms.data.local.datastore.LockMode.BIOMETRIC) {
                            viewModel.disableBiometric()
                        } else {
                            pinSetupOpen = true
                        }
                    }
                    com.filestech.sms.data.local.datastore.LockMode.BIOMETRIC -> {
                        // Biometric requires a PIN as fallback. If none yet → run PIN setup first;
                        // the post-PIN flow will flip to BIOMETRIC. Otherwise just promote in place.
                        if (state.security.lockMode == com.filestech.sms.data.local.datastore.LockMode.OFF) {
                            pinSetupOpen = true
                            // We rely on the user re-picking BIOMETRIC after setting the PIN —
                            // safer than auto-switching behind a successful PIN setup.
                        } else {
                            scope.launch {
                                val ok = viewModel.enableBiometricOverPin()
                                if (!ok) android.widget.Toast.makeText(
                                    biometricCtx,
                                    biometricCtx.getString(R.string.lock_biometric_unavailable),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onDismiss = { lockModePickerOpen = false },
        )
    }

    if (pinSetupOpen) {
        PinSetupDialog(
            onConfirm = { pin ->
                viewModel.setPin(pin)
                pinSetupOpen = false
            },
            onDismiss = { pinSetupOpen = false },
        )
    }
}

/**
 * Lock-mode picker — v1.1.x exposes only **None** and **PIN**. Pattern / Biometric ride on the
 * same `LockMode` enum but their UX (system biometric prompt, pattern grid) is reserved for a
 * later iteration.
 *
 * The dialog is purely a selector; actual lock setup happens in [PinSetupDialog] when the user
 * picks "PIN".
 */
@Composable
private fun LockModePickerDialog(
    currentMode: com.filestech.sms.data.local.datastore.LockMode,
    onPick: (com.filestech.sms.data.local.datastore.LockMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_mode_dialog_title)) },
        text = {
            Column {
                LockModeOption(
                    label = stringResource(R.string.lock_mode_off),
                    selected = currentMode == com.filestech.sms.data.local.datastore.LockMode.OFF,
                    onClick = { onPick(com.filestech.sms.data.local.datastore.LockMode.OFF) },
                )
                LockModeOption(
                    label = stringResource(R.string.lock_mode_pin),
                    selected = currentMode == com.filestech.sms.data.local.datastore.LockMode.PIN,
                    onClick = { onPick(com.filestech.sms.data.local.datastore.LockMode.PIN) },
                )
                LockModeOption(
                    label = stringResource(R.string.lock_mode_biometric),
                    selected = currentMode == com.filestech.sms.data.local.datastore.LockMode.BIOMETRIC,
                    onClick = { onPick(com.filestech.sms.data.local.datastore.LockMode.BIOMETRIC) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun LockModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

/**
 * Two-field PIN-setup dialog. Both fields hold the secret in a `CharArray` derived from the
 * `TextFieldValue.text` snapshot so we can wipe it deterministically when the dialog goes
 * away or after the manager has consumed it.
 *
 * Validation:
 *  - digits only ([Char.isDigit]),
 *  - length 4–12,
 *  - both fields must match.
 *
 * On confirm, the new PIN is handed to the caller as a fresh `CharArray`. The dialog's own
 * working buffers are wiped synchronously before [onConfirm] returns control to recomposition.
 */
@Composable
private fun PinSetupDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val digitsOnly = remember(pin, confirm) {
        pin.all { it.isDigit() } && confirm.all { it.isDigit() }
    }
    val tooShort = pin.length < 4
    val mismatch = pin.isNotEmpty() && confirm.isNotEmpty() && pin != confirm
    val canSubmit = digitsOnly && !tooShort && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_setup_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12) pin = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pin_setup_field)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                )
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 12) confirm = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pin_setup_confirm)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                )
                val errorMessage = when {
                    !digitsOnly -> stringResource(R.string.pin_setup_digits_only)
                    pin.isNotEmpty() && tooShort -> stringResource(R.string.pin_setup_too_short)
                    mismatch -> stringResource(R.string.pin_setup_mismatch)
                    else -> null
                }
                if (errorMessage != null) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chars = pin.toCharArray()
                    pin = ""
                    confirm = ""
                    onConfirm(chars)
                },
                enabled = canSubmit,
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = {
                pin = ""
                confirm = ""
                onDismiss()
            }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Single toggle line. Pass [description] to surface a small explanatory line under the title
 * (Material 3 `supportingContent`) — useful for security/privacy toggles whose effect is not
 * obvious from the title alone.
 *
 * The container is forced transparent so the parent [SectionCard]'s tinted surface shows
 * through and the whole section reads as one unified group.
 */
@Composable
private fun ToggleRow(
    title: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
) {
    // v1.2.6 polish : Row compact à la place du `ListItem` (qui forçait min-height 56/72 dp et
    // un Switch pleine taille). On garde un touch target ≥ 48 dp via `heightIn` tout en
    // resserrant les paddings verticaux. Switch légèrement scaled-down (0.85f) pour qu'il
    // pèse moins dans l'œil — purement visuel, le hit-area natif reste intact.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
private fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    description: String? = null,
) {
    // Aligné sur ToggleRow ci-dessus pour une densité visuelle homogène dans les SectionCard.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Visual theme picker: a horizontal row of four phone-shaped swatches, each rendering a tiny
 * mock of the conversation list under the corresponding theme (background, accent strip, two
 * message-bubble bars). The active swatch is wrapped in a primary-colored ring; tapping any
 * swatch commits the selection immediately.
 *
 * Beats both the previous radio list and the dropdown attempt: the user sees the actual look
 * of the theme before committing, and the four-up layout makes the choice scannable at a glance
 * (no menu to open, no scrolling).
 */
@Composable
private fun ThemeSwatchPicker(
    current: com.filestech.sms.data.local.datastore.ThemeMode,
    onSelect: (com.filestech.sms.data.local.datastore.ThemeMode) -> Unit,
) {
    val items = listOf(
        com.filestech.sms.data.local.datastore.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        com.filestech.sms.data.local.datastore.ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        com.filestech.sms.data.local.datastore.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        com.filestech.sms.data.local.datastore.ThemeMode.DARK_TECH to stringResource(R.string.settings_theme_dark_tech),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { (mode, label) ->
                ThemeSwatch(
                    mode = mode,
                    label = label,
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (current == com.filestech.sms.data.local.datastore.ThemeMode.DARK_TECH) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.settings_theme_dark_tech_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeSwatch(
    mode: com.filestech.sms.data.local.datastore.ThemeMode,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = swatchPalette(mode)
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .border(width = 2.dp, color = ringColor, shape = RoundedCornerShape(14.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.background),
        ) {
            // Tiny mock-UI: header strip (accent), two bars (incoming bubble + outgoing bubble).
            Column(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.incomingBubble),
                )
                Spacer(Modifier.size(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.incomingBubble.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.size(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.outgoingBubble),
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hand-picked palette used to render each [ThemeSwatch] preview. */
private data class SwatchPalette(
    val background: Color,
    val accent: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
)

@Composable
private fun swatchPalette(mode: com.filestech.sms.data.local.datastore.ThemeMode): SwatchPalette =
    when (mode) {
        com.filestech.sms.data.local.datastore.ThemeMode.SYSTEM -> SwatchPalette(
            // Split-style: half light, half dark — we use the light side and a contrast accent.
            background = Color(0xFFF2F4F8),
            accent = Color(0xFF1F2937),
            incomingBubble = Color(0xFFD8DEE9),
            outgoingBubble = Color(0xFF1F2937),
        )
        com.filestech.sms.data.local.datastore.ThemeMode.LIGHT -> SwatchPalette(
            background = Color(0xFFFFFFFF),
            accent = Color(0xFF1565C0),
            incomingBubble = Color(0xFFE7ECF3),
            outgoingBubble = Color(0xFF1565C0),
        )
        com.filestech.sms.data.local.datastore.ThemeMode.DARK -> SwatchPalette(
            background = Color(0xFF15171C),
            accent = Color(0xFF82B1FF),
            incomingBubble = Color(0xFF2A2E36),
            outgoingBubble = Color(0xFF3D6FE0),
        )
        com.filestech.sms.data.local.datastore.ThemeMode.DARK_TECH -> SwatchPalette(
            background = Color(0xFF050505),
            accent = Color(0xFF00E5FF),
            incomingBubble = Color(0xFF111418),
            outgoingBubble = Color(0xFFE040FB),
        )
    }

/**
 * Groups related settings inside a soft tinted card with an icon-led header. Visual replacement
 * for the previous flat [SectionHeader] approach — the screen now reads as a stack of clearly
 * separated topical groups, which is what users expect from a top-tier Settings screen on
 * Android 14+ (Samsung One UI 6, Pixel Material 3, etc.).
 */
@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(10.dp))
            // v1.2.6 polish : grossi titleMedium + SemiBold pour les en-têtes de section.
            // Anciennement labelLarge ~14sp qui se perdait visuellement dans la page.
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  v1.2.6 audit F4 — "Mon numéro" row + dialog + auto-detection helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result d'une tentative de détection automatique du MSISDN du SIM par défaut.
 *  - [Success] : un numéro a été trouvé.
 *  - [PermissionDenied] : `READ_PHONE_NUMBERS` n'est pas accordée — message dédié à l'UI.
 *  - [Unavailable] : permission OK mais l'OS ne connaît pas le numéro (Free Mobile FR, MVNO).
 */
private sealed interface MsisdnDetection {
    data class Success(val msisdn: String) : MsisdnDetection
    data object PermissionDenied : MsisdnDetection
    data object Unavailable : MsisdnDetection
}

/**
 * Lit le MSISDN via `SubscriptionManager`. Discrimine "permission révoquée" vs "OS ne sait pas"
 * pour pouvoir afficher un message d'aide ciblé. Pas de magie : si la perm n'est pas accordée,
 * on ne fait pas l'appel système (évite `SecurityException` silencieusement attrapée v1.2.6).
 *
 * v1.2.7 audits S4 (checkSelfPermission strict) + Q6 (l'appel doit être fait sur Dispatchers.IO
 * par le caller — `getSystemService` + `activeSubscriptionInfoList` font un binder IPC qui peut
 * geler le main thread sur Samsung).
 */
@android.annotation.SuppressLint("MissingPermission")
private fun detectMsisdn(context: android.content.Context): MsisdnDetection {
    // v1.2.7 audit S4 : check runtime permission AVANT l'appel binder. On rate l'appel propre
    // au lieu de le faire crasher silencieusement dans le catch.
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_PHONE_NUMBERS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return MsisdnDetection.PermissionDenied

    val sm = context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        as? android.telephony.SubscriptionManager ?: return MsisdnDetection.Unavailable
    val defaultId = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
    if (defaultId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        return MsisdnDetection.Unavailable
    }
    // v1.2.8 : la lecture du MSISDN est notoirement capricieuse sur Samsung One UI 6 /
    // Free Mobile FR / MVNO. On essaie 3 sources dans l'ordre, et la première qui rend une
    // valeur non vide gagne :
    //   1. `SubscriptionManager.getPhoneNumber(subId)` (API 33+) — méthode officielle moderne,
    //      ajoutée précisément pour résoudre ce problème ; agrège SIM / carrier / IMS.
    //   2. `SubscriptionInfo.number` (API 22+, deprecated API 30+) — historique.
    //   3. `TelephonyManager.createForSubscriptionId(subId).line1Number` (API 22+) — fallback
    //      ROM/carrier qui répond parfois quand les deux autres rendent vide.
    val candidates = sequence {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching { sm.getPhoneNumber(defaultId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { yield(it) }
        }
        runCatching {
            val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == defaultId }
            @Suppress("DEPRECATION")
            info?.number
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { yield(it) }
        runCatching {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                ?.createForSubscriptionId(defaultId)
            @Suppress("DEPRECATION")
            tm?.line1Number
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { yield(it) }
    }
    val number = candidates.firstOrNull()
    return if (number.isNullOrBlank()) {
        timber.log.Timber.w("detectMsisdn: no source returned a number (subId=%d)", defaultId)
        MsisdnDetection.Unavailable
    } else {
        MsisdnDetection.Success(number)
    }
}

/** v1.2.7 audit Q7 : regex de validation MSISDN — autorise +, digits, espaces, tirets, parens. */
private val MSISDN_PATTERN = Regex("^\\+?[0-9 ()\\-]{4,20}$")

@Composable
private fun MyNumberRow(current: String?, onChange: (String?) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { dialogOpen = true }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.settings_my_number), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = current?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_my_number_not_set),
                style = MaterialTheme.typography.bodySmall,
                color = if (current.isNullOrBlank()) cs.onSurfaceVariant else cs.primary,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
        )
    }
    if (dialogOpen) {
        MyNumberDialog(
            initial = current.orEmpty(),
            onDismiss = { dialogOpen = false },
            onConfirm = { value ->
                dialogOpen = false
                onChange(value)
            },
        )
    }
}

@Composable
private fun MyNumberDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initial) }
    // v1.2.7 audit Q16 : le précédent SnackbarHost imbriqué dans `AlertDialog.text` ne
    // s'affichait jamais (le host n'a pas de surface dans le content slot du dialog). On
    // remplace par un message inline placé sous le bouton "Détecter".
    var detectFeedback by remember { mutableStateOf<MsisdnDetection?>(null) }
    val cs = MaterialTheme.colorScheme

    // v1.2.8 : si la permission READ_PHONE_NUMBERS n'est pas accordée au moment de tap
    // "Détecter", on la demande automatiquement au lieu d'afficher juste un message d'erreur.
    // Si l'utilisateur accepte, on relance la détection ; sinon on affiche le feedback.
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) { detectMsisdn(context) }
                when (result) {
                    is MsisdnDetection.Success -> {
                        value = result.msisdn
                        detectFeedback = null
                    }
                    else -> detectFeedback = result
                }
            }
        } else {
            detectFeedback = MsisdnDetection.PermissionDenied
        }
    }

    // v1.2.7 audit Q7 : la valeur est valide si vide (= clear) OU matche le pattern MSISDN.
    val canSubmit = value.isBlank() || MSISDN_PATTERN.matches(value)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_my_number_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_my_number_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    placeholder = { Text(stringResource(R.string.settings_my_number_placeholder)) },
                    singleLine = true,
                    isError = value.isNotBlank() && !MSISDN_PATTERN.matches(value),
                    supportingText = if (value.isNotBlank() && !MSISDN_PATTERN.matches(value)) {
                        { Text(stringResource(R.string.settings_my_number_invalid)) }
                    } else null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        // v1.2.7 audit Q6 : detectMsisdn fait un binder IPC ; on l'exécute
                        // sur Dispatchers.IO pour ne pas geler le main thread.
                        // v1.2.8 : si la permission n'est pas encore accordée, on la demande
                        // d'abord — le callback du launcher relance la détection.
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.READ_PHONE_NUMBERS,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(android.Manifest.permission.READ_PHONE_NUMBERS)
                            return@TextButton
                        }
                        scope.launch {
                            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                detectMsisdn(context)
                            }
                            when (result) {
                                is MsisdnDetection.Success -> {
                                    value = result.msisdn
                                    detectFeedback = null
                                }
                                else -> detectFeedback = result
                            }
                        }
                    },
                ) {
                    Icon(
                        Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_my_number_detect))
                }
                detectFeedback?.let { fb ->
                    val msgRes = when (fb) {
                        MsisdnDetection.PermissionDenied -> R.string.settings_my_number_detect_no_perm
                        MsisdnDetection.Unavailable -> R.string.settings_my_number_detect_failed
                        is MsisdnDetection.Success -> null // jamais ici
                    }
                    if (msgRes != null) {
                        Text(
                            text = stringResource(msgRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.error,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onConfirm(value) },
                enabled = canSubmit,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
