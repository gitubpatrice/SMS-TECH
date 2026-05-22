package com.filestech.sms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
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
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun quickReplyDao(): QuickReplyDao

    companion object {
        const val DATABASE_NAME = "smstech.db"
        // v2 (2026-05-15): adds `messages.reply_to_message_id` + matching index for the
        //   contextual-reply feature (#8). Migration in `Migrations.kt`.
        // v3 (2026-05-16, v1.2.6 audit F2): adds `messages.mms_system_id` (nullable Long) +
        //   matching index. Lets the retry path delete the stale `content://mms` row from the
        //   previous attempt before inserting a fresh outbox row — guarantees one MMS = one
        //   system-provider row visible across all SMS apps, even during retry windows.
        // v4 (2026-05-16, v1.3.0): adds `messages.reaction_emoji` (nullable TEXT) for the
        //   per-message local emoji reaction feature. Pas d'index (jamais filtré dessus).
        // v5 (2026-05-16, v1.3.0 audit P1): adds `index_messages_date` so the auto-purge
        //   `WHERE date < cutoff` (TelephonySyncWorker tick) ne fait plus de full scan
        //   SQLCipher. Bump séparé de v4 pour absorber proprement les users qui ont
        //   reçu un build v1.3.0 intermédiaire avec migration v3→v4 sans index.
        // v6 (2026-05-17, v1.3.7 G4 audit): DROP TABLE conversation_overrides — table
        //   morte (entity + DAO existaient mais aucun consommateur métier). Confirmé
        //   via grep transversal : seulement référencé par AppDatabase + DatabaseModule.
        //   Migration v5→v6 `DROP TABLE IF EXISTS conversation_overrides` (idempotente).
        // v7 (2026-05-22, v1.11.0): adds `conversations.bubble_color_argb INTEGER` +
        //   `conversations.avatar_uri TEXT` (both nullable) for the per-contact appearance
        //   feature. Strictly additive — legacy rows project NULL = default bubble color
        //   + default contact avatar. Migration v6→v7 in `Migrations.kt`.
        const val SCHEMA_VERSION = 7
    }
}
