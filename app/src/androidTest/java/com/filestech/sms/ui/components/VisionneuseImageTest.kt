package com.filestech.sms.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * 2026-11-06 (issue #76) — la visionneuse affiche la photo dans l'app, rend « Ouvrir avec » et « Fermer »,
 * et dit quand une image ne peut pas être affichée au lieu de montrer un écran noir.
 */
class VisionneuseImageTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fichier: File by lazy { File(context.cacheDir, "visionneuse-test.png") }

    @After
    fun tearDown() {
        fichier.delete()
    }

    private fun image(chemin: String) = Attachment(
        id = 1L,
        messageId = 1L,
        mimeType = "image/png",
        fileName = "photo.png",
        sizeBytes = 0L,
        localUri = chemin,
        width = null,
        height = null,
        durationMs = null,
    )

    private fun ecrirePng() {
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        fichier.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun laPhotoSAfficheEtLesDeuxBoutonsRepondent() {
        ecrirePng()
        var ouvertAvec = false
        var ferme = false
        compose.setContent {
            VisionneuseImage(image(fichier.absolutePath), onOuvrirAvec = { ouvertAvec = true }, onFermer = { ferme = true })
        }

        compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.image_viewer_error)).assertDoesNotExist()

        compose.onNodeWithContentDescription(context.getString(R.string.image_viewer_open_with)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        compose.waitForIdle()
        assertThat(ouvertAvec).isTrue()
        assertThat(ferme).isTrue()
    }

    /** Contrôle négatif : un fichier disparu ne laisse pas un écran noir muet. */
    @Test
    fun fichierAbsentLeMessageLeDit() {
        compose.setContent {
            VisionneuseImage(image(File(context.cacheDir, "absent.png").absolutePath), onOuvrirAvec = {}, onFermer = {})
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTextExists(context.getString(R.string.image_viewer_error))
        }
        compose.onNodeWithText(context.getString(R.string.image_viewer_error)).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(texte: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasText(texte)).fetchSemanticsNodes().isNotEmpty()
