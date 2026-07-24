package com.filestech.sms.di

import com.filestech.sms.data.backup.BackupService
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.location.LocationResolver
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.mms.OutgoingAttachmentStoreImpl
import com.filestech.sms.data.repository.BlockedNumberRepositoryImpl
import com.filestech.sms.data.repository.ContactRepositoryImpl
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.ConversationRepositoryImpl
import com.filestech.sms.data.repository.QuickReplyRepositoryImpl
import com.filestech.sms.data.repository.ScheduledMessageRepositoryImpl
import com.filestech.sms.data.sender.SenderNameProviderImpl
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.data.sms.SmsSenderImpl
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.backup.BackupRestorer
import com.filestech.sms.domain.emergency.IAmOkMessageProvider
import com.filestech.sms.domain.location.LocationProvider
import com.filestech.sms.domain.mms.MmsDispatcher
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.pdf.PdfExporter
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.repository.QuickReplyRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SenderNameProvider
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.vault.VaultMover
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultManager
import com.filestech.sms.system.emergency.IAmOkMessageProviderImpl
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.pdf.ConversationPdfExporter
import com.filestech.sms.system.scheduler.ScheduledMessageSchedulerImpl
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

    @Binds @Singleton
    abstract fun bindSmsSender(impl: SmsSenderImpl): SmsSender

    @Binds @Singleton
    abstract fun bindDefaultSmsAppChecker(impl: DefaultSmsAppManager): DefaultSmsAppChecker

    @Binds @Singleton
    abstract fun bindOutgoingAttachmentStore(impl: OutgoingAttachmentStoreImpl): OutgoingAttachmentStore

    @Binds @Singleton
    abstract fun bindScheduledMessageScheduler(impl: ScheduledMessageSchedulerImpl): ScheduledMessageScheduler

    @Binds @Singleton
    abstract fun bindVaultMover(impl: VaultManager): VaultMover

    @Binds @Singleton
    abstract fun bindPanicStateProvider(impl: AppLockManager): PanicStateProvider

    @Binds @Singleton
    abstract fun bindLocationProvider(impl: LocationResolver): LocationProvider

    @Binds @Singleton
    abstract fun bindSenderNameProvider(impl: SenderNameProviderImpl): SenderNameProvider

    @Binds @Singleton
    abstract fun bindPdfExporter(impl: ConversationPdfExporter): PdfExporter

    @Binds @Singleton
    abstract fun bindBackupRestorer(impl: BackupService): BackupRestorer

    @Binds @Singleton
    abstract fun bindIAmOkMessageProvider(impl: IAmOkMessageProviderImpl): IAmOkMessageProvider

    @Binds @Singleton
    abstract fun bindSentSmsRecorder(impl: TelephonyReader): SentSmsRecorder

    @Binds @Singleton
    abstract fun bindMmsDispatcher(impl: MmsSender): MmsDispatcher

    @Binds @Singleton
    abstract fun bindOutgoingMessageMirror(impl: ConversationMirror): OutgoingMessageMirror

    @Binds @Singleton
    abstract fun bindAppSettingsSource(impl: SettingsRepository): AppSettingsSource

    @Binds @Singleton
    abstract fun bindConversationNotificationCanceller(
        impl: IncomingMessageNotifier,
    ): ConversationNotificationCanceller
}
