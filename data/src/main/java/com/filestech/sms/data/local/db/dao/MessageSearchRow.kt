package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/** v1.28.8 — plafond de résultats de [MessageDao.observeSearch], les plus récents d'abord. */
internal const val SEARCH_LIMIT = 200

/** v1.28.8 — longueur, en mots, de l'extrait rendu par `snippet()`. */
internal const val SEARCH_EXCERPT_TOKENS = 16

/** v1.28.8 — une ligne de [MessageDao.observeSearch] : le message trouvé et son extrait marqué. */
data class MessageSearchRow(
    val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val date: Long,
    val excerpt: String,
)
