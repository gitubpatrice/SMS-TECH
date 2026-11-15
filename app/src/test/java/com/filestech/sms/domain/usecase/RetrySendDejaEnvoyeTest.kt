package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SmsSender
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1, relecture Claude) — **un message devenu « envoyé » pendant l'avertissement de doublon
 * ne repart pas.**
 *
 * Un vrai accusé remplace désormais l'échec supposé du chien de garde. Il peut donc arriver entre l'ouverture
 * de l'avertissement (« ce message a peut-être été reçu ») et sa confirmation. La ligne n'est alors plus en
 * échec, la nouvelle tentative ne s'ouvre pas (`resetOutgoingForRetry` rend `null`), et rien ne doit partir.
 * Ce n'est pas une erreur à afficher : la bulle dit déjà « envoyé ». Ligne disparue : erreur, comme avant.
 */
class RetrySendDejaEnvoyeTest {

    private val repo = mockk<ConversationRepository>()
    private val sender = mockk<SmsSender>()
    private val mirror = mockk<OutgoingMessageMirror>()
    private val blocked = mockk<BlockedNumberRepository>()

    private fun useCase() = RetrySendUseCase(
        conversationRepo = repo,
        sender = sender,
        mirror = mirror,
        blockedRepo = blocked,
        simDEnvoi = SimDEnvoi(mockk(relaxed = true), kotlinx.coroutines.Dispatchers.Unconfined),
    )

    private val ligneDuChienDeGarde = Message(
        id = 42L,
        conversationId = 1L,
        address = "+33611111111",
        body = "bonjour",
        type = Message.Type.SMS,
        direction = Message.Direction.OUTGOING,
        date = 0L,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.FAILED,
        errorCode = SendErrorCode.WATCHDOG_TIMEOUT,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    @Test
    fun `un message passe envoye entre-temps ne repart pas, sans erreur`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligneDuChienDeGarde
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns null
        coEvery { mirror.outgoingStatus(42L) } returns MessageStatus.SENT

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Success(Unit))
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }

    /** Relecture GPT : de nouveau en échec à la relecture, ce n'est pas un succès. */
    @Test
    fun `une ligne redevenue en echec entre-temps n'est pas annoncee comme un succes`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligneDuChienDeGarde
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns null
        coEvery { mirror.outgoingStatus(42L) } returns MessageStatus.FAILED

        val issue = useCase().invoke(42L)

        assertThat(issue).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `une ligne disparue reste une erreur`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligneDuChienDeGarde
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns null
        coEvery { mirror.outgoingStatus(42L) } returns null

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Failure(AppError.NotFound("message")))
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }
}
