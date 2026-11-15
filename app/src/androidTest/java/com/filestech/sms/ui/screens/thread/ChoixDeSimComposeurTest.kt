package com.filestech.sms.ui.screens.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.filestech.sms.domain.model.SimActive
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-10 (v1.30.1) — **le choix de la SIM d'envoi au composeur.**
 *
 * Un utilisateur à deux SIM ne pouvait pas choisir : tout partait par la SIM 1. Le composant est monté seul,
 * avec un enregistreur à la place du ViewModel : aucun SMS ne part.
 *
 * Le test « une seule SIM » n'affirme qu'une absence ; son contrôle positif est le test à deux SIM, qui
 * exige la pastille et un choix effectif.
 */
class ChoixDeSimComposeurTest {

    @get:Rule val compose = createComposeRule()

    private val sim1 = SimActive(subId = 11, emplacement = 0, nom = "Orange")
    private val sim2 = SimActive(subId = 22, emplacement = 1, nom = "Free")

    private val choix = mutableListOf<Int>()

    @Test
    fun deuxSimMontrentLaSimDEnvoiEtPermettentDEnChanger() {
        compose.setContent { ChoixDeSimComposeur(sims = listOf(sim1, sim2), simEnvoi = 22, onChoisir = { choix += it }) }

        compose.onNodeWithText("SIM 2 · Free").assertIsDisplayed()
        compose.onNodeWithTag(TAG_CHOIX_SIM).performClick()
        compose.onNodeWithTag(TAG_SIM_PREFIXE + 0).performClick()

        assertThat(choix).containsExactly(11)
    }

    @Test
    fun uneSeuleSimNAfficheRien() {
        compose.setContent { ChoixDeSimComposeur(sims = listOf(sim1), simEnvoi = 11, onChoisir = { choix += it }) }

        compose.onNodeWithTag(TAG_CHOIX_SIM).assertDoesNotExist()
        assertThat(choix).isEmpty()
    }

    @Test
    fun uneSimSansNomGardeSonNumero() {
        val sansNom = SimActive(subId = 33, emplacement = 1, nom = null)
        compose.setContent { ChoixDeSimComposeur(sims = listOf(sim1, sansNom), simEnvoi = 33, onChoisir = { choix += it }) }

        compose.onNodeWithText("SIM 2").assertIsDisplayed()
    }
}
