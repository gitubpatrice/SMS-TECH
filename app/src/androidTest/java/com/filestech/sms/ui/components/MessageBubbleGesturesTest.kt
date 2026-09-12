package com.filestech.sms.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
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
        fun message() = Message(
            id = 1L,
            conversationId = 1L,
            address = "+33612345678",
            body = BODY,
            type = Message.Type.SMS,
            direction = Message.Direction.INCOMING,
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
