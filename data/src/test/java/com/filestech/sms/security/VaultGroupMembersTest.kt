package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global, X-01 — **mesuré sur le S9**) — **mettre un groupe au coffre met ses
 * membres au coffre, et l'en sortir les en sort.**
 *
 * Le SMS n'a pas de groupe : ce qu'on écrit depuis un fil de groupe vit dans les 1-à-1 de ses
 * membres, et leurs réponses aussi. Un groupe au coffre dont les membres restent dehors laissait
 * le texte écrit DEPUIS le coffre en aperçu dans la liste ouverte.
 */
class VaultGroupMembersTest {

    private val io = UnconfinedTestDispatcher()
    private val repo = mockk<ConversationRepository>(relaxed = true)

    private fun conversation(id: Long, vararg adresses: String) = Conversation(
        id = id,
        threadId = null,
        addresses = adresses.map { PhoneAddress.of(it) },
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun vault(): VaultManager {
        val lock = mockk<AppLockManager>().also { m ->
            every { m.state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
            every { m.isOpenForUi(any()) } answers { callOriginal() }
        }
        val session = VaultSessionState().apply { markUnlocked() }
        return VaultManager(mockk<KeystoreManager>(relaxed = true), repo, lock, session, io, VaultPurgeBarrier())
    }

    @Test
    fun `un groupe entre au coffre avec les 1-a-1 de ses membres, en un seul deplacement`() = runTest {
        coEvery { repo.findById(10L) } returns conversation(10L, "0617332729", "0607231541")
        coEvery { repo.findOrCreate(listOf(PhoneAddress.of("0617332729"))) } returns
            Outcome.Success(conversation(21L, "0617332729"))
        coEvery { repo.findOrCreate(listOf(PhoneAddress.of("0607231541"))) } returns
            Outcome.Success(conversation(22L, "0607231541"))
        val ids = slot<List<Long>>()
        coEvery { repo.bulkMoveToVault(capture(ids), true) } returns 3

        val issue = vault().requestMoveToVault(10L, intoVault = true)

        assertThat(issue).isEqualTo(Outcome.Success(Unit))
        assertThat(ids.captured).containsExactly(10L, 21L, 22L).inOrder()
    }

    /** Le sens inverse est symétrique : sortir le groupe sort ses membres. */
    @Test
    fun `sortir un groupe du coffre en sort ses membres`() = runTest {
        coEvery { repo.findById(10L) } returns conversation(10L, "0617332729", "0607231541")
        coEvery { repo.findOrCreate(any()) } answers {
            val a = firstArg<List<PhoneAddress>>().first()
            Outcome.Success(conversation(if (a.raw == "0617332729") 21L else 22L, a.raw))
        }
        val ids = slot<List<Long>>()
        coEvery { repo.bulkMoveToVault(capture(ids), false) } returns 3

        vault().moveOutOfVault(10L)

        assertThat(ids.captured).containsExactly(10L, 21L, 22L)
    }

    /** Contrôle POSITIF : une 1-à-1 se déplace seule, rien n'est créé. */
    @Test
    fun `une conversation 1-a-1 se deplace seule`() = runTest {
        coEvery { repo.findById(7L) } returns conversation(7L, "0617332729")
        val ids = slot<List<Long>>()
        coEvery { repo.bulkMoveToVault(capture(ids), true) } returns 1

        vault().moveToVault(7L)

        assertThat(ids.captured).containsExactly(7L)
        coVerify(exactly = 0) { repo.findOrCreate(any()) }
    }
}
