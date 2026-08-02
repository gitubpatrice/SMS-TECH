package com.filestech.sms.system.scheduler

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.domain.model.ScheduledState
import com.filestech.sms.domain.usecase.SendSmsUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Garde anti-régression de l'audit C2 (v1.25.3).
 *
 * Le worker marquait la ligne `FAILED` avant de rendre `Result.retry()`. Au replay, le garde
 * « déjà réglé » la voyait non-`PENDING` et rendait `success()` sans ré-envoyer : le backoff
 * exponentiel ne rejouait jamais rien. Ces tests verrouillent les deux moitiés du contrat —
 * **on ne marque pas `FAILED` tant qu'il reste des tentatives**, et **une ligne laissée
 * `PENDING` est bien reprise au replay**.
 */
class ScheduledSendAttemptTest {

    private val dao = mockk<ScheduledMessageDao>(relaxed = true)
    private val sendSms = mockk<SendSmsUseCase>()

    /**
     * v1.26.0 — le worker aiguille desormais vers le MMS quand l'envoi porte des pieces jointes.
     * Ces tests-ci portent sur des envois SANS piece jointe, donc ce collaborateur ne doit jamais
     * etre sollicite : `relaxed = false` par defaut ferait echouer tout appel inattendu, ce qui
     * verrouille l'aiguillage au passage.
     */
    private val sendMediaMms = mockk<com.filestech.sms.domain.usecase.SendMediaMmsUseCase>()
    private val attempt = ScheduledSendAttempt(dao, sendSms, sendMediaMms)

    private fun entity(state: ScheduledState = ScheduledState.PENDING) = ScheduledMessageEntity(
        id = ID,
        conversationId = null,
        addressesCsv = "+33600000000",
        body = "Bonjour",
        scheduledAt = 1_000L,
        state = state,
        createdAt = 0L,
    )

    /**
     * Sept `any()` = la signature complète de [SendSmsUseCase.invoke]. L'appelant n'en passe que
     * trois : les quatre autres arrivent par le pont statique `invoke$default`, que mockk ne
     * court-circuite pas et qui délègue donc à la surcharge complète, seule interceptée.
     */
    private fun stubSend(result: Outcome<List<Long>>) {
        coEvery { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) } returns result
    }

    private fun failure() = Outcome.Failure(AppError.Telephony("no SIM"))

    @Test
    fun `envoi reussi marque SENT`() = runTest {
        stubSend(Outcome.Success(listOf(42L)))
        coEvery { dao.findById(ID) } returns entity()

        assertThat(attempt(ID, runAttemptCount = 0)).isEqualTo(ScheduledSendAttempt.Verdict.SENT)
        coVerify(exactly = 1) { dao.setState(ID, ScheduledState.SENT) }
        coVerify(exactly = 0) { dao.setState(ID, ScheduledState.FAILED) }
    }

    @Test
    fun `echec avec tentatives restantes ne touche jamais l etat`() = runTest {
        // Le cœur de C2 : la ligne DOIT rester PENDING, sinon le replay la considère réglée.
        stubSend(failure())
        coEvery { dao.findById(ID) } returns entity()

        repeat(ScheduledSendAttempt.MAX_ATTEMPTS - 1) { run ->
            assertThat(attempt(ID, runAttemptCount = run))
                .isEqualTo(ScheduledSendAttempt.Verdict.RETRY)
        }
        coVerify(exactly = 0) { dao.setState(ID, any()) }
    }

    @Test
    fun `echec a la derniere tentative marque FAILED`() = runTest {
        stubSend(failure())
        coEvery { dao.findById(ID) } returns entity()

        val last = ScheduledSendAttempt.MAX_ATTEMPTS - 1
        assertThat(attempt(ID, runAttemptCount = last))
            .isEqualTo(ScheduledSendAttempt.Verdict.GAVE_UP)
        coVerify(exactly = 1) { dao.setState(ID, ScheduledState.FAILED) }
    }

    @Test
    fun `le cycle complet de replays re-envoie bien a chaque tentative`() = runTest {
        // Reproduction de bout en bout du bug : une base qui applique réellement les
        // transitions d'état, et les MAX_ATTEMPTS réveils que WorkManager déclencherait.
        // Avec l'ancien code, le 1er échec écrivait FAILED et les réveils suivants
        // ressortaient sans jamais appeler `sendSms` → un seul envoi tenté au lieu de cinq.
        var stored = entity()
        coEvery { dao.findById(ID) } answers { stored }
        coEvery { dao.setState(ID, any()) } answers { stored = stored.copy(state = secondArg()) }
        stubSend(failure())

        val verdicts = (0 until ScheduledSendAttempt.MAX_ATTEMPTS).map { attempt(ID, it) }

        assertThat(verdicts.dropLast(1)).containsExactlyElementsIn(
            List(ScheduledSendAttempt.MAX_ATTEMPTS - 1) { ScheduledSendAttempt.Verdict.RETRY },
        )
        assertThat(verdicts.last()).isEqualTo(ScheduledSendAttempt.Verdict.GAVE_UP)
        coVerify(exactly = ScheduledSendAttempt.MAX_ATTEMPTS) {
            sendSms.invoke(any(), any(), any(), any(), any(), any(), any())
        }
        assertThat(stored.state).isEqualTo(ScheduledState.FAILED)
    }

    @Test
    fun `un replay sur une ligne deja reglee ne re-envoie jamais`() = runTest {
        // Symétrique du test précédent : le garde doit rester efficace pour SENT / CANCELLED /
        // FAILED — c'est lui qui empêche le double envoi, il ne doit pas sauter avec le correctif.
        for (settled in listOf(ScheduledState.SENT, ScheduledState.CANCELLED, ScheduledState.FAILED)) {
            coEvery { dao.findById(ID) } returns entity(settled)

            assertThat(attempt(ID, runAttemptCount = 0))
                .isEqualTo(ScheduledSendAttempt.Verdict.ALREADY_SETTLED)
        }
        coVerify(exactly = 0) { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.setState(any(), any()) }
    }

    @Test
    fun `un id absent de la base est un echec definitif`() = runTest {
        coEvery { dao.findById(ID) } returns null

        assertThat(attempt(ID, runAttemptCount = 0)).isEqualTo(ScheduledSendAttempt.Verdict.UNKNOWN_ID)
        coVerify(exactly = 0) { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) }
    }

    private companion object {
        const val ID = 7L
    }
}
