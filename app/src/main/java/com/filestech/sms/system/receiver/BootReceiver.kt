package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.system.scheduler.ScheduledMessageScheduler
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ScheduledMessageScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // Audit F10 + M-11: accept only the OS-protected boot / user-unlock actions.
        //  - `BOOT_COMPLETED`            → device finished booting, all user data unlocked.
        //  - `LOCKED_BOOT_COMPLETED`     → fired in direct-boot mode BEFORE the user unlocks the
        //                                   device. READ_SMS is typically NOT granted yet, so any
        //                                   sync we try is a no-op; we still enqueue the worker
        //                                   for the unlock path to pick up.
        //  - `ACTION_USER_UNLOCKED`      → fired once the user has unlocked direct-boot for the
        //                                   first time. This is the **second** sync trigger that
        //                                   was missing before: SMS that arrived between the
        //                                   bootloader and the user unlock would otherwise stay
        //                                   invisible to us until the periodic worker ran 12 h
        //                                   later. Catching this event makes the gap minutes,
        //                                   not hours.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_USER_UNLOCKED
        ) return
        TelephonySyncWorker.enqueueOneShot(context)
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.rescheduleAllPending()
            } finally {
                pending.finish()
            }
        }
    }
}
