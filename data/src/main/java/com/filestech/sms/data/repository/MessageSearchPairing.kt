package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.dao.MessageSearchRow
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit

/**
 * v1.28.8 (audit 3 axes, Q2) — la requête FTS à transmettre, ou `null` s'il n'y a rien à chercher :
 * moins de [MessageSearchHit.MIN_QUERY_LENGTH] caractères significatifs, ou plus rien après
 * l'échappement de [escapeFtsQuery]. Sorti de `ConversationRepositoryImpl` pour être testé.
 */
internal fun requeteRecherchable(query: String): String? {
    if (query.trim().length < MessageSearchHit.MIN_QUERY_LENGTH) return null
    return escapeFtsQuery(query).takeIf { it.isNotBlank() }
}

/**
 * v1.28.8 (audit 3 axes, Q2) — associe chaque message trouvé à sa conversation, relue HORS coffre.
 *
 * Défense en profondeur, en plus des filtres SQL : un message dont la conversation manque — passée au
 * coffre ou supprimée entre la recherche et cette relecture — est écarté ; une conversation marquée
 * coffre l'est aussi, même si une liste fautive l'apportait. Un message n'est jamais montré sans sa
 * conversation. L'ordre des lignes (les plus récentes d'abord) est conservé.
 */
internal fun apparierResultats(
    rows: List<MessageSearchRow>,
    conversations: List<Conversation>,
): List<MessageSearchHit> {
    val horsCoffre = conversations.filterNot { it.inVault }.associateBy { it.id }
    return rows.mapNotNull { row ->
        horsCoffre[row.conversationId]?.let { conversation ->
            MessageSearchHit(row.id, row.date, row.excerpt, conversation)
        }
    }
}
