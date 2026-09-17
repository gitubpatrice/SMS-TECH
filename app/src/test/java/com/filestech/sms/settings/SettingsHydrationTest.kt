package com.filestech.sms.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.PreviewMode
import com.filestech.sms.domain.settings.SendingSettings
import com.filestech.sms.testing.magasinDeTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
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
 * JUnit 4 — et DataStore écrit ici un vrai fichier.
 *
 * # Un magasin par test (v1.28.8)
 *
 * Chaque test a son propre fichier ([magasinDeTest]). Construits sur un `Context`, ces dépôts
 * partageaient le magasin de toute la JVM — et `laCollecteInternePublieBienDansState` échouait par
 * intermittence sous Windows : « Unable to rename », dans le dossier d'un test d'une autre classe.
 *
 * ⚠️ JUnit 4 exécuté par le moteur *vintage*. Les méthodes doivent rendre `Unit` : un corps en
 * expression (`fun f() = runBlocking { … }`) fait échouer la classe entière à l'initialisation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SettingsHydrationTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private val porteeMagasin = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val magasin by lazy { magasinDeTest(dossier, porteeMagasin) }

    @After
    fun fermeLeMagasin() {
        porteeMagasin.cancel()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** Les trois cles d'une version precedente, telles qu'elles sont ecrites sur le disque. */
        val TAG_DE_LANGUE = stringPreferencesKey("locale.tag")
        val PREMIER_JOUR = stringPreferencesKey("locale.firstDay")
        val ROLE_SMS = booleanPreferencesKey("advanced.isDefault")
    }

    /**
     * v1.28.12 (audit S9) — **deux cles retirees survivaient a « Supprimer toutes mes donnees ».**
     *
     * `locale.tag`, `locale.firstDay` et `advanced.isDefault` ont ete retirees avec les champs
     * qu'elles portaient, mais elles restaient ecrites sur le disque des installations
     * anterieures. La TROISIEME a ete oubliee le jour meme ou les deux premieres ont ete
     * corrigees, et deux relectures externes l'ont trouvee : c'est pour ca qu'elle est ici. Or `PanicService.nukeEverything`
     * REECRIT les reglages par-dessus (`update { AppSettings() }`) au lieu de vider le magasin :
     * une cle que l'ecriture ne nomme pas n'est jamais touchee. Le tag de langue choisi par
     * l'utilisateur survivait donc a une purge qui se dit complete.
     *
     * Le temoin positif est dans le test lui-meme : on verifie que les deux cles SONT la avant,
     * sans quoi un magasin vide rendrait ce test vert sans rien mesurer.
     */
    @Test
    fun laPurgeEmporteLesClesRetirees() {
        runBlocking {
            val portee = CoroutineScope(Dispatchers.Unconfined)
            try {
                // Une installation anterieure : les deux cles sont sur le disque.
                magasin.edit { prefs ->
                    prefs[TAG_DE_LANGUE] = "de"
                    prefs[PREMIER_JOUR] = "MONDAY"
                    prefs[ROLE_SMS] = true
                }
                val avant = magasin.data.first()
                assertThat(avant[TAG_DE_LANGUE]).isEqualTo("de")
                assertThat(avant[PREMIER_JOUR]).isEqualTo("MONDAY")
                assertThat(avant[ROLE_SMS]).isTrue()

                // Ce que fait « Supprimer toutes mes donnees ».
                SettingsRepository(magasin, portee).update { AppSettings() }

                val apres = magasin.data.first()
                assertThat(apres[TAG_DE_LANGUE]).isNull()
                assertThat(apres[PREMIER_JOUR]).isNull()
                assertThat(apres[ROLE_SMS]).isNull()
            } finally {
                portee.cancel()
            }
        }
    }

    /**
     * **Test de régression du finding 2** (relecture Codex du 2026-08-05).
     *
     * Sur l'implémentation précédente, `hydratedOrNull()` rendait immédiatement la vraie valeur par
     * sa lecture indépendante, et la troisième assertion échouait : `state` en était encore aux
     * défauts. C'est exactement la fenêtre qui faisait perdre l'indicatif pays au formatteur non
     * suspendable, appelé quelques instructions plus loin sur le même envoi.
     *
     * ⚠️ **La première version de ce test était instable**, et Codex l'a montré : elle occupait
     * l'unique thread de la portée par un `Thread.sleep(400)`. L'ordre n'était alors garanti que
     * pendant ces 400 ms — une pause de ramasse-miettes ou un gel Robolectric plus long libérait le
     * thread, la collecte publiait, et l'assertion attendant encore le défaut échouait **sur du
     * code de production correct**. Un gate instable est pire qu'un test absent : il apprend à
     * relancer plutôt qu'à lire.
     *
     * Le verrou est désormais **explicite** : le thread n'est libéré que lorsque le test a
     * lui-même vérifié l'état initial. Aucune horloge murale n'entre dans l'ordonnancement.
     */
    @Test
    fun apresUneLectureFroide_stateConnaitLaMemeValeur() {
        runBlocking {
            val writerScope = CoroutineScope(Dispatchers.Unconfined)
            SettingsRepository(magasin, writerScope).update { s ->
                s.copy(notifications = s.notifications.copy(previewMode = PreviewMode.NEVER))
            }
            writerScope.cancel()

            // Un thread unique, occupe par une attente que LE TEST controle : la collecte du depot
            // ne peut pas demarrer tant que le verrou n'est pas ouvert. Aucune course possible.
            val gate = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            executor.execute { gate.await() }
            val coldScope = CoroutineScope(executor.asCoroutineDispatcher())
            try {
                val cold = SettingsRepository(magasin, coldScope)

                // 1. Le snapshot chaud ment encore — il rend le DEFAUT, le plus bavard. Verifie
                //    pendant que la collecte est TENUE a l'arret.
                assertThat(cold.state.value.notifications.previewMode).isEqualTo(PreviewMode.ALWAYS)

                // On libere seulement maintenant : `hydratedOrNull` attend la collecte partagee,
                // il faut donc qu'elle puisse tourner pour que l'appel rende la main.
                gate.countDown()

                // 2. La lecture hydratee rend le choix REEL de l'utilisateur.
                val hydrated = withTimeout(TIMEOUT_MS) { cold.hydratedOrNull() }
                assertThat(hydrated?.notifications?.previewMode).isEqualTo(PreviewMode.NEVER)

                // 3. LE POINT DU FINDING 2 : `state` doit etre d'accord IMMEDIATEMENT, sans quoi
                //    tout lecteur synchrone execute juste apres lirait encore les defauts.
                assertThat(cold.state.value.notifications.previewMode).isEqualTo(PreviewMode.NEVER)
            } finally {
                gate.countDown()
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
            val repo = SettingsRepository(magasin, deadScope)

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
            val repo = SettingsRepository(magasin, scope)
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

    /**
     * v1.28.12 — **une installation NEUVE doit lire le défaut DÉCLARÉ.**
     *
     * L'hydratation construit `SendingSettings(...)` en passant chaque champ explicitement :
     * le défaut écrit sur la data-class n'est donc jamais atteint, et rien ne garantissait
     * que les deux disent la même chose. Ils ne la disaient plus. La v1.14.4 avait changé
     * le format de réaction par défaut en `EMOJI_WITH_QUOTE` à la demande de l'utilisateur,
     * sur la déclaration seulement : **le changement n'a pris effet sur aucune
     * installation**, et toute installation neuve envoyait des réactions écrites en
     * français, quelle que soit la langue de l'application.
     *
     * Deux assertions, et il en faut deux : la première interdit aux deux endroits de
     * diverger à nouveau, la seconde fige la valeur elle-même, pour qu'un changement
     * silencieux du défaut déclaré ne passe pas non plus.
     */
    @Test
    fun uneInstallationNeuveLitLeDefautDeclare() {
        runBlocking {
            val portee = CoroutineScope(Dispatchers.Unconfined)
            try {
                val depot = SettingsRepository(magasin, portee)
                val neuf = withTimeout(TIMEOUT_MS) { depot.hydratedOrNull() }
                assertThat(neuf?.sending?.reactionFormat)
                    .isEqualTo(SendingSettings().reactionFormat)
                assertThat(neuf?.sending?.reactionFormat)
                    .isEqualTo(ReactionFormat.EMOJI_WITH_QUOTE)
            } finally {
                portee.cancel()
            }
        }
    }

    /**
     * Le contrôle négatif du test précédent : une installation qui vient de la v1.7.x porte
     * la clé héritée `send.reactions.emojiOnly` et GARDE son choix. Sans lui, un correctif
     * qui rendrait le défaut déclaré à tout le monde — y compris à ceux qui avaient choisi
     * autre chose — passerait le test ci-dessus sans rien signaler.
     */
    @Test
    fun uneInstallationVenueDeLa17xGardeSonChoix() {
        runBlocking {
            magasin.edit { it[booleanPreferencesKey("send.reactions.emojiOnly")] = false }
            val portee = CoroutineScope(Dispatchers.Unconfined)
            try {
                val depot = SettingsRepository(magasin, portee)
                val ancien = withTimeout(TIMEOUT_MS) { depot.hydratedOrNull() }
                assertThat(ancien?.sending?.reactionFormat).isEqualTo(ReactionFormat.TAPBACK_EN)
            } finally {
                portee.cancel()
            }
        }
    }
}
