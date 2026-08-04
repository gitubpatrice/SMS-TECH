package com.filestech.sms.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * v1.27.2 — **preuve d'outillage**, pas test métier.
 *
 * Elle établit qu'un test **JVM** peut monter le graphe Hilt de production sous Robolectric, sans
 * émulateur. `hilt-android-testing` n'était déclaré qu'en `androidTestImplementation` : ce
 * mécanisme n'existait donc que sur appareil, à un coût qui décourage l'écriture quotidienne de
 * tests.
 *
 * ⚠️ Cette classe est en **JUnit 4** (`@RunWith`, `@Rule`), alors que le module tourne sur la
 * plateforme JUnit 5. C'est le moteur *vintage* qui la fait exécuter. Sans lui elle serait
 * **silencieusement ignorée** — la tâche passerait au vert sans avoir rien exécuté, le pire des
 * échecs possibles pour un test.
 *
 * # Ce qui reste à débloquer pour les tests de receveurs
 *
 * Le but final est de substituer un collaborateur par un faux qui **échoue**, afin d'exercer les
 * chemins d'erreur où vivaient le SMS perdu et le MMS effacé du 2026-08-04.
 *
 * Or `@BindValue` sur un type déjà lié par un module de production fait échouer la compilation
 * (« bound multiple times »), et `@UninstallModules(RepositoryModule::class)` retire d'un coup ses
 * **21 liaisons** — le graphe ne compile alors plus, faute de `ConversationRepository`,
 * `SmsSender`, `PanicStateProvider` et consorts. **Vérifié, les deux voies échouent.**
 *
 * La sortie propre serait de **scinder `RepositoryModule`** pour qu'un test puisse remplacer la
 * seule liaison qui l'intéresse — modification de code de production, à faire à tête reposée.
 *
 * # ⚠️ La limite qui prime sur tout le reste : SQLCipher est NATIF
 *
 * Injecter ici la moindre liaison adossée à la base — `BlockedNumberRepository`,
 * `ConversationMirror`, n'importe quel DAO — lève `UnsatisfiedLinkError: no sqlcipher in
 * java.library.path`. **Vérifié.** La bibliothèque n'existe qu'en `.so` Android : aucune JVM de
 * bureau ne peut la charger, et Robolectric n'y change rien.
 *
 * Conséquence directe, et elle corrige un plan trop optimiste : **les chemins d'échec des
 * receveurs ne sont pas testables en JVM**, puisque ces receveurs injectent tous des
 * collaborateurs qui ouvrent la base. Deux voies possibles, aucune gratuite :
 *
 *  1. les écrire en **androidTest** sur émulateur — l'outillage existe déjà, c'est plus lent ;
 *  2. **extraire la décision** en fonction pure, sans dépendance à la base, et tester celle-ci en
 *     JVM. C'est le patron déjà employé par `isBlockedFailOpen` et `matchOneToOneByBlockKey`, et
 *     c'est ce qui aurait attrapé le MMS effacé du 2026-08-04.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class HiltRobolectricSmokeTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    /** Liaison sans dépendance : prouve que l'injection elle-même fonctionne. */
    @Inject
    lateinit var vaultSession: VaultSessionState

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun theRealHiltGraphIsBuiltInsideAPlainJvmTest() {
        assertThat(vaultSession).isNotNull()
    }

    @Test
    fun injectedSingletonsAreTheSameInstanceAcrossInjections() {
        // `VaultSessionState` est `@Singleton` : c'est lui qui porte l'état « le Coffre est ouvert
        // pour cette session ». Deux instances signifieraient qu'une garde consulte un état
        // pendant qu'une autre en écrit un second — le graphe doit donc n'en produire qu'une.
        val again = vaultSession
        assertThat(again).isSameInstanceAs(vaultSession)
        assertThat(vaultSession.isUnlocked).isFalse()

        vaultSession.markUnlocked()
        assertThat(vaultSession.isUnlocked).isTrue()
        vaultSession.lock()
    }
}
