package com.filestech.sms.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.27.2 — les deux gardes du Coffre et du mode leurre trouvées par la relecture Gemini du
 * 2026-08-05, tenues ici parce qu'aucun test ne les couvrait.
 *
 * Les deux relèvent du même motif, celui qui a produit la majorité des vrais défauts de ce
 * projet : **la garde était sur l'affichage, pas sur l'accès.**
 *
 * ⚠️ JUnit 4 exécuté par le moteur *vintage*. Les méthodes doivent rendre `Unit` : un corps en
 * expression (`fun f() = runBlocking { … }`) fait échouer la classe entière à l'initialisation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AppLockVaultGuardTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun managerWith(session: VaultSessionState, scope: CoroutineScope) = AppLockManager(
        securityStore = SecurityStore(context),
        settings = SettingsRepository(context, scope),
        kdf = PasswordKdf(),
        vaultSession = session,
        // v1.28.3 (F07) — le garde d'abaissement du verrouillage a besoin de savoir si le Coffre
        // s'appuie sur la biometrie. Ces tests-ci portent sur `forceLock`, qui ne l'emprunte pas :
        // les deux doublures ne sont donc jamais resolues, et `error(...)` le PROUVE — si un
        // futur correctif faisait passer `forceLock` par ce chemin, le test le dirait.
        vaultFactor = { error("vaultFactor ne doit pas etre resolu par forceLock") },
        conversationDao = { error("conversationDao ne doit pas etre resolu par forceLock") },
        io = Dispatchers.Unconfined,
    )

    /**
     * 🔴 **Le Coffre survivait au verrouillage de l'application.**
     *
     * `AutoLockObserver` ne verrouillait le Coffre que si `lockVaultOnLeave` était coché — un
     * réglage de **confort**, pour basculer un instant sur une autre application sans redemander
     * le second facteur. Décoché, l'application se verrouillait mais la session du Coffre restait
     * ouverte : quiconque rouvrait ensuite avec le PIN principal trouvait le Coffre déverrouillé.
     * **Le second facteur était contourné.**
     *
     * L'invariant est désormais tenu par `forceLock` lui-même, donc par TOUS ses appelants.
     */
    @Test
    fun `verrouiller l application verrouille aussi le coffre`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val session = VaultSessionState()
            val manager = managerWith(session, scope)
            // Second facteur déjà franchi dans cette session.
            session.markUnlocked()
            assertThat(session.isUnlocked).isTrue()

            manager.forceLock()

            assertThat(session.isUnlocked).isFalse()
        } finally {
            scope.cancel()
        }
    }

    /**
     * 🔴 **Évasion du mode leurre par écrasement du code principal.**
     *
     * `clearPin` et `clearPanicCode` refusent tous deux d'agir en session leurre — et le
     * commentaire de `clearPin` explique pourquoi : « masquer un écran est une énumération, le
     * vrai garde est ici ». Ce garde manquait **précisément sur `setPin`** : le jumeau
     * asymétrique.
     *
     * Sans lui, l'agresseur qui atteint l'écran de changement de code depuis la session leurre
     * écrase le code principal, verrouille, rouvre avec le SIEN, et sort définitivement du
     * leurre — vraie session, et le Coffre avec.
     *
     * Ce test suit le scénario complet plutôt que d'inspecter un état interne : il vérifie que le
     * code de l'agresseur **n'ouvre rien** et que celui de l'utilisateur légitime **ouvre encore**.
     */
    @Test
    fun `changer le code depuis une session leurre n ecrase pas le vrai code`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val manager = managerWith(VaultSessionState(), scope)
                assertThat(manager.setPin("1111".toCharArray()))
                    .isEqualTo(AppLockManager.SetPinOutcome.Ok)
                manager.setPanicCode("2222".toCharArray())

                // L'utilisateur est sous contrainte : il ouvre avec son code panique.
                manager.forceLock()
                val decoy = manager.attemptUnlock("2222".toCharArray())
                assertThat(decoy).isEqualTo(AppLockManager.LockState.PanicDecoy)

                // L'agresseur tente de s'approprier l'application.
                manager.setPin("3333".toCharArray())
                manager.forceLock()

                // Son code n'ouvre RIEN : il reste enfermé dehors.
                assertThat(manager.attemptUnlock("3333".toCharArray()))
                    .isNotEqualTo(AppLockManager.LockState.Unlocked)
                // Et le code de l'utilisateur legitime ouvre toujours sa vraie session.
                manager.forceLock()
                assertThat(manager.attemptUnlock("1111".toCharArray()))
                    .isEqualTo(AppLockManager.LockState.Unlocked)
            } finally {
                scope.cancel()
            }
        }
    }

    // ───── v1.28.5 (sixième note d'Andrew, point 4) — le garde d'abaissement échoue FERMÉ ─────

    /** Même constructeur, mais les deux doublures du garde d'abaissement sont fournies par le cas. */
    private fun managerAvecCoffre(
        scope: CoroutineScope,
        dao: com.filestech.sms.data.local.db.dao.ConversationDao,
        facteur: () -> VaultSecondFactorPolicy = { error("vaultFactor ne doit pas etre atteint") },
    ) = AppLockManager(
        securityStore = SecurityStore(context),
        settings = SettingsRepository(context, scope),
        kdf = PasswordKdf(),
        vaultSession = VaultSessionState(),
        vaultFactor = { facteur() },
        conversationDao = { dao },
        io = Dispatchers.Unconfined,
    )

    /**
     * `countInVault()` lève : en 1.28.4, `getOrDefault(0)` en faisait « coffre vide », la seule
     * lecture qui AUTORISE l'abaissement — obtenue sans avoir rien lu. Le garde refuse désormais,
     * et le code de l'utilisateur ouvre toujours : rien n'a été retiré.
     */
    @Test
    fun `abaisser le verrou echoue ferme quand le coffre ne se lit pas`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val dao = mockk<com.filestech.sms.data.local.db.dao.ConversationDao> {
                    coEvery { countInVault() } throws IllegalStateException("BASE_INDISPONIBLE")
                }
                val manager = managerAvecCoffre(scope, dao)
                manager.setPin("1111".toCharArray())

                assertThat(manager.clearPin())
                    .isEqualTo(AppLockManager.LockDowngradeOutcome.VaultStateUnknown)

                manager.forceLock()
                assertThat(manager.attemptUnlock("1111".toCharArray()))
                    .isEqualTo(AppLockManager.LockState.Unlocked)
            } finally {
                scope.cancel()
            }
        }
    }

    /** Coffre non vide et facteur illisible : `getOrNull()` en faisait « pas de biométrie », donc `Ok`. */
    @Test
    fun `abaisser le verrou echoue ferme quand le second facteur ne se lit pas`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val dao = mockk<com.filestech.sms.data.local.db.dao.ConversationDao> {
                    coEvery { countInVault() } returns 3
                }
                val manager = managerAvecCoffre(scope, dao) { error("POLITIQUE_INDISPONIBLE") }
                manager.setPin("1111".toCharArray())

                assertThat(manager.clearPin())
                    .isEqualTo(AppLockManager.LockDowngradeOutcome.VaultStateUnknown)
            } finally {
                scope.cancel()
            }
        }
    }

    /** Contrôle positif : un coffre lu VIDE autorise l'abaissement — sinon les deux tests ci-dessus ne prouvent rien. */
    @Test
    fun `abaisser le verrou passe quand le coffre est lu vide`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val dao = mockk<com.filestech.sms.data.local.db.dao.ConversationDao> {
                    coEvery { countInVault() } returns 0
                }
                val manager = managerAvecCoffre(scope, dao)
                manager.setPin("1111".toCharArray())

                assertThat(manager.clearPin()).isEqualTo(AppLockManager.LockDowngradeOutcome.Ok)
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * Non-vacuité du test précédent : hors session leurre, changer le code **doit** fonctionner.
     * Sans cette garde, un `setPin` qui ne ferait jamais rien passerait le test ci-dessus.
     */
    @Test
    fun `changer le code hors session leurre fonctionne`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val manager = managerWith(VaultSessionState(), scope)
                manager.setPin("4444".toCharArray())
                manager.forceLock()
                manager.setPin("5555".toCharArray())

                manager.forceLock()
                assertThat(manager.attemptUnlock("5555".toCharArray()))
                    .isEqualTo(AppLockManager.LockState.Unlocked)
                manager.forceLock()
                assertThat(manager.attemptUnlock("4444".toCharArray()))
                    .isNotEqualTo(AppLockManager.LockState.Unlocked)
            } finally {
                scope.cancel()
            }
        }
    }
}
