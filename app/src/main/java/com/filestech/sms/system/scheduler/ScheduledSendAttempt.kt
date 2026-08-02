package com.filestech.sms.system.scheduler

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledState
import com.filestech.sms.domain.usecase.SendSmsUseCase
import javax.inject.Inject

/**
 * Une tentative d'envoi d'un message programmé, extraite de [ScheduledMessageWorker] pour être
 * testable sans le harnais WorkManager — même découpage que
 * [com.filestech.sms.system.startup.StartupMigrations].
 *
 * v1.25.3 (audit C2) — le worker marquait la ligne `FAILED` **avant** de rendre `Result.retry()`.
 * Au replay, le garde « déjà réglé » (`state != PENDING`) la considérait terminée et rendait
 * `Result.success()` sans jamais retenter l'envoi : le backoff exponentiel configuré avec soin
 * dans [ScheduledMessageSchedulerImpl] ne pouvait rien rejouer et le moindre échec devenait
 * définitif. La ligne ne bascule désormais en `FAILED` qu'une fois [MAX_ATTEMPTS] épuisé ;
 * entre-temps elle reste `PENDING`, ce qui est précisément la condition que le replay teste.
 */
class ScheduledSendAttempt @Inject constructor(
    private val dao: ScheduledMessageDao,
    private val sendSms: SendSmsUseCase,
) {

    /**
     * Verdict d'une tentative, exprimé sans type WorkManager pour que la décision reste
     * vérifiable en test unitaire pur. [ScheduledMessageWorker] fait seul la traduction en
     * `ListenableWorker.Result`.
     */
    enum class Verdict {
        /** Envoyé, ligne passée en `SENT`. */
        SENT,

        /** Échec, tentatives restantes — ligne laissée `PENDING` pour que le replay la reprenne. */
        RETRY,

        /** Échec après [MAX_ATTEMPTS] tentatives, ligne passée en `FAILED`. */
        GAVE_UP,

        /** Id absent de la base (ligne purgée entre la planification et le réveil). */
        UNKNOWN_ID,

        /** Déjà envoyé, annulé ou échoué — rien à faire, le replay ne doit pas ré-envoyer. */
        ALREADY_SETTLED,
    }

    /**
     * @param runAttemptCount compteur WorkManager, `0` à la première exécution. Le nombre de
     *   tentatives consommées en incluant celle-ci vaut donc `runAttemptCount + 1`.
     */
    suspend operator fun invoke(id: Long, runAttemptCount: Int): Verdict {
        val entity = dao.findById(id) ?: return Verdict.UNKNOWN_ID
        if (entity.state != ScheduledState.PENDING) return Verdict.ALREADY_SETTLED
        val recipients = PhoneAddress.list(entity.addressesCsv)
        return when (sendSms.invoke(recipients, entity.body, entity.subId)) {
            is Outcome.Success -> {
                dao.setState(id, ScheduledState.SENT)
                Verdict.SENT
            }
            is Outcome.Failure -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                dao.setState(id, ScheduledState.FAILED)
                Verdict.GAVE_UP
            } else {
                // Aucune écriture d'état ici : laisser `PENDING` EST le correctif.
                Verdict.RETRY
            }
        }
    }

    companion object {
        /**
         * Tentatives totales avant abandon. Avec le backoff exponentiel de 30 s de
         * [ScheduledMessageSchedulerImpl], les replays tombent à +30 s, +1 min, +2 min puis
         * +4 min — environ 8 minutes de fenêtre.
         *
         * Pourquoi ne pas plus : [SendSmsUseCase] ne rend `Failure` que sur un échec
         * **synchrone** (pas d'app SMS par défaut, pas de SIM, `SmsManager` qui jette). Une
         * absence de réseau, elle, remonte de façon asynchrone via le `PendingIntent` d'envoi
         * et laisse le use case en `Success` — elle ne passe donc jamais par ce chemin. Au-delà
         * de quelques minutes, une cause synchrone n'a plus de raison de se résoudre seule, et
         * chaque tentative recrée une ligne d'envoi dans le fil.
         */
        const val MAX_ATTEMPTS = 5
    }
}
