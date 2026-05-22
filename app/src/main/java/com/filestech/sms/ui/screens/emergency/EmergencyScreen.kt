package com.filestech.sms.ui.screens.emergency

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    // v1.14.1 — liste complète SafetyCall pour le bouton "Appeler un proche".
    // Audit SEC-1 fix : hoist au top-level pour respecter règle hooks Compose.
    val safetyContacts by viewModel.safetyCallContacts.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    // v1.14.1 audit PERF-2 — launcher hissé au top du Composable (vs body
    // Scaffold conditionnel) pour respecter règle position hooks. Pas de
    // outcome handler ici — la recomposition de `callPhoneGranted` ci-dessous
    // capture le nouveau status après autorisation/refus.
    val callPhonePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* outcome handled by next recomposition via checkSelfPermission */ }
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
    // Retiré v1.14.1 : le `emergencyCallBehavior` setting est dead (cf. refonte
    // EmergencyScreen full-page). Le `callPhoneGranted` ci-dessous est lu
    // direct à chaque recomposition, ce qui couvre déjà le retour de
    // Paramètres Android (recomposition au ON_RESUME).

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
        // v1.14.1 — refonte page complète. Toutes les actions urgence visibles
        // en une seule page, gros boutons couleurs, scroll vertical si besoin.
        // Ordre : (a) section Appel direct (4 tuiles + bouton proche),
        // (b) section SMS aux proches (hold-3s + preview), (c) actions
        // secondaires (Tester sans envoyer, Désactiver mode).
        val noDialerMsg = stringResource(R.string.emergency_shortcut_no_app_to_dial)
        val permDeniedMsg = stringResource(R.string.emergency_call_permission_denied)
        val osErrorMsg = stringResource(R.string.emergency_call_os_error)
        val cooldownActive = state.isInAntiSpamWindow()
        val canTrigger = state.enabled && contactsCount > 0 && !cooldownActive

        // v1.14.1 — détecte CALL_PHONE permission pour fallback dialer si
        // refusée. Pas de pré-demande agressive : on demande au 1er tap.
        // Re-évalué à chaque recomposition (post-launcher result + post-RESUME),
        // pas mémoïsé : `checkSelfPermission` est cheap (~µs).
        val callPhoneGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CALL_PHONE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // v1.14.1 — helper pour passer un appel direct (CALL_PHONE) avec
        // fallback automatique au composeur si permission refusée. Pour les
        // numéros d'urgence (112/15/17/18), passe par placeCall (whitelist).
        // Pour un proche SafetyCall, passe par placeTrustedContactCall.
        val callEmergency: (String) -> Unit = { number ->
            if (!callPhoneGranted) {
                callPhonePermLauncher.launch(android.Manifest.permission.CALL_PHONE)
            } else {
                val outcome = com.filestech.sms.system.emergency.EmergencyCallHelper.placeCall(ctx, number)
                handleCallOutcome(outcome, scope, snackbarHost, noDialerMsg, permDeniedMsg, osErrorMsg)
            }
        }
        val callRelative: (String) -> Unit = { phone ->
            if (!callPhoneGranted) {
                callPhonePermLauncher.launch(android.Manifest.permission.CALL_PHONE)
            } else {
                val outcome = com.filestech.sms.system.emergency.EmergencyCallHelper
                    .placeTrustedContactCall(ctx, phone)
                handleCallOutcome(outcome, scope, snackbarHost, noDialerMsg, permDeniedMsg, osErrorMsg)
            }
        }

        // Scroll vertical au cas où l'écran est petit (< 360dp height utile).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // ── SECTION 1 — Appel direct d'urgence ──
            Text(
                text = stringResource(R.string.emergency_section_call_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            // 4 tuiles couleurs FR/EU + 1 tuile proches.
            EmergencyCallTile(
                number = "112",
                label = stringResource(R.string.emergency_call_112_label),
                containerColor = com.filestech.sms.ui.theme.BrandDanger,
                onClick = { callEmergency("112") },
            )
            Spacer(Modifier.height(8.dp))
            EmergencyCallTile(
                number = "15",
                label = stringResource(R.string.emergency_call_15_label),
                containerColor = Color(0xFF00796B), // teal médical
                onClick = { callEmergency("15") },
            )
            Spacer(Modifier.height(8.dp))
            EmergencyCallTile(
                number = "17",
                label = stringResource(R.string.emergency_call_17_label),
                containerColor = Color(0xFF1565C0), // navy police
                onClick = { callEmergency("17") },
            )
            Spacer(Modifier.height(8.dp))
            EmergencyCallTile(
                number = "18",
                label = stringResource(R.string.emergency_call_18_label),
                containerColor = Color(0xFFE65100), // orange pompiers
                onClick = { callEmergency("18") },
            )

            // v1.14.1 — Bouton "Appeler un proche" : visible si au moins 1
            // contact SafetyCall configuré. Si 1 contact → call direct, si
            // ≥2 → picker dialog. Pas de whitelist sur le numéro (vient de
            // SafetyCallContact configuré par l'user).
            if (safetyContacts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                var pickerOpen by remember { mutableStateOf(false) }
                EmergencyCallTile(
                    number = "★",
                    label = stringResource(R.string.emergency_call_close_label),
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (safetyContacts.size == 1) {
                            callRelative(safetyContacts.first().phoneNumber)
                        } else {
                            pickerOpen = true
                        }
                    },
                )
                if (pickerOpen) {
                    RelativePickerDialog(
                        contacts = safetyContacts,
                        onPick = { phone ->
                            pickerOpen = false
                            callRelative(phone)
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── SECTION 2 — SMS d'urgence aux proches (hold-3s) ──
            Text(
                text = stringResource(R.string.emergency_section_sms_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            // Preview du SMS qui partira.
            MessagePreviewCard(
                config = state,
                includeLocationGranted = state.includeLocation && locationGranted,
            )
            Spacer(Modifier.height(16.dp))

            // Le bouton hold 3s (centré).
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmergencyHoldButton(
                    onTrigger = viewModel::trigger,
                    enabled = canTrigger,
                )
            }
            Spacer(Modifier.height(12.dp))
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
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (state.enabled && contactsCount == 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenSetup,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.emergency_setup_contacts_cta))
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── SECTION 3 — Actions secondaires ──
            Text(
                text = stringResource(R.string.emergency_section_other_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            // v1.14.5 — bouton "Tester sans envoyer" retiré sur demande user
            // (encombrait la page urgence — le mode actif est déjà visible via
            // la section recap au-dessus). Le dry-run reste accessible via
            // SettingsScreen → Mode urgence pour debug avancé.

            // "Désactiver le mode urgence" — confirm dialog.
            var disableConfirmOpen by remember { mutableStateOf(false) }
            androidx.compose.material3.OutlinedButton(
                onClick = { disableConfirmOpen = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = com.filestech.sms.ui.theme.BrandDanger,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.emergency_disable_button)) }
            if (disableConfirmOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { disableConfirmOpen = false },
                    title = { Text(stringResource(R.string.emergency_disable_confirm_title)) },
                    text = { Text(stringResource(R.string.emergency_disable_confirm_body)) },
                    confirmButton = {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                disableConfirmOpen = false
                                viewModel.disableEmergencyMode()
                                scope.launch {
                                    snackbarHost.showSnackbar(ctx.getString(R.string.emergency_disable_done))
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                containerColor = com.filestech.sms.ui.theme.BrandDanger,
                                contentColor = Color.White,
                            ),
                        ) { Text(stringResource(R.string.emergency_disable_button)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { disableConfirmOpen = false },
                        ) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // v1.14.5 — dry-run dialog + composable EmergencyDryRunDialog supprimés
    // (le bouton "Tester sans envoyer" était la seule entrée vers eux).
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

/**
 * v1.14.1 — Tuile d'appel d'urgence : un gros bouton plein couleur, numéro
 * en gras à gauche + label à droite. Tap → onClick (caller décide direct
 * call vs dialer fallback selon permission). Hauteur fixe 72dp pour tap
 * target accessible + uniformité visuelle.
 *
 * Couleur de fond fournie par caller, texte blanc systématique. Toutes les
 * couleurs utilisées (BrandDanger / 00796B / 1565C0 / E65100 / primary)
 * vérifiées WCAG AA ≥ 4.5:1 contre blanc.
 */
@Composable
private fun EmergencyCallTile(
    number: String,
    label: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 18.dp,
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * v1.14.1 — Dialog picker quand l'user a ≥2 contacts SafetyCall et tape
 * "Appeler un proche". Liste cliquable des contacts, tap → callRelative.
 * Si 1 seul contact, ce dialog n'est pas montré (call direct).
 */
@Composable
private fun RelativePickerDialog(
    contacts: List<com.filestech.sms.domain.safetycall.SafetyCallContact>,
    onPick: (phone: String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emergency_call_close_picker_title)) },
        text = {
            Column {
                contacts.forEach { c ->
                    val name = c.displayName?.takeIf { it.isNotBlank() } ?: c.phoneNumber
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = if (c.displayName?.isNotBlank() == true) {
                            { Text(c.phoneNumber) }
                        } else null,
                        modifier = Modifier.clickable { onPick(c.phoneNumber) },
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
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
