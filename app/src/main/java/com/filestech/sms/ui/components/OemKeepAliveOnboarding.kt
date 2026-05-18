package com.filestech.sms.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.AdvancedSettings
import com.filestech.sms.system.service.OemRomDetector

/**
 * v1.3.10 (C3) — one-shot onboarding suggestion for users on aggressive ROMs (Xiaomi /
 * MIUI / HyperOS, Huawei, OnePlus, Oppo, Realme, Vivo, Meizu, Asus). When all of
 *  - [OemRomDetector.isAggressiveOem] reports true (detected at process start, cheap),
 *  - the user has not already enabled `KeepAliveService` themselves,
 *  - the onboarding flag has never been flipped,
 * we surface an [AlertDialog] that quotes the detected ROM label and offers to enable
 * "Mode résistant" inline. Either action (enable or skip) flips
 * [AdvancedSettings.keepAliveOnboardingShown] to `true` so the dialog never re-appears.
 *
 * The dialog dismisses automatically as soon as `keepAliveOnboardingShown` flips — no
 * local imperative `setVisible` state needed beyond a single short-lived `remember` to
 * smooth out the recomposition while the DataStore write propagates.
 */
@Composable
fun OemKeepAliveOnboarding(
    advanced: AdvancedSettings,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    val romLabel = OemRomDetector.detectedRomLabel
    val shouldShow = romLabel != null &&
        !advanced.keepAliveService &&
        !advanced.keepAliveOnboardingShown
    if (!shouldShow) return

    // Local "dismissed" flag so the dialog hides immediately on tap, before DataStore
    // re-emits with the persisted `keepAliveOnboardingShown = true`. Without it the
    // user sees the dialog stay up for the ~50 ms write round-trip.
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = {
            // Tap outside / back press is treated as "skip" — same semantics as the
            // explicit skip button. The dialog must not be re-promptable without a
            // dedicated user action (Settings → Avancé → Mode résistant).
            dismissed = true
            onSkip()
        },
        title = { Text(stringResource(R.string.oem_detected_dialog_title)) },
        text = { Text(stringResource(R.string.oem_detected_dialog_body, romLabel)) },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                onEnable()
            }) {
                Text(stringResource(R.string.oem_detected_dialog_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissed = true
                onSkip()
            }) {
                Text(stringResource(R.string.oem_detected_dialog_skip))
            }
        },
    )
}
