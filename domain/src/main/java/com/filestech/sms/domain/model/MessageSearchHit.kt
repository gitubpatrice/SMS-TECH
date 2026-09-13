package com.filestech.sms.domain.model

/**
 * v1.28.8 (issue #17) — un message trouvé par la recherche plein texte, avec sa conversation.
 *
 * [excerpt] est un extrait du corps produit par l'index : les passages trouvés y sont encadrés par
 * [MARK_START] et [MARK_END], deux caractères de la zone à usage privé d'Unicode que l'écran
 * transforme en mise en évidence et ne montre jamais.
 */
data class MessageSearchHit(
    val messageId: Long,
    val date: Long,
    val excerpt: String,
    val conversation: Conversation,
) {
    companion object {
        const val MARK_START = "\uE000"
        const val MARK_END = "\uE001"

        /** En deçà, un préfixe comme `a*` correspond à presque tout l'historique : rien n'est cherché. */
        const val MIN_QUERY_LENGTH = 2
    }
}
