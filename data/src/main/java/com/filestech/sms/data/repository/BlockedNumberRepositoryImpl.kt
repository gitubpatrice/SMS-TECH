package com.filestech.sms.data.repository

import android.os.Build
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.BlockedNumber
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

    /**
     * v1.25.3 — clé de stockage repliée sur le libellé brut quand la normalisation ne rend rien.
     *
     * `normalizePhone()` ne garde que les chiffres, `+` et `*#` : un expéditeur alphanumérique
     * (« SFR », « Free ») se réduisait donc à la chaîne **vide**. Toutes ces entrées partageaient
     * alors la même clé, et bloquer un seul de ces expéditeurs les bloquait **tous** — sans que
     * rien ne l'indique. Même repli que [com.filestech.sms.domain.model.PhoneAddress.of].
     */
    private fun blockKey(rawNumber: String): String =
        rawNumber.normalizePhone().ifEmpty { rawNumber.trim() }

    override suspend fun isBlocked(rawNumber: String): Boolean = withContext(io) {
        val key = blockKey(rawNumber)
        key.isNotEmpty() && dao.isBlocked(key)
    }

    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        val normalized = blockKey(rawNumber)
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
        dao.deleteByNormalized(blockKey(rawNumber))
        Outcome.Success(Unit)
    }

    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        val normalized = blockKey(rawNumber)
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
