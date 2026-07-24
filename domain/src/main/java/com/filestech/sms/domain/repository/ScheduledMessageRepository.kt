package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

interface ScheduledMessageRepository {
    fun observePending(): Flow<List<ScheduledMessage>>
    suspend fun schedule(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        scheduledAt: Long,
        subId: Int?,
    ): Outcome<Long>
    suspend fun cancel(id: Long): Outcome<Unit>
    suspend fun markSent(id: Long)
    suspend fun markFailed(id: Long)
}
