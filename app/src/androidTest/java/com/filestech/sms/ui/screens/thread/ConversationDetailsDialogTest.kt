package com.filestech.sms.ui.screens.thread

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.ui.util.rememberChatFormatters
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-06 — copier le numéro depuis « Détails de la conversation ».
 *
 * Le bouton transmet le numéro EXACT affiché (l'écran le copie ensuite par `copyToClipboardSensitive`), et
 * l'icône devient une coche : un snackbar s'afficherait sous la fenêtre. Une conversation de groupe n'a pas
 * de ligne « Numéro », donc pas de bouton.
 */
class ConversationDetailsDialogTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun leBoutonCopieLeNumeroExactEtDevientUneCoche() {
        var copie: String? = null
        compose.setContent {
            ConversationDetailsDialog(
                title = "Alice",
                phoneNumber = "+33612345678",
                onCopyNumber = { copie = it },
                participants = 1,
                messages = 3,
                firstAt = null,
                lastAt = null,
                formatters = rememberChatFormatters(),
                onDismiss = {},
            )
        }

        compose.onNodeWithContentDescription(context.getString(R.string.thread_details_copy_number)).performClick()
        compose.waitForIdle()

        assertThat(copie).isEqualTo("+33612345678")
        compose.onNodeWithContentDescription(context.getString(R.string.thread_details_number_copied)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.thread_details_copy_number)).assertDoesNotExist()
    }

    /** Contrôle négatif : sans numéro (groupe), aucun bouton de copie. */
    @Test
    fun groupeSansNumeroPasDeBouton() {
        compose.setContent {
            ConversationDetailsDialog(
                title = "Groupe",
                phoneNumber = null,
                onCopyNumber = {},
                participants = 3,
                messages = 3,
                firstAt = null,
                lastAt = null,
                formatters = rememberChatFormatters(),
                onDismiss = {},
            )
        }

        compose.onNodeWithContentDescription(context.getString(R.string.thread_details_copy_number)).assertDoesNotExist()
    }
}
