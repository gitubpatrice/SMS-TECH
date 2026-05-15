package com.filestech.sms.data.repository

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.data.local.db.entity.ScheduledState
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.model.toDomain
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledMessageRepositoryImpl @Inject constructor(
    private val dao: ScheduledMessageDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ScheduledMessageRepository {

    override fun observePending(): Flow<List<ScheduledMessage>> =
        dao.observePending().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override suspend fun schedule(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        scheduledAt: Long,
        subId: Int?,
    ): Outcome<Long> = withContext(io) {
        if (addresses.isEmpty() || body.isBlank()) {
            return@withContext Outcome.Failure(AppError.Validation("addresses or body invalid"))
        }
        val now = System.currentTimeMillis()
        if (scheduledAt <= now) {
            return@withContext Outcome.Failure(AppError.Validation("scheduledAt must be in the future"))
        }
        val id = dao.upsert(
            ScheduledMessageEntity(
                conversationId = conversationId,
                addressesCsv = addresses.toCsv(),
                body = body,
                scheduledAt = scheduledAt,
                subId = subId,
                state = ScheduledState.PENDING,
                createdAt = now,
            ),
        )
        Outcome.Success(id)
    }

    override suspend fun cancel(id: Long): Outcome<Unit> = withContext(io) {
        dao.setState(id, ScheduledState.CANCELLED)
        Outcome.Success(Unit)
    }
    override suspend fun markSent(id: Long) = withContext(io) { dao.setState(id, ScheduledState.SENT) }
    override suspend fun markFailed(id: Long) = withContext(io) { dao.setState(id, ScheduledState.FAILED) }
}
