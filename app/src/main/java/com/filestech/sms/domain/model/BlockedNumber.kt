package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.entity.BlockedNumberEntity

data class BlockedNumber(
    val id: Long,
    val rawNumber: String,
    val normalizedNumber: String,
    val label: String?,
    val createdAt: Long,
)

fun BlockedNumberEntity.toDomain(): BlockedNumber = BlockedNumber(
    id = id,
    rawNumber = rawNumber,
    normalizedNumber = normalizedNumber,
    label = label,
    createdAt = createdAt,
)
