package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.data.local.db.entity.ScheduledState
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    @Query("SELECT * FROM scheduled_messages WHERE state = 0 ORDER BY scheduled_at ASC")
    fun observePending(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun findById(id: Long): ScheduledMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMessageEntity): Long

    /**
     * v1.22.x — reparent des envois programmés lors de la fusion de doublons de conversation
     * (dédup même numéro). Pas de FK sur `conversation_id` : reparent explicite par cohérence, pour
     * qu'un envoi programmé pointe vers la conversation survivante et non vers une ligne supprimée.
     */
    @Query("UPDATE scheduled_messages SET conversation_id = :toConversationId WHERE conversation_id = :fromConversationId")
    suspend fun reparentConversationId(fromConversationId: Long, toConversationId: Long)

    // v1.17.0 — Param `state` typé enum (était Int). TypeConverter [MessageEnumConverters]
    // convertit en Int pour le binding SQL. Cohérence avec MessageDao.updateStatus.
    @Query("UPDATE scheduled_messages SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: ScheduledState)

    @Query("UPDATE scheduled_messages SET work_id = :workId WHERE id = :id")
    suspend fun setWorkId(id: Long, workId: String?)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM scheduled_messages WHERE state = 0")
    suspend fun allPending(): List<ScheduledMessageEntity>
}
