package com.filestech.sms.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.Locale

/**
 * 2026-11-27 (suggestion d'un utilisateur, par courriel) — « dans la liste des messages on n'a pas l'année. Il serait
 * bien de l'avoir, pas pour l'année courante mais uniquement pour les années antérieures. »
 *
 * La date d'une ligne de la liste (et d'un résultat de recherche) : l'heure le jour même, le jour de la semaine
 * dans la semaine, le jour et le mois dans l'année, et l'année en plus avant. L'heure de référence est fixée, et
 * le calendrier suit la langue des formateurs, comme dans l'application.
 */
class RelativeRowLabelTest {

    private val formateurs = ChatFormatters(Locale.FRENCH)

    /** Jeudi 26 novembre 2026, 15 h 00. */
    private val maintenant = instant(2026, Calendar.NOVEMBER, 26, 15, 0)

    private fun instant(annee: Int, mois: Int, jour: Int, heure: Int = 12, minute: Int = 0): Calendar =
        Calendar.getInstance(Locale.FRENCH).apply {
            clear()
            set(annee, mois, jour, heure, minute)
        }

    private fun libelle(alors: Calendar): String =
        formateurs.relativeRowLabel(alors.timeInMillis, now = maintenant)

    @Test
    fun leJourMeme_lHeure() {
        assertThat(libelle(instant(2026, Calendar.NOVEMBER, 26, 9, 5))).isEqualTo("09:05")
    }

    @Test
    fun dansLaSemaine_leJour() {
        assertThat(libelle(instant(2026, Calendar.NOVEMBER, 24))).isEqualTo("mar.")
    }

    @Test
    fun dansLAnnee_leJourEtLeMois_sansLAnnee() {
        assertThat(libelle(instant(2026, Calendar.MARCH, 27))).isEqualTo("27 mars")
    }

    @Test
    fun uneAnneeAnterieure_portePlusLAnnee() {
        assertThat(libelle(instant(2024, Calendar.MARCH, 27))).isEqualTo("27 mars 2024")
    }

    @Test
    fun leMemeJourUnAnPlusTot_neSAffichePasCommeAujourdhui() {
        assertThat(libelle(instant(2025, Calendar.NOVEMBER, 26, 9, 5))).isEqualTo("26 nov. 2025")
    }

    @Test
    fun laVeilleDuNouvelAn_dansLaMemeSemaine_porteSonAnnee() {
        // Le 29 décembre 2026 et le 1er janvier 2027 sont dans la même semaine (la 53e de 2026, en français) :
        // c'est l'année qui décide, comme pour le séparateur de jours d'une conversation.
        val nouvelAn = instant(2027, Calendar.JANUARY, 1, 10, 0)
        val libelle = formateurs.relativeRowLabel(instant(2026, Calendar.DECEMBER, 29).timeInMillis, now = nouvelAn)
        assertThat(libelle).isEqualTo("29 déc. 2026")
    }
}
