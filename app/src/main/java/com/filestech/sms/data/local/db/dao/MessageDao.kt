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
        ORDER BY date ASC, id ASC
        """
    )
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    /**
     * Bounded window: the [limit] most recent messages, returned oldest-first for the UI.
     *
     * v1.24.0 (finding A) — [observeForConversation] has no `LIMIT`, so every incoming SMS and
     * every draft keystroke re-read, re-mapped and re-diffed the whole thread. On a 5 000-message
     * conversation that is 5 000 rows of work per emission. The thread UI uses this window
     * instead; [observeForConversation] stays unbounded because the PDF export
     * (`ExportConversationPdfUseCase`) must see the entire history — bounding it would silently
     * export a truncated document.
     *
     * The `id` tie-breaker is **not optional**. `date` alone is not unique — a multipart MMS lands
     * with identical timestamps — and without a total order the window boundary is
     * non-deterministic, so a row can be dropped or duplicated between two emissions.
     *
     * The `(conversation_id, date)` index covers this ordering; no new index is needed.
     *
     * The exclusion predicate is kept verbatim from [observeForConversation] — it hides the
     * reaction sentinels documented there.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages
            WHERE conversation_id = :conversationId
              AND NOT (
                  body = ''
                  AND attachments_count = 0
                  AND reaction_emoji IS NULL
              )
            ORDER BY date DESC, id DESC
            LIMIT :limit
        ) ORDER BY date ASC, id ASC
        """
    )
    fun observeWindowForConversation(conversationId: Long, limit: Int): Flow<List<MessageEntity>>

    /**
     * Whole-thread statistics, computed in SQL over **every** message — never over the loaded
     * window.
     *
     * The conversation info panel shows "N messages, from … to …". Deriving those from the window
     * would quietly report the window's own bounds as the conversation's, so a 5 000-message
     * thread would claim to hold 200 and to start last Tuesday. `COUNT`/`MIN`/`MAX` over the
     * indexed `(conversation_id, date)` pair is cheap enough to observe continuously.
     *
     * Same exclusion predicate as [observeWindowForConversation].
     */
    @Query(
        """
        SELECT COUNT(*) AS total, MIN(date) AS firstAt, MAX(date) AS lastAt FROM messages
        WHERE conversation_id = :conversationId
          AND NOT (
              body = ''
              AND attachments_count = 0
              AND reaction_emoji IS NULL
          )
        """
    )
    fun observeStatsForConversation(conversationId: Long): Flow<ThreadStats>

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

    /**
     * v1.8.0 (post-audit fix unread badges) — marque TOUS les messages INCOMING
     * comme lus en masse. Utilisé pour la migration one-shot vers v1.8.0 qui
     * purge l'état legacy v1.7.1 (compteurs inflated + flags `read=0`
     * désynchronisés du système Android). Aligne le cache Room sur la réalité
     * usuelle de l'utilisateur (les messages anciens sont déjà lus, dans
     * SMS Tech ou ailleurs).
     *
     * Direction INCOMING = `0`. Idempotent — re-exécuter ne change rien.
     * Aussi exposé via Settings → Avancé "Tout marquer comme lu" pour permettre
     * à l'utilisateur de re-aligner à tout moment si Room se désynchronise
     * à nouveau (lecture dans une autre app SMS).
     */
    @Query("UPDATE messages SET read = 1 WHERE direction = 0 AND read = 0")
    suspend fun markAllIncomingAsRead(): Int

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    // v1.16.0 — Paramètre `status` typé MessageStatus (était Int). Le TypeConverter
    // [com.filestech.sms.data.local.db.MessageEnumConverters] convertit en Int pour
    // le binding SQL. Type safety au call-site, schéma DB inchangé.
    @Query("UPDATE messages SET status = :status, error_code = :errorCode WHERE id = :id")
    suspend fun updateStatus(id: Long, status: com.filestech.sms.data.local.db.entity.MessageStatus, errorCode: Int? = null)

    /**
     * v1.15.2 — Remapping post-restore du `reply_to_message_id`. Utilisé par
     * [com.filestech.sms.data.backup.BackupService.importPayload] passe 2 pour réécrire les
     * citations contextuelles : l'id Room du message cité change entre source et cible, donc
     * la 1ʳᵉ passe d'insert pose `reply_to_message_id = NULL` et celle-ci remet la cible
     * via le mapping <oldId → newId> construit pendant la passe 1.
     */
    @Query("UPDATE messages SET reply_to_message_id = :replyTargetId WHERE id = :id")
    suspend fun setReplyTarget(id: Long, replyTargetId: Long)

    /**
     * v1.2.6 audit F2 : stocke l'`_id` `content://mms` que `MmsSystemWriteback.insertOutbox`
     * a renvoyé pour ce message Room. Permet à la prochaine tentative (retry après échec) de
     * détecter et supprimer la row OUTBOX/FAILED précédente avant d'en insérer une nouvelle.
     */
    @Query("UPDATE messages SET mms_system_id = :mmsSystemId WHERE id = :id")
    suspend fun setMmsSystemId(id: Long, mmsSystemId: Long?)

    /**
     * v1.22.x — déplace tous les messages d'une conversation source vers une conversation cible
     * (fusion des doublons du même numéro, cf. [com.filestech.sms.data.repository.ConversationMirror
     * .dedupeSameNumberConversations]). **Doit être appelé AVANT** la suppression de la conversation
     * source : la FK `messages.conversation_id` est `onDelete = CASCADE`, donc supprimer la
     * conversation d'abord effacerait ses messages. Aucun conflit d'unicité — l'index unique porte
     * sur `telephony_uri` seul, pas sur `conversation_id`.
     */
    @Query("UPDATE messages SET conversation_id = :toConversationId WHERE conversation_id = :fromConversationId")
    suspend fun reparentMessages(fromConversationId: Long, toConversationId: Long)

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
     * v1.6.2 (Tapback fold bugfix) — returns the [limit] most recent OUTGOING messages of
     * [conversationId]. Used as a fallback by [com.filestech.sms.data.repository
     * .ConversationMirror.applyIncomingReaction] when the SQL `LIKE prefix%` match fails
     * because the encoder normalizes whitespace (newlines, tabs → single space) in the
     * Tapback preview while the stored OUTGOING body keeps the original separators.
     * The caller fetches a bounded window then matches in Kotlin after collapsing
     * whitespace on BOTH sides — bulletproof and < 1 ms on real conversations.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND direction = 1
        ORDER BY date DESC
        LIMIT :limit
        """
    )
    suspend fun findRecentOutgoingForConversation(
        conversationId: Long,
        limit: Int,
    ): List<MessageEntity>

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

    /**
     * Snapshot read for backup pipeline.
     *
     * v1.6.0 (audit S2) — exclut les rows sentinels (`body = '' AND attachments_count = 0
     * AND reaction_emoji IS NULL`) qui sont des artefacts internes Tapback :
     *   - `upsertReactionSentinel` (incoming Tapback déjà folded sur le message d'origine) ;
     *   - `upsertOutgoingSms(localMirrorBody = "")` (la propre réaction sortante du user,
     *     dont seul le badge est exposé en UI).
     *
     * Ces lignes sont strictement internes au mécanisme anti-réimport TelephonySync — les
     * inclure dans un backup produirait des bulles vides à la restauration.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE NOT (body = '' AND attachments_count = 0 AND reaction_emoji IS NULL)
        ORDER BY conversation_id ASC, date ASC
        """
    )
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

    /**
     * FTS search across body + address. Returns matching message ids ordered by relevance.
     *
     * v1.11.0 audit SEC-V1 — JOIN sur `conversations` avec filtre `in_vault = 0`
     * pour ne PAS exposer les messages d'une conv déplacée dans le coffre.
     * Sans ce filtre, l'utilisateur (ou un agresseur en PanicDecoy) pourrait
     * voir le body d'un message vault dans les résultats de recherche, alors
     * que la conv parente est cachée de la liste. L'index FTS reste indexé
     * pour tous les messages (refacto FTS architectural différé v1.12.x).
     */
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts ON messages_fts.rowid = m.id
        JOIN conversations c ON c.id = m.conversation_id
        WHERE messages_fts MATCH :query
          AND c.in_vault = 0
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
