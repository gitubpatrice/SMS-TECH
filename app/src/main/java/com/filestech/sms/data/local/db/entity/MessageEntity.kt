package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "date"]),
        Index(value = ["telephony_uri"], unique = true),
        Index(value = ["status"]),
        Index(value = ["read"]),
        Index(value = ["starred"]),
        // Schema v2 (#8 contextual reply). Index the FK so loading a thread and resolving
        // "this message replied to which one?" stays an O(log n) lookup even on long threads.
        Index(value = ["reply_to_message_id"]),
        // Schema v3 (v1.2.6 audit F2 idempotence retry). Indexed because the retry path looks
        // up the previous system-provider row id by Room message id, and (future) the watchdog
        // may reconcile Room ↔ content://mms via mmsSystemId.
        Index(value = ["mms_system_id"]),
        // Schema v4 (v1.3.0 audit P1). Auto-purge filtre par `date < cutoff` sur l'ensemble
        // de la table ; sans index dédié, SQLCipher fait un full scan + déchiffrement page par
        // page (~1 s pour 50 k rows). L'index composite (conversation_id, date) est inopérant
        // ici puisque la purge est inter-conversations. Coût ~8 B / row, négligeable.
        Index(value = ["date"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "telephony_uri") val telephonyUri: String?,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "body") val body: String,
    /** 0 = SMS, 1 = MMS */
    @ColumnInfo(name = "type") val type: Int,
    /** 0 = incoming, 1 = outgoing */
    @ColumnInfo(name = "direction") val direction: Int,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "date_sent") val dateSent: Long?,
    @ColumnInfo(name = "read") val read: Boolean = false,
    @ColumnInfo(name = "starred") val starred: Boolean = false,
    /** 0 pending, 1 sent, 2 delivered, 3 failed, 4 received */
    @ColumnInfo(name = "status") val status: Int = 0,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long? = null,
    @ColumnInfo(name = "attachments_count") val attachmentsCount: Int = 0,
    /**
     * Schema v2 — contextual reply target (#8). Stores the local Room id of the message the
     * sender chose to quote when composing. NULL for regular messages and for legacy rows
     * imported before v1.2.
     *
     * Deliberately **not** a `ForeignKey`: a quoted message can be deleted (locally or via the
     * system provider) while the reply lives on; we tolerate the dangling reference and the UI
     * falls back to a "Message supprimé" placeholder rather than cascading the delete.
     */
    @ColumnInfo(name = "reply_to_message_id") val replyToMessageId: Long? = null,
    /**
     * Schema v3 — `_id` of the row we inserted into `content://mms` for this outgoing MMS
     * (v1.2.6 audit F2 idempotence retry). NULL for SMS rows, for incoming MMS, and for
     * outgoing MMS where the system writeback never succeeded (e.g. we were not default SMS
     * app at the time of dispatch).
     *
     * Used by [com.filestech.sms.data.mms.MmsSender] to delete the previous OUTBOX/FAILED
     * row before re-inserting a fresh one on retry, so we never leave two system-provider
     * rows for the same Room message even briefly.
     */
    @ColumnInfo(name = "mms_system_id") val mmsSystemId: Long? = null,
    /**
     * Schema v4 — réaction emoji locale posée par l'utilisateur sur ce message (v1.3.0).
     * `null` = pas de réaction. Une seule réaction par message (la mienne), pas envoyée
     * par SMS — c'est purement local côté Room, pas standardisé en SMS/MMS.
     */
    @ColumnInfo(name = "reaction_emoji") val reactionEmoji: String? = null,
)

/** Companion-style constants kept on file scope to avoid object boxing in Room. */
object MessageType { const val SMS = 0; const val MMS = 1 }
object MessageDirection { const val INCOMING = 0; const val OUTGOING = 1 }
object MessageStatus {
    const val PENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val FAILED = 3
    const val RECEIVED = 4
    const val SCHEDULED = 5
}

/**
 * Sentinel values for [MessageEntity.errorCode] when [MessageEntity.status] is [MessageStatus.FAILED].
 *
 * The distinction matters for retry idempotence (audit M-1): a [WATCHDOG_TIMEOUT] row may
 * actually have been accepted by the radio (we just never received the sent-broadcast), so
 * retrying it can produce a duplicate at the recipient. A [SYNCHRONOUS] row was rejected by
 * `SmsManager` before dispatch and is always safe to re-send.
 *
 * Positive integer values are reserved for OS-provided error codes
 * (`SmsManager.RESULT_ERROR_*`) propagated through `SmsSentReceiver`.
 */
object SendErrorCode {
    /** Synchronous failure: `SmsManager.sendMultipartTextMessage` threw. Safe to retry. */
    const val SYNCHRONOUS: Int = -1

    /** [com.filestech.sms.system.scheduler.TelephonySyncWorker] timed out a stale PENDING row.
     *  The message *may* have reached the recipient — UI should warn on retry. */
    const val WATCHDOG_TIMEOUT: Int = -2
}
