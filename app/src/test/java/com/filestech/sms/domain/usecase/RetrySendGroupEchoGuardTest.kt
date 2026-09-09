package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
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
 * v1.28.3 (groupes) — **la copie gardée dans un fil de groupe ne se renvoie pas d'ici.** Son
 * adresse est la liste des membres : rien n'a jamais été envoyé à « a;b », rien ne doit l'être.
 */
class RetrySendGroupEchoGuardTest {

    private val repo = mockk<ConversationRepository>()
    private val sender = mockk<SmsSender>()
    private val mirror = mockk<OutgoingMessageMirror>()
    private val blocked = mockk<BlockedNumberRepository>()

    @Test
    fun `l'echo d'un groupe est refuse, typiquement, sans rien ecrire ni envoyer`() = runTest {
        coEvery { repo.findMessageForResend(7L) } returns Message(
            id = 7L,
            conversationId = 1L,
            address = "0617332729;0607231541",
            body = "Test groupe",
            type = Message.Type.SMS,
            direction = Message.Direction.OUTGOING,
            date = 0L,
            dateSent = null,
            read = true,
            starred = false,
            status = Message.Status.FAILED,
            errorCode = SendErrorCode.SYNCHRONOUS,
            attachmentsCount = 0,
            subId = null,
            scheduledAt = null,
        )
        coEvery { blocked.isBlocked(any()) } returns false

        val issue = RetrySendUseCase(repo, sender, mirror, blocked).invoke(7L)

        assertThat(issue).isEqualTo(Outcome.Failure(AppError.GroupEchoRetryUnsupported))
        coVerify(exactly = 0) { mirror.resetOutgoingForRetry(any()) }
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }
}
