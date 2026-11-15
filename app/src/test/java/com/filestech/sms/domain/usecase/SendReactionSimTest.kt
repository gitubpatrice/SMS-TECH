package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-10 (v1.30.1) — **une réaction part par la SIM du fil.**
 *
 * Deux relectures externes ont cru la SIM perdue en route. Elle ne l'est pas : la réaction passe par
 * [SendSmsUseCase], qui donne la même SIM au miroir et à l'expéditeur. Ce test le tient, sur la SIM réellement
 * transmise à l'envoi, et non sur la copie locale.
 */
class SendReactionSimTest {

    private val recu = Message(
        id = 5L,
        conversationId = 7L,
        address = "+33612345678",
        body = "Salut",
        type = Message.Type.SMS,
        direction = Message.Direction.INCOMING,
        date = 1_000L,
        dateSent = 1_000L,
        read = true,
        starred = false,
        status = Message.Status.RECEIVED,
        errorCode = null,
        attachmentsCount = 0,
        subId = 11,
        scheduledAt = null,
    )

    @Test
    fun `la reaction part par la SIM donnee par le fil`() = runTest {
        val repo = mockk<ConversationRepository>()
        coEvery { repo.findMessageById(5L) } returns recu
        val sendSms = mockk<SendSmsUseCase>()
        coEvery { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) } returns
            Outcome.Success(SendReport(dispatched = listOf(1L), failed = emptyList(), blocked = emptyList()))

        val issue = SendReactionUseCase(sendSms, repo, mockk(relaxed = true))
            .invoke(5L, "❤️", ReactionFormat.EMOJI_ONLY, subId = 22)

        assertThat(issue).isInstanceOf(Outcome.Success::class.java)
        coVerify(exactly = 1) { sendSms.invoke(any(), "❤️", 22, null, false, "", any()) }
    }
}
