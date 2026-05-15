package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.ScheduledState
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.usecase.SendSmsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class ScheduledMessageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: ScheduledMessageDao,
    private val sendSms: SendSmsUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ScheduledMessageScheduler.KEY_SCHEDULED_ID, -1L)
        if (id < 0) return Result.failure()
        val entity = dao.findById(id) ?: return Result.failure()
        if (entity.state != ScheduledState.PENDING) return Result.success()
        val recipients = PhoneAddress.list(entity.addressesCsv)
        return when (sendSms.invoke(recipients, entity.body, entity.subId)) {
            is Outcome.Success -> {
                dao.setState(id, ScheduledState.SENT)
                Result.success()
            }
            is Outcome.Failure -> {
                Timber.w("ScheduledMessageWorker: send failed for id=%d", id)
                dao.setState(id, ScheduledState.FAILED)
                Result.retry()
            }
        }
    }
}
