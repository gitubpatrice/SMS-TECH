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
     * Both sentinels used to be recognised by their SHAPE — `body = ''` + no attachment + no
     * reaction emoji — on the assumption that a legitimate empty body always carries an
     * attachment. **That assumption was false**: a captionless MMS restored from a backup
     * comes back without its attachments (the backup does not carry them) and took exactly
     * that shape — imported, counted, shown nowhere.
     *
     * v1.28.4 — the sentinel now DECLARES itself (`hidden = 1`, set by its only two producers)
     * and every reader filters on that column, never on the shape.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
          AND hidden = 0
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
              AND hidden = 0
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
          AND hidden = 0
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
    /*
     * v1.27.2 (relecture Gemini du 2026-08-05) — le Coffre est désormais EXCLU.
     *
     * La requête écrivait sur toute la base. « Tout marquer comme lu », depuis les Réglages ou
     * depuis la migration de démarrage, marquait donc lus les messages du Coffre **alors qu'il est
     * verrouillé** : une écriture dans une zone censée être inaccessible, et la perte de l'état
     * « non lu » que l'utilisateur retrouverait en l'ouvrant.
     *
     * Sous-requête plutôt que `JOIN` : SQLite n'accepte pas de jointure dans un `UPDATE`. L'index
     * sur `in_vault` (cf. [ConversationEntity]) la rend négligeable.
     */
    @Query(
        """
        UPDATE messages SET read = 1
        WHERE direction = 0 AND read = 0
          AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun markAllIncomingAsRead(): Int

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    // v1.16.0 — Paramètre `status` typé MessageStatus (était Int). Le TypeConverter
    // [com.filestech.sms.data.local.db.MessageEnumConverters] convertit en Int pour
    // le binding SQL. Type safety au call-site, schéma DB inchangé.
    /**
     * v1.26.1 (audit M6) — détecte un doublon lors d'une RESTAURATION, pour les lignes qui n'ont
     * pas de `telephony_uri`.
     *
     * La déduplication de la restauration repose sur l'index `UNIQUE(telephony_uri)` combiné à
     * `OnConflictStrategy.IGNORE`. Or la colonne est NULLABLE, et SQLite considère deux NULL
     * comme distincts : l'index ne dédoublonne donc RIEN pour les lignes sans URI — tous les MMS
     * sortants, et les SMS dont l'écriture dans la base système avait échoué. Restaurer deux fois
     * la même sauvegarde les dupliquait sans limite, alors que la chaîne affichée à l'utilisateur
     * affirme « les messages existants sont conservés (pas de doublons) ».
     *
     * Clé de repli : même conversation, même horodatage, même sens, même corps.
     *
     * # v1.28.3 (F25) — quatre criteres ne suffisaient pas, et l'erreur etait SILENCIEUSE
     *
     * La sauvegarde ne transporte ni la table `attachments` ni les fichiers : un MMS restaure est
     * une ligne au corps souvent VIDE. La cle se reduisait alors a « meme conversation, meme
     * milliseconde, meme sens » — et deux photos distinctes envoyees dans la meme seconde y
     * repondaient de la meme facon. La seconde etait comptee « ignoree » et PERDUE, sans trace,
     * alors que l'utilisateur venait de restaurer pour la retrouver.
     *
     * Trois criteres de plus, tous deja presents dans la sauvegarde et tous NULL-safe la ou il le
     * faut (`IS` et non `=`, sans quoi une colonne nulle ne s'egale jamais elle-meme) :
     *
     *  - **`type`** — un SMS et un MMS ne se confondent plus ;
     *  - **`date_sent`** — pose par le reseau, il separe deux envois que `date` rapproche ;
     *  - **`sub_id`** — deux SIM, deux lignes distinctes.
     *
     * Le resserrement ne peut RIEN dupliquer de ce qui etait dedoublonne : restaurer deux fois la
     * meme sauvegarde produit des lignes identiques sur les sept criteres, qui se rencontrent
     * donc toujours. Il ne fait qu'ecarter des rapprochements que rien ne prouvait — et entre
     * perdre un message en silence et en montrer un en double, seul le second se voit et se
     * corrige.
     */
    @Query(
        """
        SELECT id FROM messages
        WHERE conversation_id = :conversationId AND date = :date
          AND direction = :direction AND body = :body
          AND type = :type
          AND date_sent IS :dateSent
          AND sub_id IS :subId
        LIMIT 1
        """,
    )
    suspend fun findRestoreDuplicate(
        conversationId: Long,
        date: Long,
        direction: com.filestech.sms.domain.model.MessageDirection,
        body: String,
        type: com.filestech.sms.domain.model.MessageType,
        dateSent: Long?,
        subId: Int?,
    ): Long?

    @Query("UPDATE messages SET status = :status, error_code = :errorCode WHERE id = :id")
    suspend fun updateStatus(id: Long, status: com.filestech.sms.domain.model.MessageStatus, errorCode: Int? = null)

    /**
     * v1.26.1 (audit M8) — promotion de statut qui NE PEUT PAS écraser un échec.
     *
     * Un SMS long part en plusieurs parties, et `SmsSenderImpl` construit un `PendingIntent` par
     * partie. Chaque accusé écrivait le statut sans agrégation : sur un message de trois
     * segments, un échec de la partie 0 posait `FAILED`, puis les accusés positifs des parties 1
     * et 2 le repassaient à `SENT`. Le destinataire recevait un message TRONQUÉ, l'expéditeur le
     * croyait envoyé — et comme la bulle n'était pas en échec, la relance n'était même pas
     * proposée.
     *
     * Les accusés n'arrivent pas dans l'ordre : plutôt que de parier sur l'index de la dernière
     * partie, la promotion est MONOTONE — un statut ne peut que progresser. L'échelle du chemin
     * sortant est `PENDING(0) < SENT(1) < DELIVERED(2) < FAILED(3)`, si bien que la règle donne
     * gratuitement les trois garanties voulues :
     *  - un échec de partie ne peut plus être écrasé par l'accusé positif d'une autre partie ;
     *  - un accusé de réception ne peut plus être rétrogradé en « envoyé » par un accusé
     *    d'envoi arrivé après lui ;
     *  - un échec tardif marque toujours le message, même déjà passé « envoyé ».
     *
     * [statusRaw] double [status] parce que SQLite compare la colonne à un entier ; les deux
     * doivent décrire le MÊME statut.
     *
     * # v1.28.3 (F23) — la monotonie ne suffisait pas après une RELANCE
     *
     * La règle ci-dessus protège les parties d'un même envoi les unes des autres. Elle ne
     * protégeait rien entre deux TENTATIVES, parce que la relance rétrograde délibérément la
     * ligne en `PENDING` ([com.filestech.sms.domain.repository.OutgoingMessageMirror
     * .resetOutgoingForRetry]) : l'accusé tardif de la tentative précédente retrouvait alors une
     * ligne au bas de l'échelle et s'y appliquait comme s'il était le sien. Un `FAILED` en
     * retard d'une minute écrivait `3`, sommet de l'échelle, que le succès réel de la nouvelle
     * tentative ne pouvait **plus jamais** promouvoir : bulle rouge définitive sur un message
     * bel et bien reçu.
     *
     * [attempt] identifie la tentative dont provient l'accusé. `NULL` signifie « quelle que soit
     * la tentative en cours » et reste le bon choix pour ce qui n'est pas un accusé : l'échec
     * synchrone écrit juste après la remise, la sentinelle du chien de garde, le suivi MMS.
     */
    @Query(
        """
        UPDATE messages
           SET status = :status, error_code = :errorCode
         WHERE id = :id
           AND status < :statusRaw
           AND (:attempt IS NULL OR send_attempt = :attempt)
        """,
    )
    suspend fun promoteStatusMonotonic(
        id: Long,
        status: com.filestech.sms.domain.model.MessageStatus,
        statusRaw: Int,
        errorCode: Int? = null,
        attempt: Int? = null,
    ): Int

    /**
     * v1.28.3 (F23) — ouvre une NOUVELLE tentative d'envoi : statut ramené à `PENDING`, erreur
     * effacée, compteur incrémenté.
     *
     * Les trois écritures dans le même UPDATE, et c'est la raison d'être de cette requête : si
     * l'incrément était séparé de la rétrogradation, il existerait un instant où la ligne est
     * `PENDING` sous l'ancien numéro de tentative — exactement la fenêtre dans laquelle un
     * accusé tardif s'appliquerait à tort.
     */
    @Query(
        "UPDATE messages SET status = 0, error_code = NULL, send_attempt = send_attempt + 1 WHERE id = :id",
    )
    suspend fun openNextSendAttempt(id: Long)

    /** v1.28.3 (F23) — numéro de la tentative en cours, ou `null` si la ligne n'existe plus. */
    @Query("SELECT send_attempt FROM messages WHERE id = :id")
    suspend fun sendAttemptOf(id: Long): Int?

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
     * v1.28.3 (F18) — **journal de propriété** : tous les identifiants de lignes
     * `content://mms` que SMS Tech a écrites lui-même.
     *
     * Sert au chien de garde de l'OUTBOX système
     * ([com.filestech.sms.data.mms.MmsSystemWriteback.purgeStaleOutbox]), qui supprimait
     * jusqu'ici **toute** ligne du fournisseur en `msg_box = OUTBOX` plus vieille que quinze
     * minutes, sans se demander qui l'avait créée. Une application tierce ayant laissé un MMS
     * en échec — une application constructeur cohabitante, ou celle utilisée avant SMS Tech —
     * voyait sa ligne détruite par un chien de garde qui n'avait rien à y faire.
     *
     * La colonne existait déjà et servait déjà de preuve de propriété ailleurs :
     * `MmsSentReceiver` s'en sert précisément pour refuser d'agir sur une ligne qui n'est pas
     * la sienne. La purge était le seul chemin destructeur du dépôt à ne pas faire ce contrôle,
     * alors qu'elle est de loin le plus large.
     *
     * Conséquence assumée : une ligne dont SMS Tech a perdu la trace en Room — message
     * supprimé localement, base réinitialisée — n'est plus purgée. C'est le bon sens de
     * l'échec : mieux vaut laisser une ligne orpheline qu'en détruire une qui ne nous
     * appartient pas.
     */
    @Query("SELECT mms_system_id FROM messages WHERE mms_system_id IS NOT NULL")
    suspend fun ownedMmsSystemIds(): List<Long>

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
     * v1.6.0 (audit S2) — exclut les rows sentinels (`hidden = 1` depuis la v1.28.4 ; avant,
     * reconnues à leur forme) qui sont des artefacts internes Tapback :
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
        WHERE hidden = 0
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
        ORDER BY m.telephony_uri
        """,
    )
    suspend fun listMirroredTelephonyUris(): List<String>

    /**
     * Bulk-deletes Room rows whose [MessageEntity.telephonyUri] is in [uris]. Used by the sync
     * manager's deletion-reconciliation pass. SQLite caps `IN (…)` at 999 host parameters; the
     * caller chunks larger inputs.
     */
    /**
     * 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-03) — LE PREDICAT COFFRE EST DANS LA REQUETE.
     *
     * `listMirroredTelephonyUris` excluait bien `in_vault = 1`, mais cette lecture a lieu AVANT les
     * sondes provider. Entre-temps, `moveToVault` peut poser `in_vault = 1` dans une autre
     * transaction : la suppression effacait alors du contenu que l utilisateur venait tout juste de
     * mettre a l abri, irreversiblement.
     *
     * Un `findById` avant le DELETE recreerait la meme course. La condition doit etre SQL, donc
     * evaluee atomiquement avec l ecriture.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE telephony_uri IN (:uris)
          AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun deleteByTelephonyUris(uris: List<String>): Int

    /**
     * v1.27.2 (relecture Codex 2026-08-04) — conversations touchées par un lot de suppressions,
     * À LIRE AVANT le `DELETE` (après, les lignes n'existent plus).
     *
     * Sert à `TelephonySyncManager.reconcileDeletions` pour ne recalculer QUE les aperçus des
     * fils réellement concernés. Le recalcul global qui l'a précédée réécrivait l'aperçu de
     * TOUTES les conversations, y compris celles qu'aucune suppression ne touchait — voir le
     * commentaire de cette passe pour ce que ça coûtait.
     */
    /** v1.27.2 (audit Codex, LP-03) — meme exclusion du Coffre que la suppression qui suit. */
    @Query(
        """
        SELECT DISTINCT conversation_id FROM messages
        WHERE telephony_uri IN (:uris)
          AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun findConversationIdsByTelephonyUris(uris: List<String>): List<Long>

    /**
     * v1.28.7 — les messages que [deleteByTelephonyUris] va effacer, avec leur conversation, À
     * LIRE AVANT le `DELETE`. Même clause, coffre exclu compris.
     */
    @Query(
        """
        SELECT id, conversation_id FROM messages
        WHERE telephony_uri IN (:uris)
          AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun findRefsByTelephonyUris(uris: List<String>): List<MessageRef>

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
    /*
     * v1.28.3 (F11) — le Coffre est EXCLU, comme il l'est deja de `markAllIncomingAsRead`, de
     * `search`, de `listMirroredTelephonyUris` et de trois autres requetes de ce fichier. La
     * retention etait la seule ecriture destructrice a ne pas le faire.
     *
     * Ce que cela produisait, et qui est pire que la suppression elle-meme : la ligne locale
     * partait `quoi qu'il arrive` (contrat assume de `ConversationEraser.purgeHistory`), y compris
     * quand sa copie systeme avait resiste. Le lien disparaissait donc avec elle, et la
     * resynchronisation suivante reimportait le message HORS du coffre, dans une conversation
     * ordinaire. Un contenu protege ressuscitait en clair, sans que personne ne l'ait demande —
     * l'utilisateur n'ayant regle qu'une duree de conservation pour son historique courant.
     *
     * Le coffre a sa propre purge, explicite et authentifiee (`purgeVault`), dont le contrat est
     * l'inverse : la ligne locale ne part que si la preuve de suppression systeme est faite.
     */
    @Query(
        """
        DELETE FROM messages
         WHERE date < :olderThan AND starred = 0
           AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun purgeOlderThan(olderThan: Long): Int

    /**
     * v1.28.7 — ce que [purgeOlderThan] va effacer, désigné sans contenu, à lire DANS la même
     * transaction et AVANT elle. La clause est celle du `DELETE`, mot pour mot : deux critères
     * qui divergeraient annuleraient la notification d'un message conservé, ou oublieraient celle
     * d'un message effacé.
     */
    @Query(
        """
        SELECT id, conversation_id FROM messages
         WHERE date < :olderThan AND starred = 0
           AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun findRefsOlderThan(olderThan: Long): List<MessageRef>

    /**
     * v1.28.1 — les messages que [purgeOlderThan] va effacer **et qui ont une copie dans le
     * fournisseur du systeme**, page par page.
     *
     * # Pourquoi cette requete existe
     *
     * La purge de retention n'effacait qu'en local : les messages que l'utilisateur croyait
     * supprimes restaient dans `content://sms`, lisibles par toute application ayant `READ_SMS`,
     * et une resynchronisation complete — le bouton qui remet le curseur a zero — les ramenait.
     * `delete`, `deleteMessage` et `deleteAllInVault` propageaient depuis longtemps ; la
     * retention, non, sans que rien ne le documente.
     *
     * # Pourquoi paginer, et pourquoi filtrer sur `telephony_uri`
     *
     * La premiere purge d'un historique ancien peut porter sur des dizaines de milliers de
     * lignes. Les charger d'un bloc mettrait leurs corps entiers en memoire ; la pagination par
     * cle (`id > :apresId`, jamais `OFFSET`) garde une empreinte constante. Le filtre
     * `telephony_uri IS NOT NULL` ecarte d'emblee les lignes qui n'ont rien a propager — brouillons,
     * messages jamais miroites — au lieu de les charger pour ne rien en faire.
     *
     * v1.28.3 (F10) — `OR mms_system_id IS NOT NULL`. Ce filtre-la EXCLUAIT tous les MMS
     * SORTANTS : `ConversationMirror` les ecrit avec `telephonyUri = null`, leur seul lien vers le
     * fournisseur etant `mms_system_id`. La retention ne propageait donc jamais leur suppression,
     * et ils restaient indefiniment dans `content://mms`.
     *
     * v1.28.3 (F11) — et le Coffre en est desormais exclu, comme il l'est de la suppression qui
     * suit ; voir le KDoc de [purgeOlderThan] pour ce que l'absence de ce filtre produisait.
     *
     * Le critere de selection est **exactement** celui de [purgeOlderThan], au filtre de liaison
     * pres : deux criteres qui divergeraient feraient propager la suppression de lignes qui
     * resteraient en base, ou l'inverse.
     */
    @Query(
        """
        SELECT * FROM messages
         WHERE date < :olderThan AND starred = 0 AND id > :apresId
           AND (telephony_uri IS NOT NULL OR mms_system_id IS NOT NULL)
           AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
         ORDER BY id
         LIMIT :limite
        """,
    )
    suspend fun findMirroredOlderThan(olderThan: Long, apresId: Long, limite: Int): List<MessageEntity>

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
    /**
     * v1.26.1 (audit B4) — le prédicat d'exclusion des SENTINELLES DE RÉACTION a été ajouté, et
     * le départage `id DESC` avec.
     *
     * Ses deux requêtes sœurs — `refreshConversationPreview` et `repairStaleConversationPreviews`
     * — portent toutes deux `NOT (body = '' AND attachments_count = 0 AND reaction_emoji IS
     * NULL)` ; celle-ci ne l'avait pas. Après une purge, une conversation dont la ligne restante
     * la plus récente était une sentinelle Tapback — invisible dans le fil — recevait donc un
     * aperçu VIDE et un `last_message_at` calé sur cette ligne fantôme, ce qui faussait aussi
     * l'ordre de tri de la liste.
     */
    @Query(
        """
        UPDATE conversations
        SET
          last_message_at = COALESCE(
              (SELECT MAX(date) FROM messages
               WHERE conversation_id = conversations.id
                 AND hidden = 0),
              0
          ),
          last_message_preview = (
              SELECT body FROM messages
              WHERE conversation_id = conversations.id
                AND hidden = 0
              ORDER BY date DESC, id DESC LIMIT 1
          )
        """,
    )
    suspend fun refreshAllConversationPreviewsAfterPurge(): Int

    /**
     * v1.24.0 (bug suppression) — recalcule `last_message_at` + `last_message_preview` pour UNE
     * conversation, à partir de son message le plus récent restant.
     *
     * Sans ça, supprimer le dernier message d'un fil laissait la liste afficher indéfiniment le
     * message supprimé (`deleteMessage` effaçait la ligne `messages` mais ne touchait jamais la
     * ligne `conversations`). Bug confirmé sur une vraie sauvegarde le 2026-07-23.
     *
     * Exclut les sentinelles de réaction (même prédicat que `observeForConversation`) pour ne pas
     * exposer un `body` vide. Départage `date DESC, id DESC` — cohérent avec la fenêtre du fil.
     * Si le fil n'a plus aucun message affichable, `last_message_at = 0` et `preview = NULL`.
     */
    @Query(
        """
        UPDATE conversations
        SET
          last_message_at = COALESCE(
              (SELECT MAX(date) FROM messages
               WHERE conversation_id = :conversationId
                 AND hidden = 0),
              0
          ),
          last_message_preview = (
              SELECT body FROM messages
              WHERE conversation_id = :conversationId
                AND hidden = 0
              ORDER BY date DESC, id DESC LIMIT 1
          )
        WHERE id = :conversationId
        """,
    )
    suspend fun refreshConversationPreview(conversationId: Long)

    /**
     * v1.24.0 (bug suppression, réparation one-shot) — corrige les aperçus déjà périmés par des
     * suppressions passées, sur les versions ≤ 1.23.4 qui ne recalculaient pas la conversation.
     *
     * **Ciblage strict** : seules les conversations dont le `last_message_at` stocké est SUPÉRIEUR
     * au `date` du message le plus récent réellement présent (= le dernier message a été supprimé)
     * sont recalculées. Une conversation saine (`last_message_at == MAX(date)`) n'est jamais
     * touchée — pas de réordonnancement ni de perte de libellé sur les autres. La détection de
     * péremption compte TOUS les messages ; l'aperçu se reconstruit hors sentinelles.
     */
    @Query(
        """
        UPDATE conversations
        SET
          last_message_at = COALESCE(
              (SELECT MAX(date) FROM messages
               WHERE conversation_id = conversations.id
                 AND hidden = 0),
              0
          ),
          last_message_preview = (
              SELECT body FROM messages
              WHERE conversation_id = conversations.id
                AND hidden = 0
              ORDER BY date DESC, id DESC LIMIT 1
          )
        WHERE last_message_at > COALESCE(
            (SELECT MAX(date) FROM messages WHERE conversation_id = conversations.id),
            0
        )
        """,
    )
    suspend fun repairStaleConversationPreviews(): Int

    /**
     * v1.3.0 — compte combien de messages seraient effacés par [purgeOlderThan] avec ce
     * même `olderThan`. Sert au bouton "Effacer maintenant" du dialog réglages : on
     * affiche d'abord à l'utilisateur "X messages vont être effacés, continuer ?" pour
     * éviter un wipe massif accidentel. Utilise le même filtre `starred = 0` pour la
     * cohérence parfaite avec la purge réelle.
     *
     * v1.28.3 (F11) — et la MEME exclusion du Coffre. Ce nombre est montré à l'utilisateur juste
     * avant qu'il confirme : le laisser diverger du `DELETE` qu'il annonce ferait promettre
     * l'effacement de messages que la purge ne touche plus. « Le même filtre pour la cohérence
     * parfaite » était déjà l'intention écrite ici ; elle vaut aussi pour ce filtre-ci.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
         WHERE date < :olderThan AND starred = 0
           AND conversation_id IN (SELECT id FROM conversations WHERE in_vault = 0)
        """,
    )
    suspend fun countOlderThan(olderThan: Long): Int
}
