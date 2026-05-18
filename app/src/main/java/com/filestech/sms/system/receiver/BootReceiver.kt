package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.system.scheduler.ScheduledMessageScheduler
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import com.filestech.sms.system.service.KeepAliveService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ScheduledMessageScheduler
    @Inject lateinit var settingsRepository: SettingsRepository
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
                // v1.3.10 — redémarre le foreground [KeepAliveService] au boot du device
                // SI l'utilisateur l'a activé dans Réglages → Avancé → Mode résistant.
                // MainApplication.onCreate observe aussi le flag mais ce path donne une
                // ceinture+bretelles pour les cas où Android instancie ce Receiver sans
                // déclencher un cold-start Application complet (rare mais possible sur
                // Android 14+ en mode "limited app process" pour certains broadcasts).
                // `KeepAliveService.start` est idempotent — un éventuel double-start côté
                // Android est dédoublonné nativement par le système (`onStartCommand` ré-
                // appelé sur la même instance).
                // v1.3.10 (P6) — `flow.first()` would block indefinitely if DataStore is
                // corrupted or its file lock is held by another process (observed on MIUI
                // multi-user). `goAsync()` only buys us ~10s before Android kills the
                // receiver context with a partial-ANR — explicitly cap at 3 s and treat
                // any failure as "keep-alive disabled" to keep the boot path bounded.
                val enabled = withTimeoutOrNull(3_000L) {
                    runCatching {
                        settingsRepository.flow.first().advanced.keepAliveService
                    }.getOrDefault(false)
                } ?: false
                if (enabled) {
                    KeepAliveService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
