package com.filestech.sms.data.repository

import android.os.Build
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.toDomain
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockedNumberRepositoryImpl @Inject constructor(
    private val dao: BlockedNumberDao,
    private val system: BlockedNumberSystem,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BlockedNumberRepository {

    override fun observe(): Flow<List<BlockedNumber>> =
        dao.observe().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override suspend fun isBlocked(rawNumber: String): Boolean = withContext(io) {
        dao.isBlocked(rawNumber.normalizePhone())
    }

    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        val normalized = rawNumber.normalizePhone()
        val systemUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) system.block(rawNumber) else null
        dao.upsert(
            BlockedNumberEntity(
                normalizedNumber = normalized,
                rawNumber = rawNumber,
                label = label,
                createdAt = System.currentTimeMillis(),
                systemUri = systemUri,
            ),
        )
        Outcome.Success(Unit)
    }

    override suspend fun unblock(rawNumber: String): Outcome<Unit> = withContext(io) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) system.unblock(rawNumber)
        dao.deleteByNormalized(rawNumber.normalizePhone())
        Outcome.Success(Unit)
    }

    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        val normalized = rawNumber.normalizePhone()
        // No-op when already mirrored — keeps `created_at` stable and avoids a write storm at
        // boot when nothing has changed since last launch.
        if (dao.isBlocked(normalized)) return@withContext Outcome.Success(Unit)
        dao.upsert(
            BlockedNumberEntity(
                normalizedNumber = normalized,
                rawNumber = rawNumber,
                label = null,
                createdAt = System.currentTimeMillis(),
                // Deliberately null: we did NOT call `system.block()` here, the entry already
                // exists in the OS provider.
                systemUri = null,
            ),
        )
        Outcome.Success(Unit)
    }

    override suspend fun blockedNormalizedSnapshot(): Set<String> = withContext(io) {
        dao.allNormalized().toHashSet()
    }
}
