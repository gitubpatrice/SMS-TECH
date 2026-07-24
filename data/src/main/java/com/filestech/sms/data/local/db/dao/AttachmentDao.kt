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

    /**
     * v1.14.7 — récupère toutes les attachments dont [AttachmentEntity.localUri] commence
     * par [oldPrefix]. Utilisé par la migration cache → filesDir de MainApplication.onCreate
     * pour ré-écrire les chemins pointant vers `cacheDir/mms_incoming/` (volatile, sujet à
     * cache-clear Android + clear cache utilisateur) vers `filesDir/mms_attachments/`
     * (persistant). Cold-start one-shot, idempotent (flag DataStore).
     */
    @Query("SELECT * FROM attachments WHERE local_uri LIKE :oldPrefix || '%'")
    suspend fun findByLocalUriPrefix(oldPrefix: String): List<AttachmentEntity>

    /**
     * v1.14.7 — réécrit [AttachmentEntity.localUri] d'une row spécifique. Appelé après
     * que le fichier ait été déplacé physiquement de cacheDir → filesDir par la migration.
     */
    @Query("UPDATE attachments SET local_uri = :newUri WHERE id = :id")
    suspend fun updateLocalUri(id: Long, newUri: String)
}
