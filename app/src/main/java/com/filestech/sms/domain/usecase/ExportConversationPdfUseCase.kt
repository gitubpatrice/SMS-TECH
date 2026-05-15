package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.pdf.ConversationPdfExporter
import com.filestech.sms.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ExportConversationPdfUseCase @Inject constructor(
    private val repo: ConversationRepository,
    private val exporter: ConversationPdfExporter,
) {
    suspend operator fun invoke(conversationId: Long): Outcome<ConversationPdfExporter.ExportResult> {
        val conv = repo.observeOne(conversationId).first() ?: return Outcome.Failure(AppError.NotFound("conversation"))
        val messages = repo.observeMessages(conversationId).first()
        if (messages.isEmpty()) return Outcome.Failure(AppError.Validation("no messages to export"))
        return exporter.export(conv, messages)
    }
}
