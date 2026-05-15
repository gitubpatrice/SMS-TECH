package com.filestech.sms.data.sms

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.filestech.sms.data.local.db.entity.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.MessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-side wrapper around `content://sms` and `content://mms-sms/conversations`.
 *
 * NOTE: this class deliberately exposes minimal projections — we mirror system SMS into our
 * own SQLCipher DB so most of the app reads from Room, not from the system provider.
 */
@Singleton
class TelephonyReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** All SMS rows (inbox + sent + drafts + outbox + failed). Use [readSmsBatched] for big imports. */
    fun readAllSms(): List<MessageEntity> {
        val out = ArrayList<MessageEntity>(256)
        resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val e = c.toSms() ?: continue
                out += e
            }
        }
        return out
    }

    /**
     * Streams SMS rows whose [Telephony.Sms._ID] is strictly greater than [sinceId]. Used by the
     * [com.filestech.sms.data.sync.TelephonySyncManager] for delta syncs: the cursor advances
     * monotonically so each call only sees rows the provider has accepted since the last sync.
     *
     * Rows are emitted in `_ID ASC` order in chunks of [pageSize] via the suspend [onPage]
     * lambda — important for the very first run (fresh install) where the user may have tens
     * of thousands of historical SMS: we never hold the full result set in RAM.
     *
     * Returns the highest `_ID` actually seen, which becomes the new cursor. When no rows match
     * the predicate (steady state), returns [sinceId] unchanged — the caller persists nothing.
     */
    suspend fun readSmsSince(
        sinceId: Long,
        pageSize: Int = 500,
        onPage: suspend (List<DeltaRow>) -> Unit,
    ): Long {
        var maxSeen = sinceId
        val chunk = ArrayList<DeltaRow>(pageSize)
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            SMS_PROJECTION,
            "${Telephony.Sms._ID} > ?",
            arrayOf(sinceId.toString()),
            "${Telephony.Sms._ID} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val entity = c.toSms() ?: continue
                if (id > maxSeen) maxSeen = id
                chunk += DeltaRow(id, entity)
                if (chunk.size >= pageSize) {
                    onPage(chunk.toList())
                    chunk.clear()
                }
            }
        }
        if (chunk.isNotEmpty()) onPage(chunk.toList())
        return maxSeen
    }

    /**
     * Cheap fingerprint of the SMS provider: total row count + highest `_ID`. Used by
     * [TelephonySyncManager] to **skip** the expensive [readAllSmsIds] full scan when nothing
     * has changed since the last reconciliation — the typical case on every observer burst.
     *
     * The aggregate query touches only the SQLite metadata; we do not pull any row body. On a
     * device with 50 000 messages the call is ~1 ms vs the 100-300 ms cost of materialising
     * the full id set.
     */
    fun snapshotSmsFingerprint(): SmsFingerprint {
        var count = 0
        var maxId = 0L
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("COUNT(${Telephony.Sms._ID}) AS c", "MAX(${Telephony.Sms._ID}) AS m"),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                count = c.getInt(0)
                maxId = if (c.isNull(1)) 0L else c.getLong(1)
            }
        }
        return SmsFingerprint(count = count, maxId = maxId)
    }

    /** Output of [snapshotSmsFingerprint]. `count == 0` means the provider is empty. */
    data class SmsFingerprint(val count: Int, val maxId: Long)

    /**
     * Returns every `_ID` currently in `content://sms`. Used by [TelephonySyncManager] for the
     * deletion-reconciliation pass: any [MessageEntity.telephonyUri] in our Room DB whose `_ID`
     * is missing from this set has been deleted from the system (by us, by another SMS app, or
     * via the OS "Erase data" path) and we drop the local row to converge.
     *
     * Uses a minimal projection (`_ID` only) so it stays cheap even on devices with 50 k messages.
     * Callers should still gate behind [snapshotSmsFingerprint] when possible — even a minimal
     * projection allocates 50 k `Long` boxes plus a `HashSet` of the same size.
     */
    fun readAllSmsIds(): LongArray {
        val ids = ArrayList<Long>(1024)
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            null,
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            while (c.moveToNext()) ids += c.getLong(idx)
        }
        return ids.toLongArray()
    }

    /** Pair of resolved system `_ID` and the mapped [MessageEntity]. */
    data class DeltaRow(val telephonyId: Long, val entity: MessageEntity)

    /**
     * Audit Q2: the [onPage] callback is `suspend` so the importer can call other suspending APIs
     * (like the Room `ConversationMirror`) without resorting to `runBlocking`. The receiver
     * function itself is `suspend` so callers chain naturally inside a coroutine.
     *
     * NOTE: the SMS ContentProvider **does not honour `LIMIT`/`OFFSET` in `sortOrder`** — the
     * legacy "ASC LIMIT N OFFSET M" trick worked on AOSP <11 but Samsung One UI / Pixel 12+
     * silently ignores it and returns the full result every iteration, causing an infinite
     * import loop. We now open the cursor once, iterate it, and flush chunks of [pageSize].
     */
    suspend fun readSmsBatched(pageSize: Int = 1000, onPage: suspend (List<MessageEntity>) -> Unit) {
        val chunk = ArrayList<MessageEntity>(pageSize)
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            SMS_PROJECTION,
            null,
            null,
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val row = c.toSms() ?: continue
                chunk += row
                if (chunk.size >= pageSize) {
                    onPage(chunk.toList())
                    chunk.clear()
                }
            }
        }
        if (chunk.isNotEmpty()) onPage(chunk.toList())
    }

    /** Insert a sent SMS into the system provider (mandatory for default SMS apps). */
    fun insertSentSms(
        address: String,
        body: String,
        date: Long,
        threadId: Long? = null,
        subId: Int? = null,
    ): Uri? {
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            threadId?.let { put(Telephony.Sms.THREAD_ID, it) }
            subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        return resolver.insert(Telephony.Sms.Sent.CONTENT_URI, cv)
    }

    /** Insert an incoming SMS into the system inbox. Required from SmsDeliverReceiver. */
    fun insertInboxSms(
        address: String,
        body: String,
        date: Long,
        subId: Int? = null,
    ): Uri? {
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        return resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)
    }

    fun markMessageRead(uri: Uri) {
        val cv = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        resolver.update(uri, cv, null, null)
    }

    private fun android.database.Cursor.toSms(): MessageEntity? {
        val id = getLong(getColumnIndexOrThrow(Telephony.Sms._ID))
        val addr = getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: return null
        val body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
        val date = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE))
        val dateSent = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE_SENT))
        val read = getInt(getColumnIndexOrThrow(Telephony.Sms.READ)) == 1
        val type = getInt(getColumnIndexOrThrow(Telephony.Sms.TYPE))
        val threadId = getLong(getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
        val subIdIndex = getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
        val subId = if (subIdIndex >= 0 && !isNull(subIdIndex)) getInt(subIdIndex) else null

        val direction = when (type) {
            Telephony.Sms.MESSAGE_TYPE_SENT,
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_QUEUED,
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageDirection.OUTGOING
            else -> MessageDirection.INCOMING
        }
        val status = when (type) {
            Telephony.Sms.MESSAGE_TYPE_SENT -> MessageStatus.SENT
            Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.FAILED
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> MessageStatus.PENDING
            else -> MessageStatus.RECEIVED
        }

        return MessageEntity(
            conversationId = threadId,
            telephonyUri = "content://sms/$id",
            address = addr,
            body = body,
            type = MessageType.SMS,
            direction = direction,
            date = if (date > 0) date else System.currentTimeMillis(),
            dateSent = if (dateSent > 0) dateSent else null,
            read = read,
            starred = false,
            status = status,
            errorCode = null,
            subId = subId,
            scheduledAt = null,
            attachmentsCount = 0,
        )
    }

    // ──────────────────── MMS import ────────────────────────────────────────────────────────
    //
    // Why we need this: Android stores **MMS** rows (voice notes, images, group messages…) under
    // `content://mms` — completely separate from `content://sms`. Re-installing the app wipes
    // our local SQLCipher mirror; without this method, every MMS the user ever sent/received
    // disappears from the UI after re-install (the system rows survive but we never read them).
    //
    // Date encoding caveat: `content://mms.date` is in **SECONDS** (not ms like SMS), per AOSP.
    // We multiply by 1000 for consistency with the rest of the pipeline.

    /** One MMS row, ready to be mirrored into Room. */
    data class MmsImportRow(
        val telephonyId: Long,
        val threadId: Long,
        val dateMs: Long,
        val read: Boolean,
        val direction: Int,
        val status: Int,
        val address: String,
        val subId: Int?,
        val textBody: String,
        val attachments: List<MmsPartImport>,
    )

    /** One attachment part of an MMS — referenced by its `content://mms/part/{id}` URI. */
    data class MmsPartImport(
        val partId: Long,
        val contentType: String,
        val filename: String?,
    )

    /**
     * Snapshots every MMS row in the system provider, resolved with addresses + parts. Used by
     * [com.filestech.sms.data.sync.TelephonySyncManager] at first boot (or after a re-install)
     * to rebuild the local mirror from `content://mms`.
     */
    fun readAllMms(): List<MmsImportRow> {
        val out = ArrayList<MmsImportRow>(128)
        resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "read", "msg_box", "sub_id"),
            null,
            null,
            "date DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val threadId = c.getLong(1)
                val dateSec = c.getLong(2)
                val read = c.getInt(3) == 1
                val msgBox = c.getInt(4)
                val subId = if (!c.isNull(5)) c.getInt(5) else null

                val direction = if (msgBox == MMS_MSG_BOX_INBOX) MessageDirection.INCOMING
                else MessageDirection.OUTGOING
                val status = when (msgBox) {
                    MMS_MSG_BOX_INBOX -> MessageStatus.RECEIVED
                    MMS_MSG_BOX_SENT -> MessageStatus.SENT
                    MMS_MSG_BOX_FAILED -> MessageStatus.FAILED
                    MMS_MSG_BOX_OUTBOX, MMS_MSG_BOX_DRAFT -> MessageStatus.PENDING
                    else -> MessageStatus.RECEIVED
                }

                val address = readMmsAddress(mmsId, direction).takeIf { it.isNotBlank() } ?: continue
                val (textBody, attachments) = readMmsParts(mmsId)
                // Drop pure-text rows that came in as MMS (carrier converted a long SMS) with no
                // body either — they're noise and would just clutter the UI.
                if (textBody.isBlank() && attachments.isEmpty()) continue

                out += MmsImportRow(
                    telephonyId = mmsId,
                    threadId = threadId,
                    dateMs = dateSec * 1000L,
                    read = read,
                    direction = direction,
                    status = status,
                    address = address,
                    subId = subId,
                    textBody = textBody,
                    attachments = attachments,
                )
            }
        }
        return out
    }

    /**
     * Picks the relevant address from `content://mms/{id}/addr`: FROM (type 137) for incoming,
     * the first TO (type 151) for outgoing. Skips the AOSP placeholder `insert-address-token`.
     */
    private fun readMmsAddress(mmsId: Long, direction: Int): String {
        var from = ""
        var firstTo = ""
        resolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                if (addr == "insert-address-token") continue
                val type = c.getInt(1)
                when (type) {
                    137 -> if (from.isBlank()) from = addr
                    151 -> if (firstTo.isBlank()) firstTo = addr
                }
            }
        }
        return if (direction == MessageDirection.INCOMING) from else firstTo.ifBlank { from }
    }

    /**
     * Reads every part of an MMS message and splits them into a plain-text body (joined text/
     * parts, SMIL filtered) and a list of binary attachments (audio / image / video / other).
     */
    private fun readMmsParts(mmsId: Long): Pair<String, List<MmsPartImport>> {
        val text = StringBuilder()
        val atts = ArrayList<MmsPartImport>(4)
        resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val ct = c.getString(1) ?: continue
                when {
                    ct.equals("application/smil", ignoreCase = true) -> Unit
                    ct.startsWith("text/", ignoreCase = true) -> {
                        val t = c.getString(3) ?: ""
                        if (t.isNotBlank()) {
                            if (text.isNotEmpty()) text.append('\n')
                            text.append(t)
                        }
                    }
                    else -> atts += MmsPartImport(
                        partId = partId,
                        contentType = ct,
                        filename = c.getString(2),
                    )
                }
            }
        }
        return text.toString() to atts
    }

    companion object {
        private val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        // Telephony.Mms.MESSAGE_BOX_* — kept as integers to avoid the @hide API constants.
        private const val MMS_MSG_BOX_INBOX = 1
        private const val MMS_MSG_BOX_SENT = 2
        private const val MMS_MSG_BOX_DRAFT = 3
        private const val MMS_MSG_BOX_OUTBOX = 4
        private const val MMS_MSG_BOX_FAILED = 5
    }
}
