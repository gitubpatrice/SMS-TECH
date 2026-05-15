package com.filestech.sms.di

import com.filestech.sms.data.repository.BlockedNumberRepositoryImpl
import com.filestech.sms.data.repository.ContactRepositoryImpl
import com.filestech.sms.data.repository.ConversationRepositoryImpl
import com.filestech.sms.data.repository.QuickReplyRepositoryImpl
import com.filestech.sms.data.repository.ScheduledMessageRepositoryImpl
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.QuickReplyRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindBlockedNumberRepository(impl: BlockedNumberRepositoryImpl): BlockedNumberRepository

    @Binds @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds @Singleton
    abstract fun bindQuickReplyRepository(impl: QuickReplyRepositoryImpl): QuickReplyRepository

    @Binds @Singleton
    abstract fun bindScheduledMessageRepository(impl: ScheduledMessageRepositoryImpl): ScheduledMessageRepository
}
