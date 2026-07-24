package com.filestech.sms.domain.pdf

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message

/**
 * Port domaine : rend une conversation en PDF partageable.
 *
 * [com.filestech.sms.domain.usecase.ExportConversationPdfUseCase] en dépend ; l'implémentation
 * [com.filestech.sms.data.pdf.ConversationPdfExporter] utilise le framework `PdfDocument` +
 * `FileProvider` (Android) et projette le résultat en [PdfExportResult] — `domain/` reste sans
 * import Android.
 */
interface PdfExporter {

    /** Rend [conversation] + [messages] en PDF, renvoie l'URI de partage + le nombre de pages. */
    suspend fun export(conversation: Conversation, messages: List<Message>): Outcome<PdfExportResult>
}
