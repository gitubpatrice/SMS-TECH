package com.filestech.sms.ui.screens.safetycall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.filestech.sms.R
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.filestech.sms.domain.safetycall.SafetyCallTriggerRecord
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.theme.BrandBlue
import java.text.DateFormat
import java.util.Date

/**
 * v1.9.0 — Écran de configuration du Safety call.
 *
 * Structure verticale en 4 sections, chacune dans une [Card] M3 distincte
 * pour une coordination visuelle claire :
 *  1. **État** — Switch enable/disable + description du fonctionnement
 *  2. **Durée** — 4 RadioButtons (24h, 48h, 72h, Custom) — Custom ouvre
 *     un dialog d'input numérique en heures
 *  3. **Contacts** — Liste actuelle (avec bouton remove) + bouton "Ajouter"
 *     qui ouvre un dialog avec champs Nom + Téléphone
 *  4. **Message** — 4 RadioButtons templates (Vérification, Urgent, Suivi,
 *     Personnalisé) + champ de saisie si CUSTOM + aperçu rendu du SMS final
 *
 * Bouton **Save** en bas, validé via [SafetyCallSetupViewModel.save]. Les
 * erreurs de validation s'affichent via SnackBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyCallSetupScreen(
    onBack: () -> Unit,
    viewModel: SafetyCallSetupViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    // v1.27.2 — l'etat REELLEMENT enregistre, pour que l'indicateur ne suive pas le brouillon.
    val savedEnabled by viewModel.savedEnabled.collectAsStateWithLifecycle()
    // v1.27.3 — historique des declenchements passes, lu depuis DataStore et non depuis le
    // brouillon : ce sont des faits, pas une saisie.
    val history by viewModel.history.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var addContactDialogOpen by remember { mutableStateOf(false) }
    var customDurationDialogOpen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    /*
     * v1.25.5 — quitter sans enregistrer ne doit plus jeter la saisie en silence.
     *
     * Le parcours qui a fait remonter le défaut : Mode urgence → « gérer les contacts » → Safety
     * call → « Ajouter un contact » → le dialogue, dont le bouton s'appelait « Enregistrer ».
     * Le contact apparaissait dans la liste, donc tout indiquait que c'était acquis. Or seul le
     * bouton du bas écrit sur le disque : le Retour jetait le brouillon, sans un mot.
     *
     * Le libellé du dialogue est corrigé (« Ajouter »), mais renommer ne suffit pas — on prévient
     * aussi avant de perdre quoi que ce soit.
     *
     * `BackHandler` armé seulement une fois la destination `RESUMED` : composé pendant la
     * transition d'arrivée, il capterait la fin du geste parti de l'écran précédent et ouvrirait
     * ce dialogue par-dessus lui. Voir le correctif du Coffre en v1.25.4.
     */
    val navEntryOwner = LocalLifecycleOwner.current
    val navEntryState by navEntryOwner.lifecycle.currentStateAsState()
    val navEntryResumed = navEntryState.isAtLeast(Lifecycle.State.RESUMED)
    var confirmExitOpen by remember { mutableStateOf(false) }
    val requestExit: () -> Unit = {
        if (viewModel.hasUnsavedChanges) confirmExitOpen = true else onBack()
    }
    BackHandler(enabled = navEntryResumed) { requestExit() }

    // v1.27.2 — un message PAR action, résolu à la composition (un `ctx.getString` dans le
    // `collect` ajouterait une instance de `LocalContextGetResourceValueCall` hors baseline).
    val safetyEnabledMsg = stringResource(R.string.safety_call_saved_enabled)
    val safetyDisabledMsg = stringResource(R.string.safety_call_saved_disabled)
    val contactAddedMsg = stringResource(R.string.safety_call_contact_added)
    val contactRemovedMsg = stringResource(R.string.safety_call_contact_removed)
    val disabledNowMsg = stringResource(R.string.safety_call_setup_disabled_now)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SafetyCallSetupViewModel.Event.Saved -> {
                    // Nomme l'état enregistré : « Contacts et réglages enregistrés » ne disait
                    // pas si la surveillance courait ou non.
                    snackbarHost.showSnackbar(
                        if (event.enabled) safetyEnabledMsg else safetyDisabledMsg,
                    )
                    onBack()
                }
                // v1.27.2 — l'ajout se confirme. Le dialogue se refermait en silence.
                SafetyCallSetupViewModel.Event.ContactAdded ->
                    snackbarHost.showSnackbar(contactAddedMsg)
                SafetyCallSetupViewModel.Event.ContactRemoved ->
                    snackbarHost.showSnackbar(contactRemovedMsg)
                // v1.27.2 — l'interrupteur a éteint sur-le-champ : on le confirme, sans quitter
                // l'écran (l'utilisateur peut vouloir continuer à modifier).
                SafetyCallSetupViewModel.Event.DisabledImmediately ->
                    snackbarHost.showSnackbar(disabledNowMsg)
                is SafetyCallSetupViewModel.Event.ValidationError -> {
                    val msgRes = when (event.reason) {
                        SafetyCallSetupViewModel.ValidationReason.NoContacts ->
                            R.string.safety_call_setup_save_validation_no_contacts
                        SafetyCallSetupViewModel.ValidationReason.InvalidPhone ->
                            R.string.safety_call_setup_contact_invalid
                        SafetyCallSetupViewModel.ValidationReason.MaxContactsReached ->
                            R.string.safety_call_setup_contact_max_reached
                        SafetyCallSetupViewModel.ValidationReason.EmptyCustomMessage ->
                            R.string.safety_call_setup_save_validation_custom_empty
                        // v1.26.1 (audit M5) — carnet partagé avec le Mode urgence.
                        SafetyCallSetupViewModel.ValidationReason.SharedWithEmergency ->
                            R.string.safety_call_setup_shared_with_emergency
                    }
                    // v1.9.0 — validation = erreur rouge, pas confirmation bleue.
                    snackbarHost.showError(ctx.getString(msgRes))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.safety_call_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusSection(
                draft = draft,
                savedEnabled = savedEnabled,
                onToggle = viewModel::setEnabled,
            )
            DurationSection(
                draft = draft,
                onSelect = viewModel::setTimeoutMs,
                onOpenCustom = { customDurationDialogOpen = true },
            )
            ContactsSection(
                draft = draft,
                onAdd = { addContactDialogOpen = true },
                onRemove = viewModel::removeContact,
            )
            TemplateSection(
                draft = draft,
                onSelectTemplate = viewModel::setTemplate,
                onCustomMessageChange = viewModel::setCustomMessage,
            )
            Spacer(Modifier.size(8.dp))
            // v1.10.0 — BrandBlue + texte blanc (identité marque), demande
            // user 2026-05-21. Cohérent avec Mode urgence Setup.
            Button(
                onClick = viewModel::save,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.safety_call_setup_save))
            }
            // v1.27.3 — l'historique vient APRÈS le bouton d'enregistrement : ce n'est pas un champ
            // du formulaire mais une lecture du passé, et il ne doit pas s'interposer entre la
            // saisie et l'action qui la valide.
            HistorySection(history = history)
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmExitOpen) {
        AlertDialog(
            onDismissRequest = { confirmExitOpen = false },
            title = { Text(stringResource(R.string.safety_call_setup_unsaved_title)) },
            text = { Text(stringResource(R.string.safety_call_setup_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExitOpen = false
                    // `save` émet `Saved`, dont l'observateur appelle déjà `onBack()`. En cas
                    // d'échec de validation, on reste sur l'écran avec le motif affiché — c'est
                    // voulu : sortir alors reviendrait à perdre la saisie malgré l'avertissement.
                    viewModel.save()
                }) { Text(stringResource(R.string.safety_call_setup_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmExitOpen = false
                    onBack()
                }) { Text(stringResource(R.string.action_discard)) }
            },
        )
    }

    if (addContactDialogOpen) {
        AddContactDialog(
            onDismiss = { addContactDialogOpen = false },
            onConfirm = { name, phone ->
                // v1.25.5 — on ne referme QUE si l'ajout a abouti. Le dialogue se fermait
                // auparavant dans tous les cas : un numéro refusé disparaissait avec lui, et le
                // motif du refus s'affichait dans un bandeau, derrière un dialogue déjà parti.
                if (viewModel.addContact(name, phone)) addContactDialogOpen = false
            },
        )
    }
    if (customDurationDialogOpen) {
        CustomDurationDialog(
            initialHours = (draft.timeoutMs / 3_600_000L).toInt().coerceIn(1, MAX_CUSTOM_HOURS),
            onDismiss = { customDurationDialogOpen = false },
            onConfirm = { hours ->
                viewModel.setTimeoutMs(hours.toLong() * 3_600_000L)
                customDurationDialogOpen = false
            },
        )
    }
}

