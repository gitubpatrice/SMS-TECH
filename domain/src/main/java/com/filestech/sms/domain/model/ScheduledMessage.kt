package com.filestech.sms.domain.model

data class ScheduledMessage(
    val id: Long,
    val conversationId: Long?,
    val addresses: List<PhoneAddress>,
    val body: String,
    val scheduledAt: Long,
    val subId: Int?,
    val state: State,
    val createdAt: Long,
    /**
     * v1.26.0 — pieces jointes de l'envoi. Vide = SMS texte, non vide = MMS.
     *
     * Les fichiers pointes sont **durables** (`filesDir/mms_attachments/`), promus des la
     * programmation : un envoi differe peut attendre des heures, alors que les URI du selecteur
     * et le cache de staging ne survivent ni au verrouillage ni au menage systeme.
     */
    val attachments: List<com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload> =
        emptyList(),
) {
    enum class State { PENDING, SENT, FAILED, CANCELLED }
}
