package com.filestech.sms.ui.screens.thread

import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageWindow
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.SendingSettings
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.security.AppLockManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 2026-11-14 — un [ThreadViewModel] de test, monté une fois pour les tests du fil (sorti de `ThreadSimEnvoiTest`
 * quand un second test en a eu besoin). Sans confirmation d'envoi : `send()` envoie tout de suite.
 *
 * @param repo les comportements propres au test s'ajoutent sur le dépôt déjà câblé (conversation, fenêtre).
 */
@Suppress("LongParameterList")
internal fun threadViewModelDeTest(
    dispatcher: CoroutineDispatcher,
    envoyer: EnvoyerMessageUseCase,
    adresses: List<String> = listOf("+33612345678"),
    historique: List<Message> = emptyList(),
    sims: List<SimActive> = emptyList(),
    etat: SavedStateHandle = SavedStateHandle(mapOf("conversationId" to 7L)),
    scheduleMessage: ScheduleMessageUseCase = mockk(relaxed = true),
    repo: ConversationRepository.() -> Unit = {},
): ThreadViewModel {
    val conversation = Conversation(
        id = 7L,
        threadId = null,
        addresses = adresses.map { PhoneAddress.of(it) },
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )
    val depot: ConversationRepository = mockk(relaxed = true) {
        every { observeOne(any()) } returns flowOf(conversation)
        every { observeVaultHidden(any()) } returns flowOf(false)
        every { observeMessagesWindow(any(), any()) } returns flowOf(MessageWindow(messages = historique))
        repo()
    }
    val settings: SettingsRepository = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(AppSettings(sending = SendingSettings(confirmBeforeBroadcast = false)))
    }
    val appLock: AppLockManager = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
    }
    val simsActives: SimsActives = mockk {
        every { lister() } returns sims
        // Android enverrait par la SIM 1 (subId 11), cf. `ThreadSimEnvoiTest`.
        every { simSmsDAndroid() } returns 11
    }
    return ThreadViewModel(
        savedStateHandle = etat,
        repo = depot,
        envoyer = envoyer,
        sendVoiceMms = mockk(relaxed = true),
        retrySend = mockk(relaxed = true),
        markRead = mockk(relaxed = true),
        segCounter = mockk(relaxed = true),
        exportPdf = mockk(relaxed = true),
        voiceRecorder = mockk(relaxed = true),
        playbackController = mockk(relaxed = true),
        settings = settings,
        blockNumber = mockk(relaxed = true),
        toggleConvState = mockk(relaxed = true),
        contactRepo = mockk(relaxed = true),
        sendReaction = mockk(relaxed = true),
        scheduleMessage = scheduleMessage,
        incomingShare = mockk(relaxed = true),
        activeConversationTracker = mockk(relaxed = true),
        context = mockk(relaxed = true),
        io = dispatcher,
        appLock = appLock,
        phoneIdentity = mockk(relaxed = true),
        simsActives = simsActives,
    )
}
