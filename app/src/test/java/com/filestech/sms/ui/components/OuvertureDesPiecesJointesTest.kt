package com.filestech.sms.ui.components

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2026-11-06 (issue #76) — une photo s'ouvre dans la visionneuse de l'app ; une vidéo ou un fichier, dans
 * l'app du système, comme avant. « Ouvrir avec » garde, pour la photo aussi, le chemin du système.
 *
 * Depuis une ACTIVITÉ, comme dans l'app : le sélecteur est lancé sans `FLAG_ACTIVITY_NEW_TASK`, qu'un contexte
 * d'application refuserait — et « rien n'a été lancé » serait alors vrai même si le code lançait quelque chose.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OuvertureDesPiecesJointesTest {

    private val activite: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun lance(): Intent? = shadowOf(activite).nextStartedActivity

    private fun piece(mime: String) = Attachment(
        id = 1L,
        messageId = 1L,
        mimeType = mime,
        fileName = "piece",
        sizeBytes = 10L,
        // `content://` : rendue telle quelle, sans passer par le FileProvider.
        localUri = "content://mms/part/7",
        width = null,
        height = null,
        durationMs = null,
    )

    @Test
    fun `une photo s'ouvre dans la visionneuse, sans app externe`() {
        val ouverture = OuvertureDesPiecesJointes(activite)
        val photo = piece("image/jpeg")

        ouverture.ouvrir(photo)

        assertThat(ouverture.enVisionneuse).isEqualTo(photo)
        assertThat(lance()).isNull()
    }

    /** Contrôle négatif : un fichier n'entre pas dans la visionneuse, il part à l'app du système. */
    @Test
    fun `un fichier part a l'app du systeme`() {
        val ouverture = OuvertureDesPiecesJointes(activite)

        ouverture.ouvrir(piece("application/pdf"))

        assertThat(ouverture.enVisionneuse).isNull()
        assertThat(checkNotNull(lance()).action).isEqualTo(Intent.ACTION_CHOOSER)
    }

    @Test
    fun `ouvrir avec une autre app, puis fermer`() {
        val ouverture = OuvertureDesPiecesJointes(activite)
        val photo = piece("image/png")
        ouverture.ouvrir(photo)

        ouverture.ouvrirAvec(photo)

        @Suppress("DEPRECATION")
        val cible = checkNotNull(lance()).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(cible?.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(cible?.flags?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION)).isNotEqualTo(0)

        ouverture.fermer()
        assertThat(ouverture.enVisionneuse).isNull()
    }
}
