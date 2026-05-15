package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT * FROM conversations
        WHERE in_vault = 0
          AND archived = :includeArchived
        ORDER BY pinned DESC, last_message_at DESC
        """,
    )
    fun observe(includeArchived: Boolean = false): Flow<List<ConversationEntity>>

    /**
     * One-shot **unfiltered** snapshot of every non-vault conversation (both archived and not).
     * Used by [com.filestech.sms.data.blocking.BlockedNumbersImporter.purgeMatchingConversations]
     * which **must** see the rows the live `observeAll` filter is hiding (otherwise the purge
     * runs on already-filtered data and never finds anything to delete — chicken-and-egg).
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE in_vault = 0
        ORDER BY pinned DESC, last_message_at DESC
        """,
    )
    suspend fun snapshotAllNonVault(): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM conversations
        WHERE in_vault = 1
        ORDER BY last_message_at DESC
        """,
    )
    fun observeVault(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE thread_id = :threadId LIMIT 1")
    suspend fun findByThreadId(threadId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE addresses_csv = :csv LIMIT 1")
    suspend fun findByAddressesCsv(csv: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE display_name IS NULL OR display_name = ''")
    suspend fun findMissingDisplayName(): List<ConversationEntity>

    @Query("UPDATE conversations SET display_name = :displayName WHERE id = :id")
    suspend fun setDisplayName(id: Long, displayName: String?)

    /** Snapshot read for the backup pipeline (includes archived + in_vault rows). */
    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun listAllIncludingArchived(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: Long, muted: Boolean)

    @Query("UPDATE conversations SET in_vault = :inVault WHERE id = :id")
    suspend fun setInVault(id: Long, inVault: Boolean)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: Long, draft: String?)

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id")
    suspend fun clearUnread(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations WHERE unread_count > 0 AND in_vault = 0")
    fun observeUnreadConversationCount(): Flow<Int>
}
