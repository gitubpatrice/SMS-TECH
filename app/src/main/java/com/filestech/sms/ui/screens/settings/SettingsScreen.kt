package com.filestech.sms.ui.screens.settings

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
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
import com.filestech.sms.ui.components.showError
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
    onOpenSafetyCall: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenEmergencySetup: () -> Unit,
    // v1.15.1 — Accès à la nouvelle screen "Messages programmés" (infra existait
    // déjà mais aucune UI ne l'exposait — feature dormante).
    onOpenScheduledMessages: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.10.0 perf P2 — recomputé toutes les 60s (ou à chaque changement de
    // state), évite l'appel à System.currentTimeMillis() à chaque recomposition.
    val safetyCallRemainingMs by viewModel.safetyCallRemainingMs.collectAsStateWithLifecycle()
    // v1.10.0 audit SEC-1 — en session PanicDecoy, masquer toutes les sections
    // qui révèlent que l'app dispose de fonctions de sécurité personnelle.
    val isPanicDecoy by viewModel.isPanicDecoy.collectAsStateWithLifecycle()
    var showNuke by remember { mutableStateOf(false) }
    var lockModePickerOpen by remember { mutableStateOf(false) }
    var autoDeletePickerOpen by remember { mutableStateOf(false) }
    var autoDeletePurgeConfirmOpen by remember { mutableStateOf(false) }
    var pinSetupOpen by remember { mutableStateOf(false) }
    // v1.8.0 (bug 3 fix) — pickers PreviewMode + NotificationStyle.
    var previewModePickerOpen by remember { mutableStateOf(false) }
    var notifStylePickerOpen by remember { mutableStateOf(false) }
    // v1.8.0 (bug 5 fix) — picker ReactionFormat (3 options).
    var reactionFormatPickerOpen by remember { mutableStateOf(false) }
    // v1.8.0 — 3 nouveaux dialogs de confirmation pour les actions destructives :
    //  - resync depuis le téléphone (réimporte tout)
    //  - reset all settings (defaults)
    //  - purge conversations bloquées
    var showResyncConfirm by remember { mutableStateOf(false) }
    var showResetAllConfirm by remember { mutableStateOf(false) }
    var showPurgeBlockedConfirm by remember { mutableStateOf(false) }
    // v1.13.0 — PIN/pass distinct coffre.
    var vaultPinSetupOpen by remember { mutableStateOf(false) }
    var vaultPinClearConfirmOpen by remember { mutableStateOf(false) }
    // v1.14.0 — Comportement boutons 112/17 (DIALER_ONLY vs HOLD_3S_DIRECT_CALL).
    // Retiré v1.14.1 : la page Mode urgence v1.14.1 utilise direct call avec
    // fallback automatique → picker Settings devenait orphelin (dead setting).
    // Clé DataStore préservée pour compat ascendante mais non lue par UI.

    val defaultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* no-op */ }

    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // v1.9.0 — scope partagé pour les actions instantanées qui doivent émettre
    // un snack depuis un onClick callback (ex. bouton "Je vais bien" Safety call).
    val rootScope = rememberCoroutineScope()

    // v1.14.0 callPhonePermLauncher retiré v1.14.1 : la permission est
    // désormais demandée directement depuis EmergencyScreen au 1er tap d'un
    // bouton d'appel (pas dans Settings). Le setting `emergencyCallBehavior`
    // n'est plus lu par UI → permission peut être demandée hors picker.
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is SettingsViewModel.Event.BlockedPurged -> {
                    val msg = if (e.count > 0) {
                        ctx.getString(R.string.settings_purge_blocked_result, e.count)
                    } else ctx.getString(R.string.settings_purge_blocked_none)
                    snackbarHost.showSnackbar(msg)
                }
                is SettingsViewModel.Event.HistoryPurged -> {
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.settings_auto_delete_purge_done, e.count),
                    )
                }
                SettingsViewModel.Event.ResyncRequested -> {
                    snackbarHost.showSnackbar(ctx.getString(R.string.settings_resync_started))
                }
            }
        }
    }

    // v1.14.5 — Launcher permission ACCESS_FINE_LOCATION pour le ToggleRow
    // "Inclure position GPS" du Mode urgence. Audit SEC-1 fix : si l'user
    // refuse la permission, on REVERT `includeLocation = false` en DataStore
    // pour éviter un état sale (toggle ON mais SMS sans coords). Pattern
    // miroir de `revertCallBehaviorIfPermissionRevoked` (v1.10.0/v1.14.1).
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            viewModel.update {
                it.copy(security = it.security.copy(
                    emergency = it.security.emergency.copy(includeLocation = false),
                ))
            }
            rootScope.launch {
                snackbarHost.showError(ctx.getString(R.string.settings_emergency_include_location_denied))
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
        snackbarHost = { com.filestech.sms.ui.components.SmsTechSnackbarHost(snackbarHost) },
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
                // v1.3.1 — envoi des réactions emoji au correspondant. ON par défaut. Quand
                // OFF, les réactions restent strictement locales (badge visible uniquement
                // dans SMS Tech). La description prévient explicitement du coût SMS.
                ToggleRow(
                    title = stringResource(R.string.settings_send_reactions_title),
                    description = stringResource(R.string.settings_send_reactions_desc),
                    value = state.sending.sendReactionsToRecipient,
                    onChange = { v ->
                        viewModel.update { it.copy(sending = it.sending.copy(sendReactionsToRecipient = v)) }
                    },
                )
                // v1.8.0 (bug 5 fix) — format du SMS de réaction. 3 options
                // (anciennement boolean reactionEmojiOnly à 2 valeurs) :
                //  - READABLE_FR (nouveau défaut) : "J'ai réagi par ❤️ à : «…»"
                //    — naturel et lisible pour les destinataires francophones Android
                //  - TAPBACK_EN (ancien défaut v1.7.x) : "Reacted ❤️ to «…»"
                //    — compat iMessage iPhone + Google Messages récent
                //  - EMOJI_ONLY : "❤️" seul — minimal, perd le contexte côté destinataire
                //
                // Affiché uniquement si l'envoi des réactions est activé. La transition
                // AnimatedVisibility v1.3.7 reste, on remplace juste le ToggleRow par
                // un NavigationRow + picker (cohérent avec PreviewMode / NotificationStyle
                // ajoutés en v1.8.0).
                AnimatedVisibility(
                    visible = state.sending.sendReactionsToRecipient,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val reactionFormatLabel = when (state.sending.reactionFormat) {
                        com.filestech.sms.data.local.datastore.ReactionFormat.READABLE_FR ->
                            stringResource(R.string.settings_reaction_format_fr)
                        com.filestech.sms.data.local.datastore.ReactionFormat.TAPBACK_EN ->
                            stringResource(R.string.settings_reaction_format_en)
                        com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_ONLY ->
                            stringResource(R.string.settings_reaction_format_emoji)
                        com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_WITH_QUOTE ->
                            stringResource(R.string.settings_reaction_format_emoji_quote)
                    }
                    NavigationRow(
                        title = stringResource(R.string.settings_reaction_format_title),
                        description = reactionFormatLabel,
                        onClick = { reactionFormatPickerOpen = true },
                    )
                }
                // v1.8.1 — nom inclus dans les SMS de réaction sortants au
                // format READABLE_FR. Affiché uniquement si READABLE_FR est
                // sélectionné ET envoi des réactions activé (cohérence UX).
                AnimatedVisibility(
                    visible = state.sending.sendReactionsToRecipient &&
                        state.sending.reactionFormat ==
                            com.filestech.sms.data.local.datastore.ReactionFormat.READABLE_FR,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SenderNameRow(
                        current = state.sending.senderDisplayName,
                        onChange = { name ->
                            viewModel.update {
                                it.copy(
                                    sending = it.sending.copy(
                                        senderDisplayName = name?.takeIf { s -> s.isNotBlank() },
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            // v1.15.0 — Section extraite vers [NotificationsSection] private @Composable.
            // Comportement identique, lecture facilitée (avant : 68 lignes inline ici).
            NotificationsSection(
                notifications = state.notifications,
                onUpdate = viewModel::update,
                onOpenPreviewPicker = { previewModePickerOpen = true },
                onOpenStylePicker = { notifStylePickerOpen = true },
            )

            // v1.15.0 — Section extraite vers [SecuritySection] private @Composable.
            SecuritySection(
                security = state.security,
                isPanicDecoy = isPanicDecoy,
                onUpdate = viewModel::update,
                onOpenLockModePicker = { lockModePickerOpen = true },
                onOpenVaultPinSetup = { vaultPinSetupOpen = true },
                onOpenVaultPinClearConfirm = { vaultPinClearConfirmOpen = true },
                onOpenPurgeBlockedConfirm = { showPurgeBlockedConfirm = true },
                onOpenAutoDeletePicker = { autoDeletePickerOpen = true },
            )

            // v1.9.0 — Safety call. Section dédiée pour clarté visuelle :
            // c'est une feature de sécurité PERSONNELLE (envoyer SMS à mes
            // proches si je ne donne plus signe de vie), distincte de la
            // sécurité du DEVICE (verrouillage, panic mode).
            // v1.10.0 audit SEC-1 (extension) — masquée en PanicDecoy par
            // cohérence avec Mode urgence : ne pas révéler à l'agresseur
            // l'existence des features de sécurité personnelle.
            if (!isPanicDecoy) SectionCard(
                title = stringResource(R.string.settings_section_safety_call),
                icon = Icons.Outlined.Shield,
            ) {
                val safetyCall = state.security.safetyCall
                if (safetyCall.enabled) {
                    // Récap visible quand armé : durée, restant, contacts,
                    // template + 2 actions (Modifier / Je vais bien).
                    SafetyCallArmedRecap(
                        config = safetyCall,
                        remainingMs = safetyCallRemainingMs,
                        onModify = onOpenSafetyCall,
                        onImOk = {
                            viewModel.update { s ->
                                s.copy(
                                    security = s.security.copy(
                                        safetyCall = s.security.safetyCall.copy(
                                            lastActivityAt = System.currentTimeMillis(),
                                            // v1.10.0 SEC-11 — couple mono+wall.
                                            monotonicLastActivityAt = SystemClock.elapsedRealtime(),
                                        ),
                                    ),
                                )
                            }
                            rootScope.launch {
                                snackbarHost.showSnackbar(
                                    ctx.getString(R.string.settings_safety_call_im_ok_snack),
                                )
                            }
                        },
                    )
                } else {
                    NavigationRow(
                        title = stringResource(R.string.settings_safety_call_title),
                        description = stringResource(R.string.settings_safety_call_disabled) +
                            "\n" + stringResource(R.string.settings_safety_call_desc),
                        onClick = onOpenSafetyCall,
                    )
                }
            }

            // v1.10.0 — Mode urgence. Section dédiée, distincte du Safety call :
            // ici c'est l'user qui DÉCLENCHE activement (bouton hold 3s) ; le
            // Safety call est passif (timer d'inactivité). Cohérence des
            // contacts garantie par la réutilisation de la même liste.
            // v1.10.0 audit SEC-1 — entièrement masquée en PanicDecoy
            // (l'agresseur ne doit pas savoir que la feature existe).
            // v1.15.0 — Section extraite vers [EmergencySection] private @Composable.
            if (!isPanicDecoy) {
                EmergencySection(
                    security = state.security,
                    onUpdate = viewModel::update,
                    onOpenEmergencySetup = onOpenEmergencySetup,
                    onOpenEmergency = onOpenEmergency,
                    onRequestLocationPermission = {
                        locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    },
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

            // v1.15.1 — Section dédiée Messages programmés. Avant : noyée dans "Blocage" →
            // confusion conceptuelle (un message différé n'a rien à voir avec un blocage).
            // Section autonome plus visible et cohérente avec la nature de la feature.
            SectionCard(
                title = stringResource(R.string.settings_section_scheduled),
                icon = Icons.Outlined.Schedule,
            ) {
                NavigationRow(
                    title = stringResource(R.string.settings_scheduled_messages),
                    description = stringResource(R.string.settings_scheduled_messages_desc),
                    onClick = onOpenScheduledMessages,
                )
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
                // v1.3.0 — re-sync complète depuis le content provider Android. Utile pour
                // récupérer un historique purgé par erreur (le system provider conserve les SMS
                // indépendamment de l'app). Le re-import dedup via l'index UNIQUE telephony_uri.
                NavigationRow(
                    title = stringResource(R.string.settings_resync_title),
                    description = stringResource(R.string.settings_resync_desc),
                    // v1.8.0 — dialog de confirmation (peut faire ré-apparaître
                    // des conversations précédemment supprimées si toujours
                    // présentes dans le content provider système).
                    onClick = { showResyncConfirm = true },
                )
                // v1.3.10 + v1.4.1 — Mode résistant OPT-IN (KeepAliveService foreground
                // permanent). Toggle exposé pour TOUS les téléphones car la détection ROM
                // est conservatrice : certains utilisateurs sur ROM "propre" pourraient
                // quand même vouloir activer ; certains sur ROM "agressive" pourraient avoir
                // whitelisté SMS Tech à la main et ne pas vouloir la notif persistante. La
                // description liste les ROMs concernées pour aider la décision.
                // **v1.4.1** : retrait du dialog onboarding qui auto-proposait l'activation
                // sur Xiaomi/Redmi — trop pushy, des users tapaient "Activer" sans
                // comprendre la notif persistante.
                ToggleRow(
                    title = stringResource(R.string.settings_keep_alive_title),
                    description = stringResource(R.string.settings_keep_alive_desc),
                    value = state.advanced.keepAliveService,
                    onChange = { v ->
                        // v1.3.10 (C4) — single source of truth: only persist the flag here.
                        // [MainApplication] observes the DataStore via `distinctUntilChanged`
                        // and calls `KeepAliveService.start/stop` within ~50 ms — well under
                        // the threshold of perceptibility. The previous inline `start/stop`
                        // was redundant and caused a double `startForeground` on the same
                        // notification id, which produced a brief notif-flash on MIUI.
                        viewModel.update { it.copy(advanced = it.advanced.copy(keepAliveService = v)) }
                    },
                )
                // v1.8.0 — dialog de confirmation (les préférences revient
                // aux defaults, mais les conversations restent intactes).
                NavigationRow(
                    stringResource(R.string.settings_reset_all),
                    onClick = { showResetAllConfirm = true },
                )
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

    if (autoDeletePickerOpen) {
        AutoDeleteDialog(
            current = state.security.autoDeleteOlderThanDays,
            onSelect = { days ->
                // Persist mais on NE ferme PAS le dialog : l'utilisateur peut vouloir
                // enchaîner sur "Effacer maintenant" avec la nouvelle profondeur.
                viewModel.update { it.copy(security = it.security.copy(autoDeleteOlderThanDays = days)) }
            },
            onPurgeNow = { /* delegated to confirm dialog */ },
            onRequestConfirm = { autoDeletePurgeConfirmOpen = true },
            onDismiss = { autoDeletePickerOpen = false },
        )
    }

    if (autoDeletePurgeConfirmOpen) {
        val days = state.security.autoDeleteOlderThanDays ?: 0
        if (days > 0) {
            PurgeNowConfirmDialog(
                days = days,
                countLoader = { viewModel.countHistoryToPurge(days) },
                onConfirm = {
                    viewModel.purgeHistoryNow(days)
                    autoDeletePurgeConfirmOpen = false
                    autoDeletePickerOpen = false
                },
                onDismiss = { autoDeletePurgeConfirmOpen = false },
            )
        } else {
            // Cas sentinelle : `days = 0` ne devrait pas arriver car le bouton est
            // disabled, mais on ferme proprement si on y est tombé via une race.
            autoDeletePurgeConfirmOpen = false
        }
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

    if (previewModePickerOpen) {
        PreviewModePickerDialog(
            current = state.notifications.previewMode,
            onSelect = { mode ->
                viewModel.update {
                    it.copy(notifications = it.notifications.copy(previewMode = mode))
                }
                previewModePickerOpen = false
            },
            onDismiss = { previewModePickerOpen = false },
        )
    }

    if (notifStylePickerOpen) {
        NotificationStylePickerDialog(
            current = state.notifications.style,
            onSelect = { style ->
                viewModel.update {
                    it.copy(notifications = it.notifications.copy(style = style))
                }
                notifStylePickerOpen = false
            },
            onDismiss = { notifStylePickerOpen = false },
        )
    }

    // v1.8.0 — Dialog de confirmation : Resync depuis le téléphone.
    // Pattern Files Tech : autofocus Cancel + bouton confirm primary action.
    // v1.10.0 — BrandBlue + blanc (demande user 2026-05-21), action non
    // destructive (re-importe depuis content://sms, pas de perte).
    if (showResyncConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { showResyncConfirm = false },
            title = { Text(stringResource(R.string.settings_resync_confirm_title)) },
            text = { Text(stringResource(R.string.settings_resync_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        viewModel.forceResyncFromTelephony()
                        showResyncConfirm = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = com.filestech.sms.ui.theme.BrandBlue,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResyncConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // v1.8.0 — Dialog de confirmation : Réinitialiser tous les réglages.
    // Pattern Files Tech : autofocus Cancel — l'user qui tape rapidement après
    // un précédent dialog ne réinitialise pas par réflexe.
    // v1.10.0 — confirm BrandBlue + blanc (demande user 2026-05-21).
    // Action remet les réglages aux défauts mais NE touche PAS aux messages
    // (donc non-destructive au sens contenu utilisateur).
    if (showResetAllConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { showResetAllConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_all_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_all_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        viewModel.resetAll()
                        showResetAllConfirm = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = com.filestech.sms.ui.theme.BrandBlue,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetAllConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // v1.8.0 — Dialog de confirmation : Purger les conversations bloquées.
    // Action IRRÉVERSIBLE → autofocus Cancel critique + couleur error pour
    // éviter qu'un user clique "OK" par réflexe et perde ses conv.
    if (showPurgeBlockedConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { showPurgeBlockedConfirm = false },
            title = { Text(stringResource(R.string.settings_purge_blocked_confirm_title)) },
            text = { Text(stringResource(R.string.settings_purge_blocked_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        viewModel.purgeBlockedConversations()
                        showPurgeBlockedConfirm = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        // v1.9.0 — passe de `errorContainer` (rose pâle Material 3)
                        // à `BrandDanger` (rouge logo) pour cohérence cross-app
                        // avec les autres boutons destructifs.
                        containerColor = com.filestech.sms.ui.theme.BrandDanger,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPurgeBlockedConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (reactionFormatPickerOpen) {
        ReactionFormatPickerDialog(
            current = state.sending.reactionFormat,
            onSelect = { fmt ->
                viewModel.update {
                    // v1.8.0 (bug 5 fix) — synchronise le legacy `reactionEmojiOnly`
                    // au passage pour qu'un downgrade éventuel vers v1.7.x retrouve
                    // un état cohérent (EMOJI_ONLY → true, autres → false).
                    val legacyEmojiOnly = fmt == com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_ONLY
                    it.copy(
                        sending = it.sending.copy(
                            reactionFormat = fmt,
                            reactionEmojiOnly = legacyEmojiOnly,
                        ),
                    )
                }
                reactionFormatPickerOpen = false
            },
            onDismiss = { reactionFormatPickerOpen = false },
        )
    }

    // v1.13.0 — Dialog setup PIN/pass coffre : saisie + confirmation.
    if (vaultPinSetupOpen) {
        VaultPinSetupDialog(
            appPinHint = state.security.lockMode == com.filestech.sms.data.local.datastore.LockMode.PIN ||
                state.security.lockMode == com.filestech.sms.data.local.datastore.LockMode.BIOMETRIC,
            onConfirm = { pin ->
                viewModel.setVaultPin(pin)
                vaultPinSetupOpen = false
                rootScope.launch {
                    snackbarHost.showSnackbar(ctx.getString(R.string.settings_vault_pin_saved))
                }
            },
            onDismiss = { vaultPinSetupOpen = false },
        )
    }

    // v1.13.0 — Dialog confirmation retrait PIN/pass coffre (l'user désactive
    // le toggle). Action non destructive (rien n'est effacé sauf le hash),
    // mais on confirme pour éviter un toggle accidentel qui réduirait la
    // sécurité. Pattern : confirm Button BrandDanger, Cancel autofocus.
    if (vaultPinClearConfirmOpen) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { vaultPinClearConfirmOpen = false },
            title = { Text(stringResource(R.string.settings_vault_pin_title)) },
            text = { Text(stringResource(R.string.settings_vault_pin_desc)) },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        viewModel.clearVaultPin()
                        vaultPinClearConfirmOpen = false
                        rootScope.launch {
                            snackbarHost.showSnackbar(ctx.getString(R.string.settings_vault_pin_cleared))
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = com.filestech.sms.ui.theme.BrandDanger,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text(stringResource(R.string.action_disable)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { vaultPinClearConfirmOpen = false },
                    modifier = Modifier.focusRequester(cancelFocus).focusable(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // v1.14.1 — `EmergencyCallBehaviorPickerDialog` retiré (setting orphelin
    // post-redesign EmergencyScreen full-page direct-call).
}

/**
 * v1.8.0 (bug 3 fix HIGH 3a) — picker pour [com.filestech.sms.data.local.datastore
 * .PreviewMode]. Pattern strictement aligné sur [LockModePickerDialog] (radio + label +
 * hint sous-titre pour les options dont l'effet est non-évident).
 *
 * Le hint sous l'option `NEVER` est crucial : c'est exactement le scénario du bug 3
 * remonté par l'utilisateur (son joué, notif invisible). Documenter explicitement
 * "le son est joué mais rien ne s'affiche sur l'écran de verrouillage" élimine la
 * confusion.
 */
@Composable
private fun PreviewModePickerDialog(
    current: com.filestech.sms.data.local.datastore.PreviewMode,
    onSelect: (com.filestech.sms.data.local.datastore.PreviewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notif_preview)) },
        text = {
            Column {
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_preview_always),
                    hint = null,
                    selected = current == com.filestech.sms.data.local.datastore.PreviewMode.ALWAYS,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.PreviewMode.ALWAYS) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_preview_unlocked),
                    hint = stringResource(R.string.settings_notif_preview_unlocked_hint),
                    selected = current == com.filestech.sms.data.local.datastore.PreviewMode.WHEN_UNLOCKED,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.PreviewMode.WHEN_UNLOCKED) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_preview_never),
                    hint = stringResource(R.string.settings_notif_preview_never_hint),
                    selected = current == com.filestech.sms.data.local.datastore.PreviewMode.NEVER,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.PreviewMode.NEVER) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PreviewModeOption(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * v1.8.0 (bug 3 fix MEDIUM 3b) — picker pour [com.filestech.sms.data.local.datastore
 * .NotificationStyle] (anciennement dead field). Wire l'enum à l'effet réel côté
 * [com.filestech.sms.system.notifications.IncomingMessageNotifier] :
 *  - HEADS_UP → canal IMPORTANCE_HIGH (default Android, heads-up + son)
 *  - BANNER → canal IMPORTANCE_HIGH mais son désactivé côté builder (badge seul)
 *  - SILENT → canal IMPORTANCE_LOW dédié (heads-up et son masqués par l'OS)
 */
/**
 * v1.8.0 (bug 5 fix) — picker pour [com.filestech.sms.data.local.datastore.ReactionFormat].
 * 3 options avec hints expliquant clairement le trade-off (lisibilité francophone vs
 * compat iMessage vs minimalisme).
 */
@Composable
private fun ReactionFormatPickerDialog(
    current: com.filestech.sms.data.local.datastore.ReactionFormat,
    onSelect: (com.filestech.sms.data.local.datastore.ReactionFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reaction_format_title)) },
        text = {
            Column {
                PreviewModeOption(
                    label = stringResource(R.string.settings_reaction_format_fr),
                    hint = stringResource(R.string.settings_reaction_format_fr_hint),
                    selected = current == com.filestech.sms.data.local.datastore.ReactionFormat.READABLE_FR,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.ReactionFormat.READABLE_FR) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_reaction_format_en),
                    hint = stringResource(R.string.settings_reaction_format_en_hint),
                    selected = current == com.filestech.sms.data.local.datastore.ReactionFormat.TAPBACK_EN,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.ReactionFormat.TAPBACK_EN) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_reaction_format_emoji_quote),
                    hint = stringResource(R.string.settings_reaction_format_emoji_quote_hint),
                    selected = current == com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_WITH_QUOTE,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_WITH_QUOTE) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_reaction_format_emoji),
                    hint = stringResource(R.string.settings_reaction_format_emoji_hint),
                    selected = current == com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_ONLY,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.ReactionFormat.EMOJI_ONLY) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NotificationStylePickerDialog(
    current: com.filestech.sms.data.local.datastore.NotificationStyle,
    onSelect: (com.filestech.sms.data.local.datastore.NotificationStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notif_style)) },
        text = {
            Column {
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_style_heads_up),
                    hint = stringResource(R.string.settings_notif_style_heads_up_hint),
                    selected = current == com.filestech.sms.data.local.datastore.NotificationStyle.HEADS_UP,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.NotificationStyle.HEADS_UP) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_style_banner),
                    hint = stringResource(R.string.settings_notif_style_banner_hint),
                    selected = current == com.filestech.sms.data.local.datastore.NotificationStyle.BANNER,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.NotificationStyle.BANNER) },
                )
                PreviewModeOption(
                    label = stringResource(R.string.settings_notif_style_silent),
                    hint = stringResource(R.string.settings_notif_style_silent_hint),
                    selected = current == com.filestech.sms.data.local.datastore.NotificationStyle.SILENT,
                    onClick = { onSelect(com.filestech.sms.data.local.datastore.NotificationStyle.SILENT) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
    //
    // v1.3.7 U1 audit — `Modifier.toggleable(role = Role.Switch)` remplace `.clickable {...}` +
    // `Switch.onCheckedChange`. Avant : TalkBack annonçait DEUX éléments interactifs (la Row
    // "double-tap pour activer" + le Switch "interrupteur, activé/désactivé") → confusion
    // utilisateur. Maintenant : un seul nœud sémantique avec `Role.Switch`, le Switch interne
    // a `onCheckedChange = null` (délègue au parent toggleable). Pattern Material 3 officiel
    // recommandé pour les "selectable list items" → toggle entier sans double-tap a11y.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = value,
                role = Role.Switch,
                onValueChange = onChange,
            )
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
            // v1.3.7 U1 audit — `null` délègue le clic au `Modifier.toggleable` parent
            // (fusion sémantique, un seul élément interactif côté TalkBack/Switch Access).
            onCheckedChange = null,
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

/**
 * v1.8.1 — row Settings pour saisir/effacer le nom personnel inclus dans les
 * SMS de réaction sortants au format `READABLE_FR`. `null` = utiliser l'auto-
 * détection via `ContactsContract.Profile` (le "moi" Android).
 */
@Composable
private fun SenderNameRow(current: String?, onChange: (String?) -> Unit) {
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
            Text(
                stringResource(R.string.settings_sender_name_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = current?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_sender_name_auto),
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
        SenderNameDialog(
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
private fun SenderNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sender_name_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_sender_name_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        // Cap à 40 chars au save (cf. SenderNameProvider.MAX_NAME_LENGTH).
                        value = input.take(40)
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_sender_name_label)) },
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onConfirm(value.trim().takeIf { it.isNotBlank() })
            }) {
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

// ─────────────────────────────────────────────────────────────────────────────
//  v1.3.0 — Auto-suppression historique (rétention 30/60/180 jours + safety 5 j)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Label statut affiché en sous-titre de la `NavigationRow` Réglages → Sécurité →
 * Nettoyage de l'historique. Wording centré sur ce qui est CONSERVÉ pour lever toute
 * ambiguïté ("On garde les 30 derniers jours" plutôt que "Après 30 jours"). Composable
 * car les libellés sont localisés (FR/EN) via `stringResource`.
 */
@Composable
private fun retentionLabel(days: Int?): String = when (days) {
    null, 0 -> stringResource(R.string.settings_auto_delete_status_off)
    30 -> stringResource(R.string.settings_auto_delete_status_30)
    60 -> stringResource(R.string.settings_auto_delete_status_60)
    180 -> stringResource(R.string.settings_auto_delete_status_180)
    else -> stringResource(R.string.settings_auto_delete_status_n_days, days)
}

/**
 * Dialog "Nettoyage de l'historique". Combine :
 *  - un sélecteur radio 4 options (Désactivé / 30 / 60 / 180 j) qui pilote l'auto mensuel ;
 *  - un bouton "Effacer maintenant" qui applique IMMÉDIATEMENT la même profondeur, après
 *    un sous-dialog de confirmation montrant le nombre de messages concernés (pour éviter
 *    un wipe massif accidentel).
 *
 * v1.3.0 : la sélection est persistée à chaque tap radio via [onSelect] ; le dialog ne
 * ferme pas tant que l'utilisateur ne clique pas Close ou Effacer. Le bouton manuel est
 * désactivé tant que `current` vaut null/0 (rien à effacer sans profondeur choisie).
 */
@Composable
private fun AutoDeleteDialog(
    current: Int?,
    onSelect: (Int?) -> Unit,
    onPurgeNow: () -> Unit,
    onRequestConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val options: List<Pair<Int?, String>> = listOf(
        null to stringResource(R.string.settings_auto_delete_off),
        30 to stringResource(R.string.settings_auto_delete_30),
        60 to stringResource(R.string.settings_auto_delete_60),
        180 to stringResource(R.string.settings_auto_delete_180),
    )
    val purgeEnabled = (current ?: 0) > 0
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_delete_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_auto_delete_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = current == value,
                            onClick = { onSelect(value) },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.size(8.dp))
                // Bouton manuel : déclenche d'abord [onRequestConfirm] pour afficher
                // le sous-dialog (qui appelle [onPurgeNow] si l'utilisateur confirme).
                androidx.compose.material3.OutlinedButton(
                    onClick = onRequestConfirm,
                    enabled = purgeEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (purgeEnabled) {
                            stringResource(R.string.settings_auto_delete_purge_now)
                        } else {
                            stringResource(R.string.settings_auto_delete_purge_now_disabled)
                        },
                    )
                }
                // `onPurgeNow` est passé au parent pour la composition du sous-dialog ;
                // on l'utilise ici aussi pour empêcher le linter "unused parameter".
                @Suppress("UNUSED_EXPRESSION") onPurgeNow
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

/**
 * v1.3.0 — sous-dialog de confirmation du bouton "Effacer maintenant". Affiche le nombre
 * de messages concernés (lu via [countLoader]) avant de laisser l'utilisateur valider.
 * Si zéro message ne correspond, le bouton de confirmation est désactivé et le corps est
 * remplacé par un message neutre.
 */
@Composable
private fun PurgeNowConfirmDialog(
    days: Int,
    countLoader: suspend () -> Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days) {
        count = runCatching { countLoader() }.getOrDefault(0)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_delete_purge_confirm_title)) },
        text = {
            val resolved = count
            val body = when {
                resolved == null -> stringResource(R.string.settings_auto_delete_purge_confirm_body, 0, days)
                resolved == 0 -> stringResource(R.string.settings_auto_delete_purge_confirm_zero)
                else -> stringResource(R.string.settings_auto_delete_purge_confirm_body, resolved, days)
            }
            Text(body, color = cs.onSurfaceVariant)
        },
        confirmButton = {
            androidx.compose.material3.FilledTonalButton(
                onClick = onConfirm,
                enabled = (count ?: 0) > 0,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    // v1.9.0 — `BrandDanger` (rouge logo) au lieu d'`errorContainer`.
                    containerColor = com.filestech.sms.ui.theme.BrandDanger,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Text(stringResource(R.string.action_delete_now))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(remember { FocusRequester() }),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * v1.9.0 — Récap visuel d'un Safety call armé, affiché dans Settings →
 * section Safety Call à la place du `NavigationRow` simple quand
 * `enabled=true`. Donne à l'utilisateur la confirmation visuelle que la
 * config a bien été sauvée + tous ses détails clés sans avoir à rouvrir
 * le wizard.
 *
 * Affiche :
 *  - Chip "Armé" coloré (`primaryContainer`)
 *  - Durée totale configurée (formatée via [SafetyCallTemplate.formatDuration])
 *  - Temps restant avant déclenchement (heures, ou "Moins de 2h" si imminent)
 *  - Liste des contacts (1 ligne avec noms concaténés + "+N autres" si > 2)
 *  - Modèle de message choisi (libellé localisé)
 *  - 2 boutons d'action : "Modifier" (→ setup) + "Je vais bien" (reset timer)
 *
 * Les noms des contacts utilisent [SafetyCallContact.sanitizedDisplayName]
 * ou le numéro si pas de nom — cohérent avec l'écran setup.
 */
@Composable
private fun SafetyCallArmedRecap(
    config: com.filestech.sms.domain.safetycall.SafetyCallConfig,
    remainingMs: Long,
    onModify: () -> Unit,
    onImOk: () -> Unit,
) {
    val durationLabel = com.filestech.sms.domain.safetycall.SafetyCallTemplate
        .formatDuration(config.timeoutMs)
    // v1.10.0 perf P2 — [remainingMs] vient du ViewModel (StateFlow tick 60s),
    // plus de System.currentTimeMillis() à chaque recomposition.
    val remainingLabel = when {
        remainingMs <= 0L -> stringResource(R.string.settings_safety_call_armed_remaining_imminent)
        remainingMs < 2 * 3_600_000L ->
            stringResource(R.string.settings_safety_call_armed_remaining_imminent)
        else -> {
            val hours = (remainingMs / 3_600_000L).toInt()
            val niceHours = if (hours >= 24) {
                val days = hours / 24
                val rem = hours % 24
                if (rem == 0) {
                    if (days == 1) "1 jour" else "$days jours"
                } else "$days j ${rem} h"
            } else {
                "$hours h"
            }
            stringResource(R.string.settings_safety_call_armed_remaining, niceHours)
        }
    }
    val contactsLabel = run {
        val labels = config.contacts.map { c ->
            c.sanitizedDisplayName() ?: c.phoneNumber
        }
        when {
            labels.isEmpty() -> ""
            labels.size == 1 -> stringResource(
                R.string.settings_safety_call_armed_contacts_one,
                labels[0],
            )
            labels.size <= 2 -> stringResource(
                R.string.settings_safety_call_armed_contacts_one,
                labels.joinToString(", "),
            )
            else -> stringResource(
                R.string.settings_safety_call_armed_contacts_many,
                labels.take(2).joinToString(", "),
                labels.size - 2,
            )
        }
    }
    val templateLabel = stringResource(
        when (config.template) {
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.CHECK_IN ->
                R.string.safety_call_setup_template_check_in
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.URGENT ->
                R.string.safety_call_setup_template_urgent
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.FOLLOW_UP ->
                R.string.safety_call_setup_template_follow_up
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.CUSTOM ->
                R.string.safety_call_setup_template_custom
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header : titre + chip "Armé"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_safety_call_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.settings_safety_call_armed_chip),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        // Détails — chaque ligne en bodyMedium / onSurfaceVariant pour
        // hiérarchie visuelle (titre en gros, détails en plus discret).
        Text(
            text = stringResource(R.string.settings_safety_call_armed_duration, durationLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = remainingLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (contactsLabel.isNotEmpty()) {
            Text(
                text = contactsLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_safety_call_armed_template, templateLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        // 2 actions côte à côte. v1.9.0 UX :
        //  - "Modifier" → OutlinedButton (action secondaire, neutre)
        //  - "Je vais bien" → Button filled `primary` (bleu BrandBlue
        //    du logo) car c'est l'action positive principale du récap.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onModify,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_safety_call_armed_modify))
            }
            Button(
                onClick = onImOk,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_safety_call_armed_im_ok_short))
            }
        }
    }
}

/**
 * v1.10.0 — Récap visuel d'un Mode urgence armé, affiché dans Settings →
 * section Mode urgence à la place du `NavigationRow` quand `enabled=true`.
 * Pendant exact de [SafetyCallArmedRecap] pour cohérence visuelle.
 *
 * Affiche :
 *  - Chip "Armé" coloré (`primary` brand-blue) avec icône WarningAmber
 *  - Modèle de message choisi (NEED_HELP / DANGER / DISCREET)
 *  - Géoloc incluse oui/non
 *  - Liste des contacts (réutilise la liste Safety Call)
 *  - 2 actions : "Modifier" (→ setup) + "Ouvrir" (→ EmergencyScreen pour
 *    accéder au bouton URGENCE hold 3 s)
 *
 * **Note d'UX** : pas de bouton "trigger" ici car l'écran Settings est
 * la mauvaise surface pour un trigger d'urgence (parcours mental "je
 * configure" vs "j'ai besoin"). Le bouton URGENCE rouge vit uniquement
 * dans [com.filestech.sms.ui.screens.emergency.EmergencyScreen].
 */
@Composable
private fun EmergencyArmedRecap(
    config: com.filestech.sms.domain.emergency.EmergencyConfig,
    contacts: List<com.filestech.sms.domain.safetycall.SafetyCallContact>,
    onModify: () -> Unit,
    onOpen: () -> Unit,
) {
    val templateLabel = stringResource(
        when (config.template) {
            com.filestech.sms.domain.emergency.EmergencyTemplate.NEED_HELP ->
                R.string.emergency_setup_template_need_help
            com.filestech.sms.domain.emergency.EmergencyTemplate.DANGER ->
                R.string.emergency_setup_template_danger
            com.filestech.sms.domain.emergency.EmergencyTemplate.DISCREET ->
                R.string.emergency_setup_template_discreet
        },
    )
    val locationLabel = stringResource(
        if (config.includeLocation) R.string.settings_emergency_armed_location
        else R.string.settings_emergency_armed_no_location,
    )
    val contactsLabel = run {
        val labels = contacts.map { c -> c.sanitizedDisplayName() ?: c.phoneNumber }
        when {
            labels.isEmpty() -> stringResource(R.string.settings_emergency_armed_no_contacts)
            labels.size <= 2 -> stringResource(
                R.string.settings_emergency_armed_contacts_one,
                labels.joinToString(", "),
            )
            else -> stringResource(
                R.string.settings_emergency_armed_contacts_many,
                labels.take(2).joinToString(", "),
                labels.size - 2,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header : titre + chip "Armé" identique à SafetyCallArmedRecap.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_emergency_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.settings_emergency_armed_chip),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.settings_emergency_armed_template, templateLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = locationLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = contactsLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        // 2 actions côte à côte (mêmes types que SafetyCallArmedRecap) :
        //  - "Modifier" → OutlinedButton (secondaire)
        //  - "Ouvrir" → Button filled `primary` (BrandBlue) car c'est
        //    l'action de référence (accéder au bouton URGENCE).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onModify,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_emergency_armed_modify))
            }
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_emergency_armed_open))
            }
        }
    }
}

/**
 * v1.13.0 — Dialog setup PIN/pass coffre : 2 étapes (saisie + confirmation).
 * Bouton "Sauver" actif uniquement si :
 *  - PIN ≥ 4 caractères
 *  - confirmation == PIN
 *
 * **Sécurité** :
 *  - 2 champs `PasswordVisualTransformation` (jamais en clair).
 *  - 2 `String` locaux au composable, jamais persistés. Conversion en
 *    `CharArray` UNIQUEMENT à la validation, immédiatement passé à
 *    [VaultPinManager.setVaultPin] qui le wipe.
 *  - Pas d'autofill, pas de prédictions (`KeyboardType.Password`).
 *  - [appPinHint] = true si l'user a un PIN d'app configuré → warning UX
 *    visible "choisis un PIN différent" (pas une validation crypto — l'user
 *    DOIT pouvoir choisir le même PIN s'il insiste, c'est sa décision).
 *  - v1.13.0 audit NEW-2 : limitation acceptée — les `String pin` et
 *    `String confirm` sont des objets JVM immutable. Chaque frappe
 *    crée une nouvelle String, l'ancienne reste en heap jusqu'au GC.
 *    Acceptable car : (1) durée de vie < temps de saisie (~30 s), (2)
 *    `FLAG_SECURE` actif empêche screen-capture, (3) heap dump nécessite
 *    root (vecteur déjà hors-threat-model). Mitigation max appliquée :
 *    `confirm = ""` AVANT `onConfirm(snapshot)` pour minimiser la fenêtre
 *    (cf. ligne ~2515).
 */
@Composable
private fun VaultPinSetupDialog(
    appPinHint: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val tooShort = pin.isNotEmpty() && pin.length < 4
    val mismatch = confirm.isNotEmpty() && confirm != pin
    val canConfirm = pin.length >= 4 && confirm == pin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_vault_pin_set_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = stringResource(R.string.settings_vault_pin_set_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (appPinHint) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_vault_pin_same_as_app),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.take(64) },
                    label = { Text(stringResource(R.string.pin_entry_label)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    ),
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text(stringResource(R.string.settings_vault_pin_too_short)) }
                    } else null,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.take(64) },
                    label = { Text(stringResource(R.string.settings_vault_pin_confirm_title)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.settings_vault_pin_mismatch)) }
                    } else null,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canConfirm,
                onClick = {
                    val snapshot = pin.toCharArray()
                    pin = ""
                    confirm = ""
                    onConfirm(snapshot)
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ============================================================================
// v1.15.0 — Sections extraites de SettingsScreen pour réduire la taille du
// Composable principal (était >1000 lignes). Comportement strictement identique.
// ============================================================================

/**
 * v1.15.0 — Section Notifications. Extraite de [SettingsScreen] (lignes 333-400 v1.14.9).
 *
 * Pourquoi extraire ? La fonction `SettingsScreen` dépassait 1000 lignes avec 10 sections
 * imbriquées + 14 `mutableStateOf` au niveau parent — lisibilité dégradée, previews Compose
 * impossibles section par section. La factorisation conserve les pickers state au parent
 * (multi-callsite via callbacks) tout en isolant le rendu par section.
 */
@Composable
private fun NotificationsSection(
    notifications: com.filestech.sms.data.local.datastore.NotificationSettings,
    onUpdate: (transform: (com.filestech.sms.data.local.datastore.AppSettings) -> com.filestech.sms.data.local.datastore.AppSettings) -> Unit,
    onOpenPreviewPicker: () -> Unit,
    onOpenStylePicker: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionCard(
        title = stringResource(R.string.settings_section_notifications),
        icon = Icons.Outlined.Notifications,
    ) {
        ToggleRow(
            title = stringResource(R.string.settings_notifications_enabled),
            value = notifications.enabled,
            onChange = { v -> onUpdate { it.copy(notifications = it.notifications.copy(enabled = v)) } },
        )
        ToggleRow(
            title = stringResource(R.string.settings_inline_reply),
            value = notifications.inlineReply,
            onChange = { v -> onUpdate { it.copy(notifications = it.notifications.copy(inlineReply = v)) } },
        )
        ToggleRow(
            title = stringResource(R.string.settings_vibrate),
            value = notifications.vibrate,
            onChange = { v -> onUpdate { it.copy(notifications = it.notifications.copy(vibrate = v)) } },
        )
        // v1.8.0 (bug 3 fix HIGH 3a) — expose PreviewMode dans l'UI.
        // L'option existait en DataStore mais aucun toggle ne l'exposait.
        // Sous-titre = libellé localisé de la valeur actuelle pour que
        // l'utilisateur voit du premier coup d'œil son réglage actif.
        val previewLabel = when (notifications.previewMode) {
            com.filestech.sms.data.local.datastore.PreviewMode.ALWAYS ->
                stringResource(R.string.settings_notif_preview_always)
            com.filestech.sms.data.local.datastore.PreviewMode.WHEN_UNLOCKED ->
                stringResource(R.string.settings_notif_preview_unlocked)
            com.filestech.sms.data.local.datastore.PreviewMode.NEVER ->
                stringResource(R.string.settings_notif_preview_never)
        }
        NavigationRow(
            title = stringResource(R.string.settings_notif_preview),
            description = previewLabel,
            onClick = onOpenPreviewPicker,
        )
        // v1.8.0 (bug 3 fix MEDIUM 3b) — wire NotificationStyle (dead field).
        val styleLabel = when (notifications.style) {
            com.filestech.sms.data.local.datastore.NotificationStyle.HEADS_UP ->
                stringResource(R.string.settings_notif_style_heads_up)
            com.filestech.sms.data.local.datastore.NotificationStyle.BANNER ->
                stringResource(R.string.settings_notif_style_banner)
            com.filestech.sms.data.local.datastore.NotificationStyle.SILENT ->
                stringResource(R.string.settings_notif_style_silent)
        }
        NavigationRow(
            title = stringResource(R.string.settings_notif_style),
            description = styleLabel,
            onClick = onOpenStylePicker,
        )
        // v1.8.0 (bug 3 fix MEDIUM 3c) — deeplink réglages système app.
        // ACTION_APP_NOTIFICATION_SETTINGS (API 26+) ouvre directement la
        // page Paramètres → Apps → SMS Tech → Notifications, où l'user
        // peut vérifier que ses canaux ne sont pas désactivés.
        NavigationRow(
            title = stringResource(R.string.settings_notif_open_system),
            description = stringResource(R.string.settings_notif_open_system_desc),
            onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                ).putExtra(
                    android.provider.Settings.EXTRA_APP_PACKAGE,
                    ctx.packageName,
                )
                runCatching { ctx.startActivity(intent) }
            },
        )
    }
}

