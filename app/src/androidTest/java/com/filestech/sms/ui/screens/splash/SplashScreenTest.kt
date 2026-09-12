package com.filestech.sms.ui.screens.splash

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * v1.28.6 — **le splash de première ouverture restait bloqué après une réinitialisation.**
 *
 * Signalé par un testeur le 2026-09-12, reproduit sur S9 : « Réinitialiser tous les réglages » et
 * « Supprimer toutes mes données » remettent `splashShown` à `false`, le splash se rejoue en overlay
 * au-dessus du graphe, mais son garde d'idempotence était resté armé depuis la première sortie.
 * Tap, retour et auto-fermeture ne faisaient plus rien ; seule la mort du processus libérait
 * l'écran.
 *
 * Le test monte l'écran seul sur un faux `AppSettingsSource`, ferme le splash une première fois,
 * rejoue la réinitialisation telle que la font `SettingsViewModel.resetAll` et
 * `PanicService.nukeEverything`, puis exige qu'une tape ferme à nouveau le splash.
 *
 * ⚠️ La tape passe par `performTouchInput`, pas `performClick` : le `Box` du splash efface ses
 * sémantiques (`clearAndSetSemantics`), il n'y a donc aucune action « clic » à invoquer — seul un
 * vrai évènement de pointeur atteint le `clickable`, comme un doigt.
 *
 * Contrôle négatif fait le 2026-09-12 : sur le `SplashScreen` d'avant le réarmement, le test
 * échoue sur la seconde attente (`splashShown` ne repasse jamais à `true`).
 */
class SplashScreenTest {

    @get:Rule val compose = createComposeRule()

    private class FakeSettings(initial: AppSettings) : AppSettingsSource {
        private val backing = MutableStateFlow(initial)
        override val flow: Flow<AppSettings> get() = backing
        override val state: StateFlow<AppSettings> get() = backing
        override suspend fun hydratedOrNull(): AppSettings = backing.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = backing.update(transform)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    @Test
    fun uneTapeFermeLeSplashMemeApresUneReinitialisation() {
        val settings = FakeSettings(AppSettings())
        val viewModel = SplashViewModel(settings)
        var finished = 0
        val journal = mutableListOf<String>()
        compose.setContent {
            SplashScreen(
                onFinished = {
                    finished++
                    journal += "fin#$finished show=${viewModel.shouldShow.value} " +
                        "flag=${settings.state.value.advanced.splashShown}"
                },
                viewModel = viewModel,
            )
        }

        // Première ouverture : une tape ferme le splash et persiste le drapeau.
        compose.onRoot().performTouchInput { click() }
        compose.waitUntil(WAIT_MS) { settings.state.value.advanced.splashShown }
        assertThat(finished).isEqualTo(1)
        // ⚠️ Laisser la composition OBSERVER la sortie (overlay retiré) avant de réinitialiser.
        // Sans cette frame, `shouldShow` fait vrai → faux → vrai sans jamais être recomposé, et le
        // réarmement — qui suit les transitions vues par la composition — n'a rien à voir passer.
        // C'est ce qui se passe dans l'application : des frames s'écoulent entre les deux gestes.
        compose.waitForIdle()
        compose.onAllNodesWithTag(SPLASH_TEST_TAG).assertCountEquals(0)

        // Réinitialisation, à l'identique de `resetAll` : tout revient aux défauts sauf la sécurité.
        runBlocking { settings.update { AppSettings(security = it.security) } }
        compose.waitForIdle()
        assertThat(settings.state.value.advanced.splashShown).isFalse()
        compose.onNodeWithTag(SPLASH_TEST_TAG).assertExists()

        // Le splash s'est rejoué : une tape doit le fermer à nouveau, sans tuer le processus.
        compose.onNodeWithTag(SPLASH_TEST_TAG).performTouchInput { click() }
        compose.waitForIdle()
        assertWithMessage("finished=$finished shouldShow=${viewModel.shouldShow.value} journal=$journal")
            .that(settings.state.value.advanced.splashShown).isTrue()
        assertThat(finished).isEqualTo(2)
        compose.onAllNodesWithTag(SPLASH_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun laBrancheDejaVuNeRendRienEtSignaleUneSeuleFois() {
        val settings = FakeSettings(
            AppSettings().let { it.copy(advanced = it.advanced.copy(splashShown = true)) },
        )
        var finished = 0
        compose.setContent { SplashScreen(onFinished = { finished++ }, viewModel = SplashViewModel(settings)) }
        compose.waitForIdle()
        assertThat(finished).isEqualTo(1)

        // Une tape sur un écran qui ne rend rien ne doit rien déclencher de plus.
        compose.onRoot().performTouchInput { click() }
        compose.waitForIdle()
        assertThat(finished).isEqualTo(1)
    }
}
