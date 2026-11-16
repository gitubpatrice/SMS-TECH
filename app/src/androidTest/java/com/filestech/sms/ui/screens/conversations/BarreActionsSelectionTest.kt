package com.filestech.sms.ui.screens.conversations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-16 (v1.31.0, courriel du 2026-11-11) — **les actions d'une sélection se lisent**, en bas de l'écran.
 *
 * L'utilisateur qui demandait de mettre une sélection au Coffre n'avait pas trouvé l'icône de cadenas sans libellé
 * qui le faisait déjà. Chaque action a donc un texte, est un bouton pour TalkBack, et déclenche la bonne action ;
 * dans les Archives, archiver devient désarchiver. La suppression groupée montre le nombre avant d'agir.
 */
class BarreActionsSelectionTest {

    @get:Rule val compose = createComposeRule()

    private fun texte(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val estUnBouton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun troisActionsLibelleesChacuneSonGeste() {
        val touchees = mutableListOf<String>()
        compose.setContent {
            BarreActionsSelection(
                archives = false,
                onArchiver = { touchees += "archiver" },
                onCoffre = { touchees += "coffre" },
                onSupprimer = { touchees += "supprimer" },
            )
        }

        compose.onNodeWithText(texte(R.string.action_archive)).assertIsDisplayed().assert(estUnBouton).performClick()
        compose.onNodeWithText(texte(R.string.bulk_vault_move_in)).assertIsDisplayed().assert(estUnBouton)
            .performClick()
        compose.onNodeWithText(texte(R.string.action_delete)).assertIsDisplayed().assert(estUnBouton).performClick()

        assertThat(touchees).containsExactly("archiver", "coffre", "supprimer").inOrder()
    }

    @Test
    fun dansLesArchivesArchiverDevientDesarchiver() {
        compose.setContent {
            BarreActionsSelection(archives = true, onArchiver = {}, onCoffre = {}, onSupprimer = {})
        }

        compose.onNodeWithText(texte(R.string.action_unarchive)).assertIsDisplayed()
        assertThat(compose.onAllNodesWithTextCount(texte(R.string.action_archive))).isEqualTo(0)
    }

    @Test
    fun laConfirmationDonneLeNombreEtSeulConfirmerSupprime() {
        var confirmee = 0
        var ouverte by mutableStateOf(true)
        compose.setContent {
            if (ouverte) {
                ConfirmationSuppressionGroupee(
                    nombre = 3,
                    onConfirmer = { confirmee++; ouverte = false },
                    onAnnuler = { ouverte = false },
                )
            }
        }

        val titre = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.conversations_bulk_delete_title, 3, 3)
        compose.onNodeWithText(titre).assertIsDisplayed()
        compose.onNodeWithText(texte(R.string.action_cancel)).performClick()
        assertThat(confirmee).isEqualTo(0)

        ouverte = true
        compose.waitForIdle()
        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        assertThat(confirmee).isEqualTo(1)
    }

    @Test
    fun rienHorsSelectionNiEnSessionLeurre() {
        var selection by mutableStateOf(false)
        var leurre by mutableStateOf(false)
        compose.setContent {
            BasDeSelection(
                selection = selection,
                leurre = leurre,
                archives = false,
                nombre = 2,
                onArchiver = {},
                onCoffre = {},
                onSupprimer = {},
            )
        }
        val supprimer = texte(R.string.action_delete)

        assertThat(compose.onAllNodesWithTextCount(supprimer)).isEqualTo(0)
        selection = true
        compose.waitForIdle()
        compose.onNodeWithText(supprimer).assertIsDisplayed()
        leurre = true
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTextCount(supprimer)).isEqualTo(0)
    }

    @Test
    fun supprimerDemandeConfirmationAvantDAgir() {
        var supprimee = 0
        compose.setContent {
            BasDeSelection(
                selection = true,
                leurre = false,
                archives = false,
                nombre = 2,
                onArchiver = {},
                onCoffre = {},
                onSupprimer = { supprimee++ },
            )
        }
        val titre = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.conversations_bulk_delete_title, 2, 2)

        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        compose.onNodeWithText(titre).assertIsDisplayed()
        assertThat(supprimee).isEqualTo(0)
        // Le bouton de la fenêtre porte le même mot que celui de la barre : on vise celui de la fenêtre.
        compose.onNode(
            androidx.compose.ui.test.hasText(texte(R.string.action_delete)) and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()),
        ).performClick()
        assertThat(supprimee).isEqualTo(1)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(texte: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(texte)).fetchSemanticsNodes().size
}
