package com.filestech.sms.data.local.db

import com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload
import java.io.File

/**
 * v1.26.0 — sérialise les pièces jointes d'un envoi programmé pour la colonne
 * `scheduled_messages.attachments_json`.
 *
 * Cette colonne existait **depuis la création de la table et n'était écrite nulle part** : sa
 * seule occurrence dans tout le dépôt était sa propre déclaration. La fonctionnalité avait été
 * prévue, la colonne créée, et le câblage jamais fait — si bien qu'on pouvait programmer un
 * message avec une pièce jointe et voir partir le texte seul.
 *
 * **Format ligne par ligne, champs séparés par `|`, chemin en DERNIER** :
 * ```
 * image/jpeg|1024|768||/data/user/0/…/files/mms_attachments/out-1712-ab12cd34.jpg
 * audio/amr||||/data/user/0/…/files/mms_attachments/out-1713-ef56ab78.amr
 * ```
 * Les champs numériques vides valent `null`. Le chemin est placé en dernier **exprès** : le
 * découpage se fait avec une limite, donc un chemin contenant un `|` survit au voyage. Les sauts
 * de ligne, eux, sont impossibles dans un nom de fichier Android.
 *
 * Même parti que [com.filestech.sms.data.local.datastore.SafetyCallContactCodec] : un format
 * trivial plutôt que du JSON — pas de dépendance Android-only (`org.json` n'est pas pur JVM),
 * schéma fixe, lisible directement en base pendant un diagnostic.
 *
 * Tolérant au décodage : une ligne malformée est ignorée plutôt que de faire échouer la lecture
 * de tout l'envoi. Un envoi programmé perdu vaut mieux qu'une liste illisible.
 */
internal object ScheduledAttachmentCodec {

    private const val FIELDS = 5

    fun encode(attachments: List<AttachmentPayload>): String? {
        if (attachments.isEmpty()) return null
        return attachments.joinToString(separator = "\n") { a ->
            listOf(
                a.mimeType.trim().replace(FORBIDDEN, ""),
                a.width?.toString().orEmpty(),
                a.height?.toString().orEmpty(),
                a.durationMs?.toString().orEmpty(),
                a.file.absolutePath,
            ).joinToString(separator = "|")
        }
    }

    fun decode(raw: String?): List<AttachmentPayload> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = FIELDS)
                if (parts.size < FIELDS) return@mapNotNull null
                val path = parts[4].trim()
                if (path.isEmpty()) return@mapNotNull null
                AttachmentPayload(
                    file = File(path),
                    mimeType = parts[0].trim().ifEmpty { "application/octet-stream" },
                    width = parts[1].toIntOrNull(),
                    height = parts[2].toIntOrNull(),
                    durationMs = parts[3].toLongOrNull(),
                )
            }
            .toList()
    }

    /** Séparateur et caractères de contrôle : interdits dans les champs autres que le chemin. */
    private val FORBIDDEN = Regex("[\\u0000-\\u001F\\u007F|]")
}
