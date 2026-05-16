package com.filestech.sms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.ConversationOverrideDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.ConversationOverrideEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageFts
import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity

@Database(
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageFts::class,
        AttachmentEntity::class,
        BlockedNumberEntity::class,
        ScheduledMessageEntity::class,
        QuickReplyEntity::class,
        ConversationOverrideEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun quickReplyDao(): QuickReplyDao
    abstract fun conversationOverrideDao(): ConversationOverrideDao

    companion object {
        const val DATABASE_NAME = "smstech.db"
        // v2 (2026-05-15): adds `messages.reply_to_message_id` + matching index for the
        //   contextual-reply feature (#8). Migration in `Migrations.kt`.
        // v3 (2026-05-16, v1.2.6 audit F2): adds `messages.mms_system_id` (nullable Long) +
        //   matching index. Lets the retry path delete the stale `content://mms` row from the
        //   previous attempt before inserting a fresh outbox row — guarantees one MMS = one
        //   system-provider row visible across all SMS apps, even during retry windows.
        const val SCHEMA_VERSION = 3
    }
}
