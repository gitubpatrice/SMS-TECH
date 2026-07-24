package com.filestech.sms.domain.mms

import java.io.File

/**
 * Description typée d'une pièce jointe pour un MMS multipart (domaine). Les octets sont lus depuis
 * le disque par l'implémentation d'envoi ; ce type ne porte que le [File] (JDK), le type MIME et le
 * [Kind] présentationnel — `domain/` reste sans dépendance data/Android.
 */
data class MmsAttachment(
    val file: File,
    val mimeType: String,
    /** Kind drives the SMIL element used for presentation (audio/img/text/ref). */
    val kind: Kind,
) {
    enum class Kind { AUDIO, IMAGE, VIDEO, OTHER }
}