/**
 * v1.15.0 — Section Sécurité. Extraite de [SettingsScreen] (lignes 402-491 v1.14.9).
 *
 * Pickers (`lockModePickerOpen`, `vaultPinSetupOpen`, `vaultPinClearConfirmOpen`,
 * `showPurgeBlockedConfirm`, `autoDeletePickerOpen`) restent gérés par le parent —
 * la section ne reçoit que les callbacks d'ouverture. Cohérent avec [NotificationsSection].
 *
 * Comportement vault-PIN masqué en PanicDecoy préservé (audit UX-2 v1.13.0).
 */
@Composable
private fun SecuritySection(
    security: com.filestech.sms.data.local.datastore.SecuritySettings,
    isPanicDecoy: Boolean,
    onUpdate: (transform: (com.filestech.sms.data.local.datastore.AppSettings) -> com.filestech.sms.data.local.datastore.AppSettings) -> Unit,
    onOpenLockModePicker: () -> Unit,
    onOpenVaultPinSetup: () -> Unit,
    onOpenVaultPinClearConfirm: () -> Unit,
    onOpenPurgeBlockedConfirm: () -> Unit,
    onOpenAutoDeletePicker: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_section_security),
        icon = Icons.Outlined.Shield,
    ) {
        // App lock entry — opens the lock-mode picker (None / PIN). Subtitle reflects the
        // current state so the user sees at a glance whether the lock is armed.
        val currentLockLabel = when (security.lockMode) {
            com.filestech.sms.data.local.datastore.LockMode.PIN ->
                stringResource(R.string.lock_mode_pin)
            else -> stringResource(R.string.lock_mode_off)
        }
        NavigationRow(
            title = stringResource(R.string.settings_app_lock),
            description = currentLockLabel,
            onClick = onOpenLockModePicker,
        )
        ToggleRow(
            title = stringResource(R.string.settings_flag_secure),
            description = stringResource(R.string.settings_flag_secure_desc),
            value = security.flagSecure,
            onChange = { v -> onUpdate { it.copy(security = it.security.copy(flagSecure = v)) } },
        )
        ToggleRow(
            title = stringResource(R.string.settings_lock_vault_on_leave),
            description = stringResource(R.string.settings_lock_vault_on_leave_desc),
            value = security.lockVaultOnLeave,
            onChange = { v -> onUpdate { it.copy(security = it.security.copy(lockVaultOnLeave = v)) } },
        )
        // v1.11.0 — Sujet 3 anti-smishing.
        ToggleRow(
            title = stringResource(R.string.settings_smishing_detection),
            description = stringResource(R.string.settings_smishing_detection_desc),
            value = security.smishingDetectionEnabled,
            onChange = { v ->
                onUpdate {
                    it.copy(security = it.security.copy(smishingDetectionEnabled = v))
                }
            },
        )
        // v1.13.0 — PIN/pass distinct pour le coffre (second-factor).
        // ToggleRow non-onChange (le toggle déclenche un setup dialog au lieu d'écrire
        // directement la valeur, sinon on activerait le gate sans avoir posé de hash,
        // ce qui dégraderait l'UX au prochain unlock vault). C'est le `vaultPinSetupOpen`
        // qui contrôle la suite.
        // v1.13.0 audit UX-2 — masqué en PanicDecoy : l'existence du toggle révélerait
        // la présence d'un coffre dans Settings, alors que le cadenas TopAppBar et la
        // nav vers Vault sont déjà masqués en décoy. Cohérence cross-écran.
        if (!isPanicDecoy) {
            ToggleRow(
                title = stringResource(R.string.settings_vault_pin_title),
                description = stringResource(R.string.settings_vault_pin_desc),
                value = security.vaultPinEnabled,
                onChange = { v ->
                    if (v) onOpenVaultPinSetup()
                    else onOpenVaultPinClearConfirm()
                },
            )
            if (security.vaultPinEnabled) {
                NavigationRow(
                    title = stringResource(R.string.settings_vault_pin_change),
                    onClick = onOpenVaultPinSetup,
                )
            }
        }
        NavigationRow(
            title = stringResource(R.string.settings_purge_blocked),
            description = stringResource(R.string.settings_purge_blocked_desc),
            // v1.8.0 — dialog de confirmation (action irréversible).
            onClick = onOpenPurgeBlockedConfirm,
        )
        // v1.3.0 — auto-purge historique selon rétention choisie. Description sur
        // 2 lignes : statut courant + rappel du filet de sécurité 5 j + favoris.
        val currentRetentionLabel = retentionLabel(security.autoDeleteOlderThanDays)
        val explainer = stringResource(R.string.settings_auto_delete_explainer)
        NavigationRow(
            title = stringResource(R.string.settings_auto_delete_title),
            description = "$currentRetentionLabel\n$explainer",
            onClick = onOpenAutoDeletePicker,
        )
    }
}

