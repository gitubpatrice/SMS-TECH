package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.ConversationOverrideEntity

@Dao
interface ConversationOverrideDao {
    @Query("SELECT * FROM conversation_overrides WHERE conversation_id = :id LIMIT 1")
    suspend fun find(id: Long): ConversationOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationOverrideEntity)

    @Query("DELETE FROM conversation_overrides WHERE conversation_id = :id")
    suspend fun delete(id: Long)
}
