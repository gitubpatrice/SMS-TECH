package com.filestech.sms.data.local.db.dao

/**
 * Whole-thread aggregates projected straight out of SQL by
 * [MessageDao.observeStatsForConversation].
 *
 * Deliberately independent of the loaded window: the conversation info panel must describe the
 * conversation, not the slice currently in memory.
 *
 * [firstAt] and [lastAt] are null when the thread holds no displayable message — `MIN`/`MAX` over
 * an empty set.
 */
data class ThreadStats(
    val total: Int,
    val firstAt: Long?,
    val lastAt: Long?,
)
