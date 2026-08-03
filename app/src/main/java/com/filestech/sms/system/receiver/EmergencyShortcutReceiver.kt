package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.filestech.sms.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * v1.12.0 — Receiver du raccourci d'urgence (notification persistante lock-screen).
 *
 * Actions restantes :
 *
 *  - **[ACTION_DIAL_112]** : ouvre le dialer pré-rempli sur 112 (numéro
 *    d'urgence européen). Utilise `Intent.ACTION_DIAL` (pas CALL_PHONE qui
 *    demanderait permission runtime + déclencherait l'appel sans confirmation).
 *    Le numéro 112 est reconnu par l'OS comme numéro d'urgence et le dialer
 *    s'ouvre même écran verrouillé sur la plupart des devices.
 *  - **[ACTION_DIAL_POLICE]** : idem pour le 17, en opt-in.
 *
 * **v1.26.1 (audit B5) — `ACTION_TRIGGER_EMERGENCY` a été RETIRÉE.**
 *
 * Elle envoyait de VRAIS SMS d'urgence + la géolocalisation sur un SEUL broadcast, sans
 * anti-rebond, sans temporisation et sans retour utilisateur. Son unique constructeur d'Intent
 * n'avait plus aucun appelant depuis le retrait de la quick action en v1.14.2 : c'était un
 * chemin armé sans consommateur, dont l'innocuité ne tenait qu'à `exported=false`. Une
 * régression du manifeste — ou un copier-coller d'`intent-filter` — l'aurait rendue
 * déclenchable par n'importe quelle application. Le déclenchement légitime passe par l'écran
 * in-app, avec appui maintenu 3 secondes.
 *
 * **Sécurité** :
 *  - `exported = false` dans le Manifest — uniquement le PendingIntent de
 *    SMS Tech peut déclencher ces actions, pas une autre app.
 *  - Les numéros ne peuvent PAS être détournés : ils sont codés en dur ici,
 *    jamais passés en extra modifiable, et re-filtrés par la liste blanche
 *    d'`EmergencyCallHelper`.
 */
@AndroidEntryPoint
class EmergencyShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // v1.26.1 (audit B5) — `ACTION_TRIGGER_EMERGENCY` retiree, cf. le KDoc de tete.
            ACTION_DIAL_112 -> handleDial(context, EMERGENCY_NUMBER_EU)
            ACTION_DIAL_POLICE -> handleDial(context, EMERGENCY_NUMBER_POLICE_FR)
            else -> Timber.w("EmergencyShortcutReceiver: unknown action %s", intent.action)
        }
    }

    private fun handleDial(context: Context, number: String) {
        Timber.i("EmergencyShortcutReceiver: dial %s requested", number)
        // v1.14.0 — délégué au helper centralisé `EmergencyCallHelper.openDialer`
        // pour cohérence + whitelist stricte des numéros. Lock-screen action =
        // TOUJOURS dialer (jamais auto-call) : hold-3s direct-call est réservé
        // aux écrans in-app où la garde gestuelle est applicable. Sur lock-
        // screen, le tap accidentel poche-pocket est trop probable.
        com.filestech.sms.system.emergency.EmergencyCallHelper.openDialer(context, number)
    }

    companion object {
        /** Numéro européen unifié pour les urgences (24/24). */
        const val EMERGENCY_NUMBER_EU = "112"
        /** Police nationale française (depuis France). */
        const val EMERGENCY_NUMBER_POLICE_FR = "17"

        const val ACTION_DIAL_112 = "com.filestech.sms.SHORTCUT_DIAL_112"
        const val ACTION_DIAL_POLICE = "com.filestech.sms.SHORTCUT_DIAL_POLICE"

        /**
         * v1.14.1 — action portée par le `setContentIntent` de la notification
         * persistante : tap sur le corps de la notif → ouvre la page Mode
         * urgence in-app (full screen avec tous les boutons d'urgence visibles).
         * Distinct des 3 quick actions qui restent ACTION_DIAL_* (lock-screen
         * sécurité, l'OS gère le unlock si besoin).
         */
        const val ACTION_OPEN_EMERGENCY = "com.filestech.sms.SHORTCUT_OPEN_EMERGENCY"

        /** ID unique de la notif persistante (jamais collisionne avec celles SMS). */
        const val NOTIF_ID_EMERGENCY_SHORTCUT = 0x53484f52 // 'SHOR'

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
