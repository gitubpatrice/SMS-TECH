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

    private fun entity(
        state: ScheduledState = ScheduledState.PENDING,
        claimedAt: Long? = null,
    ) = ScheduledMessageEntity(
        id = ID,
        conversationId = null,
        addressesCsv = "+33600000000",
        body = "Bonjour",
        scheduledAt = 1_000L,
        state = state,
        createdAt = 0L,
        claimedAt = claimedAt,
    )

    /**
     * Sept `any()` = la signature complète de [SendSmsUseCase.invoke]. L'appelant n'en passe que
     * trois : les quatre autres arrivent par le pont statique `invoke$default`, que mockk ne
     * court-circuite pas et qui délègue donc à la surcharge complète, seule interceptée.
     */
    private fun stubSend(result: Outcome<com.filestech.sms.domain.model.SendReport>) {
        coEvery { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) } returns result
    }

    private fun failure() = Outcome.Failure(AppError.Telephony("no SIM"))

    /** v1.28.3 (F21) — un envoi qui atteint son unique destinataire. */
    private fun rapportComplet() = com.filestech.sms.domain.model.SendReport(
        dispatched = listOf(42L),
        failed = emptyList(),
        blocked = emptyList(),
    )

    /**
     * v1.26.1 (audit H6) — la revendication atomique `PENDING -> SENDING` precede desormais tout
     * envoi. Par defaut on la fait reussir : les tests ci-dessous portent sur ce qui suit.
     */
    @org.junit.jupiter.api.BeforeEach
    fun claimSucceedsByDefault() {
        coEvery { dao.claimForSending(ID, any()) } returns 1
    }

    @Test
    fun `envoi reussi marque SENT`() = runTest {
        stubSend(Outcome.Success(rapportComplet()))
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
        coVerify(exactly = ScheduledSendAttempt.MAX_ATTEMPTS - 1) {
            dao.setState(ID, ScheduledState.PENDING)
        }
        coVerify(exactly = 0) { dao.setState(ID, ScheduledState.FAILED) }
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

    // ------------------------------------------------------------------------------------------
    // v1.28.3 (F20) — l'état `SENDING` n'est plus une impasse.
    //
    // Il tombait dans `ALREADY_SETTLED`, que le worker traduit en `Result.success()` : une
    // exécution morte en vol laissait la ligne `SENDING` **définitivement**. Elle restait
    // affichée en attente avec une échéance passée, son bouton « Annuler » ne pouvait rien
    // contre elle, et « Échecs » ne la voyait pas.
    //
    // Les deux moitiés du contrat à verrouiller : **on conclut** quand le bail a expiré, et **on
    // ne renvoie JAMAIS** — ni dans un cas, ni dans l'autre, l'issue étant inconnue.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `un envoi revendique dont le bail court encore est laisse tranquille`() = runTest {
        val now = 1_000_000_000L
        coEvery { dao.findById(ID) } returns
            entity(ScheduledState.SENDING, claimedAt = now - ScheduledSendAttempt.SEND_LEASE_MS + 1)
        coEvery { dao.markInterruptedIfStale(ID, any()) } returns 0

        assertThat(attempt(ID, runAttemptCount = 0, now = now))
            .isEqualTo(ScheduledSendAttempt.Verdict.IN_FLIGHT)
        coVerify(exactly = 0) { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.setState(any(), any()) }
    }

    @Test
    fun `un envoi revendique dont le bail a expire est conclu INTERRUPTED`() = runTest {
        val now = 1_000_000_000L
        coEvery { dao.findById(ID) } returns
            entity(ScheduledState.SENDING, claimedAt = now - ScheduledSendAttempt.SEND_LEASE_MS - 1)
        coEvery { dao.markInterruptedIfStale(ID, any()) } returns 1

        assertThat(attempt(ID, runAttemptCount = 0, now = now))
            .isEqualTo(ScheduledSendAttempt.Verdict.INTERRUPTED)
        coVerify(exactly = 1) { dao.markInterruptedIfStale(ID, now - ScheduledSendAttempt.SEND_LEASE_MS) }
    }

    /**
     * Le point sur lequel tout repose : conclure `INTERRUPTED` ne doit **jamais** valoir renvoi.
     *
     * Le processus a pu mourir après que `SmsManager` a accepté le message. Un renvoi
     * automatique coûterait un second SMS facturé et reçu deux fois — le défaut même que
     * l'état `SENDING` avait été introduit pour fermer en v1.26.1. On conclut sur l'incertitude,
     * on ne la tranche pas à la place de l'utilisateur.
     */
    @Test
    fun `conclure un envoi interrompu ne renvoie rien et ne le remet pas en attente`() = runTest {
        coEvery { dao.findById(ID) } returns entity(ScheduledState.SENDING, claimedAt = null)
        coEvery { dao.markInterruptedIfStale(ID, any()) } returns 1

        attempt(ID, runAttemptCount = 0, now = 1_000_000_000L)

        coVerify(exactly = 0) { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.claimForSending(any(), any()) }
        coVerify(exactly = 0) { dao.setState(ID, ScheduledState.PENDING) }
    }

    /**
     * Contrôle POSITIF de la borne : sans lui, un correctif qui conclurait `INTERRUPTED` sur
     * TOUTE ligne `SENDING` — donc y compris sur un envoi réellement en cours — passerait les
     * deux tests ci-dessus. C'est le DAO qui arbitre le bail ; ce qu'on vérifie ici est que la
     * date qu'on lui passe est bien `now - bail`, et non `now` (tout conclure) ni `0` (ne rien
     * conclure jamais).
     */
    @Test
    fun `la date limite passee au DAO est exactement le bail`() = runTest {
        val now = 1_723_456_789_000L
        coEvery { dao.findById(ID) } returns entity(ScheduledState.SENDING, claimedAt = 1L)
        coEvery { dao.markInterruptedIfStale(ID, any()) } returns 1

        attempt(ID, runAttemptCount = 0, now = now)

        // 900 000 ms = un quart d'heure, écrit en dur : réécrire la constante ici ferait passer
        // le test quelle que soit sa valeur, y compris zéro.
        coVerify(exactly = 1) { dao.markInterruptedIfStale(ID, now - 900_000L) }
    }

    /**
     * La revendication horodate son bail. Sans cette date, `markInterruptedIfStale` ne pourrait
     * jamais distinguer un envoi en cours d'un envoi abandonné — c'est toute la mécanique.
     */
    @Test
    fun `la revendication horodate le bail`() = runTest {
        val now = 999_000L
        stubSend(Outcome.Success(rapportComplet()))
        coEvery { dao.findById(ID) } returns entity()

        attempt(ID, runAttemptCount = 0, now = now)

        coVerify(exactly = 1) { dao.claimForSending(ID, now) }
    }

    // ------------------------------------------------------------------------------------------
    // v1.28.3 (F21, troisieme passage) — l'envoi PROGRAMME jetait le rapport d'envoi.
    //
    // Le correctif F21 avait ete pose sur les trois chemins d'envoi, mais pas sur leur APPELANT
    // de fond : `ScheduledSendAttempt` ignorait `SendReport` et traitait toute `Outcome.Failure`
    // a l'identique. Trouve par l'audit de coherence lance sur cette branche — la TROISIEME
    // occurrence du meme motif, apres F02 et F21 lui-meme.
    // ------------------------------------------------------------------------------------------

    /**
     * Un blocage ne se resorbe pas tout seul : le retenter cinq fois, avec backoff exponentiel,
     * ne pouvait aboutir dans aucun des quatre essais suivants. Et le message final annoncait
     * « echec apres plusieurs tentatives », qui designe une panne reseau — la mauvaise cause,
     * donc la mauvaise action proposee a l'utilisateur.
     */
    @Test
    fun `un destinataire bloque n est pas retente et abandonne des la premiere tentative`() = runTest {
        coEvery { sendSms.invoke(any(), any(), any(), any(), any(), any(), any()) } returns
            Outcome.Failure(AppError.RecipientBlocked)
        coEvery { dao.findById(ID) } returns entity()

        assertThat(attempt(ID, runAttemptCount = 0))
            .isEqualTo(ScheduledSendAttempt.Verdict.GAVE_UP)
        coVerify(exactly = 1) { dao.setState(ID, ScheduledState.FAILED) }
        coVerify(exactly = 0) { dao.setState(ID, ScheduledState.PENDING) }
    }

    /**
     * Controle POSITIF, sans lequel le precedent ne prouverait rien : un correctif qui
     * abandonnerait a la premiere tentative QUELLE QUE SOIT la cause le passerait aussi, et
     * detruirait la reprise que l'audit C2 avait mise en place. Une panne de telephonie, elle,
     * doit toujours etre retentee.
     */
    @Test
    fun `une panne de telephonie continue d etre retentee`() = runTest {
        stubSend(failure())
        coEvery { dao.findById(ID) } returns entity()

        assertThat(attempt(ID, runAttemptCount = 0))
            .isEqualTo(ScheduledSendAttempt.Verdict.RETRY)
        coVerify(exactly = 0) { dao.setState(ID, ScheduledState.FAILED) }
    }

    /**
     * Un envoi PARTIEL reste `SENT`, et c'est un choix : le marquer en echec ferait proposer une
     * relance qui RE-ENVERRAIT aux destinataires deja servis — le doublon facture que F20 s'est
     * interdit d'ouvrir. Ce test fige cette decision pour qu'un futur correctif zele ne la
     * renverse pas sans s'en apercevoir.
     */
    @Test
    fun `un envoi partiel reste marque SENT`() = runTest {
        stubSend(
            Outcome.Success(
                com.filestech.sms.domain.model.SendReport(
                    dispatched = listOf(42L),
                    failed = emptyList(),
                    blocked = listOf(com.filestech.sms.domain.model.PhoneAddress.of("+33611111111")),
                ),
            ),
        )
        coEvery { dao.findById(ID) } returns entity()

        assertThat(attempt(ID, runAttemptCount = 0)).isEqualTo(ScheduledSendAttempt.Verdict.SENT)
        coVerify(exactly = 1) { dao.setState(ID, ScheduledState.SENT) }
    }

    private companion object {
        const val ID = 7L
    }
}
