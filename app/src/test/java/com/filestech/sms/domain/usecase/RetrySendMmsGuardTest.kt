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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global B-1) — **la bulle rouge d'un MMS ne se relance pas par le chemin SMS.**
 *
 * `RetrySendUseCase` ne connaît que [SmsSender]. Un MMS en échec arrivait ici comme n'importe
 * quelle autre ligne : la légende repartait en SMS texte, sans la pièce jointe, et la ligne
 * pouvait passer « envoyé » sous une vignette jamais partie. `SECURITY.md` le documentait depuis
 * la v1.3.9. Ce fichier prouve la garde, et — c'est la moitié qui compte — qu'elle est posée
 * AVANT toute écriture : rien n'est rétrogradé, rien ne part.
 *
 * Dans `:app` parce que `:domain` n'a pas mockk et que [ConversationRepository] est trop large
 * pour une doublure à la main.
 */
class RetrySendMmsGuardTest {

    private val repo = mockk<ConversationRepository>()
    private val sender = mockk<SmsSender>()
    private val mirror = mockk<OutgoingMessageMirror>()
    private val blocked = mockk<BlockedNumberRepository>()

    private fun useCase() = RetrySendUseCase(repo, sender, mirror, blocked)

    private fun ligne(type: Message.Type) = Message(
        id = 42L,
        conversationId = 1L,
        address = "+33611111111",
        body = "légende",
        type = type,
        direction = Message.Direction.OUTGOING,
        date = 0L,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.FAILED,
        errorCode = SendErrorCode.SYNCHRONOUS,
        attachmentsCount = if (type == Message.Type.MMS) 1 else 0,
        subId = null,
        scheduledAt = null,
    )

    @Test
    fun `un MMS en echec est refuse, typiquement, sans rien ecrire ni envoyer`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligne(Message.Type.MMS)
        coEvery { blocked.isBlocked(any()) } returns false

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Failure(AppError.MmsRetryUnsupported))
        coVerify(exactly = 0) { mirror.resetOutgoingForRetry(any()) }
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Contrôle POSITIF, sans lequel le précédent ne prouverait rien : une garde qui refuserait
     * tout passerait le test ci-dessus et tuerait la relance des SMS.
     */
    @Test
    fun `un SMS en echec repart par le meme chemin`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligne(Message.Type.SMS)
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns 2
        every { sender.send(42L, "+33611111111", "légende", null, any(), attempt = 2) } returns
            Outcome.Success(Unit)

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Success(Unit))
        coVerify(exactly = 1) { mirror.resetOutgoingForRetry(42L) }
    }
}
