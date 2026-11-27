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
            BasDeSelectionDuCoffre(
                selectionnees = setOf(4L),
                visible = true,
                onSortir = {},
                onSupprimer = { supprimees += it },
            )
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
            BasDeSelectionDuCoffre(
                selectionnees = selection,
                visible = true,
                onSortir = {},
                onSupprimer = { supprimees += it },
            )
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

    /** L'appui long d'une conversation du Coffre : « Supprimer » y est, et ne supprime qu'une fois confirmé. */
    @Test
    fun lAppuiLongProposeSupprimerConfirme() {
        val gestes = mutableListOf<String>()
        var cible by mutableStateOf<Long?>(7L)
        compose.setContent {
            ActionsDUneConversationDuCoffre(
                visible = true,
                cible = cible,
                onFermer = { cible = null },
                onSortir = { gestes += "sortir $it" },
                onSelectionner = { gestes += "selection $it" },
                onSupprimer = { gestes += "supprimer $it" },
            )
        }
        val titre = texte(R.string.conversations_delete_confirm_title)

        compose.onNodeWithText(texte(R.string.vault_move_out)).assertIsDisplayed()
        compose.onNodeWithText(texte(R.string.bulk_select_multiple)).assertIsDisplayed()
        compose.onNodeWithText(texte(R.string.action_delete)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        // La feuille se ferme, la confirmation s'ouvre : rien n'est supprimé avant elle.
        compose.onNodeWithText(titre).assertIsDisplayed()
        assertThat(cible).isNull()
        assertThat(gestes).isEmpty()
        compose.onNode(hasText(texte(R.string.action_delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        assertThat(gestes).containsExactly("supprimer 7")
        assertThat(compose.compter(titre)).isEqualTo(0)
    }

    @Test
    fun lAppuiLongAnnulerNeSupprimeRienSortirAgit() {
        val gestes = mutableListOf<String>()
        var cible by mutableStateOf<Long?>(7L)
        compose.setContent {
            ActionsDUneConversationDuCoffre(
                visible = true,
                cible = cible,
                onFermer = { cible = null },
                onSortir = { gestes += "sortir $it" },
                onSelectionner = { gestes += "selection $it" },
                onSupprimer = { gestes += "supprimer $it" },
            )
        }

        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(texte(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        cible = 8L
        compose.waitForIdle()
        compose.onNodeWithText(texte(R.string.vault_move_out)).performClick()
        compose.waitForIdle()

        assertThat(gestes).containsExactly("sortir 8")
        assertThat(cible).isNull()
    }

    /**
     * Audit pré-release du 2026-11-27 : la confirmation restait affichée par-dessus un Coffre verrouillé. Hors
     * dévoilement, ni feuille ni confirmation ; et la confirmation ne revient pas avec lui.
     */
    @Test
    fun lAppuiLongRienAvantDevoilementEtLaConfirmationNeRevientPas() {
        var visible by mutableStateOf(false)
        var cible by mutableStateOf<Long?>(7L)
        val supprimees = mutableListOf<Long>()
        compose.setContent {
            ActionsDUneConversationDuCoffre(
                visible = visible,
                cible = cible,
                onFermer = { cible = null },
                onSortir = {},
                onSelectionner = {},
                onSupprimer = { supprimees += it },
            )
        }
        val titre = texte(R.string.conversations_delete_confirm_title)

        assertThat(compose.compter(texte(R.string.vault_move_out))).isEqualTo(0)
        visible = true
        compose.waitForIdle()
        compose.onNodeWithText(texte(R.string.action_delete)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(titre).assertIsDisplayed()
        visible = false
        compose.waitForIdle()
        assertThat(compose.compter(titre)).isEqualTo(0)
        visible = true
        compose.waitForIdle()

        assertThat(compose.compter(titre)).isEqualTo(0)
        assertThat(supprimees).isEmpty()
    }

    private fun ComposeContentTestRule.compter(texte: String): Int =
        onAllNodes(hasText(texte)).fetchSemanticsNodes().size
}
