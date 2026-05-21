package com.filestech.sms.ui.screens.emergency

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.emergency.EmergencyConfig
import com.filestech.sms.domain.usecase.TriggerEmergencyUseCase
import com.filestech.sms.ui.components.EmergencyHoldButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

/**
 * v1.10.0 — Écran principal du Mode urgence.
 *
 * Une seule action visible : le gros bouton circulaire rouge à maintenir
 * 3 secondes pour déclencher l'envoi du SMS d'urgence aux contacts. Au-
 * dessus : preview du message qui sera envoyé. Au-dessous : un lien
 * discret vers le setup pour modifier le template / la géoloc.
 *
 * **Bouton désactivé** (grisé, sans réaction au touch) si :
 *  - `state.enabled == false` (l'user a désactivé Mode urgence dans Setup)
 *  - `safetyCallContactsCount == 0` (pas de contacts d'urgence configurés)
 *  - Anti-spam cooldown actif (60s post-trigger récent)
 *
 * Le snackbar des résultats utilise le pattern marque (bleu = succès,
 * rouge = pas de contacts / pas de location / panic suppressed).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun EmergencyScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    viewModel: EmergencyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contactsCount by viewModel.safetyCallContactsCount.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    // v1.10.0 audit U1 — preview reflète le statut RÉEL de la permission
    // (pas juste la préférence config). Évite un faux sentiment de sécurité.
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val locationGranted = locationPermission.status == PermissionStatus.Granted

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EmergencyViewModel.Event.Triggered -> handleTriggerResult(
                    result = event.result,
                    snackbarHost = snackbarHost,
                    ctxGetter = { ctx },
                )
                is EmergencyViewModel.Event.Saved -> Unit // pas pertinent ici
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emergency_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSetup) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.action_edit),
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
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Preview du SMS qui partira. v1.10.0 audit U1 — la preview
            // n'affiche une URL Maps que si la permission est effectivement
            // accordée (pas juste préférée).
            MessagePreviewCard(
                config = state,
                includeLocationGranted = state.includeLocation && locationGranted,
            )
            Spacer(Modifier.height(32.dp))

            // Le bouton hold 3s. Le `enabled` calcule toutes les conditions
            // d'usage (config, contacts, cooldown).
            val cooldownActive = state.isInAntiSpamWindow()
            val canTrigger = state.enabled && contactsCount > 0 && !cooldownActive
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmergencyHoldButton(
                    onTrigger = viewModel::trigger,
                    enabled = canTrigger,
                )
            }
            Spacer(Modifier.height(24.dp))

            // Statut explicatif sous le bouton.
            val statusRes = when {
                !state.enabled -> R.string.emergency_status_disabled
                contactsCount == 0 -> R.string.emergency_status_no_contacts
                cooldownActive -> R.string.emergency_status_cooldown
                else -> R.string.emergency_status_ready
            }
            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Lien discret vers setup si pas de contacts.
            if (state.enabled && contactsCount == 0) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onOpenSetup) {
                    Text(stringResource(R.string.emergency_setup_contacts_cta))
                }
            }
        }
    }
}

@Composable
private fun MessagePreviewCard(
    config: EmergencyConfig,
    includeLocationGranted: Boolean,
) {
    val previewBody = remember(config.template, includeLocationGranted) {
        val sampleUrl = if (includeLocationGranted) "https://maps.google.com/?q=48.85661,2.35222" else null
        config.template.renderBody(sampleUrl)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.emergency_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = previewBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private suspend fun handleTriggerResult(
    result: TriggerEmergencyUseCase.Result,
    snackbarHost: SnackbarHostState,
    ctxGetter: () -> android.content.Context,
) {
    val ctx = ctxGetter()
    when (result) {
        is TriggerEmergencyUseCase.Result.Triggered -> {
            if (result.sent == 0 && result.failed > 0) {
                snackbarHost.showError(
                    ctx.getString(R.string.emergency_triggered_all_failed, result.failed),
                )
            } else if (!result.hadLocation) {
                // Succès partiel — SMS partis mais sans géoloc.
                snackbarHost.showError(
                    ctx.getString(R.string.emergency_triggered_no_location, result.sent),
                )
            } else {
                snackbarHost.showSnackbar(
                    ctx.getString(R.string.emergency_triggered_ok, result.sent),
                )
            }
        }
        is TriggerEmergencyUseCase.Result.NoContacts ->
            snackbarHost.showError(ctx.getString(R.string.emergency_status_no_contacts))
        is TriggerEmergencyUseCase.Result.Disabled ->
            snackbarHost.showError(ctx.getString(R.string.emergency_status_disabled))
        is TriggerEmergencyUseCase.Result.EmptyBody,
        is TriggerEmergencyUseCase.Result.PanicSuppressed ->
            snackbarHost.showError(ctx.getString(R.string.snack_generic_error))
    }
}
