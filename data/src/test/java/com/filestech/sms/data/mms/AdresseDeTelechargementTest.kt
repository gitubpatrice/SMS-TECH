package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (Visible/Verizon) — [AdresseDeTelechargement] complète l'adresse d'un MMS reçu comme son MMSC l'attend.
 *
 * Ce que ces cas fixent : l'adresse incomplète de Verizon reçoit l'identifiant de transaction ; une adresse complète
 * (opérateur français) n'est jamais touchée, ni une adresse dont l'opérateur ne demande rien ; l'identifiant n'est
 * pas ajouté deux fois ; la liste des opérateurs est celle de `android-smsmms`, et un opérateur voisin n'y est pas.
 */
class AdresseDeTelechargementTest {

    private companion object {
        /** Forme relevée dans les journaux publics (Signal #11831, ClearSMS #135). */
        const val VERIZON = "http://63.59.0.1/servlets/mms?message-id="
        const val ID = "110A10EC4B2D0000E290000101"
    }

    @Test
    fun `adresse incomplete de Verizon - l'identifiant est ajoute`() {
        assertThat(AdresseDeTelechargement.completer(VERIZON, ID, ajouter = true)).isEqualTo(VERIZON + ID)
    }

    /** Contrôle négatif : la même adresse, opérateur qui ne demande rien — telle quelle. */
    @Test
    fun `sans demande de l'operateur - telle quelle`() {
        assertThat(AdresseDeTelechargement.completer(VERIZON, ID, ajouter = false)).isEqualTo(VERIZON)
    }

    @Test
    fun `adresse complete - telle quelle, meme si l'operateur le demande`() {
        val complete = "http://mms.example.fr/mms/retrieve?id=ABC123"

        assertThat(AdresseDeTelechargement.completer(complete, ID, ajouter = true)).isEqualTo(complete)
    }

    @Test
    fun `pas d'identifiant ou deja present - telle quelle`() {
        assertThat(AdresseDeTelechargement.completer(VERIZON, null, ajouter = true)).isEqualTo(VERIZON)
        assertThat(AdresseDeTelechargement.completer(VERIZON, "", ajouter = true)).isEqualTo(VERIZON)
        val dejaComplete = "http://mmsc.example/get?id=QUJD="

        assertThat(AdresseDeTelechargement.completer(dejaComplete, "QUJD=", ajouter = true)).isEqualTo(dejaComplete)
    }

    @Test
    fun `familles Verizon et US Cellular - et elles seules`() {
        listOf("311480", "310004", "310005", "311012", "311220", "311229", "311580", "311589").forEach {
            assertThat(AdresseDeTelechargement.operateurAttendLIdentifiant(it)).isTrue()
        }
        // Orange France, T-Mobile US, AT&T, voisins immédiats des plages, et l'absence de SIM.
        listOf("20801", "310260", "310410", "311219", "311230", "311590", "311481", "").forEach {
            assertThat(AdresseDeTelechargement.operateurAttendLIdentifiant(it)).isFalse()
        }
        assertThat(AdresseDeTelechargement.operateurAttendLIdentifiant(null)).isFalse()
    }
}
