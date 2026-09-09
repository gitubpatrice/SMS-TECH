package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Adaptateur WorkManager : lit l'id d'entrée, délègue la décision à [ScheduledSendAttempt] et
 * traduit son verdict en `Result`. Toute la logique de reprise vit dans le délégué, testable
 * sans harnais WorkManager.
 */
@HiltWorker
class ScheduledMessageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sendAttempt: ScheduledSendAttempt,
    /** v1.26.1 (audit M3) — pour différer un envoi pendant une session leurre, cf. [doWork]. */
    private val appLock: com.filestech.sms.security.AppLockManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ScheduledMessageSchedulerImpl.KEY_SCHEDULED_ID, -1L)
        if (id < 0) return Result.failure()
        // v1.26.1 (audit M3) — en session leurre, on DIFFÈRE l'envoi.
        //
        // Les use-cases d'urgence portent tous une garde `PanicDecoy` ; les envois programmés
        // n'en avaient aucune. Un message partait donc et APPARAISSAIT dans la liste sous les
        // yeux de l'agresseur, sans que personne ne l'ait composé — rupture de l'illusion, et
        // fuite de son contenu (« je pars », « j'appelle la police »).
        //
        // On diffère au lieu d'annuler : le mode leurre est TRANSITOIRE (l'observateur de
        // verrouillage le réinitialise dès que l'application passe en arrière-plan), alors
        // qu'annuler perdrait définitivement un message que l'utilisateur avait programmé.
        appLock.ensureResolved()
        if (appLock.state.value is com.filestech.sms.security.AppLockManager.LockState.PanicDecoy) {
            Timber.i("ScheduledMessageWorker: deferring id=%d — panic-decoy session", id)
            return Result.retry()
        }
        return when (sendAttempt(id, runAttemptCount)) {
            ScheduledSendAttempt.Verdict.SENT,
            ScheduledSendAttempt.Verdict.ALREADY_SETTLED,
            -> Result.success()

            ScheduledSendAttempt.Verdict.RETRY -> {
                Timber.w(
                    "ScheduledMessageWorker: send failed for id=%d, attempt %d/%d — retrying",
                    id,
                    runAttemptCount + 1,
                    ScheduledSendAttempt.MAX_ATTEMPTS,
                )
                Result.retry()
            }

            ScheduledSendAttempt.Verdict.GAVE_UP -> {
                Timber.w(
                    "ScheduledMessageWorker: giving up on id=%d after %d attempts",
                    id,
                    ScheduledSendAttempt.MAX_ATTEMPTS,
                )
                Result.failure()
            }

            // v1.28.3 (F20) — un envoi revendiqué dont le bail court encore n'est PAS terminé.
            //
            // Il tombait auparavant dans `ALREADY_SETTLED`, donc dans `Result.success()` : le
            // travail s'arrêtait là et plus rien ne revenait jamais sur cette ligne. On revient,
            // par le backoff exponentiel — d'abord parce que l'envoi en cours peut encore aboutir
            // et écrire `SENT` lui-même, ensuite parce que si son exécution est morte, c'est ce
            // retour qui constatera l'expiration du bail et conclura `INTERRUPTED`. Le backoff
            // dépassant [ScheduledSendAttempt.SEND_LEASE_MS] au bout de quelques réveils, la
            // boucle est bornée : elle se termine par un verdict, jamais par un silence.
            ScheduledSendAttempt.Verdict.IN_FLIGHT -> {
                Timber.i("ScheduledMessageWorker: id=%d still claimed — lease not expired", id)
                Result.retry()
            }

            ScheduledSendAttempt.Verdict.INTERRUPTED -> {
                Timber.w(
                    "ScheduledMessageWorker: id=%d claimed then abandoned — outcome unknown",
                    id,
                )
                Result.failure()
            }

            ScheduledSendAttempt.Verdict.UNKNOWN_ID -> Result.failure()
        }
    }
}
