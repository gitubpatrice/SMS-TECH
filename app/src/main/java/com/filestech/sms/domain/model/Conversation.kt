package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.entity.ConversationEntity

data class Conversation(
    val id: Long,
    val threadId: Long,
    val addresses: List<PhoneAddress>,
    val displayName: String?,
    val lastMessageAt: Long,
    val lastMessagePreview: String?,
    val unreadCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val muted: Boolean,
    val inVault: Boolean,
    val draft: String?,
) {
    val isGroup: Boolean get() = addresses.size > 1
    val firstAddress: PhoneAddress? get() = addresses.firstOrNull()
}

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
)
