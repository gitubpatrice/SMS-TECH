package com.filestech.sms.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.settings.PreviewMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * v1.27.2 — verrouille la lecture des réglages sur un processus **démarré à froid**.
 *
 * # Le défaut d'origine
 *
 * [SettingsRepository.state] démarre sur `AppSettings()` et s'hydrate depuis DataStore de façon
 * asynchrone. Tant que la première émission n'est pas arrivée, `state.value` rend les valeurs PAR
 * DÉFAUT — pas les réglages de l'utilisateur. Or les chemins les plus sensibles de l'application
 * (SMS entrant, worker Safety call, réponse rapide depuis une notification) s'exécutent
 * précisément sur un processus qui vient de naître. Le Safety call ne partait jamais pour cette
 * raison, et l'aperçu d'un message s'affichait sur l'écran de verrouillage de quelqu'un qui
 * l'avait masqué.
 *
 * # Le contrat, après la relecture Codex du 2026-08-05
 *
 * La première version de [SettingsRepository.hydratedOrNull] lançait sa **propre** lecture. Elle
 * rendait bien le vrai instantané, mais [SettingsRepository.state] pouvait encore servir les
 * défauts à un lecteur synchrone exécuté juste après — typiquement
 * `PhoneNumberWireFormatter.resolveRegion`, qui n'est pas suspendable et tourne quelques
 * instructions plus loin sur le MÊME envoi. Un Safety call parti d'un processus froid pouvait donc
 * perdre l'indicatif pays choisi et composer un numéro étranger.
 *
 * Le contrat est désormais plus fort, et c'est lui que ces tests figent :
 * **après le retour de `hydratedOrNull()`, `state` connaît la même valeur.**
 *
 * # Pourquoi dans `:app` et non dans `:data`
 *
 * `SettingsRepository` vit dans `:data`, mais ce module n'a ni Robolectric ni moteur vintage
 * JUnit 4 — et un `Context` Android est indispensable ici, DataStore écrivant un vrai fichier.
 *
 * ⚠️ JUnit 4 exécuté par le moteur *vintage*. Les méthodes doivent rendre `Unit` : un corps en
 * expression (`fun f() = runBlocking { … }`) fait échouer la classe entière à l'initialisation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SettingsHydrationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private companion object {
        /**
         * Retard imposé à la collecte interne pour simuler un processus froid.
         *
         * Assez long pour dépasser de très loin la gigue d'ordonnancement, assez court pour ne
         * pas ralentir la tâche de test. Le test ne mesure pas ce délai : il vérifie un ordre.
         */
        const val COLD_START_DELAY_MS = 400L
        const val TIMEOUT_MS = 10_000L
    }

    /**
     * **Test de régression du finding 2.**
     *
     * La collecte interne est empêchée de démarrer pendant [COLD_START_DELAY_MS] en occupant le
     * seul thread de sa portée. Sur l'implémentation précédente, `hydratedOrNull()` rendait
     * immédiatement la vraie valeur par sa lecture indépendante, et la troisième assertion
     * échouait : `state` en était encore aux défauts. C'est exactement la fenêtre qui faisait
     * perdre l'indicatif pays au formatteur non suspendable.
     */
    @Test
    fun apresUneLectureFroide_stateConnaitLaMemeValeur() {
        runBlocking {
            val writerScope = CoroutineScope(Dispatchers.Unconfined)
            SettingsRepository(context, writerScope).update { s ->
                s.copy(notifications = s.notifications.copy(previewMode = PreviewMode.NEVER))
            }
            writerScope.cancel()

            // Un thread unique, occupe d'emblee : la collecte du depot ne PEUT pas demarrer avant
            // que ce sommeil soit termine. Le processus froid est donc simule sans dependre de la
            // vitesse de la machine.
            val executor = Executors.newSingleThreadExecutor()
            executor.execute { Thread.sleep(COLD_START_DELAY_MS) }
            val coldScope = CoroutineScope(executor.asCoroutineDispatcher())
            try {
                val cold = SettingsRepository(context, coldScope)

                // 1. Le snapshot chaud ment encore — il rend le DEFAUT, le plus bavard.
                assertThat(cold.state.value.notifications.previewMode).isEqualTo(PreviewMode.ALWAYS)

                // 2. La lecture hydratee rend le choix REEL de l'utilisateur.
                val hydrated = withTimeout(TIMEOUT_MS) { cold.hydratedOrNull() }
                assertThat(hydrated?.notifications?.previewMode).isEqualTo(PreviewMode.NEVER)

                // 3. LE POINT DU FINDING 2 : `state` doit etre d'accord IMMEDIATEMENT, sans quoi
                //    tout lecteur synchrone execute juste apres lirait encore les defauts.
                assertThat(cold.state.value.notifications.previewMode).isEqualTo(PreviewMode.NEVER)
            } finally {
                coldScope.cancel()
                executor.shutdownNow()
            }
        }
    }

    /**
     * Le filet anti-blocage de la barrière.
     *
     * `hydratedOrNull()` attend désormais la collecte partagée. Si celle-ci ne démarre jamais —
     * portée déjà annulée, fichier durablement illisible — une attente sans filet resterait
     * suspendue **pour toujours**, sur le chemin d'un SMS entrant. `invokeOnCompletion` complète
     * la barrière dans tous les cas ; la fonction rend alors `null`, jamais des défauts déguisés.
     */
    @Test
    fun uneCollecteQuiNeDemarreJamais_rendNullSansSeSuspendreIndefiniment() {
        runBlocking {
            val deadScope = CoroutineScope(Dispatchers.IO).apply { cancel() }
            val repo = SettingsRepository(context, deadScope)

            val value = withTimeout(TIMEOUT_MS) { repo.hydratedOrNull() }

            assertThat(value).isNull()
        }
    }

    /**
     * Garde anti-régression sur le remplacement de `stateIn` par une collecte explicite : si elle
     * cessait de publier, TOUTE l'application lirait des défauts en permanence — sans qu'aucun
     * test ne le signale, puisque les défauts sont des valeurs plausibles. C'est le rayon
     * d'explosion maximal du changement, donc il est tenu ici.
     */
    @Test
    fun laCollecteInternePublieBienDansState() {
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

            // Et `hydratedOrNull` doit alors emprunter le chemin CHAUD : meme valeur, sans I/O.
            assertThat(repo.hydratedOrNull()?.notifications?.previewMode)
                .isEqualTo(PreviewMode.WHEN_UNLOCKED)

            scope.cancel()
        }
    }
}
