package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.system.notifications.EmergencyShortcutNotifier
import com.filestech.sms.system.scheduler.SafetyCallWorker
import com.filestech.sms.system.scheduler.ScheduledMessageSchedulerImpl
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

    // v1.24.0 SEC-CRIT — `Lazy` : ce collaborateur atteint un DAO, donc `AppDatabase`, donc la
    // réparation zéro-clé. L'injection de champ Hilt précède le corps de `onReceive`, sur le main
    // thread : en eager, la reconstruction de la base y tournait sous un timeout ANR de 10 s.
    @Inject lateinit var schedulerLazy: dagger.Lazy<ScheduledMessageSchedulerImpl>
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var emergencyShortcutNotifier: EmergencyShortcutNotifier
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
        // v1.9.0 audit fix SEC-2 — Safety call : reschedule le worker
        // périodique au boot pour résister aux force-stop OEM (Xiaomi,
        // Huawei) qui cancellent les jobs WorkManager. Sans ça, un user qui
        // a armé le Safety call mais redémarre son device verra le deadman
        // ne plus s'exécuter jusqu'à ouverture manuelle de l'app — or par
        // définition du deadman, l'user n'est pas censé ouvrir l'app.
        // Idempotent (KEEP policy).
        SafetyCallWorker.schedulePeriodic(context)
        val pending = goAsync()
        scope.launch {
            try {
                val scheduler = schedulerLazy.get()
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
                // v1.12.0 — re-poster la notification persistante du raccourci
                // urgence si l'user l'a activée. Sans ça, après reboot, la notif
                // disparaît et l'user perd son accès rapide sans le savoir.
                val (shortcutEnabled, policeEnabled) = withTimeoutOrNull(3_000L) {
                    runCatching {
                        val sec = settingsRepository.flow.first().security
                        sec.emergencyShortcutEnabled to sec.emergencyCallPoliceEnabled
                    }.getOrDefault(false to false)
                } ?: (false to false)
                if (shortcutEnabled) {
                    emergencyShortcutNotifier.postShortcut(policeEnabled = policeEnabled)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
