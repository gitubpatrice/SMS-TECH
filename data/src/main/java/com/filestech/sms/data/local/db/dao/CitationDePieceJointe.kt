package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * v1.28.9 (septième note d'Andrew, constat 1) — une ligne `attachments` qui cite un fichier, avec
 * ce qu'il faut pour décider si elle compte encore : son message et sa conversation.
 *
 * Projeté par [AttachmentDao.findCitations]. Un même fichier peut être cité par plusieurs lignes —
 * un envoi à plusieurs destinataires, l'écho de groupe, un envoi programmé — et c'est cette
 * projection qui permet à [com.filestech.sms.data.repository.FichiersDePiecesJointes] d'écarter
 * les citations qui partent avec ce que l'on supprime, et de garder le fichier pour les autres.
 */
data class CitationDePieceJointe(
    @ColumnInfo(name = "local_uri") val localUri: String,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
)