@Composable
private fun StatusSection(
    draft: SafetyCallConfig,
    savedEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.safety_call_setup_section_status)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.safety_call_setup_toggle_enable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.safety_call_setup_toggle_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.enabled, onCheckedChange = onToggle)
        }

        // v1.27.2 — l'état RÉELLEMENT enregistré, et non la position de l'interrupteur.
        //
        // Un indicateur qui suivrait le brouillon mentirait exactement comme lui : c'est ce qui a
        // fait croire à une désactivation qui n'avait jamais été écrite. Ici, ce qui s'affiche
        // vient de DataStore.
        Spacer(Modifier.height(12.dp))
        val activeColor = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            com.filestech.sms.ui.theme.BrandSuccessDark
        } else {
            com.filestech.sms.ui.theme.BrandSuccessLight
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.safety_call_setup_state_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (savedEnabled) {
                    stringResource(R.string.safety_call_setup_state_on)
                } else {
                    stringResource(R.string.safety_call_setup_state_off)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (savedEnabled) activeColor else MaterialTheme.colorScheme.error,
            )
        }

        // Et quand l'intention diverge de l'enregistré, on le NOMME — plutôt que de laisser
        // deviner que l'interrupteur n'a pas encore d'effet.
        if (draft.enabled != savedEnabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.safety_call_setup_state_unsaved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DurationSection(
    draft: SafetyCallConfig,
    onSelect: (Long) -> Unit,
    onOpenCustom: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.safety_call_setup_section_duration)) {
        val current = draft.timeoutMs
        val isStandard = current == SafetyCallConfig.TIMEOUT_24H_MS ||
            current == SafetyCallConfig.TIMEOUT_48H_MS ||
            current == SafetyCallConfig.TIMEOUT_72H_MS
        DurationOption(
            label = stringResource(R.string.safety_call_setup_duration_24h),
            selected = current == SafetyCallConfig.TIMEOUT_24H_MS,
            onClick = { onSelect(SafetyCallConfig.TIMEOUT_24H_MS) },
        )
        DurationOption(
            label = stringResource(R.string.safety_call_setup_duration_48h),
            selected = current == SafetyCallConfig.TIMEOUT_48H_MS,
            onClick = { onSelect(SafetyCallConfig.TIMEOUT_48H_MS) },
        )
        DurationOption(
            label = stringResource(R.string.safety_call_setup_duration_72h),
            selected = current == SafetyCallConfig.TIMEOUT_72H_MS,
            onClick = { onSelect(SafetyCallConfig.TIMEOUT_72H_MS) },
        )
        val customLabel = if (!isStandard) {
            stringResource(R.string.safety_call_setup_duration_custom) + " · " +
                stringResource(
                    R.string.safety_call_setup_duration_custom_format,
                    (current / 3_600_000L).toInt(),
                )
        } else {
            stringResource(R.string.safety_call_setup_duration_custom)
        }
        DurationOption(
            label = customLabel,
            selected = !isStandard,
            onClick = onOpenCustom,
        )
        Text(
            stringResource(R.string.safety_call_setup_duration_custom_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp),
        )
    }
}

