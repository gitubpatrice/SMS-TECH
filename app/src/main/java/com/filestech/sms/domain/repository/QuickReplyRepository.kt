package com.filestech.sms.domain.repository

import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import kotlinx.coroutines.flow.Flow

interface QuickReplyRepository {
    fun observe(): Flow<List<QuickReplyEntity>>
    suspend fun add(text: String)
    suspend fun update(entity: QuickReplyEntity)
    suspend fun delete(id: Long)
}
