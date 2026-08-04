package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.27.2 — verrouille les gardes du **coffre**, corrigées le 2026-08-04.
 *
 * Ces quatre défauts partageaient un motif : la garde était posée sur l'ÉCRAN et pas sur l'ACCÈS.
 * L'interface masquait, refusait, redirigeait — mais la fonction sous-jacente restait appelable et
 * ne vérifiait rien. Un futur refactor de l'interface les rouvrirait sans qu'aucun test ne bronche.
 * D'où ces assertions, posées au niveau de [VaultManager], hors de toute UI.
 *
 * Ce que chacune protège :
 *  - **déplacer VERS le coffre n'ouvre pas la session** — l'auto-déverrouillage datait d'avant
 *    l'existence du code du coffre et le contournait donc entièrement ;
 *  - **en SORTIR exige la session** — sortir révèle du contenu protégé, seule la variante
 *    non groupée portait la garde ;
 *  - **la variante groupée honore `LockedOut`** — elle énumérait les états et en oubliait un,
 *    exactement le défaut que la v1.26.1 avait fermé sur sa jumelle ;
 *  - **la session leurre refuse tout** — protection historique, ré-affirmée ici.
 */
class VaultGuardsTest {

    private val io = UnconfinedTestDispatcher()
    private val keystore = mockk<KeystoreManager>(relaxed = true)
    private val repo = mockk<ConversationRepository>(relaxed = true)

    /**
     * `AppLockManager` est une classe concrète adossée à DataStore : non instanciable hors
     * appareil. On la simule, mais `isOpenForUi` **appelle l'implémentation réelle** — c'est le
     * prédicat qui fait autorité, le dupliquer ici rendrait le test complaisant.
     */
    private fun lockManager(state: AppLockManager.LockState): AppLockManager =
        mockk<AppLockManager>().also { m ->
            every { m.state } returns MutableStateFlow(state)
            every { m.isOpenForUi(any()) } answers { callOriginal() }
        }

    private fun vault(
        state: AppLockManager.LockState,
        sessionUnlocked: Boolean,
    ): Pair<VaultManager, VaultSessionState> {
        // Le porteur de session est le VRAI : c'est lui l'objet du test.
        val session = VaultSessionState().apply { if (sessionUnlocked) markUnlocked() }
        return VaultManager(keystore, repo, lockManager(state), session, io) to session
    }

    // ──────────── Entrer dans le coffre n'ouvre pas la session ────────────

    @Test fun `moving INTO the vault does not unlock the session`() = runTest {
        val (manager, session) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        val outcome = manager.requestMoveToVault(1L, intoVault = true)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // LE défaut de la v1.27.2 : cet appel posait `markUnlocked()`, si bien qu'ouvrir le
        // Coffre juste après ne demandait plus ni code ni biométrie.
        assertThat(session.isUnlocked).isFalse()
    }

    @Test fun `bulk moving INTO the vault does not unlock the session either`() = runTest {
        val (manager, session) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        val outcome = manager.requestBulkMoveToVault(listOf(1L, 2L), intoVault = true)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // Le jumeau : le rapport externe n'avait cité que la variante non groupée.
        assertThat(session.isUnlocked).isFalse()
    }

    // ──────────── En sortir exige le second facteur ────────────

    @Test fun `moving OUT of the vault is refused when the session is locked`() = runTest {
        val (manager, _) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        val outcome = manager.requestMoveToVault(1L, intoVault = false)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat((outcome as Outcome.Failure).error).isInstanceOf(AppError.Locked::class.java)
    }

    @Test fun `bulk moving OUT of the vault is refused when the session is locked`() = runTest {
        val (manager, _) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        val outcome = manager.requestBulkMoveToVault(listOf(1L), intoVault = false)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `moving OUT succeeds once the vault session is open`() = runTest {
        val (manager, _) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = true)

        assertThat(manager.requestMoveToVault(1L, intoVault = false))
            .isInstanceOf(Outcome.Success::class.java)
        assertThat(manager.requestBulkMoveToVault(listOf(1L), intoVault = false))
            .isInstanceOf(Outcome.Success::class.java)
    }

    // ──────────── États de verrouillage refusés ────────────

    @Test fun `LockedOut is refused by BOTH variants`() = runTest {
        // La variante groupée énumérait `PanicDecoy` et `Locked` et laissait `LockedOut` — un
        // blocage après trop de tentatives — tomber dans le `else`. C'est très exactement le
        // défaut que la v1.26.1 avait fermé sur la variante non groupée, jamais porté ici.
        val lockedOut = AppLockManager.LockState.LockedOut(until = 1L)
        val (single, _) = vault(lockedOut, sessionUnlocked = true)
        val (bulk, _) = vault(lockedOut, sessionUnlocked = true)

        assertThat(single.requestMoveToVault(1L, intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
        assertThat(bulk.requestBulkMoveToVault(listOf(1L), intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `Locked is refused by BOTH variants`() = runTest {
        val (single, _) = vault(AppLockManager.LockState.Locked, sessionUnlocked = true)
        val (bulk, _) = vault(AppLockManager.LockState.Locked, sessionUnlocked = true)

        assertThat(single.requestMoveToVault(1L, intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
        assertThat(bulk.requestBulkMoveToVault(listOf(1L), intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `panic decoy is refused even with an open vault session`() = runTest {
        // Un agresseur en session leurre ne doit pas pouvoir cacher en masse les conversations
        // légitimes derrière un coffre que la victime ne rouvrira pas.
        val (single, _) = vault(AppLockManager.LockState.PanicDecoy, sessionUnlocked = true)
        val (bulk, _) = vault(AppLockManager.LockState.PanicDecoy, sessionUnlocked = true)

        assertThat(single.requestMoveToVault(1L, intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
        assertThat(bulk.requestBulkMoveToVault(listOf(1L), intoVault = true))
            .isInstanceOf(Outcome.Failure::class.java)
    }

    // ──────────── Les variantes brutes exigent la session, dans les deux sens ────────────

    @Test fun `raw move in and out both require an open session`() = runTest {
        val (locked, _) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        assertThat(locked.moveToVault(1L)).isInstanceOf(Outcome.Failure::class.java)
        assertThat(locked.moveOutOfVault(1L)).isInstanceOf(Outcome.Failure::class.java)

        val (open, _) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = true)
        assertThat(open.moveToVault(1L)).isInstanceOf(Outcome.Success::class.java)
        assertThat(open.moveOutOfVault(1L)).isInstanceOf(Outcome.Success::class.java)
    }

    // ──────────── Cas limite ────────────

    @Test fun `an empty bulk move is a no-op success`() = runTest {
        val (manager, session) = vault(AppLockManager.LockState.Unlocked, sessionUnlocked = false)

        val outcome = manager.requestBulkMoveToVault(emptyList(), intoVault = false)

        // Sortie anticipée AVANT toute garde : elle ne doit ni échouer, ni ouvrir la session.
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(session.isUnlocked).isFalse()
    }
}
