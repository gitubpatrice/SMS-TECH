package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.entity.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.MessageType

data class Message(
    val id: Long,
    val conversationId: Long,
    val address: String,
    val body: String,
    val type: Type,
    val direction: Direction,
    val date: Long,
    val dateSent: Long?,
    val read: Boolean,
    val starred: Boolean,
    val status: Status,
    val errorCode: Int?,
    val attachmentsCount: Int,
    val subId: Int?,
    val scheduledAt: Long?,
    val attachments: List<Attachment> = emptyList(),
    /**
     * Local id of the message this one is replying to (#8 contextual reply). NULL for regular
     * messages. The UI resolves the preview by looking up [id] in the same conversation's
     * loaded list — a dangling reference (quoted message deleted) renders a fallback placeholder.
     */
    val replyToMessageId: Long? = null,
    /**
     * v1.3.0 — réaction emoji locale posée par l'utilisateur. `null` = pas de réaction.
     * Une seule réaction par message (la mienne), pas standardisée en SMS donc pas envoyée
     * — purement local pour annoter son propre fil.
     */
    val reactionEmoji: String? = null,
) {
    enum class Type { SMS, MMS }
    enum class Direction { INCOMING, OUTGOING }
    enum class Status { PENDING, SENT, DELIVERED, FAILED, RECEIVED, SCHEDULED }

    val isIncoming: Boolean get() = direction == Direction.INCOMING
    val isOutgoing: Boolean get() = direction == Direction.OUTGOING

    /** First audio attachment, if the message carries one. Convenience for the UI dispatch. */
    val audioAttachment: Attachment? get() = attachments.firstOrNull { it.isAudio }
}

fun MessageEntity.toDomain(attachments: List<Attachment> = emptyList()): Message = Message(
    id = id,
    conversationId = conversationId,
    address = address,
    body = body,
    type = if (type == MessageType.MMS) Message.Type.MMS else Message.Type.SMS,
    direction = if (direction == MessageDirection.OUTGOING) Message.Direction.OUTGOING else Message.Direction.INCOMING,
    date = date,
    dateSent = dateSent,
    read = read,
    starred = starred,
    status = when (status) {
        MessageStatus.PENDING -> Message.Status.PENDING
        MessageStatus.SENT -> Message.Status.SENT
        MessageStatus.DELIVERED -> Message.Status.DELIVERED
        MessageStatus.FAILED -> Message.Status.FAILED
        MessageStatus.RECEIVED -> Message.Status.RECEIVED
        MessageStatus.SCHEDULED -> Message.Status.SCHEDULED
        else -> Message.Status.PENDING
    },
    errorCode = errorCode,
    attachmentsCount = attachmentsCount,
    subId = subId,
    scheduledAt = scheduledAt,
    attachments = attachments,
    replyToMessageId = replyToMessageId,
    reactionEmoji = reactionEmoji,
)
