package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY date ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE telephony_uri = :uri LIMIT 1")
    suspend fun findByTelephonyUri(uri: String): MessageEntity?

    /** One-shot suspend snapshot used by deletion paths that need each row's telephony URI. */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId")
    suspend fun findByConversation(conversationId: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET read = 1 WHERE conversation_id = :conversationId AND read = 0")
    suspend fun markConversationRead(conversationId: Long)

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE messages SET status = :status, error_code = :errorCode WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int, errorCode: Int? = null)

    /**
     * Audit M-5 + M-1: stalls-watchdog. Bulk-promotes outgoing messages stuck in `PENDING`
     * (status 0) past [olderThanMs] to `FAILED` (status 3) **and tags them with the
     * dedicated `error_code = -2` (WATCHDOG_TIMEOUT) sentinel** to distinguish them from
     * synchronously-failed sends (which use `-1`).
     *
     * The discrimination matters for idempotence: a watchdog-promoted row may have been
     * accepted by the radio (we just never received the sent-broadcast), so a retry could
     * produce a duplicate SMS at the recipient. Synchronously-failed rows are guaranteed
     * never to have reached the radio (SmsManager threw before dispatch) and can be retried
     * freely. The UI consumes `errorCode` to show the appropriate warning on retry.
     *
     * Direction filter: only `OUTGOING` rows (direction=1) — incoming RECEIVED-status
     * messages never sit in PENDING.
     */
    @Query(
        """
        UPDATE messages
           SET status = 3, error_code = -2
         WHERE status = 0
           AND direction = 1
           AND date < :olderThanMs
        """,
    )
    suspend fun timeoutStalePending(olderThanMs: Long): Int

    /** Snapshot read for backup pipeline. */
    @Query("SELECT * FROM messages ORDER BY conversation_id ASC, date ASC")
    suspend fun listAll(): List<MessageEntity>

    /**
     * Count of MMS rows in the mirror. Used by [com.filestech.sms.data.sync.TelephonySyncManager]
     * to decide whether the historical MMS import from `content://mms` is needed — first-run
     * (`lastSyncedSmsId == 0`) used to be the only trigger, but reinstalling over a different
     * package (release vs. debug) leaves the SMS cursor populated while Room is freshly empty.
     * Querying both numbers lets the manager catch every case.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE type = 1")
    suspend fun countMms(): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteAllForConversation(conversationId: Long)

    /**
     * Returns every non-vault `telephony_uri` we have mirrored. Used by [TelephonySyncManager]
     * to detect rows that have been deleted from the system content provider (by any actor)
     * so we can drop them locally and stay convergent with the OS. Vault rows are filtered out
     * here because they exist only in our SQLCipher DB — they have no telephony_uri to compare.
     */
    @Query(
        """
        SELECT m.telephony_uri FROM messages m
        JOIN conversations c ON c.id = m.conversation_id
        WHERE m.telephony_uri IS NOT NULL AND c.in_vault = 0
        """,
    )
    suspend fun listMirroredTelephonyUris(): List<String>

    /**
     * Bulk-deletes Room rows whose [MessageEntity.telephonyUri] is in [uris]. Used by the sync
     * manager's deletion-reconciliation pass. SQLite caps `IN (…)` at 999 host parameters; the
     * caller chunks larger inputs.
     */
    @Query("DELETE FROM messages WHERE telephony_uri IN (:uris)")
    suspend fun deleteByTelephonyUris(uris: List<String>): Int

    /** FTS search across body + address. Returns matching message ids ordered by relevance. */
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts ON messages_fts.rowid = m.id
        WHERE messages_fts MATCH :query
        ORDER BY m.date DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<MessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = :conversationId AND date >= :since
        """,
    )
    suspend fun countSince(conversationId: Long, since: Long): Int

    @Query(
        """
        DELETE FROM messages WHERE date < :olderThan AND starred = 0
        """,
    )
    suspend fun purgeOlderThan(olderThan: Long): Int
}
