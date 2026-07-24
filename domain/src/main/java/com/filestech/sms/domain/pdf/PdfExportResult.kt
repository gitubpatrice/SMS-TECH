package com.filestech.sms.domain.pdf

/**
 * Résultat d'un export PDF (domaine). [shareUri] est l'URI de partage (content://) sous forme de
 * chaîne opaque — l'UI la reparse en `android.net.Uri` — de sorte que `domain/` reste sans import
 * Android.
 */
data class PdfExportResult(
    val shareUri: String,
    val pages: Int,
)
