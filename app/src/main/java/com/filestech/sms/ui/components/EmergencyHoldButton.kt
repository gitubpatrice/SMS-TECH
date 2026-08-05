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
                    // v1.14.2 hotfix CRITIQUE — distance au-delà de laquelle on
                    // considère que ce n'est plus un "hold" mais un "drag" (ex.
                    // scroll vertical de la page Mode urgence v1.14.1). Sans
                    // cette protection, un scroll lent qui traversait le bouton
                    // était interprété comme un appui maintenu 3s → trigger
                    // SMS d'urgence intempestif (bug user reported 2026-05-22).
                    // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 `touchSlop` NE VAUT QUE
                    // PENDANT LES PREMIÈRES CENTAINES DE MILLISECONDES.
                    //
                    // Le seuil est calibré pour trancher « scroll ou tap » en ~500 ms. On
                    // l'appliquait ici pendant **trois secondes entières** : passé le premier
                    // instant, le moindre tremblement, le roulement de la pulpe du doigt ou
                    // quelques pas suffisaient à dépasser le seuil. Le maintien était alors annulé
                    // **en silence** — l'anneau se rembobinait, et quelqu'un en train de demander
                    // du secours pouvait rester le doigt appuyé sur un bouton qui ne déclencherait
                    // jamais.
                    //
                    // Nouvelle règle, en deux temps :
                    //
                    //  1. **Les 300 premières ms** — le doute « scroll ou appui ? » est réel :
                    //     `touchSlop` tranche, exactement comme avant. Le scroll de la page reste
                    //     donc protégé, ce que le correctif v1.14.2 avait mis en place après un
                    //     déclenchement intempestif signalé le 2026-05-22.
                    //  2. **Ensuite** — l'intention de maintenir est établie. Seule une sortie
                    //     FRANCHE annule : le doigt doit quitter le DISQUE visible. Une dérive de
                    //     quelques millimètres ne veut plus rien dire, et n'annule plus.
                    //
                    // v1.27.2 (audit Codex du 2026-08-05, P-08) — DEUX TROUS RESTAIENT OUVERTS
                    // dans cette version en deux temps :
                    //
                    //  - un glissement démarré APRÈS 300 ms, resté dans les bornes, n'annulait plus
                    //    rien **et le composant ne revendiquait pas le geste** : la page défilait
                    //    pendant que l'anneau se remplissait. Trois secondes de contact plus tard,
                    //    de VRAIS SMS d'urgence partaient — et la position était partagée — sur un
                    //    geste destiné à faire défiler l'écran. Le correctif v1.14.2 fermait le
                    //    scroll rapide, celui-ci rouvrait le scroll lent ;
                    //  - les bornes testées étaient le CARRÉ de 200 dp, alors que le bouton visible
                    //    est le disque inscrit. Une dérive en diagonale vers un coin restait donc
                    //    acceptée bien qu'ayant quitté le bouton à l'écran.
                    //
                    // La règle devient donc : passé la fenêtre de discrimination, le composant
                    // **consomme** le geste — le parent ne peut plus défiler, le contrat est net —
                    // et seule une sortie du disque l'annule. Et à tout instant, un geste déjà
                    // consommé par un ancêtre annule le maintien : il ne nous appartient pas.
                    val touchSlopPx = viewConfiguration.touchSlop
                    val slopSq = touchSlopPx * touchSlopPx
                    // 🔴 v1.27.2 (audit Codex du 2026-08-05, C-01) — UNE GARDE DE DÉPLACEMENT
                    // SUBSISTE APRÈS LA FENÊTRE DE DISCRIMINATION.
                    //
                    // La version précédente ne testait plus que la sortie du disque. Un doigt posé,
                    // immobile 300 ms, puis glissant de 60 à 80 dp sans quitter un disque de
                    // 100 dp de rayon déclenchait donc l'alerte au bout de trois secondes.
                    // `pressed.consume()` empêchait le parent de défiler — le symptôme visible —
                    // **sans fermer le faux déclenchement**. J'avais présenté ce comportement
                    // comme un contrat assumé ; c'était une rationalisation.
                    //
                    // Le seuil est délibérément entre les deux : très au-dessus de `touchSlop`,
                    // pour qu'un tremblement ou le roulement de la pulpe du doigt n'annule pas
                    // l'appel au secours de quelqu'un ; très en dessous du rayon, pour qu'une
                    // trajectoire de défilement l'annule. Sur un bouton de sécurité, en cas de
                    // doute, l'état sûr est l'annulation.
                    val driftTolerancePx = HOLD_DRIFT_TOLERANCE.toPx()
                    val driftSq = driftTolerancePx * driftTolerancePx
                    // ⚠️ `this@pointerInput.size` et non `size` : le paramètre `size: Dp` du
                    // composable masque celui de la zone de pointeur, qui est en PIXELS.
                    val boundsPx = this@pointerInput.size
                    val centerX = boundsPx.width / 2f
                    val centerY = boundsPx.height / 2f
                    val radiusPx = minOf(boundsPx.width, boundsPx.height) / 2f
                    val radiusSq = radiusPx * radiusPx
                    awaitPointerEventScope {
                        while (true) {
                            // Attend le 1er DOWN, ignore les autres pointeurs.
                            val down = awaitPointerEvent(PointerEventPass.Main)
                            val firstPressed = down.changes.firstOrNull { it.pressed }
                                ?: continue
                            val startPos = firstPressed.position
                            val downTime = firstPressed.uptimeMillis
                            // v1.27.2 (audit Codex du 2026-08-05, C-02) — on suit CE pointeur, pas
                            // « le premier appuyé ».
                            //
                            // La boucle reprenait `firstOrNull { it.pressed }` à chaque évènement,
                            // sans vérifier l'identifiant. Un second doigt posé pendant le maintien
                            // devenait donc le propriétaire quand le premier se relevait : le
                            // minuteur, lui, continuait de courir depuis le DOWN d'origine. Une
                            // main qui en remplace une autre pouvait déclencher sans qu'aucun
                            // doigt n'ait tenu les trois secondes.
                            val ownerId = firstPressed.id
                            isHolding = true
                            // Attend la libération (UP), la perte de focus, un drag pendant la
                            // fenêtre de discrimination, OU une sortie du bouton après elle.
                            var draining = false
                            inner@ while (true) {
                                val next = awaitPointerEvent(PointerEventPass.Main)
                                // Le propriétaire du geste, et lui seul. Un autre doigt ne peut
                                // pas reprendre le maintien en cours (C-02).
                                val pressed = next.changes
                                    .firstOrNull { it.id == ownerId && it.pressed }
                                if (pressed == null) {
                                    // UP / cancel du propriétaire — fin propre du geste. On draine
                                    // jusqu'à ce que plus aucun pointeur ne soit appuyé, pour ne
                                    // pas réarmer un maintien sur un doigt resté posé (C-02).
                                    isHolding = false
                                    if (next.changes.none { it.pressed }) break@inner
                                    draining = true
                                } else if (!draining) {
                                    val inDiscriminationWindow =
                                        (pressed.uptimeMillis - downTime) < SCROLL_DISCRIMINATION_MS
                                    val dx = pressed.position.x - startPos.x
                                    val dy = pressed.position.y - startPos.y
                                    val movedSq = dx * dx + dy * dy
                                    val cancelled = when {
                                        // Un ancêtre a déjà pris le geste (défilement, glissement
                                        // imbriqué). Ce n'est pas un maintien, quel que soit le
                                        // moment : on ne déclenche pas une alerte sur le geste de
                                        // quelqu'un d'autre.
                                        pressed.isConsumed -> true
                                        // Premier instant : `touchSlop` tranche « scroll ou
                                        // appui ? », et le parent reste libre de défiler.
                                        inDiscriminationWindow -> movedSq > slopSq
                                        // Ensuite : une dérive franche annule quand même (C-01),
                                        // et la sortie du DISQUE visible aussi — les coins du
                                        // carré englobant n'ont jamais fait partie du bouton.
                                        else -> {
                                            val ox = pressed.position.x - centerX
                                            val oy = pressed.position.y - centerY
                                            movedSq > driftSq || ox * ox + oy * oy > radiusSq
                                        }
                                    }
                                    if (cancelled) {
                                        // Annule le hold et draine jusqu'à UP pour ne pas
                                        // re-fire isHolding au tour suivant sur le même geste.
                                        isHolding = false
                                        draining = true
                                    } else if (!inDiscriminationWindow) {
                                        // Le maintien est établi : le composant REVENDIQUE le
                                        // geste. Sans cette ligne, un glissement lent démarré après
                                        // la fenêtre de discrimination faisait défiler la page
                                        // pendant que l'anneau se remplissait — et l'alerte partait
                                        // pour de bon au bout de trois secondes.
                                        pressed.consume()
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

/**
 * v1.27.2 — durée pendant laquelle `touchSlop` peut encore annuler le maintien.
 *
 * Au-delà, l'intention de maintenir est établie et seule une sortie du bouton annule. 300 ms est
 * plus long que le délai d'appui long d'Android (500 ms est le seuil de reconnaissance, mais un
 * scroll se manifeste bien plus tôt) et assez court pour qu'un tremblement de main n'ait pas le
 * temps de déplacer le doigt au-delà du seuil.
 */
private const val SCROLL_DISCRIMINATION_MS: Long = 300L

/**
 * v1.27.2 (audit Codex du 2026-08-05, C-01) — dérive tolérée **après** la fenêtre de
 * discrimination, mesurée depuis le point d'appui initial.
 *
 * 24 dp : environ le triple de `touchSlop`, donc un tremblement de main, le roulement de la pulpe
 * du doigt ou quelques pas ne coupent pas un appel au secours. Et le quart du rayon du bouton,
 * donc toute trajectoire ressemblant à un défilement l'annule bien avant d'en sortir.
 *
 * ⚠️ Ne pas remonter ce seuil pour « plus de confort » : c'est exactement ce raisonnement qui avait
 * produit la version sans garde du tout, où un glissement de 60 à 80 dp envoyait de vrais SMS
 * d'urgence.
 */
private val HOLD_DRIFT_TOLERANCE: Dp = 24.dp
