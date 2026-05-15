package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.BlockedNumber
import kotlinx.coroutines.flow.Flow

interface BlockedNumberRepository {
    fun observe(): Flow<List<BlockedNumber>>
    suspend fun isBlocked(rawNumber: String): Boolean
    suspend fun block(rawNumber: String, label: String? = null): Outcome<Unit>
    suspend fun unblock(rawNumber: String): Outcome<Unit>

    /**
     * Mirrors a number that **already exists** in the system blocklist into our Room cache,
     * without writing back to the system provider. Used by [com.filestech.sms.data.blocking.BlockedNumbersImporter]
     * at boot to absorb the OS-wide blocklist (Téléphone / Samsung Messages entries the user
     * already has) without creating an insert loop. No-op if the number is already mirrored.
     */
    suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit>

    /** Returns every blocked normalized number currently mirrored in Room. Snapshot. */
    suspend fun blockedNormalizedSnapshot(): Set<String>
}
