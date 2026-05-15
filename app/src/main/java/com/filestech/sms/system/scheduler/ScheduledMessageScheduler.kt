package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledMessageScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ScheduledMessageRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) {
        val delay = (epochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        // Audit P-P1-8: exponential backoff on transient send failures (no service, RIL busy,
        // SmsManager transient throw). The OS default for `WorkManager.Result.retry()` is a
        // linear 30 s — re-firing every 30 s on a phone in a dead zone hammers the modem for
        // nothing and drains the battery. EXPONENTIAL starts at our 30 s base and doubles
        // (30 s → 60 s → 120 s …) capped by WorkManager at 5 h. Acceptable for a "send was
        // scheduled, retry later" flow; users in a hurry can also manually trigger a retry
        // from the failed-message affordance.
        val work = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putLong(KEY_SCHEDULED_ID, scheduledMessageId).build())
            .addTag(TAG_PREFIX + scheduledMessageId)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(scheduledMessageId),
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    fun cancel(scheduledMessageId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(scheduledMessageId))
    }

    suspend fun rescheduleAllPending() = withContext(io) {
        val pending = repo.observePending().first()
        for (item in pending) scheduleAt(item.id, item.scheduledAt)
    }

    companion object {
        const val KEY_SCHEDULED_ID = "scheduled_id"
        private const val TAG_PREFIX = "scheduled_sms_"
        private fun workName(id: Long) = "scheduled_sms_$id"
    }
}
