package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.system.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

/**
 * v1.15.1 — Annule un message programmé : enlève le job WorkManager + marque la row Room en
 * [com.filestech.sms.domain.model.ScheduledState.CANCELLED]. Idempotent : un appel sur
 * un id déjà annulé est un no-op (le worker filtre déjà sur state==PENDING). Symétrique avec
 * [ScheduleMessageUseCase] qui orchestre repo+scheduler côté création.
 */
class CancelScheduledMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
) {
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        // Cancel WorkManager first — si le worker s'exécute pile entre notre call et le DB update,
        // il verra état == PENDING et tentera d'envoyer. L'ordre WorkManager → DB minimise cette
        // fenêtre (mais ne l'élimine pas totalement — c'est OK, le worker re-check toujours l'état
        // post-load via repo.observePending).
        scheduler.cancel(id)
        return repo.cancel(id)
    }
}
