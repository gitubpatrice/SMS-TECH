package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.settings.SortMode

/**
 * v1.28.8 (issue #17) — ordre de la liste : les conversations ÉPINGLÉES d'abord, dans tous les tris.
 *
 * En v1.6.1 (audit QUAL-14), `DATE` avait cessé de remonter les épinglées pour se distinguer de
 * `PINNED_FIRST`. Conséquence signalée par un utilisateur : épingler ne faisait RIEN de visible
 * dans le tri par défaut. L'épinglage redevient ce qu'il promet, et `PINNED_FIRST`, devenu
 * identique à `DATE`, est retiré. La requête de la liste triait déjà ainsi
 * (`ORDER BY pinned DESC, last_message_at DESC`).
 *
 * `sortedWith` est stable : à égalité, l'ordre reçu est conservé.
 */
internal fun trierConversations(rows: List<Conversation>, mode: SortMode): List<Conversation> {
    val epingleesDabord = compareByDescending<Conversation> { it.pinned }
    val ordre = when (mode) {
        SortMode.DATE -> epingleesDabord.thenByDescending { it.lastMessageAt }
        SortMode.UNREAD_FIRST ->
            epingleesDabord
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastMessageAt }
    }
    return rows.sortedWith(ordre)
}
