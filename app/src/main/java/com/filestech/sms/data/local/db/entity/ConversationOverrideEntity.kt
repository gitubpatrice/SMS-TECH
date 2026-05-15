package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_overrides",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "notification_sound_uri") val notificationSoundUri: String? = null,
    @ColumnInfo(name = "vibrate_pattern") val vibratePattern: String? = null,
    @ColumnInfo(name = "led_color") val ledColor: Int? = null,
    @ColumnInfo(name = "muted") val muted: Boolean = false,
)
