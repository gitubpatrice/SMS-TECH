package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

interface ScheduledMessageRepository {
    fun observePending(): Flow<List<ScheduledMessage>>

    /** v1.25.3 (audit H6) — envois abandonnés, les plus récents d'abord. */
    fun observeFailed(): Flow<List<ScheduledMessage>>

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

    /**
     * v1.25.3 (audit H6) — remet un envoi abandonné en attente pour [scheduledAt]. Ne re-planifie
     * rien par lui-même : c'est [com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase]
     * qui enchaîne avec le scheduler.
     */
    suspend fun rearmPending(id: Long, scheduledAt: Long)

    /** v1.25.3 (audit H6) — retire définitivement un envoi de la liste. */
    suspend fun delete(id: Long)
}
