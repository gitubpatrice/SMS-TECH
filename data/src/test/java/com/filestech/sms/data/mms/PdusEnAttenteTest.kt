package com.filestech.sms.data.mms

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.9 (F17) — la clé et le nom des PDU gardés pour reprise.
 *
 * Un nom mal écrit ou mal lu donne un fichier « sans clé », que la reprise ignore : le MMS serait perdu
 * au bout de 24 h sans que rien ne le dise. D'où des tests sur la forme exacte, dans les deux sens, et
 * sur ce qu'elle refuse.
 */
class PdusEnAttenteTest {

    @TempDir lateinit var racine: File

    private fun pdus(): PdusEnAttente {
        val context = mockk<Context>()
        every { context.cacheDir } returns File(racine, "cache").apply { mkdirs() }
        return PdusEnAttente(context)
    }

    @Test
    fun `la cle est une empreinte SHA-256 complete, stable, en minuscules`() {
        val cle = PdusEnAttente.cle("TX-42", "http://mmsc.example/abc", 1)

        assertThat(cle).matches("[0-9a-f]{64}")
        assertThat(PdusEnAttente.cle("TX-42", "http://mmsc.example/abc", 1)).isEqualTo(cle)
    }

    /** Relecture GPT 5.2 : un `transactionId` n'est unique que pour un MMSC ; deux SIM, deux MMSC. */
    @Test
    fun `la SIM et l'adresse de telechargement distinguent deux MMS de meme transactionId`() {
        val base = PdusEnAttente.cle("TX-42", "http://mmsc.example/abc", 1)

        assertThat(PdusEnAttente.cle("TX-42", "http://mmsc.example/abc", 2)).isNotEqualTo(base)
        assertThat(PdusEnAttente.cle("TX-42", "http://mmsc.example/abc", null)).isNotEqualTo(base)
        assertThat(PdusEnAttente.cle("TX-42", "http://autre.example/abc", 1)).isNotEqualTo(base)
        assertThat(PdusEnAttente.cle("TX-43", "http://mmsc.example/abc", 1)).isNotEqualTo(base)
    }

    @Test
    fun `sans transactionId il n'y a pas de cle`() {
        assertThat(PdusEnAttente.cle(null, "http://mmsc.example/abc", 1)).isNull()
        assertThat(PdusEnAttente.cle("  ", "http://mmsc.example/abc", 1)).isNull()
    }

    @Test
    fun `le nom se relit a l'identique, avec ou sans cle et SIM`() {
        val cle = PdusEnAttente.cle("TX", "http://m/1", 3)!!

        assertThat(PdusEnAttente.lireNom(PdusEnAttente.nom(1_726_000_000_000L, "ab12cd34", cle, 3)))
            .isEqualTo(PdusEnAttente.Nom(cle, 3))
        assertThat(PdusEnAttente.lireNom(PdusEnAttente.nom(1L, "ab12cd34", cle, null)))
            .isEqualTo(PdusEnAttente.Nom(cle, null))
        assertThat(PdusEnAttente.lireNom(PdusEnAttente.nom(1L, "ab12cd34", null, null)))
            .isEqualTo(PdusEnAttente.Nom(null, null))
    }

    /** Un fichier écrit par une version antérieure garde son nom : il se lit, sans clé. */
    @Test
    fun `un nom d'avant la 1_28_9 se lit sans cle`() {
        assertThat(PdusEnAttente.lireNom("in-1726000000000-ab12cd34.pdu")).isEqualTo(PdusEnAttente.Nom(null, null))
    }

    @Test
    fun `les formes approchantes sont refusees`() {
        val cle = "a".repeat(64)
        assertThat(PdusEnAttente.lireNom("in-1-ab12cd34-k$cle.pdu.tmp")).isNull()
        assertThat(PdusEnAttente.lireNom("in-1-ab12cd34-k${"a".repeat(63)}.pdu")).isNull()
        assertThat(PdusEnAttente.lireNom("in-1-AB12CD34.pdu")).isNull()
        assertThat(PdusEnAttente.lireNom("out-1-ab12cd34.pdu")).isNull()
        assertThat(PdusEnAttente.lireNom("in-1-ab12cd34-k$cle-s.pdu")).isNull()
        assertThat(PdusEnAttente.lireNom("x/in-1-ab12cd34.pdu")).isNull()
    }

    @Test
    fun `effacer par cles ne retire que les PDU de ces cles`() {
        val service = pdus()
        val cleA = PdusEnAttente.cle("A", "http://m/a", null)!!
        val cleB = PdusEnAttente.cle("B", "http://m/b", null)!!
        val dossier = service.dossier.apply { mkdirs() }
        val pduA = File(dossier, PdusEnAttente.nom(1L, "aaaaaaaa", cleA, null)).apply { writeBytes(ByteArray(8)) }
        val pduB = File(dossier, PdusEnAttente.nom(2L, "bbbbbbbb", cleB, 1)).apply { writeBytes(ByteArray(8)) }
        val ancien = File(dossier, "in-3-cccccccc.pdu").apply { writeBytes(ByteArray(8)) }

        val echecs = service.effacerPourCles(listOf(cleA))

        assertThat(echecs).isEqualTo(0)
        assertThat(pduA.exists()).isFalse()
        assertThat(pduB.exists()).isTrue()
        assertThat(ancien.exists()).isTrue()
    }

    @Test
    fun `un dossier absent n'est pas un echec`() {
        assertThat(pdus().effacerPourCles(listOf("a".repeat(64)))).isEqualTo(0)
    }
}
