package com.filestech.sms.domain.repository

import com.filestech.sms.domain.model.QuickReply
import kotlinx.coroutines.flow.Flow

interface QuickReplyRepository {
    fun observe(): Flow<List<QuickReply>>
    suspend fun add(text: String)
    suspend fun update(quickReply: QuickReply)
    suspend fun delete(id: Long)
}
