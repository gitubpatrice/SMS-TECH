package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.AttachmentEntity

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE message_id = :messageId")
    suspend fun findForMessage(messageId: Long): List<AttachmentEntity>

    /**
     * Audit P-P0-1: bulk-fetch every attachment for a whole conversation in a single query.
     * Used by [com.filestech.sms.data.repository.ConversationRepositoryImpl.observeMessages]
     * which previously fired one [findForMessage] per audio row — an N+1 that exploded during
     * fresh-install imports (50 audio messages × N flow emissions = 50 N queries). The caller
     * groups the result by [AttachmentEntity.messageId] in memory; both reads share the same
     * `(message_id)` index so the join is index-only on the attachments side.
     */
    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.message_id
        WHERE m.conversation_id = :conversationId
        """,
    )
    suspend fun findForConversation(conversationId: Long): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteForMessage(messageId: Long)
}
