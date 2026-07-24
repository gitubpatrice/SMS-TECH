package com.filestech.sms.domain.mms

import java.io.File

/**
 * Description fournie par l'appelant d'une pièce jointe MMS **sortante** à miroiter en base
 * (domaine). Distinct de [MmsAttachment] (qui décrit la pièce pour l'encodage PDU) : celui-ci
 * porte les métadonnées d'affichage (dimensions, durée) de la ligne Room. Ne dépend que de [File]
 * (JDK).
 */
data class MediaAttachmentSpec(
    val file: File,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)
