package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    @Query("SELECT * FROM scheduled_messages WHERE state = 0 ORDER BY scheduled_at ASC")
    fun observePending(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun findById(id: Long): ScheduledMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMessageEntity): Long

    @Query("UPDATE scheduled_messages SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: Int)

    @Query("UPDATE scheduled_messages SET work_id = :workId WHERE id = :id")
    suspend fun setWorkId(id: Long, workId: String?)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM scheduled_messages WHERE state = 0")
    suspend fun allPending(): List<ScheduledMessageEntity>
}
