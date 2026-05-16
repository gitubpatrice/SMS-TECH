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
     * v1.2.6 audit F2 : stocke l'`_id` `content://mms` que `MmsSystemWriteback.insertOutbox`
     * a renvoyé pour ce message Room. Permet à la prochaine tentative (retry après échec) de
     * détecter et supprimer la row OUTBOX/FAILED précédente avant d'en insérer une nouvelle.
     */
    @Query("UPDATE messages SET mms_system_id = :mmsSystemId WHERE id = :id")
    suspend fun setMmsSystemId(id: Long, mmsSystemId: Long?)

    /** Inverse de [setMmsSystemId] — utilisé par le retry pour récupérer la row à purger. */
    @Query("SELECT mms_system_id FROM messages WHERE id = :id")
    suspend fun findMmsSystemId(id: Long): Long?

    /**
     * v1.3.0 — set / clear la réaction emoji posée par l'utilisateur sur un message. `null`
     * = retire la réaction. Aucun écho côté SMS/MMS (réactions non standardisées en SMS).
     */
    @Query("UPDATE messages SET reaction_emoji = :emoji WHERE id = :id")
    suspend fun setReaction(id: Long, emoji: String?)

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
     * v1.2.3 audit P4: rewritten as `EXISTS` so SQLite stops at the first hit instead of
     * scanning every row of `messages` (no index on `type`). The sync manager only cares about
     * "zero vs. non-zero" — first-run import trigger vs. fresh DB after package switch — so a
     * 1-bit answer is enough. Cuts the recurring per-sync cost from ~10-30 ms on a 50k-message
     * DB down to ~1 ms.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE type = 1 LIMIT 1)")
    suspend fun hasAnyMms(): Boolean

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

    /**
     * Purge les messages dont la date est antérieure à [olderThan] et qui ne sont pas starred.
     *
     * v1.3.0 audit Q2/Q3 : le caller doit pré-calculer `olderThan` en intégrant le safety net
     * (`min(now - retentionDays·DAY, now - SAFETY_NET_DAYS·DAY)`). On ne fait pas ce calcul ici
     * pour éviter une 2ᵉ surcharge SQL dont la condition `date < safetyNet` est impliquée par
     * `date < olderThan` dès que `retentionDays >= SAFETY_NET_DAYS` — c'était un faux filet de
     * sécurité côté DB. La logique reste centralisée côté worker, observable et testable.
     */
    @Query("DELETE FROM messages WHERE date < :olderThan AND starred = 0")
    suspend fun purgeOlderThan(olderThan: Long): Int

    /**
     * v1.3.0 — compte combien de messages seraient effacés par [purgeOlderThan] avec ce
     * même `olderThan`. Sert au bouton "Effacer maintenant" du dialog réglages : on
     * affiche d'abord à l'utilisateur "X messages vont être effacés, continuer ?" pour
     * éviter un wipe massif accidentel. Utilise le même filtre `starred = 0` pour la
     * cohérence parfaite avec la purge réelle.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE date < :olderThan AND starred = 0")
    suspend fun countOlderThan(olderThan: Long): Int
}
