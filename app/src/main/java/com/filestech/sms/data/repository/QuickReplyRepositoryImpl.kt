package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.QuickReplyRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickReplyRepositoryImpl @Inject constructor(
    private val dao: QuickReplyDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : QuickReplyRepository {
    override fun observe(): Flow<List<QuickReplyEntity>> = dao.observe().flowOn(io)
    override suspend fun add(text: String) = withContext(io) {
        val count = dao.count()
        dao.upsert(QuickReplyEntity(text = text, position = count))
        Unit
    }
    override suspend fun update(entity: QuickReplyEntity) = withContext(io) { dao.upsert(entity); Unit }
    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }
}
