package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.filestech.sms.data.backup.BackupService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupService: BackupService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            backupService.runScheduledBackup()
            Result.success()
        } catch (t: Throwable) {
            Timber.w(t, "BackupWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "scheduled_backup"
    }
}
