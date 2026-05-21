package com.filestech.sms.ui.screens.safetycall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.filestech.sms.ui.components.showError
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.ui.theme.BrandBlue
import com.filestech.sms.domain.safetycall.SafetyCallTemplate

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
    val snackbarHost = remember { SnackbarHostState() }
    var addContactDialogOpen by remember { mutableStateOf(false) }
    var customDurationDialogOpen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SafetyCallSetupViewModel.Event.Saved -> {
                    snackbarHost.showSnackbar(ctx.getString(R.string.action_save))
                    onBack()
                }
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusSection(draft = draft, onToggle = viewModel::setEnabled)
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
            Spacer(Modifier.size(24.dp))
        }
    }

    if (addContactDialogOpen) {
        AddContactDialog(
            onDismiss = { addContactDialogOpen = false },
            onConfirm = { name, phone ->
                viewModel.addContact(name, phone)
                addContactDialogOpen = false
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
                Text(stringResource(R.string.action_save))
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
