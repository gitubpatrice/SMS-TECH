package com.filestech.sms.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.BrandDanger
import kotlinx.coroutines.delay

/**
 * v1.10.0 — Gros bouton circulaire rouge "URGENCE" qui se déclenche sur
 * appui long de [holdDurationMs] millisecondes (3 secondes par défaut).
 *
 * **Anti-faux-déclenchement** :
 *  - Le simple tap ne fait RIEN. Il faut maintenir la pression.
 *  - Si le doigt se relève avant [holdDurationMs], le hold est annulé et
 *    l'anneau de progression se vide.
 *  - À déclenchement effectif : `HapticFeedback.LongPress` (vibration
 *    forte) ET appel à [onTrigger].
 *
 * **Robustesse coroutine** :
 *  - Le timer 3s est implémenté via [LaunchedEffect] + `delay(holdDurationMs)`
 *    qui est cancellable proprement par recomposition (key = `isHolding`).
 *  - PAS de `Thread.sleep` qui bloquerait le main thread.
 *  - PAS de coroutine launchée manuellement qui pourrait fuiter.
 *
 * **Accessibilité** :
 *  - `Role.Button` + `contentDescription` explicite ("URGENCE — maintenir
 *    3 secondes").
 *  - Surface min 96 dp (≥ tap target 48 dp x2 pour signaler l'importance).
 *  - Texte central très contrasté (white sur rouge marque).
 *
 * **Disabled** : quand [enabled] = false (pas de contacts, ou cooldown
 * anti-spam post-trigger), le bouton ne réagit pas au touch et apparaît
 * grisé. L'anneau de progression reste vide.
 */
@Composable
fun EmergencyHoldButton(
    onTrigger: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = DEFAULT_HOLD_MS,
    size: Dp = 200.dp,
) {
    val haptics = LocalHapticFeedback.current
    var isHolding by remember { mutableStateOf(false) }

    // L'animation suit `isHolding` — quand on lâche avant la fin, elle
    // revient en arrière en 200 ms (effet "rembobinage" visuel).
    val progress by animateFloatAsState(
        targetValue = if (isHolding) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isHolding) holdDurationMs.toInt() else 200,
            easing = LinearEasing,
        ),
        label = "emergency-progress",
    )

    // Le timer 3s tourne uniquement quand isHolding=true. La key=isHolding
    // garantit que `delay()` est cancellé proprement si on lâche avant la
    // fin (le LaunchedEffect est recréé avec isHolding=false, l'ancien job
    // est annulé par recomposition).
    LaunchedEffect(isHolding) {
        if (isHolding) {
            delay(holdDurationMs)
            // On est arrivé au bout sans annulation → trigger.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            isHolding = false // reset visuel
            onTrigger()
        }
    }

    val activeColor = if (enabled) BrandDanger else BrandDanger.copy(alpha = 0.35f)
    val ringColor = if (enabled) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f)
    val descText = stringResource(R.string.emergency_button_hint_hold_3s)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = modifier
                .size(size)
                .semantics {
                    role = Role.Button
                    contentDescription = descText
                }
                .pointerInput(enabled, holdDurationMs) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            // Attend le 1er DOWN, ignore les autres pointeurs.
                            val down = awaitPointerEvent(PointerEventPass.Main)
                            if (down.changes.any { it.pressed }) {
                                isHolding = true
                                // Attend la libération (UP) ou la perte de focus.
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
                },
            contentAlignment = Alignment.Center,
        ) {
            // Cercle de fond + anneau de progression dessiné par-dessus.
            Surface(
                color = activeColor,
                shape = CircleShape,
                modifier = Modifier.size(size),
            ) {}
            Canvas(modifier = Modifier.size(size)) {
                val stroke = 8.dp.toPx()
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(
                        this.size.width - stroke,
                        this.size.height - stroke,
                    ),
                    style = Stroke(width = stroke),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.emergency_button_label),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val DEFAULT_HOLD_MS: Long = 3_000L
