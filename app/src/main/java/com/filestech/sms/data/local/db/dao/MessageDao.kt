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

    /**
     * v1.4.1 — excludes the **reaction sentinel rows** so the thread doesn't paint
     * an empty bubble for every Tapback-folded reaction. Two flavors of sentinel
     * exist :
     *
     *   - **Incoming sentinel** — inserted by [com.filestech.sms.data.repository
     *     .ConversationMirror.upsertReactionSentinel] after we fold an incoming
     *     Tapback SMS into a reaction badge. Carries the `telephony_uri` of the
     *     system inbox row so the UNIQUE index blocks
     *     [com.filestech.sms.data.sync.TelephonySyncManager] from re-importing
     *     the `Reacted ❤️ to «…»` body as a phantom text bubble.
     *   - **Outgoing sentinel** — inserted by [com.filestech.sms.data.repository
     *     .ConversationMirror.upsertOutgoingSms] when [SendReactionUseCase]
     *     passes `localMirrorBody = ""`. The Tapback SMS is still on the wire +
     *     in the system inbox (read by other SMS apps / the correspondent), but
     *     the reactor doesn't see a redundant outgoing text bubble in their own
     *     thread — they already have the local badge on the message they
     *     reacted to.
     *
     * Both sentinels share the same shape : `body = ''` + no attachment + no
     * reaction emoji, regardless of direction. A legitimate empty body always
     * carries either an attachment (image / audio / file MMS without caption) or
     * a reaction emoji, so the predicate is tight enough to never hide a real
     * message — outgoing SMS reject blank bodies in [SendSmsUseCase] before they
     * ever reach the DB.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND NOT (
              body = ''
              AND attachments_count = 0
              AND reaction_emoji IS NULL
          )
        ORDER BY date ASC
        """
    )
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
     * v1.4.1 — finds the most recent OUTGOING message in [conversationId] whose body
     * starts with [bodyPrefix] (used by the incoming Tapback parser to match a remote
     * "Reacted ❤️ to «hello…»" SMS back to the original outgoing message it was reacting
     * to). The lookup is scoped to outgoing messages because a reaction-back can only
     * target something WE sent.
     *
     * The `bodyPrefix` is fed as the LHS of a SQL `LIKE :bodyPrefix || '%'` so the
     * caller must escape any `%` / `_` / `\` it contains beforehand.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND direction = 1
          AND body LIKE :bodyPrefix || '%' ESCAPE '\'
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun findMostRecentOutgoingByBodyPrefix(
        conversationId: Long,
        bodyPrefix: String,
    ): MessageEntity?

    /**
     * v1.4.1 — finds the most recent OUTGOING message in [conversationId] regardless of
     * its body. Used when the incoming Tapback has no `to «…»` segment (the reacting
     * party reacted to a message with no text body, e.g. voice MMS or image-only).
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND direction = 1
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun findMostRecentOutgoing(conversationId: Long): MessageEntity?

    /**
     * v1.4.1 — finds the most recent OUTGOING message in [conversationId] whose `date`
     * is strictly greater than [sinceMs] (epoch ms). Used by the emoji-only reaction
     * decode path : a bare emoji SMS is only folded onto an outgoing message if that
     * message was sent within the last ~5 minutes, so a standalone "❤️" sent days
     * later cannot be silently glued onto an unrelated past message.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND direction = 1
          AND date > :sinceMs
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun findMostRecentOutgoingAfter(
        conversationId: Long,
        sinceMs: Long,
    ): MessageEntity?

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
     * v1.3.3 G1 audit fix — après une purge, refresh `last_message_at` +
     * `last_message_preview` de TOUTES les conversations (post-purge sur 1 transaction).
     * Sans ça, les conv dont tous les messages ont été purgés gardent l'ancien preview en
     * clair (leak privacy : le contenu purgé reste visible sur l'écran de liste).
     *
     * Logique :
     *  - Si une conv n'a plus aucun message : `last_message_at = 0`, `preview = NULL`.
     *  - Sinon : recalcul depuis le message le plus récent restant.
     *
     * O(N) sur le nombre de conversations (typiquement <500), exécuté UNIQUEMENT après une
     * purge effective. Pas d'impact perf en steady state.
     */
    @Query(
        """
        UPDATE conversations
        SET
          last_message_at = COALESCE(
              (SELECT MAX(date) FROM messages WHERE conversation_id = conversations.id),
              0
          ),
          last_message_preview = (
              SELECT body FROM messages
              WHERE conversation_id = conversations.id
              ORDER BY date DESC LIMIT 1
          )
        """,
    )
    suspend fun refreshAllConversationPreviewsAfterPurge(): Int

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
