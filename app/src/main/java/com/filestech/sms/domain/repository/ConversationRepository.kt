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
}
