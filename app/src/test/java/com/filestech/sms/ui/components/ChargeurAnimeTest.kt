package com.filestech.sms.ui.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.imageLoader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-11-06 (issue #76) — la visionneuse anime les GIF : son chargeur porte le décodeur d'images animées
 * (`ImageDecoderDecoder` à partir d'Android 9, `GifDecoder` avant), le chargeur de l'application non.
 */
@RunWith(AndroidJUnit4::class)
class ChargeurAnimeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun `android 9 et plus - ImageDecoderDecoder`() {
        val decodeurs = chargeurAnime(app).components.decoderFactories

        assertThat(decodeurs.any { it is ImageDecoderDecoder.Factory }).isTrue()
    }

    @Test
    @Config(sdk = [26])
    fun `avant android 9 - GifDecoder`() {
        val decodeurs = chargeurAnime(app).components.decoderFactories

        assertThat(decodeurs.any { it is GifDecoder.Factory }).isTrue()
    }

    /** Contrôle négatif : le chargeur de l'application, celui des vignettes, n'anime rien. */
    @Test
    @Config(sdk = [34])
    fun `le chargeur de l'application n'anime pas les vignettes`() {
        val decodeurs = app.imageLoader.components.decoderFactories

        assertThat(decodeurs.none { it is ImageDecoderDecoder.Factory || it is GifDecoder.Factory }).isTrue()
    }
}
