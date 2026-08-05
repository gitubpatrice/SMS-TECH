package com.filestech.sms.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.settings.PreviewMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.27.2 — verrouille la lecture des réglages sur un processus **démarré à froid**.
 *
 * # Le défaut que ces tests ferment
 *
 * [SettingsRepository.state] démarre sur `AppSettings()` et s'hydrate depuis DataStore de façon
 * asynchrone. Tant que la première émission n'est pas arrivée, `state.value` rend les valeurs PAR
 * DÉFAUT — pas les réglages de l'utilisateur. Or les chemins les plus sensibles de l'application
 * (SMS entrant, worker Safety call, réponse rapide depuis une notification) s'exécutent
 * précisément sur un processus qui vient de naître. Le Safety call ne partait jamais pour cette
 * raison, et l'aperçu d'un message s'affichait sur l'écran de verrouillage de quelqu'un qui
 * l'avait masqué.
 *
 * # Pourquoi ces tests sont NON VACANTS
 *
 * Le processus froid est simulé par un `StandardTestDispatcher` **jamais avancé** : la collecte
 * interne du dépôt ne tourne donc pas du tout, jamais, quelle que soit la vitesse de la machine.
 * Le premier test affirme les DEUX faits à la fois — `state.value` rend le défaut, et
 * [SettingsRepository.hydratedOrNull] rend le réglage réel. Si l'implémentation régressait vers
 * `state.value`, la seconde assertion tomberait. Aucune attente, aucune tolérance de timing.
 *
 * # Pourquoi dans `:app` et non dans `:data`
 *
 * `SettingsRepository` vit dans `:data`, mais ce module n'a ni Robolectric ni moteur vintage
 * JUnit 4 — et un `Context` Android est indispensable ici, DataStore écrivant un vrai fichier.
 * `:app` dispose déjà des deux et voit `:data`. Y ajouter Robolectric serait une dépendance de
 * plus pour un seul fichier de test.
 *
 * ⚠️ JUnit 4 exécuté par le moteur *vintage*, comme
 * [com.filestech.sms.di.HiltRobolectricSmokeTest]. Les méthodes doivent rendre `Unit` : un corps
 * en expression (`fun f() = runBlocking { … }`) fait échouer la classe entière à l'initialisation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SettingsHydrationTest {

    @Test
    fun hydratedOrNullRendsLeReglageReelQuandLeSnapshotChaudEstEncoreVide() {
        val context: Context = ApplicationProvider.getApplicationContext()
        runBlocking {
            // 1. Un utilisateur a explicitement masqué les aperçus de notification.
            val writerScope = CoroutineScope(Dispatchers.Unconfined)
            val writer = SettingsRepository(context, writerScope)
            writer.update { s ->
                s.copy(notifications = s.notifications.copy(previewMode = PreviewMode.NEVER))
            }
            writerScope.cancel()

            // 2. Un processus qui vient de naître. Son ordonnanceur n'est jamais avancé : la
            //    collecte interne du dépôt ne s'exécutera pas une seule fois.
            val coldScope = CoroutineScope(StandardTestDispatcher())
            val cold = SettingsRepository(context, coldScope)

            // Le snapshot chaud ment — il rend le DÉFAUT, qui est le mode le plus bavard.
            assertThat(cold.state.value.notifications.previewMode).isEqualTo(PreviewMode.ALWAYS)

            // La lecture hydratée rend le choix RÉEL de l'utilisateur.
            assertThat(cold.hydratedOrNull()?.notifications?.previewMode)
                .isEqualTo(PreviewMode.NEVER)

            coldScope.cancel()
        }
    }

    /**
     * Garde anti-régression sur le remplacement de `stateIn` par une collecte explicite dans
     * [SettingsRepository] : si elle cessait de publier, TOUTE l'application lirait des défauts en
     * permanence — sans qu'aucun test ne le signale, puisque les défauts sont des valeurs
     * plausibles. C'est le rayon d'explosion maximal du changement, donc il est tenu ici.
     */
    @Test
    fun laCollecteInternePublieBienDansState() {
        val context: Context = ApplicationProvider.getApplicationContext()
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val repo = SettingsRepository(context, scope)
            repo.update { s ->
                s.copy(
                    notifications = s.notifications.copy(
                        previewMode = PreviewMode.WHEN_UNLOCKED,
                    ),
                )
            }

            val published = withTimeout(TIMEOUT_MS) {
                repo.state.first { it.notifications.previewMode == PreviewMode.WHEN_UNLOCKED }
            }
            assertThat(published.notifications.previewMode).isEqualTo(PreviewMode.WHEN_UNLOCKED)

            // Et `hydratedOrNull` doit alors emprunter le chemin CHAUD : même valeur, sans I/O.
            assertThat(repo.hydratedOrNull()?.notifications?.previewMode)
                .isEqualTo(PreviewMode.WHEN_UNLOCKED)

            scope.cancel()
        }
    }

    private companion object {
        /** Large : on ne mesure pas une latence, on empêche un test bloqué de figer la tâche. */
        const val TIMEOUT_MS = 10_000L
    }
}
