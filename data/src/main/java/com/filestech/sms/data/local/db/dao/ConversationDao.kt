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
     * Used by [com.filestech.sms.data.blocking.BlockedNumbersImporter.purgeMatchingConversations].
     *
     * v1.25.4 — le motif d'origine (« `observeAll` masque les conversations bloquées, la purge doit
     * pouvoir les voir ») a disparu avec le masquage : depuis la v1.25.3 elles restent listées et
     * portent simplement `Conversation.blocked`. L'instantané reste néanmoins la bonne source pour
     * la purge : il ne dépend d'aucun flux d'affichage et lui donne l'état brut de la base.
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
     * adresse stockée). Sert au fallback de matching par clé numérique dans
     * [com.filestech.sms.data.repository.ConversationMirror.ensureConversation] : un SMS
     * reçu en format national (`0612…`) doit retrouver la conversation existante créée
     * lors de l'import système en format international (`+33612…`), et inversement.
     *
     * On évite un `LIKE '%suffix'` SQL imprécis sur le CSV : ici on filtre les 1-to-1 puis
     * le matching exact se fait en mémoire via `blockKey()` — 9 chiffres significatifs
     * (v1.26.1 H13 côté réception, v1.27.2 côté composition `findOrCreate`, qui utilisait
     * encore `phoneSuffix8()`). Volume négligeable (qq centaines de conv max sur usage
     * normal).
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

    /**
     * v1.27.2 (audit externe 2026-08-04 #4) — nombre de conversations dans le coffre. Sert au
     * garde d'export de [com.filestech.sms.data.backup.BackupService.writeSmsbk] : la
     * sauvegarde lit TOUT (cf. [listAllIncludingArchived]) et ne doit donc partir que si la
     * session coffre est déverrouillée — ou si le coffre est vide, auquel cas il n'y a rien à
     * protéger et l'export reste sans friction.
     */
    @Query("SELECT COUNT(*) FROM conversations WHERE in_vault = 1")
    suspend fun countInVault(): Int

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
     * v1.22.x — assigne le `thread_id` système AOSP d'un survivant de fusion (dédup même numéro).
     * Appelé UNIQUEMENT après suppression de la conversation source qui détenait ce `thread_id` :
     * l'index `conversations.thread_id` est UNIQUE, un doublon transitoire lèverait une contrainte.
     */
    @Query("UPDATE conversations SET thread_id = :threadId WHERE id = :id")
    suspend fun setThreadId(id: Long, threadId: Long)

    /**
     * v1.8.0 (post-audit fix) — recalcule `unread_count` à partir des `messages.read=0`
     * réellement présents dans Room. Corrige l'état legacy hérité de v1.7.1 où les
     * syncs successifs incrémentaient le compteur même pour les rows déjà mirror-ées
     * (cf. fix dans `ConversationMirror.bulkImportFromTelephony`). Appelé une fois
     * au cold-start de v1.8.0 depuis `MainApplication.onCreate` pour purger les
     * compteurs inflated. Idempotent — coût négligeable (~10 ms pour 100 conv).
     *
     * Direction INCOMING = `0` (cf. [com.filestech.sms.domain.model.MessageDirection]).
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

    /**
     * v1.27.2 (test appareil du 2026-08-05) — recalcule le compteur de non-lus d'UNE conversation.
     *
     * ⚠️ Le defaut que ca ferme, trouve en supprimant une conversation depuis Google Messages sur
     * le S9 : `reconcileDeletions` effacait bien la ligne `messages` et rafraichissait l'apercu,
     * mais ne touchait JAMAIS `unread_count`. La conversation restait affichee avec une pastille
     * « 1 » alors que son unique message n'existait plus. Un badge qui affirme un message que
     * l'utilisateur ne peut pas ouvrir.
     *
     * Meme predicat de sentinelle que `refreshConversationPreview` : une reaction repliee ne
     * compte pas comme un message non lu.
     */
    @Query(
        """
        UPDATE conversations
        SET unread_count = (
            SELECT COUNT(*) FROM messages
            WHERE conversation_id = :conversationId
              AND read = 0
              AND direction = 0
              AND NOT (body = '' AND attachments_count = 0 AND reaction_emoji IS NULL)
        )
        WHERE id = :conversationId
        """,
    )
    suspend fun recomputeUnreadCount(conversationId: Long)

    /**
     * v1.27.2 (test appareil du 2026-08-05) — supprime une conversation devenue **entierement
     * vide**.
     *
     * ⚠️ Meme constat : apres suppression du dernier message depuis une autre application, il
     * restait une coquille sans apercu, datee du **1er janvier 1970** (`last_message_at = 0`) et
     * portant un faux badge. L'utilisateur avait supprime la conversation ailleurs ; la voir
     * survivre sous cette forme n'a aucun sens.
     *
     * `NOT EXISTS` porte sur TOUTES les lignes, sentinelles de reaction comprises : on ne supprime
     * que ce qui ne contient plus rien du tout. Une conversation dont il reste ne serait-ce qu'une
     * sentinelle est conservee — elle porte encore un etat que le miroir doit refleter.
     *
     * 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-02 / LP-03) — « AUCUN MESSAGE » N EST PAS
     * « CONVERSATION VIDE ».
     *
     * La premiere version ne testait que `messages`. Or une conversation sans message peut porter
     * un BROUILLON non envoye, et etre referencee par un ENVOI PROGRAMME — cette reference n a
     * meme pas de cle etrangere. Quelqu un qui laisse un brouillon dans un fil, puis supprime le
     * dernier SMS de ce fil depuis une autre application, perdait donc son texte non envoye et
     * laissait un envoi programme orphelin. Une suppression faite AILLEURS ne peut pas exprimer
     * l intention d effacer ces donnees, qui n existent que dans SMS Tech.
     *
     * Et `in_vault = 0` est exige ICI, dans le SQL : le fil a pu etre mis au Coffre entre la
     * photographie des URI et cette transaction.
     */
    @Query(
        """
        DELETE FROM conversations
        WHERE id = :conversationId
          AND in_vault = 0
          AND (draft IS NULL OR TRIM(draft) = '')
          AND NOT EXISTS (SELECT 1 FROM messages WHERE conversation_id = :conversationId)
          AND NOT EXISTS (
              SELECT 1 FROM scheduled_messages WHERE conversation_id = :conversationId
          )
        """,
    )
    suspend fun deleteIfEmpty(conversationId: Long): Int

    /**
     * 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-07) — REPRISE HISTORIQUE des coquilles **deja
     * creees**.
     *
     * [deleteIfEmpty] ne s applique qu aux conversations dont un message vient d etre supprime :
     * leur identifiant est releve dans le lot `gone` AVANT le `DELETE`. Une coquille deja vide n a
     * plus aucun message, donc ne peut plus apparaitre dans ce lot — elle etait condamnee a rester
     * affichee pour toujours, datee du 1er janvier 1970 et parfois porteuse d un faux badge.
     *
     * Cette requete est la passe de rattrapage, executee UNE fois au demarrage. Elle porte
     * exactement les memes gardes que [deleteIfEmpty] — brouillon, envoi programme, Coffre — parce
     * que « aucun message » ne veut pas dire « rien a perdre » : un fil vide peut porter un texte
     * non envoye, ou etre reference par un envoi programme sans cle etrangere.
     *
     * Rend le nombre de coquilles retirees, pour que l appelant ne memorise la completion que sur
     * une base reellement propre.
     */
    @Query(
        """
        DELETE FROM conversations
        WHERE in_vault = 0
          AND (draft IS NULL OR TRIM(draft) = '')
          AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = conversations.id)
          AND NOT EXISTS (
              SELECT 1 FROM scheduled_messages sm WHERE sm.conversation_id = conversations.id
          )
        """,
    )
    suspend fun deleteAllEmptyConversations(): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE unread_count > 0 AND in_vault = 0")
    fun observeUnreadConversationCount(): Flow<Int>
}
