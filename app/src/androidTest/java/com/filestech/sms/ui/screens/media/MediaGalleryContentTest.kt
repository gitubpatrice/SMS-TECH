package com.filestech.sms.ui.screens.media

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Issue #59 — l'écran sans état : ce qu'il dit quand il n'y a rien, et ce qu'ouvre chaque case. */
class MediaGalleryContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun piece(id: Long, mime: String, nom: String) = Attachment(
        id = id,
        messageId = 1,
        mimeType = mime,
        fileName = nom,
        sizeBytes = 1,
        localUri = "/nexiste/pas/$nom",
        width = null,
        height = null,
        durationMs = null,
    )

    @Test
    fun avantLaPremiereLectureLEcranNeDitPasQuIlNYARien() {
        compose.setContent { MediaGalleryContent(null, PaddingValues()) {} }

        compose.onAllNodesWithText(context.getString(R.string.media_gallery_empty)).assertCountEquals(0)
    }

    @Test
    fun uneConversationSansPhotoLeDit() {
        compose.setContent { MediaGalleryContent(emptyList(), PaddingValues()) {} }

        compose.onNodeWithText(context.getString(R.string.media_gallery_empty)).assertExists()
    }

    @Test
    fun chaqueCaseOuvreSaProprePieceJointe() {
        val photo = piece(1, "image/jpeg", "photo.jpg")
        val video = piece(2, "video/mp4", "film.mp4")
        val ouvertes = mutableListOf<Attachment>()
        compose.setContent { MediaGalleryContent(listOf(photo, video), PaddingValues()) { ouvertes += it } }

        compose.onNodeWithContentDescription("photo.jpg").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.attachment_video_label)).performClick()

        assertThat(ouvertes).containsExactly(photo, video).inOrder()
    }
}
