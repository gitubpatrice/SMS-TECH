package com.filestech.sms.domain.model

/**
 * Réponse rapide pré-enregistrée (domaine). Projection de `QuickReplyEntity` (Room) pour que
 * l'interface [com.filestech.sms.domain.repository.QuickReplyRepository] n'expose plus de type data.
 */
data class QuickReply(
    val id: Long,
    val text: String,
    val position: Int,
)
