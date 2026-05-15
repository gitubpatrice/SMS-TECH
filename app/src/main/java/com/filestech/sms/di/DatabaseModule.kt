package com.filestech.sms.di

import android.content.Context
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseFactory
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.ConversationOverrideDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context, factory: DatabaseFactory): AppDatabase =
        factory.build(context)

    @Provides fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()
    @Provides fun messageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun attachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun blockedNumberDao(db: AppDatabase): BlockedNumberDao = db.blockedNumberDao()
    @Provides fun scheduledMessageDao(db: AppDatabase): ScheduledMessageDao = db.scheduledMessageDao()
    @Provides fun quickReplyDao(db: AppDatabase): QuickReplyDao = db.quickReplyDao()
    @Provides fun conversationOverrideDao(db: AppDatabase): ConversationOverrideDao = db.conversationOverrideDao()
}
