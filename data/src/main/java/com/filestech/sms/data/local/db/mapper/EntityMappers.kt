package com.filestech.sms.data.local.db.mapper

import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.QuickReply
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.model.ScheduledState

/**
 * Room entity → domain model mappers (v1.25.0, Étage 2.1).
 *
 * These live in the **data** layer, not in `domain/model`: it is the data layer that knows how to
 * turn its own Room rows into domain objects, never the reverse. Moving them here removes the last
 * `domain → data` imports, so `domain/model` no longer depends on Room entities.
 */

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    messageId = messageId,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    localUri = localUri,
    width = width,
    height = height,
    durationMs = durationMs,
)

fun BlockedNumberEntity.toDomain(): BlockedNumber = BlockedNumber(
    id = id,
    rawNumber = rawNumber,
    normalizedNumber = normalizedNumber,
    label = label,
    createdAt = createdAt,
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    threadId = threadId,
    addresses = PhoneAddress.list(addressesCsv),
    displayName = displayName,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unreadCount = unreadCount,
    pinned = pinned,
    archived = archived,
    muted = muted,
    inVault = inVault,
    draft = draft,
    bubbleColorArgb = bubbleColorArgb,
    avatarUri = avatarUri,
)

fun MessageEntity.toDomain(attachments: List<Attachment> = emptyList()): Message = Message(
    id = id,
    conversationId = conversationId,
    address = address,
    body = body,
    type = mapType(type),
    direction = mapDirection(direction),
    date = date,
    dateSent = dateSent,
    read = read,
    starred = starred,
    status = mapStatus(status),
    errorCode = errorCode,
    attachmentsCount = attachmentsCount,
    subId = subId,
    scheduledAt = scheduledAt,
    attachments = attachments,
    replyToMessageId = replyToMessageId,
    reactionEmoji = reactionEmoji,
)

/**
 * v1.16.0 — Mapping enum DB → enum domain. La conversion `object Int` → `enum class` permet
 * désormais un `when` EXHAUSTIVE compile-time (pas de `else`) — le compilateur signale tout
 * cas manquant si on ajoute un statut dans [MessageStatus]. Filet de sécurité K-8 LIGHT
 * v1.15.0 (Timber + test garde-fou) reste pertinent mais devient redondant en pratique :
 * le compilateur fait le travail.
 */
fun mapStatus(status: MessageStatus): Message.Status = when (status) {
    MessageStatus.PENDING -> Message.Status.PENDING
    MessageStatus.SENT -> Message.Status.SENT
    MessageStatus.DELIVERED -> Message.Status.DELIVERED
    MessageStatus.FAILED -> Message.Status.FAILED
    MessageStatus.RECEIVED -> Message.Status.RECEIVED
    MessageStatus.SCHEDULED -> Message.Status.SCHEDULED
}

fun mapType(type: MessageType): Message.Type = when (type) {
    MessageType.SMS -> Message.Type.SMS
    MessageType.MMS -> Message.Type.MMS
}

fun mapDirection(direction: MessageDirection): Message.Direction = when (direction) {
    MessageDirection.INCOMING -> Message.Direction.INCOMING
    MessageDirection.OUTGOING -> Message.Direction.OUTGOING
}

fun QuickReplyEntity.toDomain(): QuickReply = QuickReply(
    id = id,
    text = text,
    position = position,
)

fun QuickReply.toEntity(): QuickReplyEntity = QuickReplyEntity(
    id = id,
    text = text,
    position = position,
)

fun ScheduledMessageEntity.toDomain(): ScheduledMessage = ScheduledMessage(
    id = id,
    conversationId = conversationId,
    addresses = PhoneAddress.list(addressesCsv),
    body = body,
    scheduledAt = scheduledAt,
    subId = subId,
    // v1.17.0 — `when` exhaustif compile-time (enum class) — `else` retiré ; le compilateur
    // signale toute valeur manquante si on ajoute un état dans [ScheduledState].
    state = when (state) {
        ScheduledState.PENDING -> ScheduledMessage.State.PENDING
        ScheduledState.SENT -> ScheduledMessage.State.SENT
        ScheduledState.FAILED -> ScheduledMessage.State.FAILED
        ScheduledState.CANCELLED -> ScheduledMessage.State.CANCELLED
    },
    createdAt = createdAt,
)
