package com.filestech.sms.ui.screens.thread

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R
import timber.log.Timber

/**
 * 2026-11-15 (issue #76) — **après l'export, OUVRIR le PDF ou le PARTAGER.**
 *
 * L'export ouvrait directement le menu « Partager » (`ACTION_SEND`). Or une liseuse PDF déclare
 * d'ordinaire `ACTION_VIEW` seulement : celle de GrapheneOS, application du système, ne déclare QUE
 * `VIEW` + `application/pdf` (manifeste relu le 2026-11-15). Elle ne pouvait donc jamais apparaître dans
 * ce menu, et l'utilisateur devait copier le fichier pour le lire. La fenêtre propose les deux gestes.
 */
@Composable
internal fun FenetrePdfPret(uri: Uri, pages: Int, onFermer: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onFermer,
        // Pluriel : « PDF enregistré (1 pages) » s'affichait (relecture Claude du 2026-11-15).
        title = { Text(pluralStringResource(R.plurals.pdf_export_title, pages, pages)) },
        text = { Text(stringResource(R.string.pdf_ready_body)) },
        confirmButton = {
            TextButton(onClick = {
                onFermer()
                lancer(context, intentionOuvrirPdf(uri))
            }) { Text(stringResource(R.string.pdf_action_open)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onFermer()
                lancer(context, intentionPartagerPdf(uri))
            }) { Text(stringResource(R.string.pdf_action_share)) }
        },
    )
}

/**
 * « Ouvrir » : les applications qui LISENT un PDF, par le choix ordinaire d'Android — sans sélecteur
 * imposé, pour que « Toujours » puisse retenir la liseuse préférée (relecture Gemini du 2026-11-15). Aucune
 * application : `ActivityNotFoundException`, rattrapée par [lancer].
 */
internal fun intentionOuvrirPdf(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, MIME_PDF)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Le menu « Partager », tel qu'il s'ouvrait seul jusqu'ici. */
internal fun intentionPartagerPdf(uri: Uri): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = MIME_PDF
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    "PDF",
)

private fun lancer(context: Context, intention: Intent) {
    runCatching { context.startActivity(intention) }
        .onFailure { Timber.w(it, "PDF: aucune application pour ce geste") }
}

private const val MIME_PDF = "application/pdf"
