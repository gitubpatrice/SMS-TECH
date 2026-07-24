package com.filestech.sms.domain.model

/**
 * Domain projection of a stored attachment. Carries the MIME type + local file URI so the UI
 * layer can decide how to render the attachment (audio player, image, generic chip…) without
 * pulling in the Room dependency.
 */
data class Attachment(
    val id: Long,
    val messageId: Long,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val localUri: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
) {
    val isAudio: Boolean get() = mimeType.startsWith("audio/", ignoreCase = true)
    val isImage: Boolean get() = mimeType.startsWith("image/", ignoreCase = true)
}
