package com.filestech.sms.ui.screens.thread

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-11-15 (issue #76) — après l'export, « Ouvrir » doit proposer les applications qui LISENT un PDF
 * (`ACTION_VIEW`), et non le seul menu « Partager » (`ACTION_SEND`) : la liseuse PDF de GrapheneOS ne
 * déclare que `VIEW` + `application/pdf`, et n'apparaissait donc jamais.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IntentionsPdfTest {

    private val uri = Uri.parse("content://com.filestech.sms.fileprovider/exports/conversation.pdf")

    @Suppress("DEPRECATION")
    private fun cible(selecteur: Intent): Intent = checkNotNull(selecteur.getParcelableExtra(Intent.EXTRA_INTENT))

    @Test
    fun `ouvrir propose les lecteurs de PDF, avec le droit de lire le fichier`() {
        val selecteur = intentionOuvrirPdf(uri)

        assertThat(selecteur.action).isEqualTo(Intent.ACTION_CHOOSER)
        val ouvrir = cible(selecteur)
        assertThat(ouvrir.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(ouvrir.data).isEqualTo(uri)
        assertThat(ouvrir.type).isEqualTo("application/pdf")
        assertThat(ouvrir.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `partager reste le menu Partager d'avant`() {
        val selecteur = intentionPartagerPdf(uri)

        assertThat(selecteur.action).isEqualTo(Intent.ACTION_CHOOSER)
        val partager = cible(selecteur)
        assertThat(partager.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(partager.type).isEqualTo("application/pdf")
        @Suppress("DEPRECATION")
        assertThat(partager.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
        assertThat(partager.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }
}
