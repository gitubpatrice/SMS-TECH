package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.system.scheduler.SafetyCallWorker
import timber.log.Timber

/**
 * v1.27.2 — reçoit l'alarme posée par [com.filestech.sms.system.scheduler.SafetyCallAlarmScheduler]
 * à l'échéance exacte du Safety call, et déclenche un contrôle **immédiat**.
 *
 * Volontairement minimal : il n'envoie rien lui-même. Un `BroadcastReceiver` dispose d'environ dix
 * secondes avant d'être tué, et l'envoi passe par DataStore, la base et la radio. Il se contente
 * donc de mettre [SafetyCallWorker] en file, qui a le droit de prendre son temps.
 *
 * Pas de `@AndroidEntryPoint` : rien n'est injecté ici, et un receveur Hilt monterait le graphe
 * complet pour une seule ligne.
 */
class SafetyCallAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAFETY_CALL_DUE) return
        Timber.i("SafetyCallAlarmReceiver: echeance atteinte, controle immediat")
        SafetyCallWorker.enqueueNow(context)
    }

    companion object {
        /** Action portée par le `PendingIntent` de l'alarme. Jamais diffusée par un tiers. */
        const val ACTION_SAFETY_CALL_DUE = "com.filestech.sms.SAFETY_CALL_DUE"
    }
}
