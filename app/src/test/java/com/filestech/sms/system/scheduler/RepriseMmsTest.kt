package com.filestech.sms.system.scheduler

import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.system.scheduler.ReprendrePduGarde.Sort
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.9 (F17) — les règles d'une passe de reprise des PDU gardés, au fichier près.
 *
 * Le traitement est remplacé par une réponse choisie : ce qui est testé ici, c'est ce que la passe fait du
 * FICHIER — l'effacer, le garder pour plus tard, le laisser au balayage — et ce qu'elle promet à WorkManager.
 * Chaque test « gardé » vérifie que le fichier existe toujours, et le test « repris » qu'il a disparu : une
 * passe qui n'effacerait jamais rien, ou qui effacerait tout, tomberait.
 */
class RepriseMmsTest {

    @TempDir lateinit var dossier: File

    private val maintenant = 1_726_000_000_000L
    private val presentes = mutableListOf<File>()

    private fun pdu(age: Long, octets: Int = 16, nom: String = PdusEnAttente.nom(1L, "ab12cd34", CLE, 1)) =
        File(dossier, nom).apply {
            writeBytes(ByteArray(octets))
            setLastModified(maintenant - age)
        }

    private fun reprise(sort: Sort) = RepriseMms({ dossier }) { fichier, _ ->
        presentes += fichier
        sort
    }

    @Test
    fun `un PDU repris et consomme est efface`() = runTest {
        val fichier = pdu(age = HEURE)

        val bilan = reprise(Sort.CONSOMME).passe(maintenant)

        assertThat(presentes).containsExactly(fichier)
        assertThat(fichier.exists()).isFalse()
        assertThat(bilan).isEqualTo(RepriseMms.Bilan(repris = 1, aReessayer = 0, laisses = 0))
    }

    @Test
    fun `un PDU de moins de dix minutes n'est pas touche et se reessaie`() = runTest {
        val fichier = pdu(age = 5 * MINUTE)

        val bilan = reprise(Sort.CONSOMME).passe(maintenant)

        assertThat(presentes).isEmpty()
        assertThat(fichier.exists()).isTrue()
        assertThat(bilan).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 1, laisses = 0))
    }

    @Test
    fun `un PDU a garder se reessaie jusqu'a vingt heures, puis est laisse au balayage`() = runTest {
        val fichier = pdu(age = HEURE)

        val tot = reprise(Sort.A_REESSAYER).passe(maintenant)

        assertThat(fichier.exists()).isTrue()
        assertThat(tot).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 1, laisses = 0))

        fichier.setLastModified(maintenant - 21 * HEURE)
        val tard = reprise(Sort.A_REESSAYER).passe(maintenant)

        assertThat(fichier.exists()).isTrue()
        assertThat(tard).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 0, laisses = 1))
    }

    @Test
    fun `un PDU sans cle, ou vide, n'est pas repris et reste au balayage`() = runTest {
        val sansCle = pdu(age = HEURE, nom = "in-1-ab12cd34.pdu")
        val vide = pdu(age = HEURE, octets = 0, nom = PdusEnAttente.nom(2L, "cd34ef56", CLE, null))

        val bilan = reprise(Sort.CONSOMME).passe(maintenant)

        assertThat(presentes).isEmpty()
        assertThat(sansCle.exists()).isTrue()
        assertThat(vide.exists()).isTrue()
        assertThat(bilan).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 0, laisses = 2))
    }

    @Test
    fun `un PDU abandonne reste au balayage, sans nouvelle tentative`() = runTest {
        val fichier = pdu(age = HEURE)

        val bilan = reprise(Sort.ABANDONNE).passe(maintenant)

        assertThat(fichier.exists()).isTrue()
        assertThat(bilan).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 0, laisses = 1))
    }

    @Test
    fun `un traitement qui leve garde le PDU pour la passe suivante`() = runTest {
        val fichier = pdu(age = HEURE)
        val reprise = RepriseMms({ dossier }) { _, _ -> error("base indisponible") }

        val bilan = reprise.passe(maintenant)

        assertThat(fichier.exists()).isTrue()
        assertThat(bilan).isEqualTo(RepriseMms.Bilan(repris = 0, aReessayer = 1, laisses = 0))
    }

    @Test
    fun `une annulation n'est pas avalee`() {
        pdu(age = HEURE)
        val reprise = RepriseMms({ dossier }) { _, _ -> throw CancellationException("arret") }

        assertThrows(CancellationException::class.java) { runBlocking { reprise.passe(maintenant) } }
    }

    @Test
    fun `seuls les PDU sont regardes, et un dossier absent ne fait rien`() = runTest {
        File(dossier, "note.pdu.tmp").writeBytes(ByteArray(4))
        val absent = RepriseMms({ File(dossier, "absent") }) { _, _ -> Sort.CONSOMME }

        assertThat(reprise(Sort.CONSOMME).passe(maintenant)).isEqualTo(RepriseMms.Bilan(0, 0, 0))
        assertThat(absent.passe(maintenant)).isEqualTo(RepriseMms.Bilan(0, 0, 0))
        assertThat(presentes).isEmpty()
    }

    private companion object {
        val CLE = "a".repeat(64)
        const val MINUTE = 60_000L
        const val HEURE = 60 * MINUTE
    }
}
