package com.filestech.sms.system.receiver

import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.IntegrationMmsRecu
import com.filestech.sms.data.mms.IntegrationMmsRecu.Resultat
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.SendingSettings
import com.filestech.sms.pdu.EncodedStringValue
import com.filestech.sms.pdu.PduBody
import com.filestech.sms.pdu.RetrieveConf
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.notifications.MmsFailureNotifier
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.9 (F17) — **le sort du PDU et les notifications, pour chaque issue de l'écriture.**
 *
 * Le traitement est partagé par le receveur et la reprise : une issue mal rendue garderait un PDU pour
 * rien, ou pire, effacerait la seule copie d'un média non écrit. L'écriture est simulée — elle a son test
 * sur un vrai Room, `IntegrationMmsRecuTest` — ; ce qui est tenu ici, c'est ce qu'on DÉCIDE de chacun de
 * ses résultats, et ce qu'on passe jusqu'à elle.
 */
class TraitementMmsRecuTest {

    private val blocage = mockk<IncomingBlockPolicy> { coEvery { doitEcarter(any()) } returns false }
    private val reglages = mockk<AppSettingsSource> { coEvery { hydratedOrNull() } returns AppSettings() }
    private val identite = mockk<PhoneIdentity> { coEvery { snapshot() } returns PhoneIdentity.Snapshot("FR") }
    private val integration = mockk<IntegrationMmsRecu>()
    private val messages = mockk<MessageDao> {
        coEvery { findById(ID) } returns mockk<MessageEntity> { every { conversationId } returns CONVERSATION }
    }
    private val notifier = mockk<IncomingMessageNotifier>(relaxed = true)
    private val echecs = mockk<MmsFailureNotifier>(relaxed = true)

    private val traitement = TraitementMmsRecu(blocage, reglages, identite, integration, messages, notifier, echecs)

    private fun conf() = RetrieveConf().apply {
        setFrom(EncodedStringValue("$ALICE/TYPE=PLMN"))
        setDate(1_700_000_000L)
        setBody(PduBody())
    }

    private suspend fun traiter(resultat: Resultat, conf: RetrieveConf = conf()): TraitementMmsRecu.Issue {
        coEvery { integration.integrer(any(), any(), any(), any(), any()) } returns resultat
        return traitement.traiter(conf, CLE, 1, null) { true }
    }

    @Test
    fun `un expediteur ecarte ne fait rien ecrire, et son PDU part sans etre consigne`() = runTest {
        // L'adresse soumise à la liste noire est déjà NETTOYÉE du suffixe de passerelle (audit H5).
        coEvery { blocage.doitEcarter(ALICE) } returns true

        val issue = traitement.traiter(conf(), CLE, 1, null) { true }

        assertThat(issue).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = false, consigner = false))
        coVerify(exactly = 0) { integration.integrer(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un message ecrit est notifie et son PDU part`() = runTest {
        val issue = traiter(Resultat.Ecrit(ID))

        assertThat(issue).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = false, consigner = true))
        coVerify(exactly = 1) { notifier.notifyIncoming(ALICE, any(), ID, CONVERSATION) }
        coVerify(exactly = 0) { echecs.notifyFailure(any(), any(), any()) }
    }

    @Test
    fun `un media non ecrit garde le PDU et le dit`() = runTest {
        val issue = traiter(Resultat.EcritIncomplet(ID))

        assertThat(issue).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = true, consigner = true))
        coVerify(exactly = 1) { echecs.notifyFailure(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, ALICE, any()) }
        coVerify(exactly = 1) { notifier.notifyIncoming(ALICE, any(), ID, CONVERSATION) }
    }

    @Test
    fun `une reprise qui ne complete toujours pas garde le PDU, sans notifier de nouveau`() = runTest {
        val issue = traiter(Resultat.ToujoursIncomplet(ID))

        assertThat(issue).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = true, consigner = true))
        coVerify(exactly = 0) { notifier.notifyIncoming(any(), any(), any(), any()) }
        coVerify(exactly = 0) { echecs.notifyFailure(any(), any(), any()) }
    }

    /** Un message supprimé depuis est consigné lui aussi : son exemplaire suivant ne doit pas le ressusciter. */
    @Test
    fun `un doublon, une completion ou un message disparu consomment le PDU sans notifier`() = runTest {
        for (resultat in listOf(Resultat.DejaComplet(ID), Resultat.Complete(ID), Resultat.Disparu)) {
            assertThat(traiter(resultat)).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = false, consigner = true))
        }
        coVerify(exactly = 0) { notifier.notifyIncoming(any(), any(), any(), any()) }
        coVerify(exactly = 0) { echecs.notifyFailure(any(), any(), any()) }
    }

    @Test
    fun `une notification qui leve ne change pas le sort du PDU`() = runTest {
        coEvery { notifier.notifyIncoming(any(), any(), any(), any()) } throws IllegalStateException("canal")

        val issue = traiter(Resultat.Ecrit(ID))

        assertThat(issue).isEqualTo(TraitementMmsRecu.Issue(garderLePdu = false, consigner = true))
    }

    @Test
    fun `la cle, la SIM et la porte du PDU vont jusqu'a l'ecriture`() = runTest {
        var porteLue = false
        coEvery { integration.integrer(any(), 1, null, CLE, any()) } answers {
            porteLue = arg<() -> Boolean>(4).invoke()
            Resultat.DejaComplet(ID)
        }

        val issue = traitement.traiter(conf(), CLE, 1, null) { true }

        assertThat(issue.garderLePdu).isFalse()
        assertThat(porteLue).isTrue()
    }

    @Test
    fun `un MMS de groupe porte ses membres jusqu'a l'ecriture quand le reglage et mon numero le permettent`() =
        runTest {
            coEvery { reglages.hydratedOrNull() } returns
                AppSettings(sending = SendingSettings(groupMms = true, userMsisdn = MOI))
            val vus = mutableListOf<List<PhoneAddress>?>()
            coEvery { integration.integrer(any(), any(), captureNullable(vus), any(), any()) } returns
                Resultat.DejaComplet(ID)
            val conf = conf().apply {
                addTo(EncodedStringValue(MOI))
                addTo(EncodedStringValue(BOB))
            }

            traitement.traiter(conf, CLE, 1, null) { true }

            assertThat(vus.single()?.map { it.raw }).containsExactly(ALICE, BOB).inOrder()
        }

    @Test
    fun `sans le reglage, le meme MMS reste une conversation ordinaire`() = runTest {
        val vus = mutableListOf<List<PhoneAddress>?>()
        coEvery { integration.integrer(any(), any(), captureNullable(vus), any(), any()) } returns
            Resultat.DejaComplet(ID)
        val conf = conf().apply {
            addTo(EncodedStringValue(MOI))
            addTo(EncodedStringValue(BOB))
        }

        traitement.traiter(conf, CLE, 1, null) { true }

        assertThat(vus).containsExactly(null)
    }

    private companion object {
        const val ID = 42L
        const val CONVERSATION = 7L
        const val ALICE = "+33600000001"
        const val BOB = "+33600000002"
        const val MOI = "+33600000009"
        val CLE = "b".repeat(64)
    }
}
