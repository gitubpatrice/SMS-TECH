package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

/**
 * v1.25.3 (audit H6) — relance un envoi programmé abandonné, à la demande explicite de
 * l'utilisateur depuis la section « Échoués ».
 *
 * L'ordre compte, et il est l'inverse de [CancelScheduledMessageUseCase] : on remet d'abord la
 * ligne en `PENDING`, **ensuite** on planifie. Le worker refuse tout ce qui n'est pas `PENDING`,
 * donc planifier en premier ouvrirait une fenêtre où il se réveille, voit encore `FAILED` et
 * repart sans rien envoyer.
 *
 * Le replay repart avec un compteur de tentatives neuf (nouveau `WorkRequest`), donc de nouveau
 * [com.filestech.sms.system.scheduler.ScheduledSendAttempt.MAX_ATTEMPTS] essais.
 *
 * À noter : chaque relance qui aboutit crée une nouvelle ligne d'envoi dans le fil, à côté de la
 * ligne en échec de la tentative précédente. C'est assumé — la relance est un geste utilisateur
 * délibéré, et masquer la trace de l'échec serait pire.
 */
class RetryScheduledMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
) {
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        val now = System.currentTimeMillis()
        repo.rearmPending(id, now)
        scheduler.scheduleAt(id, now)
        return Outcome.Success(Unit)
    }
}
