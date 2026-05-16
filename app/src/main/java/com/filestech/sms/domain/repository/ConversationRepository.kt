package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Conversation>>
    fun observeVault(): Flow<List<Conversation>>
    fun observeOne(id: Long): Flow<Conversation?>
    fun observeMessages(conversationId: Long): Flow<List<Message>>
    fun observeUnreadConversationCount(): Flow<Int>

    suspend fun findOrCreate(addresses: List<PhoneAddress>): Outcome<Conversation>
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun setMuted(id: Long, muted: Boolean)
    suspend fun moveToVault(id: Long, inVault: Boolean)
    suspend fun setDraft(id: Long, draft: String?)
    suspend fun markRead(id: Long)
    suspend fun delete(id: Long)
    suspend fun deleteMessage(messageId: Long)
    suspend fun search(query: String): List<Message>

    /**
     * v1.3.0 — pose ([emoji] non-null) ou retire ([emoji] = null) une réaction emoji locale
     * sur le message [messageId]. Aucun écho réseau (pas de send-side-effect — les réactions
     * ne sont pas standardisées en SMS/MMS, c'est purement côté Room). No-op silencieux si le
     * message n'existe plus (ex. : purgé par l'auto-purge, supprimé concurrently).
     */
    suspend fun setReaction(messageId: Long, emoji: String?)

    /**
     * v1.3.0 — compte combien de messages seraient effacés par un nettoyage manuel à la
     * profondeur [olderThanDays] (jours). Utilisé par le dialog réglages avant confirmation.
     * Ne touche jamais aux favoris ni aux 5 derniers jours (filet interne).
     */
    suspend fun countMessagesToPurge(olderThanDays: Int): Int

    /**
     * v1.3.0 — efface MAINTENANT les messages plus vieux que [olderThanDays] jours, en
     * appliquant le filet interne 5 jours et en épargnant les favoris. Ne touche pas au
     * cycle auto-mensuel (le `lastAutoPurgeAt` n'est pas mis à jour). Retourne le nombre
     * de rows effacées pour feedback UI.
     */
    suspend fun purgeHistoryNow(olderThanDays: Int): Int
}
