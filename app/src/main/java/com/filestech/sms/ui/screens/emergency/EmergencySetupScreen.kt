package com.filestech.sms.ui.screens.emergency

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.theme.BrandBlue
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

/**
 * v1.10.0 — Écran de configuration du Mode urgence.
 *
 * 3 sections :
 *  1. **Activation** — Switch enable/disable + description
 *  2. **Message** — RadioButtons sur les 3 templates (NEED_HELP / DANGER /
 *     DISCREET) + preview du rendu
 *  3. **Géolocalisation** — Switch inclure les coordonnées (`includeLocation`)
 *     + alerte explicative ("nécessite la permission Localisation")
 *  4. **Contacts** — Lien vers le setup Safety Call (réutilise la même
 *     liste, pas de duplication)
 *
 * Validation au save : aucun champ requis individuellement (tout est
 * optionnel). Si `enabled = true` ET pas de contacts Safety Call, on
 * laisse sauver mais le bouton URGENCE sera grisé jusqu'à la config.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun EmergencySetupScreen(
    onBack: () -> Unit,
    onOpenSafetyCallSetup: () -> Unit,
    viewModel: EmergencyViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val contactsCount by viewModel.safetyCallContactsCount.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    // v1.10.0 audit S1+U2 — prompt permission ACCESS_FINE_LOCATION quand
    // l'user active "Inclure la position". Sans ça, le switch ON sans
    // permission = SMS sans coordonnées en cas d'urgence (faux sentiment
    // de sécurité).
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val locationGranted = locationPermission.status == PermissionStatus.Granted

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // v1.10.0 audit C2 — `when` exhaustif (le compilateur signale
            // tout nouveau case ajouté à Event).
            when (event) {
                is EmergencyViewModel.Event.Saved -> {
                    snackbarHost.showSnackbar(ctx.getString(R.string.action_save))
                    onBack()
                }
                is EmergencyViewModel.Event.Triggered -> Unit // vient d'EmergencyScreen
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emergency_setup_title)) },
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
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Activation
            SetupCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.emergency_setup_enable_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.emergency_setup_enable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                }
            }

            // 2. Template du message
            SetupCard {
                Text(
                    text = stringResource(R.string.emergency_setup_template_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                EmergencyTemplate.entries.forEach { template ->
                    val labelRes = when (template) {
                        EmergencyTemplate.NEED_HELP -> R.string.emergency_setup_template_need_help
                        EmergencyTemplate.DANGER -> R.string.emergency_setup_template_danger
                        EmergencyTemplate.DISCREET -> R.string.emergency_setup_template_discreet
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = draft.template == template,
                                onClick = { viewModel.setTemplate(template) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = draft.template == template,
                            onClick = null,
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = template.renderBody("https://maps.google.com/?q=48.85,2.35"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 3. Géolocalisation
            SetupCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.emergency_setup_location_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.emergency_setup_location_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.includeLocation,
                        onCheckedChange = { newValue ->
                            viewModel.setIncludeLocation(newValue)
                            // v1.10.0 audit S1 — au passage OFF→ON sans
                            // permission, on prompte. Si l'user refuse,
                            // le warning ci-dessous s'affiche et le SMS
                            // partira sans coordonnées en cas d'urgence.
                            if (newValue && !locationGranted) {
                                locationPermission.launchPermissionRequest()
                            }
                        },
                    )
                }
                // v1.10.0 audit U2 — feedback explicite sur l'état réel
                // de la permission. Si l'user a activé le switch mais
                // refusé/non accordé la permission, on l'avertit que le
                // SMS d'urgence partira sans coordonnées.
                if (draft.includeLocation && !locationGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.emergency_setup_location_permission_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 4. Contacts (lien vers Safety call setup)
            SetupCard {
                Text(
                    text = stringResource(R.string.emergency_setup_contacts_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (contactsCount == 0) {
                        stringResource(R.string.emergency_setup_contacts_empty)
                    } else {
                        stringResource(R.string.emergency_setup_contacts_count, contactsCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSafetyCallSetup) {
                    Text(stringResource(R.string.emergency_setup_contacts_cta))
                }
            }

            // Bouton de save — BrandBlue + texte blanc (identité marque),
            // demande user 2026-05-21.
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * v1.10.0 audit C1 — wrapper de section cohérent avec
 * `SafetyCallSetupScreen.SectionCard` : `surfaceContainer` (et non
 * `surface`) pour rester visuellement aligné en mode AMOLED/dark. Le
 * titre est passé en paramètre plutôt qu'inline dans le content pour
 * éviter la duplication boilerplate à chaque appelant.
 */
@Composable
private fun SetupCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
