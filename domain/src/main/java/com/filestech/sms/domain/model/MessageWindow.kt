package com.filestech.sms.domain.model

/**
 * A bounded slice of a conversation: the [messages] currently loaded, ordered oldest-first, plus
 * enough context for the UI to describe what it is *not* showing.
 *
 * [totalCount], [firstMessageAt] and [lastMessageAt] describe the **whole** thread, not the loaded
 * window — the conversation info panel would otherwise report the window's own bounds as the
 * conversation's.
 *
 * @property messages the loaded window, oldest first — the order the thread renders in.
 * @property totalCount every displayable message in the thread, window included.
 * @property hasMore whether older messages exist beyond the window.
 * @property firstMessageAt timestamp of the oldest message in the thread, null when empty.
 * @property lastMessageAt timestamp of the newest message in the thread, null when empty.
 */
data class MessageWindow(
    val messages: List<Message> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
)
