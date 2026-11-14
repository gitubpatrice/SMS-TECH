package com.filestech.sms.data.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2026-11-14 (seconde relecture GPT) — l'effacement d'urgence ANNULE le résultat des téléchargements MMS en cours.
 *
 * Un téléchargement lancé avant l'effacement pouvait aboutir après lui et écrire le message dans la base vidée ; la
 * marque en mémoire ne tient pas si le processus meurt entre-temps. Le `PendingIntent` de résultat, lui, est annulé
 * dans le système.
 *
 * Le `PendingIntent` est recréé ici avec l'identité que lui donne [MmsDownloader.download] (action, receveur, code
 * tiré du chemin du PDU, mutable) : sous Robolectric, le `FileProvider` ne connaît pas le dossier de test et le vrai
 * téléchargement échoue avant de le créer. Cette identité est le contrat que l'annulation doit tenir.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnnulationDesResultatsTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val dossier = File(contexte.cacheDir, MmsDownloader.MMS_IN_DIR)

    private fun identite() = Intent(MmsDownloader.ACTION_MMS_DOWNLOADED)
        .setClassName(contexte, "com.filestech.sms.system.receiver.MmsDownloadedReceiver")

    private fun code(pdu: File) = pdu.absolutePath.hashCode() and 0x7FFFFFFF

    /** Le `PendingIntent` de résultat de [pdu] ; `null` s'il n'existe pas. */
    private fun resultatDe(pdu: File): PendingIntent? = PendingIntent.getBroadcast(
        contexte,
        code(pdu),
        identite(),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
    )

    @Test
    fun `l'effacement annule le resultat d'un telechargement en cours`() {
        val pdu = File(dossier.apply { mkdirs() }, "in-1-0000abcd.pdu").apply { createNewFile() }
        PendingIntent.getBroadcast(
            contexte,
            code(pdu),
            identite().putExtra(MmsDownloader.EXTRA_PDU_FILE, pdu.absolutePath),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        // Témoin positif : le résultat attendu existe bien avant l'effacement.
        assertThat(resultatDe(pdu)).isNotNull()

        val annules = MmsDownloader.annulerLesResultatsEnAttente(contexte)

        assertThat(annules).isEqualTo(1)
        assertThat(resultatDe(pdu)).isNull()
    }

    /** Contrôle négatif : un PDU sans téléchargement en cours n'annule rien, et pas d'erreur sans dossier. */
    @Test
    fun `rien en cours - rien d'annule`() {
        dossier.deleteRecursively()
        assertThat(MmsDownloader.annulerLesResultatsEnAttente(contexte)).isEqualTo(0)

        File(dossier.apply { mkdirs() }, "in-2-0000beef.pdu").createNewFile()
        assertThat(MmsDownloader.annulerLesResultatsEnAttente(contexte)).isEqualTo(0)
    }
}
