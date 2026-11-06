package com.filestech.sms.data.sync

import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.MmsAVerifier
import com.filestech.sms.data.sms.AdressesMms
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.mms.DestinatairesMms
import com.filestech.sms.domain.model.MessageDirection
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (issue #76) — le rattrapage rend leur liste aux MMS importés avant la v15.
 *
 * Ce que ces cas fixent : un envoi groupé reçoit sa liste ; un MMS sans groupe est marqué « regardé » pour ne
 * plus être relu ; une lecture en échec ne marque RIEN et arrête la passe (la suivante reprendra) ; une URI
 * qu'on ne sait pas relire est marquée regardée, sinon elle serait relue à chaque passe.
 */
class RattrapageDestinatairesMmsTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val reader = mockk<TelephonyReader>()
    private val rattrapage = RattrapageDestinatairesMms(dao, reader)
    private val cle: (String) -> String = { it }

    private fun ligne(id: Long, uri: String = "content://mms/${id + 100}") =
        MmsAVerifier(id = id, telephonyUri = uri, direction = MessageDirection.INCOMING)

    @Test
    fun `groupe garde, sans groupe marque regarde`() = runTest {
        coEvery { dao.mmsSansDestinatairesVerifies(any()) } returns listOf(ligne(1), ligne(2))
        every { reader.lireAdressesMms(101, MessageDirection.INCOMING, any()) } returns
            AdressesMms("+331", listOf("+33607231541", "+33698765432"))
        every { reader.lireAdressesMms(102, MessageDirection.INCOMING, any()) } returns AdressesMms("+331", null)

        val regardees = rattrapage.rattraper(cle)

        assertThat(regardees).isEqualTo(2)
        coVerify { dao.setMmsRecipients(1, "+33607231541;+33698765432") }
        coVerify { dao.setMmsRecipients(2, DestinatairesMms.VERIFIE_SANS_GROUPE) }
    }

    /** Contrôle négatif : une lecture en échec ne marque pas la ligne, et la passe s'arrête là. */
    @Test
    fun `lecture en echec - rien n'est marque, la passe s'arrete`() = runTest {
        coEvery { dao.mmsSansDestinatairesVerifies(any()) } returns listOf(ligne(1), ligne(2))
        every { reader.lireAdressesMms(101, any(), any()) } returns null

        val regardees = rattrapage.rattraper(cle)

        assertThat(regardees).isEqualTo(0)
        coVerify(exactly = 0) { dao.setMmsRecipients(any(), any()) }
    }

    @Test
    fun `uri illisible marquee regardee, sans lecture`() = runTest {
        coEvery { dao.mmsSansDestinatairesVerifies(any()) } returns listOf(ligne(1, uri = "content://mms/inbox/x"))

        rattrapage.rattraper(cle)

        coVerify { dao.setMmsRecipients(1, DestinatairesMms.VERIFIE_SANS_GROUPE) }
    }

    @Test
    fun `identifiant dans le fournisseur`() {
        assertThat(RattrapageDestinatairesMms.idDansLeFournisseur("content://mms/123")).isEqualTo(123L)
        assertThat(RattrapageDestinatairesMms.idDansLeFournisseur("content://sms/123")).isNull()
        assertThat(RattrapageDestinatairesMms.idDansLeFournisseur("content://mms/part/5")).isNull()
    }
}
