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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    // v1.12.0 audit U2 — needed pour afficher un snackbar si aucun dialer
    // n'est installé (ACTION_DIAL ActivityNotFoundException). Sinon le tap
    // du bouton 112/17 reste silencieux et l'user croit que l'appel passe.
    val scope = rememberCoroutineScope()
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

    // v1.14.0 audit SEC-1 — re-check CALL_PHONE permission au ON_RESUME.
    // Si l'user a révoqué la perm via Paramètres Android entre temps, le
    // setting `emergencyCallBehavior = HOLD_3S_DIRECT_CALL` devient orphelin
    // (l'OS refusera tout `placeCall` → PERMISSION_DENIED silencieux). On
    // revert le setting à DIALER_ONLY pour aligner état app + OS, et l'user
    // verra immédiatement le picker Settings refléter le bon état.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.lifecycle.compose.LifecycleEventEffect(
        event = androidx.lifecycle.Lifecycle.Event.ON_RESUME,
        lifecycleOwner = lifecycleOwner,
    ) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CALL_PHONE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        viewModel.revertCallBehaviorIfPermissionRevoked(granted)
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

            // v1.12.0 — Mode urgence VOCAL (appel téléphonique).
            // Indépendant du Mode SMS : utile même sans contacts d'urgence
            // configurés. ACTION_DIAL = composeur pré-rempli, l'user
            // confirme manuellement dans le dialer (pas d'appel auto, donc
            // pas besoin de hold-3s pour anti-faux-déclenchement).
            //
            //  - 112 = SOS européen unifié, toujours visible (toute UE)
            //  - 17 = Police nationale FR, opt-in via Settings (FR-specific)
            Spacer(Modifier.height(32.dp))
            val callPolice by viewModel.callPoliceEnabled.collectAsStateWithLifecycle()
            val callBehavior by viewModel.callBehavior.collectAsStateWithLifecycle()
            val holdToCall = callBehavior == com.filestech.sms.data.local.datastore.EmergencyCallBehavior.HOLD_3S_DIRECT_CALL
            val noDialerMsg = stringResource(R.string.emergency_shortcut_no_app_to_dial)
            val permDeniedMsg = stringResource(R.string.emergency_call_permission_denied)
            val osErrorMsg = stringResource(R.string.emergency_call_os_error)

            // v1.14.0 — Bouton 112 : mode selon `emergencyCallBehavior`.
            // DIALER_ONLY → tap → openDialer (default v1.12).
            // HOLD_3S_DIRECT_CALL → hold-3s → placeCall (CALL_PHONE permission).
            // Anti-pocket-dial : seul le hold déclenche CALL_PHONE.
            com.filestech.sms.ui.components.EmergencyCallButton(
                label = stringResource(R.string.emergency_shortcut_action_112),
                holdToCall = holdToCall,
                filled = true,
                onTrigger = {
                    val outcome = if (holdToCall) {
                        com.filestech.sms.system.emergency.EmergencyCallHelper.placeCall(ctx, "112")
                    } else {
                        com.filestech.sms.system.emergency.EmergencyCallHelper.openDialer(ctx, "112")
                    }
                    handleCallOutcome(outcome, scope, snackbarHost, noDialerMsg, permDeniedMsg, osErrorMsg)
                },
            )
            if (callPolice) {
                Spacer(Modifier.height(8.dp))
                com.filestech.sms.ui.components.EmergencyCallButton(
                    label = stringResource(R.string.emergency_shortcut_action_police),
                    holdToCall = holdToCall,
                    filled = false,
                    onTrigger = {
                        val outcome = if (holdToCall) {
                            com.filestech.sms.system.emergency.EmergencyCallHelper.placeCall(ctx, "17")
                        } else {
                            com.filestech.sms.system.emergency.EmergencyCallHelper.openDialer(ctx, "17")
                        }
                        handleCallOutcome(outcome, scope, snackbarHost, noDialerMsg, permDeniedMsg, osErrorMsg)
                    },
                )
            }

            // v1.14.0 — "Tester sans envoyer" : dry-run preview. Toujours
            // visible (même si pas de contacts) car c'est précisément ce qui
            // permet à l'user de configurer en confiance.
            // Audit PERF-1 — bouton désactivé + spinner pendant le GPS
            // resolve (jusqu'à 8s). Guard double-tap géré côté ViewModel.
            Spacer(Modifier.height(24.dp))
            val isLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()
            androidx.compose.material3.TextButton(
                onClick = { viewModel.previewTrigger() },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.emergency_dry_run_loading))
                } else {
                    Text(stringResource(R.string.emergency_dry_run_button))
                }
            }
        }
    }

    // v1.14.0 — Dialog dry-run "Tester sans envoyer".
    val preview by viewModel.previewState.collectAsStateWithLifecycle()
    preview?.let { p ->
        EmergencyDryRunDialog(preview = p, onDismiss = { viewModel.dismissPreview() })
    }
}

