package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.MessageType
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
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
    private val attachmentDao: AttachmentDao,
    private val contacts: ContactRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Process-wide cache of `address → displayName` lookups. Avoids re-querying the contacts
     * provider for the same phone number across multiple receive/send paths.
     *
     * **Thread-safety.** This `@Singleton` is invoked concurrently from broadcast-receiver
     * threads (`SmsDeliverReceiver`, `MmsDownloadedReceiver`), WorkManager workers
     * (`TelephonySyncWorker.runSync → bulkImportFromTelephony`), and Hilt-scoped coroutines
     * (`SendSmsUseCase → upsertOutgoingSms`). A plain `HashMap` would race during a fresh-install
     * import while a live SMS arrives — Android's `HashMap.put` is known to spin at 100 % CPU
     * forever when two threads rehash at the same time (sporadic ANR with no reproducer).
     *
     * `ConcurrentHashMap` rejects null values, so we encode "no name found" as the empty string
     * and translate back to `null` in [resolveDisplayName] — the negative cache is just as
     * valuable as the positive one (skips the contacts query a second time around).
     */
    private val displayNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun resolveDisplayName(rawAddress: String): String? {
        displayNameCache[rawAddress]?.let { return if (it.isEmpty()) null else it }
        val name = runCatching { contacts.lookupByPhone(rawAddress)?.displayName }.getOrNull()
        // Empty string is our sentinel for "looked up, no match" — keeps `ConcurrentHashMap`
        // happy (it rejects nulls) without losing the negative-cache behaviour.
        displayNameCache[rawAddress] = name.orEmpty()
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

    suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
        initialStatus: Int = MessageStatus.PENDING,
        replyToMessageId: Long? = null,
    ): Long = withContext(io) {
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = body,
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
            touchConversation(convId, date, body, deltaUnread = 0)
            msgId
        }
    }

    suspend fun updateOutgoingStatus(localId: Long, status: Int, errorCode: Int? = null) = withContext(io) {
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
        val preview = textBody.ifBlank { mediaPreviewLabel(attachments.first()) }
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
     * Mirrors an incoming MMS retrieved through [com.google.android.mms.pdu.PduParser]. The
     * caller is responsible for having already written the audio bytes to disk and passed the
     * absolute file path here.
     */
    suspend fun upsertIncomingMms(
        address: String,
        audioFile: File?,
        mimeType: String?,
        durationMs: Long?,
        body: String,
        date: Long,
        telephonyUri: String? = null,
        subId: Int? = null,
    ): Long = withContext(io) {
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = body,
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
                attachmentsCount = if (audioFile != null && mimeType != null) 1 else 0,
            )
            val msgId = messageDao.insert(msg)
            if (audioFile != null && mimeType != null) {
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
            }
            touchConversation(convId, date, body, deltaUnread = +1)
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
        displayNameCache.clear()
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
                messageDao.insertAll(withConv)
                val last = group.maxBy { it.date }
                val unreadDelta = group.count {
                    it.direction == MessageDirection.INCOMING && !it.read
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
        val existing = conversationDao.findByAddressesCsv(csv)
        val resolved = resolveDisplayName(addresses.first().raw)
        if (existing != null) {
            // Back-fill the contact name if it wasn't available when the row was first created
            // (e.g. READ_CONTACTS not yet granted at import time, or contact added afterwards).
            if (existing.displayName == null && resolved != null) {
                conversationDao.update(existing.copy(displayName = resolved))
            }
            return existing.id
        }
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

    private companion object { const val MAX_PREVIEW = 240 }
}
