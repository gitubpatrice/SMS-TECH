package com.filestech.sms.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.BrandDanger
import kotlinx.coroutines.delay

/**
 * v1.14.0 — Bouton d'appel d'urgence (112 ou 17) avec 2 comportements selon
 * `EmergencyCallBehavior` :
 *
 *  - **DIALER_ONLY** (default) — tap normal → callback simple (caller appelle
 *    [com.filestech.sms.system.emergency.EmergencyCallHelper.openDialer]).
 *    Bouton rendu comme un Material Button standard, cliquable normal.
 *
 *  - **HOLD_3S_DIRECT_CALL** — maintenir 3 secondes → callback (caller appelle
 *    [com.filestech.sms.system.emergency.EmergencyCallHelper.placeCall]).
 *    Bouton non-cliquable au tap. Le pointerInput détecte le hold et anime
 *    une fine barre de progression sous le bouton. Si l'user lâche avant
 *    3s → animation reset 200 ms.
 *
 * **Anti-pocket-dial** : seul HOLD_3S déclenche `CALL_PHONE`. Le hold-3s est
 * la garde anti-poche. Tap accidentel = aucun effet en mode HOLD.
 *
 * **Robustesse** : même pattern que [EmergencyHoldButton] (le gros bouton
 * URGENCE central) — `LaunchedEffect(isHolding) { delay(3000) }` cancellable
 * proprement à chaque recomposition. Pas de Thread.sleep, pas de coroutine
 * fuyante.
 *
 * **Style** : caller choisit `filled = true` (Button BrandDanger blanc texte,
 * pour 112) ou `filled = false` (OutlinedButton, pour 17 opt-in).
 */
@Composable
fun EmergencyCallButton(
    label: String,
    holdToCall: Boolean,
    filled: Boolean,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = DEFAULT_HOLD_MS,
) {
    if (!holdToCall) {
        // DIALER_ONLY : Material Button standard, tap → trigger.
        if (filled) {
            Button(
                onClick = onTrigger,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandDanger,
                    contentColor = Color.White,
                ),
                modifier = modifier.fillMaxWidth(),
            ) { Text(label) }
        } else {
            OutlinedButton(
                onClick = onTrigger,
                modifier = modifier.fillMaxWidth(),
            ) { Text(label) }
        }
        return
    }

    // HOLD_3S_DIRECT_CALL : Material Button + pointerInput, tap = no-op.
    val haptics = LocalHapticFeedback.current
    var isHolding by remember { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (isHolding) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isHolding) holdDurationMs.toInt() else 200,
            easing = LinearEasing,
        ),
        label = "emergency-call-progress",
    )

    LaunchedEffect(isHolding) {
        if (isHolding) {
            delay(holdDurationMs)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            isHolding = false
            onTrigger()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val pointerModifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .pointerInput(holdDurationMs) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Main)
                        if (down.changes.any { it.pressed }) {
                            isHolding = true
                            while (true) {
                                val next = awaitPointerEvent(PointerEventPass.Main)
                                if (next.changes.none { it.pressed }) {
                                    isHolding = false
                                    break
                                }
                            }
                        }
                    }
                }
            }
        if (filled) {
            Button(
                onClick = { /* hold-3s only, tap no-op */ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandDanger,
                    contentColor = Color.White,
                ),
                modifier = pointerModifier,
            ) { Text(label) }
        } else {
            OutlinedButton(
                onClick = { /* hold-3s only */ },
                modifier = pointerModifier,
            ) { Text(label) }
        }
        // Fine barre de progression visuel en-dessous du bouton, hauteur 0 en idle.
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = BrandDanger,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private const val DEFAULT_HOLD_MS: Long = 3_000L
