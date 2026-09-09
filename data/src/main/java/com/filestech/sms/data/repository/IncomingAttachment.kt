package com.filestech.sms.data.repository

import java.io.File

/**
 * v1.28.3 (F16) — une pièce jointe d'un MMS **entrant**, déjà écrite sur disque.
 *
 * # Pourquoi ce type existe
 *
 * `ConversationMirror.upsertIncomingMms` prenait un unique `attachmentFile`, et le sélecteur du
 * receveur s'appelait `extractFirstMediaPart` : d'un MMS à trois photos, **une seule** était
 * conservée. Le PDU était ensuite supprimé — et c'était la seule copie, aucun MMS entrant n'étant
 * écrit côté fournisseur système. Les autres parties étaient perdues définitivement.
 *
 * La table `attachments` savait pourtant porter plusieurs lignes par message depuis toujours :
 * c'est le chemin SORTANT qui en profitait (`upsertOutgoingMediaMms` et sa liste de
 * `MediaAttachmentSpec`), le chemin entrant non. Le même motif de jumeau asymétrique que la
 * plupart des défauts de cette relecture.
 *
 * Distinct de `MediaAttachmentSpec`, qui décrit une pièce jointe à ENVOYER : celle-ci est déjà
 * persistée, son `File` est final, et elle porte une durée quand le média en a une.
 */
data class IncomingAttachment(
    val file: File,
    val mimeType: String,
    val durationMs: Long? = null,
)
