package com.filestech.sms.system.scheduler

import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.system.receiver.TraitementMmsRecu
import com.filestech.sms.system.scheduler.ReprendrePduGarde.Sort
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * v1.28.9 (F17) — **ce que la reprise fait d'un PDU gardé qu'elle rouvre**, avant et après le traitement.
 *
 * Les PDU sont de vrais octets : un `m-retrieve-conf` minimal encodé à la main (WAP-209), lu par le vrai
 * `PduParser`. Seul le traitement est simulé — il a ses propres tests —, pour vérifier ce qu'on lui passe
 * et ce qu'on décide de son issue.
 */
class ReprendrePduGardeTest {

    @TempDir lateinit var dossier: File

    private val traitement = mockk<TraitementMmsRecu>()
    private val reprise = ReprendrePduGarde(traitement)
    private val nom = PdusEnAttente.Nom(cle = "a".repeat(64), subId = 2)

    private fun fichier(nomDeFichier: String, octets: ByteArray) =
        File(dossier, nomDeFichier).apply { writeBytes(octets) }

    @Test
    fun `un PDU au-dela du plafond est consomme sans etre lu ni traite`() = runTest {
        val gros = File(dossier, "gros.pdu")
        RandomAccessFile(gros, "rw").use { it.setLength(PdusEnAttente.PLAFOND_OCTETS + 1) }

        assertThat(reprise.reprendre(gros, nom)).isEqualTo(Sort.CONSOMME)
        coVerify(exactly = 0) { traitement.traiter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `des octets qui ne sont pas un RetrieveConf sont consommes sans traitement`() = runTest {
        val bruit = fichier("bruit.pdu", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertThat(reprise.reprendre(bruit, nom)).isEqualTo(Sort.CONSOMME)
        coVerify(exactly = 0) { traitement.traiter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un PDU qui ne se lit pas se reessaie`() = runTest {
        assertThat(reprise.reprendre(File(dossier, "parti.pdu"), nom)).isEqualTo(Sort.A_REESSAYER)
        coVerify(exactly = 0) { traitement.traiter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un PDU sans expediteur est abandonne au balayage, sans traitement`() = runTest {
        val anonyme = fichier("anonyme.pdu", retrieveConf(de = null))

        assertThat(reprise.reprendre(anonyme, nom)).isEqualTo(Sort.ABANDONNE)
        coVerify(exactly = 0) { traitement.traiter(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un PDU lisible part au traitement avec sa cle et sa SIM, et son sort suit l'issue`() = runTest {
        val pdu = fichier("lisible.pdu", retrieveConf(de = "+33600000001/TYPE=PLMN"))
        var porte: (() -> Boolean)? = null
        coEvery { traitement.traiter(any(), nom.cle, nom.subId, null, any()) } answers {
            porte = arg(4)
            TraitementMmsRecu.Issue(garderLePdu = false, consigner = true)
        }

        assertThat(reprise.reprendre(pdu, nom)).isEqualTo(Sort.CONSOMME)
        // La porte passée au traitement est l'existence de CE fichier.
        assertThat(porte?.invoke()).isTrue()
        pdu.delete()
        assertThat(porte?.invoke()).isFalse()

        val garde = fichier("garde.pdu", retrieveConf(de = "+33600000001/TYPE=PLMN"))
        coEvery { traitement.traiter(any(), nom.cle, nom.subId, null, any()) } returns
            TraitementMmsRecu.Issue(garderLePdu = true, consigner = true)

        assertThat(reprise.reprendre(garde, nom)).isEqualTo(Sort.A_REESSAYER)
    }

    /**
     * Un `m-retrieve-conf` minimal : type, identifiant de transaction, version, expéditeur facultatif, date,
     * type de contenu multipart — le dernier en-tête — et un corps de zéro partie.
     */
    private fun retrieveConf(de: String?): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x8C.toByte(), 0x84.toByte()))
        write(byteArrayOf(0x98.toByte()) + "T1".toByteArray(Charsets.US_ASCII) + 0)
        write(byteArrayOf(0x8D.toByte(), 0x90.toByte()))
        if (de != null) {
            val adresse = de.toByteArray(Charsets.US_ASCII) + 0
            write(byteArrayOf(0x89.toByte(), (adresse.size + 1).toByte(), 0x80.toByte()) + adresse)
        }
        write(byteArrayOf(0x85.toByte(), 0x04, 0x65, 0x53, 0xF1.toByte(), 0x00))
        write(byteArrayOf(0x84.toByte(), 0xB3.toByte()))
        write(0x00)
    }.toByteArray()
}
