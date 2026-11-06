package com.filestech.sms.ui.screens.thread

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-06 (issue #76) — la fenêtre des destinataires d'un message adressé à plusieurs.
 *
 * Elle liste chacun (nom de contact s'il est connu, l'utilisateur marqué « vous »), copie le numéro EXACT, et
 * renvoie aux réglages quand il en manque un. Réglages faits, elle ne propose plus d'y aller.
 */
class DestinatairesMmsDialogTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val liste = listOf(
        DestinataireAffiche("+33607231541", estMoi = true),
        DestinataireAffiche("+33698765432", estMoi = false),
        DestinataireAffiche("+33611223344", estMoi = false),
    )

    @Test
    fun listeChacunMarqueLUtilisateurEtCopieLeNumeroExact() {
        var copie: String? = null
        var reglages = false
        compose.setContent {
            DestinatairesMmsDialog(
                ui = DestinatairesUi(liste, AideGroupe.ACTIVER_MMS_DE_GROUPE),
                noms = mapOf("+33698765432" to "Marie"),
                onCopy = { copie = it },
                onOpenSettings = { reglages = true },
                onDismiss = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.mms_recipients_you, "+33607231541")).assertIsDisplayed()
        compose.onNodeWithText("Marie").assertIsDisplayed()
        compose.onNodeWithText("+33611223344").assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.thread_details_copy_number_of, "+33698765432"))
            .performClick()
        compose.waitForIdle()
        assertThat(copie).isEqualTo("+33698765432")

        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        assertThat(reglages).isTrue()
    }

    /** Contrôle négatif : tout est réglé, la fenêtre ne renvoie plus aux réglages. */
    @Test
    fun reglagesFaitsPasDeBoutonReglages() {
        compose.setContent {
            DestinatairesMmsDialog(
                ui = DestinatairesUi(liste, AideGroupe.DEJA_REGLE),
                noms = emptyMap(),
                onCopy = {},
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.action_close)).assertIsDisplayed()
    }

    /** Un message ENVOYÉ : les réglages n'y changent rien, la fenêtre le dit et n'y renvoie pas. */
    @Test
    fun messageEnvoyeNiReglagesNiConversationDeLExpediteur() {
        compose.setContent {
            DestinatairesMmsDialog(
                ui = DestinatairesUi(liste, AideGroupe.ENVOI_SORTANT),
                noms = emptyMap(),
                onCopy = {},
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.mms_recipients_hint_outgoing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
    }
}
