package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * v1.28.4 (F13) — **on n'entre pas au coffre pendant qu'on le vide.**
 *
 * La relecture d'après-boucle (F09) ne protège pas contre une conversation qui ENTRE au coffre
 * entre cette relecture et le retrait du PIN. La barrière ferme cette porte ; ces cas prouvent
 * qu'elle la ferme, qu'elle laisse la sortie ouverte, et qu'elle retombe quoi qu'il arrive.
 */
class VaultPurgeBarrierTest {

    private val io = UnconfinedTestDispatcher()
    private val repo = mockk<ConversationRepository>(relaxed = true)
    private val barriere = VaultPurgeBarrier()

    private fun vault(): VaultManager {
        val lock = mockk<AppLockManager>().also { m ->
            every { m.state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
            every { m.isOpenForUi(any()) } answers { callOriginal() }
        }
        val session = VaultSessionState().apply { markUnlocked() }
        return VaultManager(mockk<KeystoreManager>(relaxed = true), repo, lock, session, io, barriere)
    }

    @Test
    fun `pendant une purge, entrer au coffre est refuse et rien n'est ecrit`() = runTest {
        barriere.pendant {
            val seul = vault().requestMoveToVault(1L, intoVault = true)
            val groupe = vault().requestBulkMoveToVault(listOf(1L, 2L), intoVault = true)
            val direct = vault().moveToVault(3L)

            assertThat(seul).isEqualTo(Outcome.Failure(AppError.VaultPurging))
            assertThat(groupe).isEqualTo(Outcome.Failure(AppError.VaultPurging))
            assertThat(direct).isEqualTo(Outcome.Failure(AppError.VaultPurging))
        }
        coVerify(exactly = 0) { repo.bulkMoveToVault(any(), true) }
    }

    /** Sortir du coffre pendant la purge reste permis : ça ne peut que réduire ce qu'elle doit détruire. */
    @Test
    fun `pendant une purge, sortir du coffre reste permis`() = runTest {
        barriere.pendant {
            assertThat(vault().requestMoveToVault(1L, intoVault = false)).isEqualTo(Outcome.Success(Unit))
        }
        coVerify(exactly = 1) { repo.bulkMoveToVault(listOf(1L), false) }
    }

    /** Contrôle POSITIF : hors purge, l'entrée passe — sinon le premier test ne prouve rien. */
    @Test
    fun `hors purge, entrer au coffre passe`() = runTest {
        assertThat(vault().requestMoveToVault(1L, intoVault = true)).isEqualTo(Outcome.Success(Unit))
        coVerify(exactly = 1) { repo.bulkMoveToVault(listOf(1L), true) }
    }

    /** La barrière retombe même quand la purge lève : sinon le coffre resterait fermé aux entrées jusqu'au redémarrage. */
    @Test
    fun `la barriere retombe meme quand la purge leve`() = runTest {
        assertThrows<IllegalStateException> {
            barriere.pendant { error("purge en echec") }
        }
        assertThat(barriere.enCours).isFalse()
        assertThat(vault().requestMoveToVault(1L, intoVault = true)).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun `deux purges ne s'entrelacent pas`() = runTest {
        barriere.pendant {
            assertThrows<IllegalStateException> { barriere.pendant { } }
        }
        assertThat(barriere.enCours).isFalse()
    }
}
