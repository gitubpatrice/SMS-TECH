package com.filestech.sms.domain.usecase

import com.filestech.sms.domain.repository.ConversationRepository
import javax.inject.Inject

class MarkConversationReadUseCase @Inject constructor(
    private val repo: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: Long) = repo.markRead(conversationId)
}
