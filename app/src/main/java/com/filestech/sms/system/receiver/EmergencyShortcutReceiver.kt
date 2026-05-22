package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import com.filestech.sms.R
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.usecase.TriggerEmergencyUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.12.0 — Receiver du raccourci d'urgence (notification persistante
 * lock-screen). Deux actions exposées :
 *
 *  - **[ACTION_TRIGGER_EMERGENCY]** : déclenche immédiatement
 *    [TriggerEmergencyUseCase] (qui garde déjà la défense PanicDecoy).
 *    Pas besoin de déverrouiller le téléphone. L'envoi SMS aux contacts
 *    + résolution GPS se fait en background via `ApplicationScope`. Un
 *    toast Android natif (ou la notif elle-même) suffit comme feedback —
 *    on n'a pas besoin de remonter une UI Compose.
 *
 *  - **[ACTION_DIAL_112]** : ouvre le dialer pré-rempli sur 112 (numéro
 *    d'urgence européen). Utilise `Intent.ACTION_DIAL` (pas CALL_PHONE qui
 *    demanderait permission runtime + déclencherait l'appel sans confirmation).
 *    Le numéro 112 est reconnu par l'OS comme numéro d'urgence et le dialer
 *    s'ouvre même écran verrouillé sur la plupart des devices.
 *
 * **Sécurité** :
 *  - `exported = false` dans le Manifest — uniquement le PendingIntent de
 *    SMS Tech peut déclencher ces actions, pas une autre app.
 *  - L'action 112 ne peut PAS être détournée pour appeler un autre numéro
 *    car le numéro est hardcodé ici (pas passé en extra modifiable).
 *  - L'action URGENCE bénéficie de la garde PanicDecoy du UseCase.
 *
 * **Threading** : `goAsync()` + `ApplicationScope.launch` pour le trigger
 * suspend. Latence acceptable (~10-50ms) sur le main thread pour le tap,
 * puis l'envoi SMS continue en background même si la notif est dismiss.
 */
@AndroidEntryPoint
class EmergencyShortcutReceiver : BroadcastReceiver() {

    @Inject lateinit var triggerEmergency: TriggerEmergencyUseCase
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_EMERGENCY -> handleTrigger(context)
            ACTION_DIAL_112 -> handleDial(context, EMERGENCY_NUMBER_EU)
            ACTION_DIAL_POLICE -> handleDial(context, EMERGENCY_NUMBER_POLICE_FR)
            else -> Timber.w("EmergencyShortcutReceiver: unknown action %s", intent.action)
        }
    }

    private fun handleTrigger(context: Context) {
        Timber.i("EmergencyShortcutReceiver: ACTION_TRIGGER_EMERGENCY received")
        val pending = goAsync()
        scope.launch {
            try {
                val result = triggerEmergency()
                Timber.i("EmergencyShortcutReceiver: trigger result = %s", result::class.simpleName)
            } catch (t: Throwable) {
                Timber.w(t, "EmergencyShortcutReceiver: trigger failed")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleDial(context: Context, number: String) {
        Timber.i("EmergencyShortcutReceiver: dial %s requested", number)
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(dialIntent) }
            .onFailure { Timber.w(it, "EmergencyShortcutReceiver: no dialer to handle ACTION_DIAL") }
    }

    companion object {
        /** Numéro européen unifié pour les urgences (24/24). */
        const val EMERGENCY_NUMBER_EU = "112"
        /** Police nationale française (depuis France). */
        const val EMERGENCY_NUMBER_POLICE_FR = "17"

        const val ACTION_TRIGGER_EMERGENCY = "com.filestech.sms.SHORTCUT_TRIGGER_EMERGENCY"
        const val ACTION_DIAL_112 = "com.filestech.sms.SHORTCUT_DIAL_112"
        const val ACTION_DIAL_POLICE = "com.filestech.sms.SHORTCUT_DIAL_POLICE"

        /** ID unique de la notif persistante (jamais collisionne avec celles SMS). */
        const val NOTIF_ID_EMERGENCY_SHORTCUT = 0x53484f52 // 'SHOR'

        fun intentTrigger(context: Context): Intent =
            Intent(context, EmergencyShortcutReceiver::class.java).apply {
                action = ACTION_TRIGGER_EMERGENCY
                component = ComponentName(context, EmergencyShortcutReceiver::class.java)
                `package` = context.packageName
            }

        fun intentDial112(context: Context): Intent =
            Intent(context, EmergencyShortcutReceiver::class.java).apply {
                action = ACTION_DIAL_112
                component = ComponentName(context, EmergencyShortcutReceiver::class.java)
                `package` = context.packageName
            }

        fun intentDialPolice(context: Context): Intent =
            Intent(context, EmergencyShortcutReceiver::class.java).apply {
                action = ACTION_DIAL_POLICE
                component = ComponentName(context, EmergencyShortcutReceiver::class.java)
                `package` = context.packageName
            }
    }
}

/**
 * Helper pour cancel la notif persistante depuis n'importe quel call site
 * sans avoir à injecter le NotificationManagerCompat dans 10 endroits.
 */
internal fun Context.cancelEmergencyShortcutNotification() {
    NotificationManagerCompat.from(this).cancel(
        EmergencyShortcutReceiver.NOTIF_ID_EMERGENCY_SHORTCUT,
    )
}
