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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ScheduledMessageSchedulerImpl.KEY_SCHEDULED_ID, -1L)
        if (id < 0) return Result.failure()
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

            ScheduledSendAttempt.Verdict.UNKNOWN_ID -> Result.failure()
        }
    }
}
