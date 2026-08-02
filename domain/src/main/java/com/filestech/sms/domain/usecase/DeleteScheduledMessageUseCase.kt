package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

/**
 * v1.25.3 (audit H6) — retire définitivement un envoi programmé de la liste.
 *
 * Le `cancel` du scheduler est fait même sur une ligne déjà en échec : il est idempotent, et il
 * garantit qu'aucun `WorkRequest` résiduel ne se réveillera sur un id qui n'existe plus (le
 * worker rendrait alors `UNKNOWN_ID`, sans dégât mais pour rien). Complémentaire de
 * [CancelScheduledMessageUseCase], qui garde la ligne en `CANCELLED` comme trace.
 */
class DeleteScheduledMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
) {
    /**
     * v1.26.0 — supprime aussi les pieces jointes durables de l'envoi.
     *
     * Ces fichiers ont ete promus dans `filesDir/mms_attachments/` a la programmation, hors
     * d'atteinte des purges de cache : sans ce menage, une programmation avec photo annulee
     * laisserait son image sur le telephone pour toujours.
     *
     * **La suppression n'a lieu QU'ICI**, pas a l'annulation ni apres envoi, et c'est
     * deliberement asymetrique :
     * - une annulation garde la ligne en `CANCELLED` comme trace, donc ses fichiers doivent
     *   survivre avec elle ;
     * - apres un envoi reussi, ce sont les `AttachmentEntity` du message envoye qui pointent vers
     *   ces memes fichiers — les effacer viderait les bulles du fil.
     *
     * La regle tient en une phrase : **les fichiers vivent tant que la ligne existe.**
     */
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        scheduler.cancel(id)
        repo.deleteWithAttachments(id)
        return Outcome.Success(Unit)
    }
}
