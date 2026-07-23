package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity

data class ScheduledMessage(
    val id: Long,
    val conversationId: Long?,
    val addresses: List<PhoneAddress>,
    val body: String,
    val scheduledAt: Long,
    val subId: Int?,
    val state: State,
    val createdAt: Long,
) {
    enum class State { PENDING, SENT, FAILED, CANCELLED }
}

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
