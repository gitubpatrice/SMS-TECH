package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A conversation aggregates messages exchanged with one or more addresses.
 *
 * Mirrors the thread_id concept of Android's Telephony provider so we can map back and forth.
 */
@Serializable
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["thread_id"], unique = true),
        Index(value = ["pinned", "last_message_at"]),
        Index(value = ["archived"]),
        Index(value = ["in_vault"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "addresses_csv") val addressesCsv: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
    @ColumnInfo(name = "muted") val muted: Boolean = false,
    @ColumnInfo(name = "in_vault") val inVault: Boolean = false,
    @ColumnInfo(name = "draft") val draft: String? = null,
    @ColumnInfo(name = "notification_channel_id") val notificationChannelId: String? = null,
)
