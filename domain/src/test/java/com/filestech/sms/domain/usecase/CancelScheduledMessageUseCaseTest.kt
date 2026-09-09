package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (F20) — **une annulation qui ne prend pas doit le dire.**
 *
 * Le verdict existait depuis la v1.26.1, où il décidait du sort des pièces jointes ; il était
 * ensuite jeté, ce use case rendant `Outcome.Success(Unit)` dans les deux cas. Or l'écran
 * « Messages programmés » affiche un bouton « Annuler » sur toute ligne en attente — l'état
 * `SENDING` compris, puisqu'il se projette sur `PENDING` côté domaine. Sur celles-là,
 * l'utilisateur confirmait dans une boîte de dialogue, ne voyait rien changer, et recommençait.
 *
 * Les deux moitiés du contrat sont verrouillées ici : le `false` remonte, et le `true` aussi —
 * sans le second, un correctif qui renverrait `false` en toutes circonstances passerait pour bon.
 */
class CancelScheduledMessageUseCaseTest {

    private class FakeScheduler : ScheduledMessageScheduler {
        var cancelled = mutableListOf<Long>()
        override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
        override fun cancel(scheduledMessageId: Long) {
            cancelled += scheduledMessageId
        }
    }

    /**
     * Fake écrit à la main : le module `domain` n'a ni mockk ni `kotlinx-coroutines-test`, et
     * c'est très bien ainsi — un use case qui a besoin d'une bibliothèque de mocks pour être
     * testé a en général trop de collaborateurs.
     */
    private class FakeRepo(private val cancelResult: Outcome<Boolean>) : ScheduledMessageRepository {
        var attachmentsCleared = 0

        override fun observePending(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override fun observeFailed(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override suspend fun allUnsettled(): List<ScheduledMessage> = emptyList()
        override suspend fun schedule(
            conversationId: Long?,
            addresses: List<PhoneAddress>,
            body: String,
            scheduledAt: Long,
            subId: Int?,
            attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
        ): Outcome<Long> = Outcome.Success(1L)

        override suspend fun cancel(id: Long): Outcome<Boolean> = cancelResult
        override suspend fun markSent(id: Long) = Unit
        override suspend fun markFailed(id: Long) = Unit
        override suspend fun rearmPending(id: Long, scheduledAt: Long) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deleteWithAttachments(id: Long) = Unit
        override suspend fun clearAttachments(id: Long) {
            attachmentsCleared++
        }
    }

    @Test
    fun `une annulation qui prend rend true et efface les pieces jointes`() {
        val repo = FakeRepo(Outcome.Success(true))
        val scheduler = FakeScheduler()

        val outcome = runBlocking { CancelScheduledMessageUseCase(repo, scheduler).invoke(ID) }

        assertThat(outcome).isEqualTo(Outcome.Success(true))
        assertThat(repo.attachmentsCleared).isEqualTo(1)
        assertThat(scheduler.cancelled).containsExactly(ID)
    }

    /**
     * Le cœur de F20 : l'envoi a déjà été revendiqué par le worker, la ligne est `SENDING`, et
     * `cancelIfPending` ne matche que `PENDING`. L'appelant doit l'apprendre.
     */
    @Test
    fun `une annulation trop tardive rend false`() {
        val repo = FakeRepo(Outcome.Success(false))

        val outcome = runBlocking {
            CancelScheduledMessageUseCase(repo, FakeScheduler()).invoke(ID)
        }

        assertThat(outcome).isEqualTo(Outcome.Success(false))
    }

    /**
     * Et les fichiers restent en place : le worker est peut-être en train de construire son PDU
     * dessus. C'est le garde posé en v1.26.1, qui ne doit pas tomber avec ce correctif-ci.
     */
    @Test
    fun `une annulation trop tardive ne touche pas aux pieces jointes`() {
        val repo = FakeRepo(Outcome.Success(false))

        runBlocking { CancelScheduledMessageUseCase(repo, FakeScheduler()).invoke(ID) }

        assertThat(repo.attachmentsCleared).isEqualTo(0)
    }

    private companion object {
        const val ID = 7L
    }
}
