package com.filestech.sms.core.io

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * v1.28.4 (F27) — **les bornes se mesurent à l'octet, au plafond exact.**
 *
 * Trois allocations sans borne avaient été bornées en v1.28.3 sans qu'aucun test ne puisse les
 * atteindre. Ces cas fixent ce qui compte : au plafond, ça passe ; un octet au-dessus, rien
 * n'est gardé ; et le refus sur taille n'alloue pas.
 */
class LectureBorneeTest {

    @TempDir
    lateinit var dossier: File

    private fun source(octets: Int): InputStream = ByteArrayInputStream(ByteArray(octets) { 7 })

    @Test
    fun `au plafond exact la copie passe et le fichier fait le plafond`() {
        val cible = File(dossier, "pile.bin")

        assertThat(LectureBornee.recopier(source(1_000), cible, plafond = 1_000L, tampon = 64)).isTrue()
        assertThat(cible.length()).isEqualTo(1_000L)
    }

    /** Un octet au-dessus : `false`, et AUCUN fichier partiel derrière — c'est l'octet qui compte. */
    @Test
    fun `un octet au-dessus du plafond ne laisse rien derriere`() {
        val cible = File(dossier, "trop.bin")

        assertThat(LectureBornee.recopier(source(1_001), cible, plafond = 1_000L, tampon = 64)).isFalse()
        assertThat(cible.exists()).isFalse()
    }

    /** Le dépassement est détecté même quand il tombe au milieu d'un tampon, pas seulement à sa frontière. */
    @Test
    fun `le depassement est vu au milieu d'un tampon`() {
        val cible = File(dossier, "milieu.bin")

        assertThat(LectureBornee.recopier(source(70), cible, plafond = 65L, tampon = 64)).isFalse()
        assertThat(cible.exists()).isFalse()
    }

    /**
     * v1.28.5 (sixième note d'Andrew, point 6) — une source qui LÈVE au milieu de la copie ne
     * laisse pas de fichier partiel. Avant, seule la sortie « dépassement » nettoyait ; une
     * exception fermait les flux et laissait derrière elle ce qui avait déjà été écrit.
     */
    @Test
    fun `une exception au milieu de la copie ne laisse rien derriere et remonte`() {
        val cible = File(dossier, "partiel.bin")
        val sourceQuiLache = object : InputStream() {
            private var restants = 200
            override fun read(): Int = if (restants-- > 0) 7 else throw java.io.IOException("SOURCE_LACHEE")
        }

        val erreur = org.junit.jupiter.api.assertThrows<java.io.IOException> {
            LectureBornee.recopier(sourceQuiLache, cible, plafond = 10_000L, tampon = 64)
        }

        assertThat(erreur).hasMessageThat().isEqualTo("SOURCE_LACHEE")
        assertThat(cible.exists()).isFalse()
    }

    @Test
    fun `une source vide ne laisse pas de fichier vide`() {
        val cible = File(dossier, "vide.bin")

        assertThat(LectureBornee.recopier(source(0), cible, plafond = 1_000L)).isFalse()
        assertThat(cible.exists()).isFalse()
    }

    @Test
    fun `un fichier au plafond se lit, un octet au-dessus rend null`() {
        val ok = File(dossier, "ok.pdu").apply { writeBytes(ByteArray(2_048)) }
        val trop = File(dossier, "trop.pdu").apply { writeBytes(ByteArray(2_049)) }

        assertThat(LectureBornee.lire(ok, plafond = 2_048L)).hasLength(2_048)
        assertThat(LectureBornee.lire(trop, plafond = 2_048L)).isNull()
    }

    @Test
    fun `un fichier absent rend null`() {
        assertThat(LectureBornee.lire(File(dossier, "absent.pdu"), plafond = 10L)).isNull()
    }
}
