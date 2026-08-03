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
     * ⚠️ v1.26.1 (audit B2) — CE PARAGRAPHE ETAIT FAUX et contredisait
     * [CancelScheduledMessageUseCase], qui supprime bel et bien les fichiers a l'annulation tout
     * en conservant la ligne `CANCELLED`. Il affirmait « la suppression n'a lieu QU'ICI » et
     * « les fichiers vivent tant que la ligne existe » : ni l'un ni l'autre n'etait vrai.
     *
     * La regle REELLE, ecrite des deux cotes :
     * - **annulation** → les fichiers partent tout de suite. Aucune liste n'affiche l'etat
     *   `CANCELLED` (`observePending` = PENDING/SENDING, `observeFailed` = FAILED), donc la ligne
     *   est inatteignable : ses fichiers resteraient sur le telephone pour toujours, invisibles
     *   et indelogeables. Sauf si le worker a deja revendique l'envoi — dans ce cas on n'y touche
     *   pas, il est en train de les lire.
     * - **apres un envoi reussi** → on ne touche a rien : ce sont les `AttachmentEntity` du
     *   message envoye qui pointent vers ces memes fichiers, les effacer viderait les bulles.
     * - **suppression explicite** (ici) → la ligne ET ses fichiers partent ensemble.
     */
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        scheduler.cancel(id)
        repo.deleteWithAttachments(id)
        return Outcome.Success(Unit)
    }
}