@Composable
private fun DurationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            RadioButton(selected = selected, onClick = null)
        }
        Spacer(Modifier.size(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ContactsSection(
    draft: SafetyCallConfig,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    SectionCard(title = stringResource(R.string.safety_call_setup_section_contacts)) {
        draft.contacts.forEachIndexed { index, contact ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    contact.sanitizedDisplayName()?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        contact.phoneNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.safety_call_setup_remove_contact),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (draft.contacts.size < SafetyCallConfig.MAX_CONTACTS) {
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.safety_call_setup_add_contact))
            }
        } else {
            Text(
                stringResource(R.string.safety_call_setup_contact_max_reached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TemplateSection(
    draft: SafetyCallConfig,
    onSelectTemplate: (SafetyCallTemplate) -> Unit,
    onCustomMessageChange: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.safety_call_setup_section_template)) {
        TemplateOption(
            label = stringResource(R.string.safety_call_setup_template_check_in),
            selected = draft.template == SafetyCallTemplate.CHECK_IN,
            onClick = { onSelectTemplate(SafetyCallTemplate.CHECK_IN) },
        )
        TemplateOption(
            label = stringResource(R.string.safety_call_setup_template_urgent),
            selected = draft.template == SafetyCallTemplate.URGENT,
            onClick = { onSelectTemplate(SafetyCallTemplate.URGENT) },
        )
        TemplateOption(
            label = stringResource(R.string.safety_call_setup_template_follow_up),
            selected = draft.template == SafetyCallTemplate.FOLLOW_UP,
            onClick = { onSelectTemplate(SafetyCallTemplate.FOLLOW_UP) },
        )
        TemplateOption(
            label = stringResource(R.string.safety_call_setup_template_custom),
            selected = draft.template == SafetyCallTemplate.CUSTOM,
            onClick = { onSelectTemplate(SafetyCallTemplate.CUSTOM) },
        )
        if (draft.template == SafetyCallTemplate.CUSTOM) {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = draft.customMessage,
                onValueChange = onCustomMessageChange,
                label = { Text(stringResource(R.string.safety_call_setup_template_custom_label)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
        }
        // Aperçu du SMS final rendu (placeholder [DURÉE] remplacé).
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.safety_call_setup_template_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Text(
                text = draft.template.render(draft.timeoutMs, draft.customMessage)
                    .ifBlank { "—" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TemplateOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            RadioButton(selected = selected, onClick = null)
        }
        Spacer(Modifier.size(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

/**
 * v1.27.3 — **historique des déclenchements**, du plus récent au plus ancien.
 *
 * # La question à laquelle cette section répond
 *
 * « Est-ce que ça s'est déjà déclenché, quand, et vers qui ? » — sans réponse jusqu'ici. La
 * notification de fin de séquence ne pouvait pas la porter : elle se balaie, elle ne survit pas au
 * redémarrage, et elle disparaît dès qu'on la tape, y compris sans l'avoir lue.
 *
 * # La garde du mode leurre est en amont, et c'est délibéré
 *
 * Rien ici ne teste `isPanicDecoy`, parce que cet écran n'est **pas atteignable** sous contrainte :
 * la route est dépilée à l'entrée en mode leurre (`AppRoot`) et la section qui y mène est masquée
 * dans les Réglages. Une troisième garde sur l'affichage laisserait croire que l'accès, lui, est
 * ouvert — c'est exactement l'inversion qui a produit le constat P-05.
 *
 * # Ce qui n'est pas affiché
 *
 * Jamais le corps des messages. Un historique qui les conserverait deviendrait, pour qui s'emparerait
 * du téléphone, la description écrite d'un réseau de soutien et de la façon de l'alerter.
 */
@Composable
private fun HistorySection(history: List<SafetyCallTriggerRecord>) {
    // Un seul formateur pour toute la liste, mémorisé : `DateFormat` n'est pas thread-safe, et la
    // composition est mono-thread par définition. Même motif que [ChatFormatters].
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    SectionCard(title = stringResource(R.string.safety_call_history_title)) {
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.safety_call_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        history.forEachIndexed { index, record ->
            if (index > 0) Spacer(Modifier.size(12.dp))
            Text(
                dateFormat.format(Date(record.triggeredAt)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                pluralStringResource(
                    R.plurals.safety_call_history_count,
                    record.messagesDelivered,
                    record.messagesDelivered,
                    record.totalMessages,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.recipients.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.safety_call_history_recipients,
                        record.recipients.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!record.isComplete) {
                // Une séquence écourtée et une séquence menée à terme sont deux issues très
                // différentes ; le seul couple de nombres les rendrait mal.
                Text(
                    stringResource(R.string.safety_call_history_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String?, phone: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safety_call_setup_add_contact)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.safety_call_setup_contact_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.take(20) },
                    label = { Text(stringResource(R.string.safety_call_setup_contact_phone_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // v1.10.0 — confirm BrandBlue + blanc (demande user 2026-05-21).
            Button(
                onClick = { onConfirm(name.ifBlank { null }, phone) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * v1.9.0 — cap absolu de la durée custom saisie en heures.
 * Aligne sur [com.filestech.sms.domain.safetycall.SafetyCallConfig.TIMEOUT_MAX_MS]
 * (30 jours = 720 heures). Au-delà, Safety Call perd son sens pratique.
 */
private const val MAX_CUSTOM_HOURS: Int = 720

@Composable
private fun CustomDurationDialog(
    initialHours: Int,
    onDismiss: () -> Unit,
    onConfirm: (hours: Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialHours.toString()) }
    val parsed = value.toIntOrNull()?.coerceIn(1, MAX_CUSTOM_HOURS)
    // v1.9.0 — aperçu auto sous le champ : `"96 h ≈ 4 jours"` dès que ≥ 24h.
    // Aide l'user à se représenter visuellement la durée sans qu'il ait à
    // diviser mentalement par 24.
    val supportingLabel = when {
        parsed == null -> stringResource(
            R.string.safety_call_setup_duration_custom_format,
            0,
        )
        parsed >= 24 -> {
            val days = parsed / 24
            val rem = parsed % 24
            if (rem == 0) {
                if (days == 1) "$parsed h ≈ 1 jour" else "$parsed h ≈ $days jours"
            } else {
                "$parsed h ≈ $days j ${rem} h"
            }
        }
        else -> stringResource(
            R.string.safety_call_setup_duration_custom_format,
            parsed,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safety_call_setup_duration_custom)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { input -> value = input.filter { it.isDigit() }.take(3) },
                singleLine = true,
                label = { Text(stringResource(R.string.safety_call_setup_duration_custom)) },
                supportingText = { Text(supportingLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // v1.10.0 — confirm BrandBlue + blanc (demande user 2026-05-21).
            Button(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
