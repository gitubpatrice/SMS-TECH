package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickReplyDao {

    @Query("SELECT * FROM quick_replies ORDER BY position ASC")
    fun observe(): Flow<List<QuickReplyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: QuickReplyEntity): Long

    @Query("DELETE FROM quick_replies WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM quick_replies")
    suspend fun count(): Int
}
