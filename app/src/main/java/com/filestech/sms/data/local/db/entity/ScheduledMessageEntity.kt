package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["scheduled_at"])],
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long?,
    @ColumnInfo(name = "addresses_csv") val addressesCsv: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String? = null,
    /** 0 pending, 1 sent, 2 failed, 3 cancelled */
    @ColumnInfo(name = "state") val state: Int = 0,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

object ScheduledState { const val PENDING = 0; const val SENT = 1; const val FAILED = 2; const val CANCELLED = 3 }
