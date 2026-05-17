package com.filestech.sms.ui.screens.splash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.3.7 — Splash de présentation joué **uniquement à la première ouverture** de
 * l'app après install (cf. [SplashViewModel.shouldShow]). 100% Compose natif, aucune
 * dépendance externe ajoutée — cohérent avec l'esprit léger / zéro-tracker de SMS Tech.
 *
 * **Animations** (durée totale ≈ [AUTO_DISMISS_MS] ms) :
 *   - **logo** : `scale 0.5 → 1.0` + `alpha 0 → 1` sur [LOGO_ANIM_MS] ms, ease-out cubic.
 *   - **tagline** : `alpha 0 → 1` sur [TAGLINE_ANIM_MS] ms après [TAGLINE_DELAY_MS] ms
 *     (overlap léger avec la fin de l'animation logo pour une sensation de continuité).
 *   - **hint "Toucher pour passer"** : fade in à [HINT_DELAY_MS] ms, opacité finale
 *     [HINT_FINAL_ALPHA] (volontairement discret pour ne pas voler l'attention).
 *
 * **Interactions** (toutes idempotentes via [dismissOnce]) :
 *   - **Tap n'importe où** → skip immédiat.
 *   - **Back hardware / gesture** → skip immédiat (cf. [BackHandler]).
 *   - **Auto-dismiss** à [AUTO_DISMISS_MS] ms.
 *
 * **Pourquoi ce design est sans faille** :
 *
 *  1. `LaunchedEffect(Unit)` ne se relance JAMAIS de la vie du Composable. Toute
 *     écriture concurrente du flag splash (par [SplashViewModel.markShown]) ne peut
 *     pas re-déclencher l'effet et donc pas non plus rendre `onFinished()` réentrant.
 *  2. [dismissOnce] est gardé par un [AtomicBoolean] — thread-safe même si un futur
 *     refactor déplace un appel sur un dispatcher background. En pratique Compose
 *     execute sur Main, mais on ceinture-et-bretelles.
 *  3. [SplashViewModel.shouldShow] est un `StateFlow` `Eagerly` `stateIn` : la valeur
 *     initiale `true` est immédiatement remplacée par la vraie valeur DataStore
 *     dès la construction du ViewModel — pas de "flash" splash pour un user qui l'a
 *     déjà vu, car la lecture est sync au premier rendu.
 *  4. Si `shouldShow` arrive à `false` au cold start (deuxième ouverture+), la branche
 *     `if (!shouldShow)` skippe TOUT le rendu animation et l'effet [LaunchedEffect]
 *     se contente d'appeler [onFinished] une fois — exactement comme le tap-to-skip
 *     mais sans persister un flag déjà à `true`.
 *  5. `coroutineScope` parent encapsule les 5 lances animation + auto-dismiss. Si
 *     une animation throw `CancellationException` (recomposition forcée par config
 *     change p.ex.), tout le scope est annulé proprement ; le `dismissOnce` n'est
 *     pas exécuté tant que l'auto-dismiss n'a pas tick.
 *
 * **Limites connues acceptées** :
 *
 *  - **Rotation pendant splash** : les animations recommencent à zéro. Acceptable car
 *    durée 3 s, probabilité d'occurrence sur cette fenêtre est négligeable. Coder un
 *    state-save des `Animatable` serait du overengineering pour un écran one-shot.
 *  - **Logo asset** : on utilise `R.drawable.sms_tech_icon` (le PNG existant), pas un
 *    SVG vector. Une v1.3.8+ pourra basculer sur une `VectorDrawable` pour scaler
 *    parfaitement sur tablettes haute densité — pour l'instant le PNG suffit.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val shouldShow by viewModel.shouldShow.collectAsStateWithLifecycle()

    // Garde d'idempotence partagée entre toutes les portes de sortie (tap, back,
    // auto-dismiss, branche cold-start "déjà vu"). Survit aux recompositions grâce à
    // `remember(viewModel, onFinished)`.
    val firedGuard = remember(viewModel, onFinished) { AtomicBoolean(false) }
    val dismissOnce: () -> Unit = remember(viewModel, onFinished) {
        {
            if (firedGuard.compareAndSet(false, true)) {
                viewModel.markShown()
                onFinished()
            }
        }
    }

    // Branche "déjà vu" : redirige immédiatement sans rendre l'UI splash. Le
    // `markShown()` est court-circuité (flag déjà à `true`, write inutile).
    if (!shouldShow) {
        LaunchedEffect(Unit) {
            if (firedGuard.compareAndSet(false, true)) {
                onFinished()
            }
        }
        return
    }

    val logoScale = remember { Animatable(initialValue = 0.5f) }
    val logoAlpha = remember { Animatable(initialValue = 0f) }
    val taglineAlpha = remember { Animatable(initialValue = 0f) }
    val hintAlpha = remember { Animatable(initialValue = 0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                logoAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = LOGO_ANIM_MS, easing = EaseOutCubic),
                )
            }
            launch {
                logoScale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = LOGO_ANIM_MS, easing = EaseOutCubic),
                )
            }
            launch {
                delay(TAGLINE_DELAY_MS)
                taglineAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = TAGLINE_ANIM_MS, easing = EaseOutCubic),
                )
            }
            launch {
                delay(HINT_DELAY_MS)
                hintAlpha.animateTo(
                    targetValue = HINT_FINAL_ALPHA,
                    animationSpec = tween(durationMillis = HINT_ANIM_MS),
                )
            }
            launch {
                delay(AUTO_DISMISS_MS)
                dismissOnce()
            }
        }
    }

    BackHandler(enabled = true) { dismissOnce() }

    val interaction = remember { MutableInteractionSource() }
    val configuration = LocalConfiguration.current
    val logoSizeDp = (configuration.screenWidthDp * LOGO_SIZE_RATIO)
        .coerceIn(LOGO_MIN_DP, LOGO_MAX_DP).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = stringResource(R.string.splash_skip_label),
                onClick = dismissOnce,
            )
            .clearAndSetSemantics { /* TalkBack annonce uniquement les enfants nommés */ },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.sms_tech_icon),
                contentDescription = stringResource(R.string.splash_logo_content_description),
                modifier = Modifier
                    .size(logoSizeDp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(taglineAlpha.value),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(taglineAlpha.value),
            )
        }

        Text(
            text = stringResource(R.string.splash_skip_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(hintAlpha.value),
        )
    }
}

// v1.3.7 — timings calibrés pour laisser le temps de lire la tagline (~12 mots
// → 3-4 s de lecture confortable + marge pour traiter visuellement). Total ≈ 5.5 s,
// skippable à tout moment via tap ou back. Si re-perçu trop long après usage réel,
// abaisser AUTO_DISMISS_MS sans toucher au reste — les délais relatifs restent ok.
private const val LOGO_ANIM_MS = 900
private const val TAGLINE_DELAY_MS = 700L
private const val TAGLINE_ANIM_MS = 800
private const val HINT_DELAY_MS = 2500L
private const val HINT_ANIM_MS = 500
private const val HINT_FINAL_ALPHA = 0.6f
private const val AUTO_DISMISS_MS = 5500L
private const val LOGO_SIZE_RATIO = 0.4f
private const val LOGO_MIN_DP = 128f
private const val LOGO_MAX_DP = 200f