/**
 * v1.14.0 — Dialog qui affiche EXACTEMENT ce qui serait envoyé/appelé si
 * l'user déclenchait le trigger MAINTENANT. Pas d'effet de bord — read-only
 * snapshot calculé une fois par `viewModel.previewTrigger()`.
 */
@Composable
private fun EmergencyDryRunDialog(
    preview: EmergencyViewModel.DryRunPreview,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emergency_dry_run_title)) },
        text = {
            Column {
                if (!preview.enabled) {
                    Text(
                        text = stringResource(R.string.emergency_dry_run_disabled),
                        color = com.filestech.sms.ui.theme.BrandDanger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(R.string.emergency_dry_run_body_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = preview.body.ifBlank { stringResource(R.string.emergency_dry_run_body_empty) },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.emergency_dry_run_contacts_label,
                        preview.contactsCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.redactedContacts.forEach { redacted ->
                    Text(
                        text = "• $redacted",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val locStatusRes = when {
                    !preview.includeLocation -> R.string.emergency_dry_run_loc_disabled
                    preview.locationResolved -> R.string.emergency_dry_run_loc_resolved
                    else -> R.string.emergency_dry_run_loc_unavailable
                }
                Text(
                    text = stringResource(locStatusRes),
                    style = MaterialTheme.typography.bodySmall,
                )
                val behaviorRes = when (preview.callBehavior) {
                    com.filestech.sms.data.local.datastore.EmergencyCallBehavior.DIALER_ONLY ->
                        R.string.emergency_call_behavior_dialer_only
                    com.filestech.sms.data.local.datastore.EmergencyCallBehavior.HOLD_3S_DIRECT_CALL ->
                        R.string.emergency_call_behavior_hold_3s
                }
                Text(
                    text = stringResource(R.string.emergency_dry_run_call_behavior_label) +
                        " " + stringResource(behaviorRes),
                    style = MaterialTheme.typography.bodySmall,
                )
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
 * v1.14.0 — gère le résultat d'un `EmergencyCallHelper.openDialer` ou
 * `placeCall` côté UI : un seul snackbar différentié selon l'outcome.
 * SUCCESS = silencieux (l'OS prend le relais et affiche le dialer/call).
 */
private fun handleCallOutcome(
    outcome: com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHost: androidx.compose.material3.SnackbarHostState,
    noDialerMsg: String,
    permDeniedMsg: String,
    osErrorMsg: String,
) {
    when (outcome) {
        com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome.SUCCESS -> Unit
        com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome.NO_DIALER ->
            scope.launch { snackbarHost.showError(noDialerMsg) }
        com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome.PERMISSION_DENIED ->
            scope.launch { snackbarHost.showError(permDeniedMsg) }
        com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome.OS_ERROR,
        com.filestech.sms.system.emergency.EmergencyCallHelper.CallOutcome.INVALID_NUMBER ->
            scope.launch { snackbarHost.showError(osErrorMsg) }
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
