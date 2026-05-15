package com.filestech.sms

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.filestech.sms.core.logging.LineNumberDebugTree
import com.filestech.sms.core.logging.NoOpReleaseTree
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.AutoLockObserver
import com.filestech.sms.system.notifications.NotificationChannelInitializer
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notificationChannelInitializer: NotificationChannelInitializer

    @Inject lateinit var autoLockObserver: AutoLockObserver

    @Inject lateinit var appLock: AppLockManager

    @Inject lateinit var telephonySyncManager: TelephonySyncManager

    @Inject lateinit var blockedNumbersImporter: BlockedNumbersImporter

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.LOG_ENABLED) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(LineNumberDebugTree())
        } else {
            Timber.plant(NoOpReleaseTree())
        }
        notificationChannelInitializer.ensureDefaultChannels()
        autoLockObserver.register()
        // Audit P-P0-5: the historical R6 fix used `runBlocking(IO) { appLock.resolveInitialState() }`
        // here to pre-resolve the lock state before any broadcast receiver could read it. That
        // blocked the main thread for 50-200 ms on DataStore on cold-start. We now kick the
        // resolution off asynchronously — receivers / services that depend on the resolved state
        // call `appLock.ensureResolved()` themselves (idempotent, mutexed) inside their own
        // coroutine context. The main thread is freed and the contract is preserved.
        appScope.launch { appLock.ensureResolved() }
        // Telephony sync: register the system-provider ContentObserver + drain anything that
        // accumulated while the process was dead. Schedule a 12 h safety-net WorkManager job
        // so we still converge even if the observer never fires (rare OEM bug, force-stop).
        telephonySyncManager.start()
        TelephonySyncWorker.schedulePeriodic(this)
        // Order matters at first boot: mirror the OS-wide blocked-numbers list **first**, then
        // kick the SMS import. Otherwise the worker may scan `content://sms` before the Room
        // blocklist is populated, and the user sees blocked correspondents resurface in the
        // very first import (audit "indésirables à l'import"). The importer is fast (< 50 ms
        // typical) and idempotent on re-runs, so paying this serial cost is harmless.
        //
        // The system-side read in `TelephonySyncWorker.runImport` *also* queries
        // `BlockedNumberContract` directly so a fresh-install user who hasn't accepted the
        // default-SMS prompt yet still gets the filter on the next sync tick — this is just
        // belt-and-braces.
        appScope.launch {
            runCatching { blockedNumbersImporter.importFromSystem() }
            TelephonySyncWorker.enqueueOneShot(this@MainApplication)
        }
    }
}
