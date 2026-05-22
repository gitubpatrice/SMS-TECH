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

    /**
     * v1.3.3 — snapshot des conv **1-to-1** (zéro `;` dans `addresses_csv`, donc une seule
     * adresse stockée). Sert au fallback de matching par suffix 8 chiffres dans
     * [com.filestech.sms.data.repository.ConversationMirror.ensureConversation] : un SMS
     * reçu en format national (`0612…`) doit retrouver la conversation existante créée
     * lors de l'import système en format international (`+33612…`), et inversement.
     *
     * On évite un `LIKE '%suffix'` SQL imprécis sur le CSV : ici on filtre les 1-to-1 puis
     * le matching exact se fait en mémoire via `phoneSuffix8()`. Volume négligeable (qq
     * centaines de conv max sur usage normal).
     */
    @Query("SELECT * FROM conversations WHERE addresses_csv NOT LIKE '%;%'")
    suspend fun snapshotOneToOneConversations(): List<ConversationEntity>

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

    /**
     * v1.11.0 — Apparence par contact. `bubbleColorArgb` = null pour reset au
     * thème, sinon ARGB Int issu de la palette WCAG-safe ([BubbleColorPalette]).
     * `avatarUri` = null pour reset au fallback contact natif, sinon URI
     * `content://` persistée via [takePersistableUriPermission].
     */
    @Query("UPDATE conversations SET bubble_color_argb = :bubbleColorArgb, avatar_uri = :avatarUri WHERE id = :id")
    suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?)

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id")
    suspend fun clearUnread(id: Long)

    /**
     * v1.8.0 (post-audit fix badges après désinstallation) — retourne le
     * `threadId` système AOSP correspondant au `id` Room. Utilisé par
     * [com.filestech.sms.data.repository.ConversationRepositoryImpl.markRead]
     * pour propager `READ=1` vers `content://sms` + `content://mms` filtré
     * par `thread_id`.
     */
    @Query("SELECT thread_id FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findThreadIdById(id: Long): Long?

    /**
     * v1.8.0 (post-audit fix) — recalcule `unread_count` à partir des `messages.read=0`
     * réellement présents dans Room. Corrige l'état legacy hérité de v1.7.1 où les
     * syncs successifs incrémentaient le compteur même pour les rows déjà mirror-ées
     * (cf. fix dans `ConversationMirror.bulkImportFromTelephony`). Appelé une fois
     * au cold-start de v1.8.0 depuis `MainApplication.onCreate` pour purger les
     * compteurs inflated. Idempotent — coût négligeable (~10 ms pour 100 conv).
     *
     * Direction INCOMING = `0` (cf. [com.filestech.sms.data.local.db.entity.MessageDirection]).
     * Cap absolu à 999 pour éviter qu'un compteur explose visuellement même si
     * un edge case poserait des milliers de messages non lus (le badge UI tronque
     * à "999+" au-delà de toute façon).
     */
    @Query(
        """
        UPDATE conversations SET unread_count = (
          SELECT COUNT(*) FROM messages
          WHERE messages.conversation_id = conversations.id
            AND messages.direction = 0
            AND messages.read = 0
        )
        """,
    )
    suspend fun recomputeAllUnreadCounts()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations WHERE unread_count > 0 AND in_vault = 0")
    fun observeUnreadConversationCount(): Flow<Int>
}
