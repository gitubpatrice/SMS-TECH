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
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        scheduler.cancel(id)
        repo.delete(id)
        return Outcome.Success(Unit)
    }
}
