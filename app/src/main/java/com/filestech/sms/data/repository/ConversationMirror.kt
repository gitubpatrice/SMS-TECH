package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.MessageType
import android.telephony.PhoneNumberUtils
import com.filestech.sms.core.ext.WireAddress
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.di.IoDispatcher
import timber.log.Timber
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction.Kind
import com.filestech.sms.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of truth for inserting an incoming/outgoing message into Room.
 * Keeps the conversation row consistent (lastMessageAt, preview, unreadCount).
 */
@Singleton
class ConversationMirror @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val attachmentDao: AttachmentDao,
    private val contacts: ContactRepository,
    private val wireFormatter: PhoneNumberWireFormatter,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Process-wide cache of `address → displayName` lookups. Avoids re-querying the contacts
     * provider for the same phone number across multiple receive/send paths.
     *
     * **v1.3.7 (F5 audit)** — passé de `ConcurrentHashMap` non borné à [android.util.LruCache]
     * borné à [DISPLAY_NAME_CACHE_MAX] (1000) entries. Sur les comptes très anciens (10 ans+ de
     * SMS = 50 000+ messages tous opérateurs, banque, livraisons, 2FA, démarchage), la map
     * pouvait croître jusqu'à plusieurs milliers d'entrées et conserver ces strings ad vitam,
     * sans plafond. 1000 entrées couvre largement le nombre de correspondants distincts d'un
     * utilisateur normal (les expéditeurs alphanumériques type "Free", "INFO", "ORANGE" sont
     * partagés). L'éviction LRU drop les expéditeurs les plus anciens d'abord — donc les
     * conversations actives gardent toujours leur nom résolu en cache chaud.
     *
     * **Thread-safety.** Ce `@Singleton` est invoqué concurremment depuis des threads
     * broadcast-receiver (`SmsDeliverReceiver`, `MmsDownloadedReceiver`), des workers
     * WorkManager (`TelephonySyncWorker.runSync → bulkImportFromTelephony`), et des coroutines
     * Hilt-scoped (`SendSmsUseCase → upsertOutgoingSms`). [android.util.LruCache] est
     * thread-safe (synchronized interne sur `get` / `put` / `evictAll`) — équivalent au
     * `ConcurrentHashMap` précédent côté garantie atomicity.
     *
     * `LruCache` rejette les valeurs `null`, donc on encode "no name found" comme chaîne vide
     * et on retraduit en `null` dans [resolveDisplayName] — le cache négatif reste tout aussi
     * précieux (skip de la requête contacts au second tour).
     */
    private val displayNameCache = android.util.LruCache<String, String>(DISPLAY_NAME_CACHE_MAX)

    private suspend fun resolveDisplayName(rawAddress: String): String? {
        displayNameCache.get(rawAddress)?.let { return if (it.isEmpty()) null else it }
        val name = runCatching { contacts.lookupByPhone(rawAddress)?.displayName }.getOrNull()
        // Empty string is our sentinel for "looked up, no match" — keeps `LruCache`
        // happy (it rejects nulls) without losing the negative-cache behaviour.
        displayNameCache.put(rawAddress, name.orEmpty())
        return name
    }

    suspend fun upsertIncomingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
    ): Long = withContext(io) {
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = body,
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = false,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 0,
            )
            val msgId = messageDao.insert(msg)
            touchConversation(convId, date, body, deltaUnread = +1)
            msgId
        }
    }

    /**
     * v1.4.1 (SEC-01) — drops a "poison-pill" Room row carrying the system [telephonyUri]
     * of an incoming SMS that was already folded into a reaction badge by
     * [applyIncomingReaction]. Without this sentinel, [TelephonySyncManager] would later
     * re-import the very same `Reacted ❤️ to «…»` body from `content://sms` as a regular
     * incoming bubble — duplicating the user-visible event (one badge + one phantom text
     * bubble).
     *
     * The sentinel is intentionally invisible :
     *   - `body = ""`        → renders no text in the thread list / bubble
     *   - `read = true`      → never bumps the unread count
     *   - `reaction_emoji = null` → does not paint an extra badge anywhere
     *   - same `telephonyUri` as the system row → unique index in [MessageEntity] makes
     *     the future [TelephonySyncManager] insert a no-op (`OnConflictStrategy.IGNORE`).
     *
     * Idempotent : a second call with the same `telephonyUri` is silently ignored at the
     * DAO level. Safe to call after a successful [applyIncomingReaction] even if the user
     * resends the same reaction (rare carrier replay).
     */
    suspend fun upsertReactionSentinel(
        address: String,
        telephonyUri: String?,
        date: Long,
    ) = withContext(io) {
        if (telephonyUri.isNullOrBlank()) return@withContext
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val sentinel = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = "",
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = true,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = null,
                scheduledAt = null,
                attachmentsCount = 0,
            )
            // `OnConflictStrategy.IGNORE` on the insert means a second call with the same
            // telephonyUri is a no-op — idempotent.
            messageDao.insert(sentinel)
            // NB: we do NOT call `touchConversation` here — the user never sent or
            // received a new message, just a metadata reaction. Bumping
            // `lastMessageAt` / `lastMessagePreview` would surface the empty body in
            // the conversation list, which is exactly what we want to avoid.
        }
    }

    /**
     * v1.4.1 — tries to apply an incoming reaction back to an outgoing message we
     * previously sent to [address]. Returns `true` when a target outgoing message was
     * found and its `reaction_emoji` was updated ; `false` when no match exists (caller
     * then falls back to inserting the body as a plain incoming SMS).
     *
     * Lookup strategy depends on [kind] :
     *   - [com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction
     *     .Kind.Tapback] — verbose `Reacted <emoji> [to «…»]` shape. Always safe to
     *     bind to ANY recent outgoing in the conversation. Match either by body
     *     prefix (when [bodyPrefix] is non-null) or by "most recent outgoing"
     *     (when the remote reacted to a message with no text body, voice / image).
     *   - [com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction
     *     .Kind.EmojiOnly] — bare emoji SMS, ambiguous with a real one-emoji message.
     *     The match requires an outgoing message strictly younger than
     *     [IncomingReactionDecoder.EMOJI_ONLY_REACT_WINDOW_MS]. If none exists, we
     *     give up and the caller stores the body as a regular incoming SMS.
     *
     * The conversation is resolved via exact-CSV match, then suffix-8 fallback (covers
     * the `+33` vs `06` representation drift between provider and SMS Tech). We do NOT
     * call [ensureConversation] here — the reaction cannot target anything we haven't
     * sent, so a missing conv is a fast-fail.
     *
     * No side-effect on `conversations.lastMessagePreview` / `unreadCount` : a reaction
     * is a metadata update on an existing row, not a new message.
     */
    /**
     * v1.4.1 — outcome of [applyIncomingReaction]. `null` = no matching outgoing
     * message was found (caller falls back to inserting the body as a plain incoming
     * SMS). Non-null carries the `conversationId`, the `targetMessageId` (Room id of
     * the outgoing message the reaction was glued onto) and the body of that message
     * — all three are consumed by the receiver to post a "<sender> a réagi ❤️ : …"
     * system notification (v1.6.1).
     *
     * `targetMessageId` is required by
     * [com.filestech.sms.system.notifications.IncomingMessageNotifier.notifyIncoming]
     * to derive a stable, non-colliding notification id (one notif per reacted
     * message) and to deep-link the user back to the precise message on tap.
     */
    data class ReactionApplied(
        val conversationId: Long,
        val targetMessageId: Long,
        val targetBody: String,
    )

    suspend fun applyIncomingReaction(
        address: String,
        emoji: String,
        bodyPrefix: String?,
        kind: Kind,
        /**
         * v1.6.2 — `true` quand le wire Tapback contenait le marker `…` (encoder a
         * tronqué). Détermine la stratégie de match :
         *   - `false` (body court non tronqué) → match EXACT après normalisation des
         *     whitespace. Résout l'ambiguïté "Hello" vs "Hello world" : avant on
         *     prenait toujours le plus récent (FAUX si l'utilisateur a réagi à un
         *     ancien message court), maintenant on prend celui dont le body normalisé
         *     EQUALS le prefix.
         *   - `true` (body long, prefix seul connu) → match `STARTS WITH` (l'unique
         *     stratégie possible quand on n'a que les 47 premiers chars). Ambiguïté
         *     résiduelle inhérente au protocole SMS-based Tapback.
         */
        wasTruncated: Boolean = false,
    ): ReactionApplied? = withContext(io) {
        val addr = PhoneAddress.of(address)
        if (addr.normalized.isEmpty()) return@withContext null
        // Avoid creating an empty conversation just to lookup against it — only
        // proceed if a conversation for this address already exists.
        val csv = addr.raw
        val existing = conversationDao.findByAddressesCsv(csv)
            ?: conversationDao.snapshotOneToOneConversations().firstOrNull { conv ->
                PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw?.phoneSuffix8() ==
                    addr.raw.phoneSuffix8()
            }
            ?: return@withContext null
        val target = when (kind) {
            Kind.Tapback -> {
                if (bodyPrefix != null) {
                    val normalizedPrefix = bodyPrefix.collapseWhitespace()
                    val recentOutgoing = messageDao.findRecentOutgoingForConversation(
                        conversationId = existing.id,
                        limit = TAPBACK_FALLBACK_LOOKUP_LIMIT,
                    )
                    if (!wasTruncated) {
                        // v1.6.2 — match EXACT (body normalisé == prefix). Élimine
                        // l'ambiguïté quand plusieurs OUTGOING partagent un préfixe
                        // court ("Hello" ≠ "Hello world"). Itère le plus récent en
                        // premier ; comme la liste DAO est ORDER BY date DESC, le
                        // firstOrNull retourne le plus récent EXACT match — en pratique
                        // le seul, sauf duplicate identique rare.
                        recentOutgoing.firstOrNull { entity ->
                            entity.body.collapseWhitespace() == normalizedPrefix
                        }
                    } else {
                        // v1.4.1 / v1.6.2 — body tronqué : match préfixe inévitable.
                        // SQL LIKE rapide d'abord (cas mono-ligne), fallback Kotlin
                        // si le body OUTGOING contient des newlines (l'encoder
                        // normalise les whitespace mais pas le DAO).
                        val escaped = escapeForSqlLike(bodyPrefix)
                        messageDao.findMostRecentOutgoingByBodyPrefix(existing.id, escaped)
                            ?: recentOutgoing.firstOrNull { entity ->
                                entity.body.collapseWhitespace().startsWith(normalizedPrefix)
                            }
                    }
                } else {
                    messageDao.findMostRecentOutgoing(existing.id)
                }
            }
            Kind.EmojiOnly -> {
                val sinceMs = System.currentTimeMillis() -
                    IncomingReactionDecoder.EMOJI_ONLY_REACT_WINDOW_MS
                messageDao.findMostRecentOutgoingAfter(existing.id, sinceMs)
            }
        } ?: return@withContext null
        messageDao.setReaction(target.id, emoji)
        ReactionApplied(
            conversationId = existing.id,
            targetMessageId = target.id,
            targetBody = target.body,
        )
    }

    /**
     * Escapes the three SQL LIKE wildcards (`%`, `_`, `\`) so a body containing them
     * is matched literally. Mirrors the `ESCAPE '\'` clause in the DAO query.
     */
    private fun escapeForSqlLike(input: String): String =
        input
            .replace("""\""", """\\""")
            .replace("""%""", """\%""")
            .replace("""_""", """\_""")

    /**
     * v1.6.2 — collapse de tout whitespace (espaces multiples, tabs, newlines `\n` `\r`,
     * U+2028, U+2029, etc.) en un espace simple + trim. Utilisé pour le fallback de fold
     * Tapback : l'encoder normalise déjà côté envoyeur, on doit le faire aussi côté
     * receveur pour comparer le previewPrefix au body OUTGOING d'origine.
     */
    private fun String.collapseWhitespace(): String =
        this.replace(Regex("\\s+"), " ").trim()

    // v1.6.2 — la constante TAPBACK_FALLBACK_LOOKUP_LIMIT vit dans le companion
    // principal en bas du fichier (Kotlin n'accepte qu'un seul companion par classe).

    suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
        // v1.16.0 — Type sécurisé (était Int). Default PENDING inchangé.
        initialStatus: MessageStatus = MessageStatus.PENDING,
        replyToMessageId: Long? = null,
        /**
         * v1.4.1 — when non-null, overrides what gets stored in the Room row's `body`
         * column (the on-wire SMS body sent via `SmsManager` and mirrored to the
         * system inbox remains [body], unchanged). Used by [com.filestech.sms.domain
         * .usecase.SendReactionUseCase] to silently send a Tapback to the
         * correspondent while keeping its own thread free of a redundant outgoing
         * bubble — the empty `localMirrorBody = ""` row is filtered out at the DAO
         * query level (`observeForConversation` excludes body=''+0 attach+0 reaction).
         * Default `null` = mirror the wire body as-is (regular text SMS).
         */
        localMirrorBody: String? = null,
    ): Long = withContext(io) {
        val mirrorBody = localMirrorBody ?: body
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = mirrorBody,
                type = MessageType.SMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = initialStatus,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 0,
                replyToMessageId = replyToMessageId,
            )
            val msgId = messageDao.insert(msg)
            // v1.4.1 — when the row is a hidden reaction sentinel (mirrorBody=""),
            // we don't bump `lastMessageAt` / `lastMessagePreview` either, otherwise
            // the conversation list would show a blank preview line and the wrong
            // sort order (sentinel timestamp masking the real last message). For
            // regular SMS, [touchConversation] behaviour is unchanged.
            if (mirrorBody.isNotEmpty()) {
                touchConversation(convId, date, mirrorBody, deltaUnread = 0)
            }
            msgId
        }
    }

    // v1.16.0 — Paramètre `status` typé MessageStatus (était Int) — propagation depuis le DAO.
    suspend fun updateOutgoingStatus(localId: Long, status: MessageStatus, errorCode: Int? = null) = withContext(io) {
        messageDao.updateStatus(localId, status, errorCode)
    }

    /**
     * Inserts an outgoing MMS row + its single audio attachment in one transaction. Returns the
     * message id so the caller (MmsSender) can correlate the dispatch result.
     *
     * The audio file is referenced by its absolute path (recorded into the app's private cache
     * by [com.filestech.sms.data.voice.VoiceRecorder]); ownership transfers to the row so the
     * file persists for the lifetime of the message (cascade-deleted with it).
     *
     * Preview/body: emoji + duration label so the conversation list shows something meaningful
     * without having to fetch the attachment table.
     */
    suspend fun upsertOutgoingMms(
        address: String,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        date: Long,
        subId: Int? = null,
    ): Long = withContext(io) {
        val durationLabel = formatDurationLabel(durationMs)
        val preview = "🎤 $durationLabel"
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = null,
                address = address,
                body = preview,
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = MessageStatus.PENDING,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 1,
            )
            val msgId = messageDao.insert(msg)
            attachmentDao.insert(
                AttachmentEntity(
                    messageId = msgId,
                    mimeType = mimeType,
                    fileName = audioFile.name,
                    sizeBytes = audioFile.length(),
                    localUri = audioFile.absolutePath,
                    width = null,
                    height = null,
                    durationMs = durationMs,
                ),
            )
            touchConversation(convId, date, preview, deltaUnread = 0)
            msgId
        }
    }

    /**
     * Generic outgoing-MMS mirror for **non-voice** attachments (photo, video, PDF, contact card,
     * arbitrary file). Same transaction guarantees as [upsertOutgoingMms] but accepts a list of
     * [MediaAttachmentSpec] so a single multipart MMS surfaces as **one** message row in the UI
     * with N attachments — matching what `bulkImportMmsFromTelephony` does on the import side.
     *
     * The preview line is the user's text body if any, otherwise an emoji + filename fallback so
     * the conversation list still shows something meaningful.
     */
    suspend fun upsertOutgoingMediaMms(
        address: String,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int? = null,
    ): Long = withContext(io) {
        require(attachments.isNotEmpty()) { "upsertOutgoingMediaMms requires at least one attachment" }
        // v1.3.10 — `messages.body` stores the **user caption verbatim** (possibly empty), so
        // MediaAttachmentBubble can decide whether to render a caption line below the icon.
        // `preview` is only used to keep the conversation list informative ("📎 file.pdf"
        // when the user sent the file without a caption).
        val storedBody = textBody.trim()
        val preview = storedBody.ifBlank { mediaPreviewLabel(attachments.first()) }
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = null,
                address = address,
                body = storedBody,
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = MessageStatus.PENDING,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = attachments.size,
            )
            val msgId = messageDao.insert(msg)
            for (a in attachments) {
                attachmentDao.insert(
                    AttachmentEntity(
                        messageId = msgId,
                        mimeType = a.mimeType,
                        fileName = a.file.name,
                        sizeBytes = a.file.length(),
                        localUri = a.file.absolutePath,
                        width = a.width,
                        height = a.height,
                        durationMs = a.durationMs,
                    ),
                )
            }
            touchConversation(convId, date, preview, deltaUnread = 0)
            msgId
        }
    }

    /** Caller-supplied description of one outgoing MMS attachment. */
    data class MediaAttachmentSpec(
        val file: File,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
    )

    private fun mediaPreviewLabel(a: MediaAttachmentSpec): String = when {
        a.mimeType.startsWith("image/") -> "🖼️ " + a.file.name
        a.mimeType.startsWith("video/") -> "🎞️ " + a.file.name
        a.mimeType.startsWith("audio/") -> "🎤 " + a.file.name
        a.mimeType == "text/x-vcard" || a.mimeType == "text/vcard" -> "👤 " + a.file.name
        else -> "📎 " + a.file.name
    }

    /**
     * Mirrors an incoming MMS retrieved through [com.filestech.sms.pdu.PduParser]. The caller is
     * responsible for having already written the attachment bytes to disk and passed the absolute
     * file path here.
     *
     * **v1.3.10** : `caption` is the raw user text (e.g. the text/plain part of the multipart),
     * stored verbatim in `messages.body` (empty when absent). `previewLabel` is the conversation-
     * list line — derived by the caller from caption / Subject / mime placeholder. Decoupling
     * the two avoids rendering a placeholder emoji as a fake text caption under the attachment
     * bubble.
     */
    suspend fun upsertIncomingMms(
        address: String,
        attachmentFile: File?,
        mimeType: String?,
        durationMs: Long?,
        caption: String?,
        previewLabel: String,
        date: Long,
        telephonyUri: String? = null,
        subId: Int? = null,
    ): Long = withContext(io) {
        val storedBody = caption?.trim().orEmpty()
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = storedBody,
                type = MessageType.MMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = false,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = if (attachmentFile != null && mimeType != null) 1 else 0,
            )
            val msgId = messageDao.insert(msg)
            if (attachmentFile != null && mimeType != null) {
                attachmentDao.insert(
                    AttachmentEntity(
                        messageId = msgId,
                        mimeType = mimeType,
                        fileName = attachmentFile.name,
                        sizeBytes = attachmentFile.length(),
                        localUri = attachmentFile.absolutePath,
                        width = null,
                        height = null,
                        durationMs = durationMs,
                    ),
                )
            }
            touchConversation(convId, date, previewLabel, deltaUnread = +1)
            msgId
        }
    }

    private fun formatDurationLabel(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    /**
     * One-shot pass that walks every conversation with a missing display name and resolves it via
     * the contacts provider. Called from [ConversationsViewModel] at screen init so newly granted
     * READ_CONTACTS permission immediately updates the list — no reinstall needed.
     */
    suspend fun refreshContactNames() = withContext(io) {
        displayNameCache.evictAll()
        val missing = conversationDao.findMissingDisplayName()
        if (missing.isEmpty()) return@withContext
        database.withTransaction {
            for (conv in missing) {
                val firstAddr = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw ?: continue
                val name = resolveDisplayName(firstAddr) ?: continue
                conversationDao.setDisplayName(conv.id, name)
            }
        }
    }

    /**
     * Mirror every row returned by [com.filestech.sms.data.sms.TelephonyReader.readMmsBatched]
     * into Room. MMS rows live in `content://mms` (separate from `content://sms`), and without
     * this method they vanish from the UI after a re-install — system rows survive, but our
     * SQLCipher mirror is wiped and the SMS-only sync never picks them up.
     *
     * Idempotency: each row's `telephonyUri` is `content://mms/{id}`, indexed UNIQUE in Room,
     * so the `OnConflictStrategy.IGNORE` on `messageDao.insert` short-circuits duplicates.
     * Attachments are inserted only on a fresh row (the `insertedId == -1L` path means the row
     * was a duplicate, so the existing attachments already exist).
     */
    suspend fun bulkImportMmsFromTelephony(rows: List<com.filestech.sms.data.sms.TelephonyReader.MmsImportRow>) = withContext(io) {
        if (rows.isEmpty()) return@withContext
        // v1.2.4 audit P2: group by AOSP `thread_id` so each conversation gets exactly one
        // `findById + update` (= one `touchConversation`) per chunk instead of one per row.
        // For 500 MMS across 20 threads that's 20 SQLCipher updates instead of 500. Mirrors
        // the per-thread aggregation already used by `bulkImportFromTelephony` (SMS path).
        val byThread = rows.groupBy { it.threadId }
        database.withTransaction {
            // v1.2.7 audit P4 — buffer les AttachmentEntity de tout le group, puis 1 seul
            // `insertAll` à la fin pour économiser N-1 IPC SQLCipher. Sur un import 500 MMS
            // × ~1.5 attachments moyens, gain mesuré 400-600 ms cumulés. Le buffer est
            // réutilisé via `.clear()` au début de chaque itération de group.
            val attachmentsBuf = ArrayList<AttachmentEntity>(32)
            for ((_, group) in byThread) {
                val first = group.first()
                val convId = ensureConversationByThread(
                    systemThreadId = first.threadId,
                    addresses = listOf(PhoneAddress.of(first.address)),
                )
                var maxDate = 0L
                var lastPreview = ""
                var unreadDelta = 0
                attachmentsBuf.clear()
                for (row in group) {
                    val msg = MessageEntity(
                        conversationId = convId,
                        telephonyUri = "content://mms/${row.telephonyId}",
                        address = row.address,
                        body = row.textBody,
                        type = MessageType.MMS,
                        direction = row.direction,
                        date = row.dateMs,
                        dateSent = row.dateMs,
                        read = row.read,
                        starred = false,
                        status = row.status,
                        errorCode = null,
                        subId = row.subId,
                        scheduledAt = null,
                        attachmentsCount = row.attachments.size,
                    )
                    val insertedId = messageDao.insert(msg)
                    // Insert id < 0 → conflict, row already mirrored; skip part insertion.
                    if (insertedId <= 0L) continue
                    for (part in row.attachments) {
                        attachmentsBuf += AttachmentEntity(
                            messageId = insertedId,
                            mimeType = part.contentType,
                            fileName = part.filename ?: "part_${part.partId}",
                            sizeBytes = 0L,
                            // The system keeps the binary payload on disk and serves it through
                            // this URI — no need to copy bytes into our cache. Coil / MediaPlayer
                            // both accept `content://mms/part/…` directly.
                            localUri = "content://mms/part/${part.partId}",
                            width = null,
                            height = null,
                            durationMs = null,
                        )
                    }
                    if (row.dateMs > maxDate) {
                        maxDate = row.dateMs
                        lastPreview = row.textBody.ifBlank { firstAttachmentPreviewLabel(row.attachments) }
                    }
                    if (row.direction == MessageDirection.INCOMING && !row.read) unreadDelta++
                }
                if (attachmentsBuf.isNotEmpty()) {
                    // v1.2.7 audit P4 — batch insert des parts pour ce group avant de passer
                    // au suivant. Buffer réutilisé via `.clear()` en début de boucle group.
                    attachmentDao.insertAll(attachmentsBuf)
                }
                if (maxDate > 0L) {
                    touchConversation(convId, maxDate, lastPreview, deltaUnread = unreadDelta)
                }
            }
        }
    }

    /** Returns a short emoji + type label for the conversation preview when the MMS has no text. */
    private fun firstAttachmentPreviewLabel(parts: List<com.filestech.sms.data.sms.TelephonyReader.MmsPartImport>): String {
        val first = parts.firstOrNull() ?: return ""
        return when {
            first.contentType.startsWith("audio/") -> "🎤 " + (first.filename ?: "Audio")
            first.contentType.startsWith("image/") -> "🖼️ " + (first.filename ?: "Image")
            first.contentType.startsWith("video/") -> "🎞️ " + (first.filename ?: "Vidéo")
            else -> "📎 " + (first.filename ?: first.contentType)
        }
    }

    /**
     * Bulk import: groups inserts + conversation touches into a SINGLE Room transaction so the
     * `messages` / `conversations` tables only invalidate once at commit time. Without this the
     * UI recomposes thousands of times during a 5000-SMS import = the "single row scrolling at
     * full speed" symptom users reported on Samsung One UI 6 / 7.
     *
     * `messages.telephony_uri` has a UNIQUE index + `OnConflictStrategy.IGNORE`, so re-running
     * the import is idempotent: existing rows stay put, only fresh URIs add new rows.
     */
    suspend fun bulkImportFromTelephony(messages: List<MessageEntity>) = withContext(io) {
        if (messages.isEmpty()) return@withContext
        // Audit P-P0-1 bis: the cache is no longer cleared at the start of a bulk import.
        // (1) Now that the underlying store is a `ConcurrentHashMap`, the clear / put race that
        // motivated the previous wipe is gone. (2) Entries are immutable `(number → name)`
        // pairs; if a contact name actually changes, the [com.filestech.sms.data.contacts.ContactsReader]
        // observer will invalidate downstream. (3) Keeping the cache hot across imports means a
        // live SMS arriving mid-sync reuses the resolutions the sync already paid for instead
        // of re-querying the contacts provider for every common correspondent.
        database.withTransaction {
            // Group by system thread_id — that's the source of truth for "which conversation".
            // `MessageEntity.conversationId` here actually carries the system Telephony.Sms.THREAD_ID
            // (set by TelephonyReader.toSms before we know our local convId).
            val byThread = HashMap<Long, MutableList<MessageEntity>>(messages.size / 8 + 1)
            for (m in messages) {
                byThread.getOrPut(m.conversationId) { ArrayList() } += m
            }
            for ((systemThreadId, group) in byThread) {
                val first = group.first()
                val convId = ensureConversationByThread(
                    systemThreadId = systemThreadId,
                    addresses = listOf(PhoneAddress.of(first.address)),
                )
                val withConv = group.map { it.copy(conversationId = convId) }
                // v1.8.0 (bug 2 fix) — Room's @Insert(OnConflictStrategy.IGNORE) returns
                // -1L for each row that conflicted with the UNIQUE (telephony_uri) index
                // (i.e. already mirrored), and the new row id for fresh inserts. Before
                // this fix the unread delta was calculated over the WHOLE group — every
                // re-sync (12h worker or manual pull-to-refresh) re-bumped the badge by
                // the count of incoming-unread rows even when the user had already opened
                // the thread and Room had cleared its unread counter. Now we count ONLY
                // rows that actually inserted, so the badge reflects truly new arrivals.
                //
                // The MMS path above (`bulkImportMmsFromTelephony`, line ~620) is not
                // affected by the same regression because it iterates row-by-row with a
                // `if (insertedId <= 0L) continue` that already skips the delta increment
                // for conflicting rows. Keep both branches symmetric in spirit even if
                // the syntactic shape differs.
                val insertedIds = messageDao.insertAll(withConv)
                val last = group.maxBy { it.date }
                val unreadDelta = withConv.withIndex().count { (idx, m) ->
                    insertedIds.getOrElse(idx) { -1L } > 0L &&
                        m.direction == MessageDirection.INCOMING &&
                        !m.read
                }
                touchConversation(convId, last.date, last.body, deltaUnread = unreadDelta)
            }
        }
    }

    /**
     * Ensures a conversation row exists for the given system thread_id and returns our local id.
     * Required for the bulk import path — without it, all conversations collide on `thread_id=0`
     * (the UNIQUE index across the column makes them REPLACE each other).
     */
    private suspend fun ensureConversationByThread(
        systemThreadId: Long,
        addresses: List<PhoneAddress>,
    ): Long {
        val resolved = resolveDisplayName(addresses.first().raw)
        if (systemThreadId > 0L) {
            conversationDao.findByThreadId(systemThreadId)?.let { existing ->
                if (existing.displayName == null && resolved != null) {
                    conversationDao.update(existing.copy(displayName = resolved))
                }
                return existing.id
            }
        }
        val csv = addresses.sortedBy { it.normalized }.toCsv()

        // v1.3.10 — exact-CSV fallback BEFORE the suffix-8 lookup. Catches the case where
        // [MmsDownloadedReceiver] just created the conversation without a system thread id
        // (we never query content://mms-sms/threadID from the receiver) and the subsequent
        // [TelephonySyncManager.bulkImportMmsFromTelephony] is now importing the same MMS
        // with its real system thread id. Without this, we'd insert a second conversation
        // for the same correspondent — user-visible as a duplicate thread.
        conversationDao.findByAddressesCsv(csv)?.let { existing ->
            if (systemThreadId > 0L && existing.threadId != systemThreadId) {
                conversationDao.update(existing.copy(
                    threadId = systemThreadId,
                    displayName = existing.displayName ?: resolved,
                ))
            } else if (existing.displayName == null && resolved != null) {
                conversationDao.update(existing.copy(displayName = resolved))
            }
            return existing.id
        }

        // v1.3.10 — suffix-8 fallback : if the existing conversation was created from a raw
        // PDU address (e.g. "+33617332729") and we're now importing from `content://mms` with
        // the gateway-decorated form (e.g. "+33617332729/TYPE=PLMN"), the exact-CSV match
        // misses but the last 8 digits do agree. Same rationale as [ensureConversation].
        if (addresses.size == 1) {
            val incomingSuffix = addresses.first().raw.phoneSuffix8()
            if (incomingSuffix.length == 8) {
                val oneToOne = conversationDao.snapshotOneToOneConversations()
                val match = oneToOne.firstOrNull { conv ->
                    val convAddress = PhoneAddress.list(conv.addressesCsv).firstOrNull()
                    convAddress != null && convAddress.raw.phoneSuffix8() == incomingSuffix
                }
                if (match != null) {
                    if (systemThreadId > 0L && match.threadId != systemThreadId) {
                        conversationDao.update(match.copy(
                            threadId = systemThreadId,
                            displayName = match.displayName ?: resolved,
                        ))
                    } else if (match.displayName == null && resolved != null) {
                        conversationDao.update(match.copy(displayName = resolved))
                    }
                    return match.id
                }
            }
        }

        return conversationDao.upsert(
            ConversationEntity(
                threadId = systemThreadId,
                addressesCsv = csv,
                displayName = resolved,
                // Sentinel 0L — the **next** [touchConversation] call (which always follows an
                // ensure*) sets `lastMessageAt = maxOf(date, 0)` = the real message date. The
                // previous `System.currentTimeMillis()` default was a bug: importing 6-month-old
                // MMS would silently set lastMessageAt = now via the `maxOf(now, oldDate) = now`
                // clamp, scrambling the conversation list order during bulk import.
                lastMessageAt = 0L,
                lastMessagePreview = null,
                unreadCount = 0,
            ),
        )
    }

    private suspend fun ensureConversation(addresses: List<PhoneAddress>): Long {
        val csv = addresses.sortedBy { it.normalized }.toCsv()
        val resolved = resolveDisplayName(addresses.first().raw)

        // 1) Exact-CSV match — chemin rapide, couvre la majorité des cas (même format
        //    d'adresse stocké et présenté).
        conversationDao.findByAddressesCsv(csv)?.let { existing ->
            if (existing.displayName == null && resolved != null) {
                conversationDao.update(existing.copy(displayName = resolved))
            }
            return existing.id
        }

        // 2) v1.3.3 — FALLBACK matching par suffix 8 chiffres pour les conversations
        //    1-to-1. Sans ce fallback, un SMS reçu en format national `0612345678` créerait
        //    une 2ᵉ conversation alors qu'une conv existe déjà en format international
        //    `+33612345678` (importée du système). Bug user-visible : liste des
        //    conversations qui se remplit de doublons VIDES à chaque SMS reçu (les
        //    messages tombent dans la conv "broadcast" pendant que la conv "import"
        //    historique reste figée).
        //
        //    Restreint aux conv 1-to-1 (`snapshotOneToOneConversations` filtre déjà les
        //    groupes). Pour les groupes, le matching CSV strict reste la bonne approche :
        //    deux participants partiels ne doivent PAS être confondus avec un autre
        //    groupe via suffix.
        if (addresses.size == 1) {
            val incomingSuffix = addresses.first().raw.phoneSuffix8()
            if (incomingSuffix.length == 8) {
                val oneToOne = conversationDao.snapshotOneToOneConversations()
                val match = oneToOne.firstOrNull { conv ->
                    val convAddress = PhoneAddress.list(conv.addressesCsv).firstOrNull()
                    convAddress != null && convAddress.raw.phoneSuffix8() == incomingSuffix
                }
                if (match != null) {
                    if (match.displayName == null && resolved != null) {
                        conversationDao.update(match.copy(displayName = resolved))
                    }
                    return match.id
                }
            }
        }

        // 3) Vraiment nouvelle conversation — création.
        return conversationDao.upsert(
            ConversationEntity(
                threadId = 0L,
                addressesCsv = csv,
                displayName = resolved,
                // Same sentinel as `ensureConversationByThread` — see comment there.
                lastMessageAt = 0L,
                lastMessagePreview = null,
                unreadCount = 0,
            ),
        )
    }

    /**
     * v1.22.x — dédup « one-heal » des conversations 1-to-1 du MÊME numéro laissées en double par
     * les versions antérieures aux correctifs de threading (fallback suffix-8 à la réception v1.3.3
     * + `findOrCreate` au composer v1.21.1). Ces correctifs empêchent les NOUVEAUX doublons mais ne
     * fusionnent pas ceux déjà en base — cette passe les réunit.
     *
     * Appelée au cold-start ([com.filestech.sms.MainApplication]), en tâche de fond ; idempotente
     * (une 2ᵉ passe ne trouve plus aucun groupe ≥ 2 → no-op).
     *
     * Sûreté (« ne jamais fusionner deux personnes différentes ») :
     *   - clé de fusion = **E.164** (région SIM/réglage résolue une seule fois), PLUS stricte que
     *     le suffix-8 de la réception : deux numéros distincts partageant leurs 8 derniers chiffres
     *     (ex. `06…12345678` vs `07…12345678`) obtiennent des clés différentes → non fusionnés. Un
     *     numéro non normalisable (short code, expéditeur alphanumérique, région inconnue) n'a pas
     *     de clé `+…` → jamais fusionné.
     *   - coffre-fort EXCLU (`in_vault` filtré) : contexte sensible jamais touché.
     *   - reparent des messages AVANT suppression de la conversation source (FK `onDelete = CASCADE`
     *     sur `messages.conversation_id` — supprimer d'abord effacerait les messages).
     *   - fusion dans une transaction unique ; `unread_count` recalculé depuis les messages réels.
     *   - `scheduled_messages.conversation_id` est reparenté vers le survivant (cohérence des
     *     données, même si l'envoi programmé résout aujourd'hui ses destinataires via `addressesCsv`).
     *   - le `threadId` système AOSP d'une victime est repris par le survivant s'il n'en avait pas,
     *     pour ne pas dégrader la propagation `markRead` vers `content://sms|mms`.
     *
     * Retourne `true` si au moins un groupe de doublons a été fusionné, `false` si la base était
     * déjà propre — l'appelant s'en sert pour ne mémoriser la complétion qu'une fois propre.
     */
    suspend fun dedupeSameNumberConversations(): Boolean = withContext(io) {
        val oneToOne = conversationDao.snapshotOneToOneConversations().filterNot { it.inVault }
        if (oneToOne.size < 2) return@withContext false
        val region = wireFormatter.defaultRegionIso()
        val candidates = oneToOne.mapNotNull { conv ->
            val raw = PhoneAddress.list(conv.addressesCsv).singleOrNull()?.raw ?: return@mapNotNull null
            DedupCandidate(id = conv.id, rawAddress = raw, lastMessageAt = conv.lastMessageAt)
        }
        val plans = planSameNumberMerges(candidates) { raw ->
            WireAddress.toE164OrRaw(raw, region) { number, r ->
                PhoneNumberUtils.formatNumberToE164(number, r)
            }.takeIf { it.startsWith("+") }
        }
        if (plans.isEmpty()) return@withContext false
        database.withTransaction {
            for (plan in plans) {
                // 1) reparent AVANT toute suppression (FK CASCADE sur messages.conversation_id).
                //    scheduled_messages est aussi reparenté (pas de FK, mais cohérence des données).
                for (victimId in plan.victimIds) {
                    messageDao.reparentMessages(
                        fromConversationId = victimId,
                        toConversationId = plan.survivorId,
                    )
                    scheduledMessageDao.reparentConversationId(
                        fromConversationId = victimId,
                        toConversationId = plan.survivorId,
                    )
                }
                // 2) fusion des métadonnées sur le survivant. Épinglage/muet = OR (on ne perd pas
                //    un flag posé sur l'une des deux) ; nom/apparence/brouillon = 1ʳᵉ valeur non
                //    nulle ; aperçu + date pris sur la conversation la plus récente du groupe.
                val survivor = conversationDao.findById(plan.survivorId)
                if (survivor == null) {
                    // Groupes disjoints + reparent-avant-delete rendent ce cas non atteignable ;
                    // on loggue au cas où une régression future casserait cet invariant.
                    Timber.e("dedupe: survivant %d introuvable, groupe ignoré", plan.survivorId)
                    continue
                }
                val victims = plan.victimIds.mapNotNull { conversationDao.findById(it) }
                val newest = (victims + survivor).maxByOrNull { it.lastMessageAt } ?: survivor
                conversationDao.update(
                    survivor.copy(
                        lastMessageAt = newest.lastMessageAt,
                        lastMessagePreview = newest.lastMessagePreview,
                        pinned = survivor.pinned || victims.any { it.pinned },
                        muted = survivor.muted || victims.any { it.muted },
                        // D1 (audit) — ne reste archivée QUE si TOUTES l'étaient : sinon une conv
                        // active fusionnée pourrait disparaître de la liste principale selon quel
                        // doublon a la date la plus récente (survivant élu).
                        archived = survivor.archived && victims.all { it.archived },
                        displayName = survivor.displayName ?: victims.firstNotNullOfOrNull { it.displayName },
                        avatarUri = survivor.avatarUri ?: victims.firstNotNullOfOrNull { it.avatarUri },
                        bubbleColorArgb = survivor.bubbleColorArgb ?: victims.firstNotNullOfOrNull { it.bubbleColorArgb },
                        // D2 (audit) — concatène les brouillons non-vides (survivant + victimes) au
                        // lieu d'écraser : un texte non envoyé tapé dans un doublon ne doit pas être
                        // perdu silencieusement à la fusion.
                        draft = (listOfNotNull(survivor.draft?.takeIf { it.isNotBlank() }) +
                            victims.mapNotNull { v -> v.draft?.takeIf { it.isNotBlank() } })
                            .distinct()
                            .joinToString("\n\n")
                            .takeIf { it.isNotBlank() },
                    ),
                )
                // 3) suppression des conversations sources (désormais vidées de leurs messages).
                for (victimId in plan.victimIds) {
                    conversationDao.delete(victimId)
                }
                // 3bis) D3 (audit) — reprise du threadId système AOSP. Si le survivant n'en a pas
                //   (créé via ensureConversation → threadId=0) mais qu'une victime en portait un
                //   (créée via ensureConversationByThread à l'import système), on le récupère pour
                //   que markRead continue de propager READ=1 vers content://sms|mms. FAIT APRÈS le
                //   delete des victimes : l'index `conversations.thread_id` est UNIQUE, la victime
                //   détenait encore ce threadId jusqu'à sa suppression.
                if (survivor.threadId == 0L) {
                    val recovered = victims.firstNotNullOfOrNull { it.threadId.takeIf { t -> t > 0L } }
                    if (recovered != null) {
                        conversationDao.setThreadId(plan.survivorId, recovered)
                    }
                }
            }
            // 4) recalcule conversations.unread_count depuis les messages réellement non lus.
            conversationDao.recomputeAllUnreadCounts()
        }
        Timber.i("dedupeSameNumberConversations: %d groupe(s) de doublons fusionné(s)", plans.size)
        true
    }

    private suspend fun touchConversation(convId: Long, date: Long, preview: String, deltaUnread: Int) {
        val current = conversationDao.findById(convId) ?: return
        conversationDao.update(
            current.copy(
                lastMessageAt = maxOf(date, current.lastMessageAt),
                lastMessagePreview = preview.take(MAX_PREVIEW),
                unreadCount = (current.unreadCount + deltaUnread).coerceAtLeast(0),
            ),
        )
    }

    private companion object {
        const val MAX_PREVIEW = 240

        /**
         * v1.3.7 (F5 audit) — borne LRU du cache `displayNameCache`. 1000 entrées couvre largement
         * un compte normal (correspondants distincts + senders alphanumériques opérateurs/banques).
         * Au-delà, l'éviction LRU drop les expéditeurs anciens d'abord — les conversations actives
         * restent toujours résolues en cache chaud sans coût ContentProvider.
         */
        const val DISPLAY_NAME_CACHE_MAX = 1000

        /**
         * v1.6.2 (Tapback fold bugfix) — borne du fallback fuzzy de fold Tapback.
         * 50 messages OUTGOING couvrent largement le scénario réel (l'utilisateur
         * réagit dans la fenêtre courte qui suit un envoi, pas 3 semaines plus tard).
         * Coût mémoire négligeable (~50 × 200 B = 10 KB), une seule query Room
         * supplémentaire grâce au LIMIT côté DAO — déclenchée UNIQUEMENT quand la
         * LIKE rapide a échoué (cas body OUTGOING multi-ligne).
         */
        const val TAPBACK_FALLBACK_LOOKUP_LIMIT = 50
    }
}
