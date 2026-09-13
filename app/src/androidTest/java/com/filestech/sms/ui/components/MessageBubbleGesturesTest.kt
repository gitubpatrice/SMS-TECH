package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * v1.28.6 — l'appui long sur une bulle **n'est plus** « copier tout » (doublon du menu ⋮) mais
 * « sélectionner le texte » ; et cette sélection est aussi une entrée du menu ⋮. Les deux chemins
 * sont testés parce que c'est exactement le motif d'asymétrie que ce dépôt a le plus souvent
 * rencontré : un geste changé sur une voie et pas sur l'autre.
 */
class MessageBubbleGesturesTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val BODY = "Rendez-vous demain à 9 h devant la gare."
        const val LONG_BODY =
            "Rendez-vous demain a 9 h devant la gare, prends ton billet a l'avance parce que le guichet " +
                "ferme tot et qu'il y aura du monde avec les vacances scolaires qui commencent samedi."

        fun message(body: String = BODY, direction: Message.Direction = Message.Direction.INCOMING) = Message(
            id = 1L,
            conversationId = 1L,
            address = "+33612345678",
            body = body,
            type = Message.Type.SMS,
            direction = direction,
            date = 1_700_000_000_000L,
            dateSent = null,
            read = true,
            starred = false,
            status = Message.Status.RECEIVED,
            errorCode = null,
            attachmentsCount = 0,
            subId = null,
            scheduledAt = null,
        )
    }

    @Test
    fun lAppuiLongOuvreLaSelectionEtNeCopiePlus() {
        var selections = 0
        var copies = 0
        compose.setContent {
            MessageBubble(
                message = message(),
                showTimestamp = false,
                onCopy = { copies++ },
                onSelectText = { selections++ },
            )
        }

        compose.onNodeWithText(BODY).performTouchInput { longClick(center) }
        compose.waitForIdle()

        assertThat(selections).isEqualTo(1)
        assertThat(copies).isEqualTo(0)
    }

    /**
     * v1.28.7 — **sur un écran étroit, le bouton ⋮ garde sa taille et ouvre le menu**, en entrée
     * comme en sortie.
     *
     * Le `Row` de la bulle mesurait la bulle — plafonnée à 320 dp fixes — AVANT le bouton : mesuré
     * sur émulateur, il tombait à 0 dp sur un écran de 320 dp, et le menu devenait inatteignable.
     * La largeur est bornée DANS le test, et non par l'écran de l'appareil : le test mesure la
     * même chose sur le S9, sur l'émulateur de la CI et partout ailleurs.
     */
    @Test
    fun surUnEcranEtroit_leBoutonGardeSaTailleEtOuvreLeMenu_messageEntrant() =
        boutonSurEcranEtroit(Message.Direction.INCOMING)

    @Test
    fun surUnEcranEtroit_leBoutonGardeSaTailleEtOuvreLeMenu_messageSortant() =
        boutonSurEcranEtroit(Message.Direction.OUTGOING)

    private fun boutonSurEcranEtroit(direction: Message.Direction) {
        var selections = 0
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                MessageBubble(
                    message = message(LONG_BODY, direction),
                    showTimestamp = false,
                    onCopy = {},
                    onSelectText = { selections++ },
                )
            }
        }

        val bouton = compose.onNodeWithContentDescription(context.getString(R.string.action_message_actions))
        bouton.assertWidthIsAtLeast(40.dp)
        bouton.performClick()
        compose.onNodeWithText(context.getString(R.string.action_select_text)).performClick()
        compose.waitForIdle()

        assertThat(selections).isEqualTo(1)
    }

    @Test
    fun leMenuProposeSelectionnerLeTexte() {
        var selections = 0
        compose.setContent {
            MessageBubble(
                message = message(),
                showTimestamp = false,
                onCopy = {},
                onSelectText = { selections++ },
            )
        }

        compose.onNodeWithContentDescription(context.getString(R.string.action_message_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_select_text)).performClick()
        compose.waitForIdle()

        assertThat(selections).isEqualTo(1)
    }
}
