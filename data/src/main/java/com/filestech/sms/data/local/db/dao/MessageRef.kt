package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * v1.28.7 — un message désigné par son identifiant ET sa conversation, sans son contenu.
 *
 * Projeté par [MessageDao.findRefsOlderThan] et [MessageDao.findRefsByTelephonyUris], lus AVANT
 * une suppression de masse pour annuler ensuite les notifications de ce qui a disparu. Le corps
 * du message n'a rien à faire en mémoire pour cela, et une purge de rétention peut en compter
 * des dizaines de milliers.
 */
data class MessageRef(
    val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
)
