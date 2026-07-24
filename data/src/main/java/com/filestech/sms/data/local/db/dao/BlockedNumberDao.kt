package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNumberDao {

    @Query("SELECT * FROM blocked_numbers ORDER BY created_at DESC")
    fun observe(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT * FROM blocked_numbers WHERE normalized_number = :normalized LIMIT 1")
    suspend fun findByNormalized(normalized: String): BlockedNumberEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE normalized_number = :normalized)")
    suspend fun isBlocked(normalized: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedNumberEntity): Long

    @Query("DELETE FROM blocked_numbers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM blocked_numbers WHERE normalized_number = :normalized")
    suspend fun deleteByNormalized(normalized: String)

    /** Snapshot of every blocked normalized number — used to filter SMS imports + UI lists. */
    @Query("SELECT normalized_number FROM blocked_numbers")
    suspend fun allNormalized(): List<String>
}
