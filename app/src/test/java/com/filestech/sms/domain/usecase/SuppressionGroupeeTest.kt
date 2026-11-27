package com.filestech.sms.domain.usecase

import com.filestech.sms.domain.repository.ConversationDeleteResult
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase.BilanSuppression
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0) — **une sélection supprimée l'est conversation par conversation**, chacune avec ses règles de
 * conservation, et le bilan compte celles qui restent, quelle qu'en soit la raison. Une exception sur l'une ne doit
 * pas épargner les suivantes ; une annulation, elle, n'est pas avalée.
 */
class SuppressionGroupeeTest {

    private val repo = mockk<ConversationRepository>()
    private val cas = ToggleConversationStateUseCase(repo, mockk())

    @Test
    fun `le bilan compte les supprimees et les conservees, quelle qu'en soit la raison`() = runTest {
        coEvery { repo.delete(1L) } returns ConversationDeleteResult.DELETED
        coEvery { repo.delete(2L) } returns ConversationDeleteResult.KEPT_SYSTEM_COPY
        coEvery { repo.delete(3L) } returns ConversationDeleteResult.KEPT_LOCAL_FAILURE
        coEvery { repo.delete(4L) } returns ConversationDeleteResult.DELETED

        // 2026-11-27 — et, parmi les conservées, celles que leur copie système a retenues (le Coffre le dit).
        assertThat(cas.deleteAll(listOf(1L, 2L, 3L, 4L)))
            .isEqualTo(BilanSuppression(supprimees = 2, conservees = 2, parCopieSysteme = 1))
    }

    @Test
    fun `une exception compte comme une conservee et n'arrete pas les suivantes`() = runTest {
        coEvery { repo.delete(1L) } throws IllegalStateException("base indisponible")
        coEvery { repo.delete(2L) } returns ConversationDeleteResult.DELETED

        assertThat(cas.deleteAll(listOf(1L, 2L))).isEqualTo(BilanSuppression(supprimees = 1, conservees = 1))
        coVerify(exactly = 1) { repo.delete(2L) }
    }

    @Test
    fun `une annulation n'est pas avalee`() = runTest {
        coEvery { repo.delete(1L) } throws CancellationException("ecran ferme")
        coEvery { repo.delete(2L) } returns ConversationDeleteResult.DELETED

        val issue = runCatching { cas.deleteAll(listOf(1L, 2L)) }

        assertThat(issue.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { repo.delete(2L) }
    }

    @Test
    fun `archiver une selection passe par une seule ecriture groupee`() = runTest {
        coEvery { repo.bulkSetArchived(listOf(1L, 2L), true) } returns 2

        assertThat(cas.setArchivedAll(listOf(1L, 2L), archived = true)).isEqualTo(2)
        coVerify(exactly = 0) { repo.setArchived(any(), any()) }
    }
}
