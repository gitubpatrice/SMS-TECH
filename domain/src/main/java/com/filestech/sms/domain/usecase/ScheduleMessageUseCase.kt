package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

class ScheduleMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
    private val attachmentStore: OutgoingAttachmentStore,
) {
    /**
     * v1.26.0 — [attachments] non vide programme un **MMS** au lieu d'un SMS.
     *
     * Jusqu'ici la programmation ne transportait que du texte : on pouvait joindre une photo,
     * programmer l'envoi, et seul le texte partait à l'heure dite. La colonne `attachments_json`
     * existait en base sans jamais être écrite ni lue.
     *
     * ## Les fichiers sont rendus durables ICI, pas au moment de l'envoi
     *
     * C'est tout l'enjeu du différé. Le fichier que l'interface prépare vit dans le cache de
     * staging, que [com.filestech.sms.security.AutoLockObserver] vide **à chaque verrouillage** et
     * que le système peut purger quand il veut. Un envoi peut attendre des heures : à l'échéance,
     * le fichier aurait disparu et le MMS serait parti amputé — ou pas du tout.
     *
     * On les promeut donc vers `filesDir/mms_attachments/` dès la programmation, exactement comme
     * [SendMediaMmsUseCase] le fait juste avant un envoi immédiat. La promotion est idempotente :
     * un fichier déjà durable est renvoyé tel quel, et la rejouer à l'échéance est sans effet.
     *
     * ## Le plafond de taille est vérifié maintenant, pas à l'échéance
     *
     * [SendMediaMmsUseCase] refuse au-delà de son plafond. Le laisser découvrir ça des heures plus
     * tard transformerait une erreur de saisie en échec constaté trop tard, quand il n'est plus
     * temps de réagir. On applique donc la même limite ici, pendant que l'utilisateur est devant
     * son écran et peut retirer une image.
     */
    suspend operator fun invoke(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        whenEpochMillis: Long,
        subId: Int?,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload> = emptyList(),
    ): Outcome<Long> {
        for (a in attachments) {
            if (!a.file.exists() || a.file.length() == 0L) {
                return Outcome.Failure(AppError.Validation("attachment file missing or empty"))
            }
        }
        val totalBytes = attachments.sumOf { it.file.length() }
        if (totalBytes > SendMediaMmsUseCase.MAX_PAYLOAD_BYTES) {
            return Outcome.Failure(AppError.Validation("payload exceeds cap ($totalBytes B)"))
        }

        val durable = attachments.map { a -> a.copy(file = attachmentStore.promoteToDurable(a.file)) }

        val scheduled = repo.schedule(conversationId, addresses, body, whenEpochMillis, subId, durable)
        return when (scheduled) {
            is Outcome.Success -> {
                scheduler.scheduleAt(scheduled.value, whenEpochMillis)
                scheduled
            }
            is Outcome.Failure -> scheduled
        }
    }
}
