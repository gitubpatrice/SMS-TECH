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
    /**
     * v1.28.3 (F20) — [INTERRUPTED] rejoint les quatre états d'origine.
     *
     * Le verrou technique `SENDING` reste, lui, invisible du domaine : il se projette sur
     * [PENDING], parce qu'un envoi revendiqué depuis dix secondes est encore, pour
     * l'utilisateur, un envoi en attente. [INTERRUPTED] est autre chose — un envoi dont
     * l'issue ne sera jamais connue — et l'écran doit pouvoir le dire.
     */
    enum class State { PENDING, SENT, FAILED, CANCELLED, INTERRUPTED }
}
