package com.filestech.sms.ui.screens.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-27 (issue #108) — **une sélection du Coffre se supprime**, et ses actions se lisent.
 *
 * Le Coffre n'offrait qu'un cadenas sans libellé en haut de l'écran (sortir du Coffre), et rien pour supprimer :
 * « It lets me select them and that's it ». Jumeau de `BarreActionsSelectionTest` pour la liste.
 */
class BasDeSelectionDuCoffreTest {

    @get:Rule val compose = createComposeRule()

    private fun texte(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun titre(n: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.resources
        .getQuantityString(R.plurals.conversations_bulk_delete_title, n, n)

    private val estUnBouton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun deuxActionsLibelleesSortirAgitSupprimerDemandeConfirmation() {
        var sorties = 0
        val supprimees = mutableListOf<List<Long>>()
        compose.setContent {
            BasDeSelectionDuCoffre(
                selectionnees = setOf(4L, 5L),
                visible = true,
                onSortir = { sorties++ },
                onSupprimer = { supprimees += it },
            )
        }

        compose.onNodeWithText(texte(R.string.bulk_vault_move_out)).assertIsDisplayed().assert(estUnBouton)
            .performClick()
        assertThat(sorties).isEqualTo(1)

        compose.onNodeWithText(texte(R.string.action_delete)).assertIsDisplayed().assert(estUnBouton).performClick()
        compose.onNodeWithText(titre(2)).assertIsDisplayed()
        assertThat(supprimees).isEmpty()
        compose.onNode(hasText(texte(R.string.action_delete)) and hasAnyAncestor(isDialog())).performClick()
        assertThat(supprimees).containsExactly(listOf(4L, 5L))
    }

    @Test
    fun annulerNeSupprimeRien() {
        val supprimees = mutableListOf<List<Long>>()
        compose.setContent {
            BasDeSelectionDuCoffre(selectionnees = setOf(4L), visible = true, onSortir = {}, onSupprimer = { supprimees += it })
        }

        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        compose.onNodeWithText(texte(R.string.action_cancel)).performClick()

        assertThat(supprimees).isEmpty()
        assertThat(compose.compter(titre(1))).isEqualTo(0)
    }

    /** La fenêtre fige les conversations à son ouverture : une cochée ensuite n'est pas emportée. */
    @Test
    fun laSuppressionNePrendQueCeQueLaFenetreACompte() {
        val supprimees = mutableListOf<List<Long>>()
        var selection by mutableStateOf(setOf(4L, 5L))
        compose.setContent {
            BasDeSelectionDuCoffre(selectionnees = selection, visible = true, onSortir = {}, onSupprimer = { supprimees += it })
        }

        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        selection = setOf(4L, 5L, 6L)
        compose.waitForIdle()
        compose.onNodeWithText(titre(2)).assertIsDisplayed()
        compose.onNode(hasText(texte(R.string.action_delete)) and hasAnyAncestor(isDialog())).performClick()

        assertThat(supprimees).containsExactly(listOf(4L, 5L))
    }

    /** Rien hors sélection, ni tant que le contenu du Coffre n'est pas dévoilé. */
    @Test
    fun rienHorsSelectionNiAvantDevoilement() {
        var selection by mutableStateOf(emptySet<Long>())
        var visible by mutableStateOf(true)
        compose.setContent {
            BasDeSelectionDuCoffre(selectionnees = selection, visible = visible, onSortir = {}, onSupprimer = {})
        }
        val supprimer = texte(R.string.action_delete)

        assertThat(compose.compter(supprimer)).isEqualTo(0)
        selection = setOf(4L)
        compose.waitForIdle()
        compose.onNodeWithText(supprimer).assertIsDisplayed()
        visible = false
        compose.waitForIdle()
        assertThat(compose.compter(supprimer)).isEqualTo(0)
    }

    private fun ComposeContentTestRule.compter(texte: String): Int = onAllNodes(hasText(texte)).fetchSemanticsNodes().size
}
