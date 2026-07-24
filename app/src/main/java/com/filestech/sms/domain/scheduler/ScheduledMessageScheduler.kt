package com.filestech.sms.domain.scheduler

/**
 * Port domaine : planifie / annule l'envoi différé d'un message programmé.
 *
 * Les use-cases [com.filestech.sms.domain.usecase.ScheduleMessageUseCase] et
 * [com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase] ne dépendent que de ces deux
 * opérations. L'implémentation [com.filestech.sms.system.scheduler.ScheduledMessageSchedulerImpl]
 * s'appuie sur `WorkManager` (Android) — hors du port, qui ne manipule que des primitives.
 *
 * La re-planification de masse au boot (`rescheduleAllPending`) est un concern de cycle de vie
 * système, consommé uniquement par `BootReceiver` : elle reste hors de ce port (ségrégation
 * d'interface).
 */
interface ScheduledMessageScheduler {

    /** Planifie l'envoi du message programmé [scheduledMessageId] à l'instant [epochMillis]. */
    fun scheduleAt(scheduledMessageId: Long, epochMillis: Long)

    /** Annule l'envoi différé du message programmé [scheduledMessageId]. */
    fun cancel(scheduledMessageId: Long)
}