/**
 * v1.15.0 — Section Mode urgence. Extraite de [SettingsScreen] (lignes 540-680 v1.14.9, la
 * section la plus volumineuse — recap + 4 toggles conditionnels).
 *
 * Le bouton "Inclure position GPS" déclenche [onRequestLocationPermission] lors d'un toggle
 * OFF→ON sans permission accordée. La logique de revert sur refus est gérée côté parent via
 * le `locationPermLauncher` qui appelle [onUpdate] en cas de denied.
 *
 * Toute la section est gated par `if (!isPanicDecoy)` au call-site parent (audit SEC-1 v1.10.0).
 */
@Composable
private fun EmergencySection(
    security: com.filestech.sms.data.local.datastore.SecuritySettings,
    onUpdate: (transform: (com.filestech.sms.data.local.datastore.AppSettings) -> com.filestech.sms.data.local.datastore.AppSettings) -> Unit,
    onOpenEmergencySetup: () -> Unit,
    onOpenEmergency: () -> Unit,
    onRequestLocationPermission: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionCard(
        title = stringResource(R.string.settings_section_emergency),
        icon = Icons.Outlined.WarningAmber,
    ) {
        val emergency = security.emergency
        if (emergency.enabled) {
            // v1.10.0 polish — récap visuel quand armé (parallèle exact de SafetyCallArmedRecap
            // pour cohérence UX).
            EmergencyArmedRecap(
                config = emergency,
                contacts = security.safetyCall.contacts,
                onModify = onOpenEmergencySetup,
                onOpen = onOpenEmergency,
            )
        } else {
            NavigationRow(
                title = stringResource(R.string.settings_emergency_title),
                description = stringResource(R.string.settings_emergency_disabled) +
                    "\n" + stringResource(R.string.settings_emergency_desc),
                onClick = onOpenEmergencySetup,
            )
        }
        // v1.12.0 — Toggle raccourci urgence en notification persistante lock-screen.
        // Disponible uniquement si emergency.enabled (un raccourci qui ouvre dans le vide
        // n'a aucun sens).
        if (emergency.enabled) {
            ToggleRow(
                title = stringResource(R.string.settings_emergency_shortcut_title),
                description = stringResource(R.string.settings_emergency_shortcut_desc),
                value = security.emergencyShortcutEnabled,
                onChange = { v ->
                    onUpdate {
                        it.copy(security = it.security.copy(emergencyShortcutEnabled = v))
                    }
                },
            )
            // v1.12.0 — Toggle bouton Police FR 17 (FR-specific opt-in).
            // Audit fix S2 : disponible uniquement si le raccourci urgence est lui-même ON.
            // Le toggle Police agit sur les actions de la notif persistante + l'écran
            // Emergency : sans raccourci, il reste un orphelin qui dupliquerait juste 112.
            if (security.emergencyShortcutEnabled) {
                ToggleRow(
                    title = stringResource(R.string.settings_emergency_call_police_title),
                    description = stringResource(R.string.settings_emergency_call_police_desc),
                    value = security.emergencyCallPoliceEnabled,
                    onChange = { v ->
                        onUpdate {
                            it.copy(security = it.security.copy(emergencyCallPoliceEnabled = v))
                        }
                    },
                )
            }
            // v1.14.0 — `emergencyCallBehavior` picker retiré v1.14.1 : l'écran Mode urgence
            // v1.14.1 utilise toujours direct call (CALL_PHONE permission) avec fallback
            // automatique au composeur si permission refusée. Le picker UI ici n'aurait
            // plus d'effet → supprimé pour cohérence UX. La clé DataStore reste pour
            // compat ascendante mais devient un dead field.
            //
            // v1.14.0 — opt-in SMS "Je vais bien" sur kill-switch.
            ToggleRow(
                title = stringResource(R.string.settings_send_i_am_ok_sms_title),
                description = stringResource(R.string.settings_send_i_am_ok_sms_desc),
                value = security.sendIAmOkSmsOnReset,
                onChange = { v ->
                    onUpdate {
                        it.copy(security = it.security.copy(sendIAmOkSmsOnReset = v))
                    }
                },
            )
            // v1.14.5 — Toggle "Inclure position GPS dans le SMS" accessible directement
            // depuis Settings (avant : seul EmergencySetupScreen exposait ce toggle, l'user
            // devait y naviguer pour activer la géoloc). Quand l'user passe OFF→ON, on
            // demande la permission ACCESS_FINE_LOCATION runtime immédiatement (avec les
            // mêmes considérations que dans EmergencySetupScreen).
            ToggleRow(
                title = stringResource(R.string.settings_emergency_include_location_title),
                description = stringResource(R.string.settings_emergency_include_location_desc),
                value = emergency.includeLocation,
                onChange = { v ->
                    onUpdate {
                        it.copy(security = it.security.copy(
                            emergency = it.security.emergency.copy(includeLocation = v),
                        ))
                    }
                    // Au passage OFF→ON sans perm déjà accordée → demande runtime.
                    if (v) {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            onRequestLocationPermission()
                        }
                    }
                },
            )
        }
    }
}
