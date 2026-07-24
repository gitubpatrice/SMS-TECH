package com.filestech.sms.domain.model

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
