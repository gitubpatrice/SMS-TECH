package com.filestech.sms.system.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.filestech.sms.R
import com.filestech.sms.system.receiver.EmergencyShortcutReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.12.0 — Raccourci d'urgence : notification persistante avec 2 actions
 * (URGENCE + 112), visible sur l'écran verrouillé.
 *
 * **Pourquoi** : un Mode urgence accessible uniquement après déverrouillage
 * + navigation dans l'app = trop de manipulations en situation d'urgence
 * réelle. Cette notif persistante offre un tap unique depuis le shade,
 * y compris lock screen (`VISIBILITY_PUBLIC`).
 *
 * **Politique d'affichage** :
 *  - Canal [NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT]
 *    = `IMPORTANCE_LOW` : pas de heads-up, pas de son, pas de vibration.
 *  - `setOngoing(true)` : impossible à dismiss par swipe (sinon l'user
 *    perdrait son raccourci par erreur).
 *  - `setVisibility(VISIBILITY_PUBLIC)` : actions visibles sur lock screen.
 *  - `setLocalOnly(true)` : pas de mirroring vers Wear OS / autre device.
 *  - `setShowWhen(false)` : pas de timestamp (l'user n'a pas besoin de savoir
 *    quand la notif a été postée, c'est un raccourci permanent).
 *
 * **Sécurité** :
 *  - Les actions sont des broadcasts `PendingIntent.getBroadcast` vers
 *    [EmergencyShortcutReceiver] (`exported=false` dans le Manifest).
 *  - Aucune app tierce ne peut déclencher ces actions.
 *  - L'action URGENCE bénéficie de la garde PanicDecoy du UseCase.
 *  - L'action 112 utilise `ACTION_DIAL` (pré-rempli, pas d'appel auto) —
 *    l'user doit confirmer en appuyant sur le bouton vert du dialer.
 *
 * **Cycle de vie** :
 *  - Posée par [MainApplication] au démarrage si `emergencyShortcutEnabled = true`.
 *  - Re-posée par [com.filestech.sms.system.receiver.BootReceiver] après reboot.
 *  - Cancelée quand l'user désactive le toggle.
 */
@Singleton
class EmergencyShortcutNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Affiche / met à jour la notification persistante. Idempotent — re-poste
     * la même notif avec le même ID = update sans clignotement.
     *
     * @param policeEnabled si `true`, ajoute une 3ᵉ action "Appeler 17" pour
     *   la police nationale FR (opt-in spécifique France). Max 3 actions
     *   par notif Android — URGENCE + 112 + 17 saturé.
     */
    fun postShortcut(policeEnabled: Boolean = false) {
        if (!hasPostPermission()) {
            Timber.w("EmergencyShortcutNotifier: POST_NOTIFICATIONS not granted, skipping")
            return
        }
        val triggerPI = PendingIntent.getBroadcast(
            context,
            REQUEST_TRIGGER,
            EmergencyShortcutReceiver.intentTrigger(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dial112PI = PendingIntent.getBroadcast(
            context,
            REQUEST_DIAL_112,
            EmergencyShortcutReceiver.intentDial112(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dialPolicePI = if (policeEnabled) {
            PendingIntent.getBroadcast(
                context,
                REQUEST_DIAL_POLICE,
                EmergencyShortcutReceiver.intentDialPolice(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val builder = NotificationCompat.Builder(
            context,
            NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.emergency_shortcut_notif_title))
            .setContentText(context.getString(R.string.emergency_shortcut_notif_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_notification_message,
                context.getString(R.string.emergency_shortcut_action_emergency),
                triggerPI,
            )
            .addAction(
                R.drawable.ic_notification_message,
                context.getString(R.string.emergency_shortcut_action_112),
                dial112PI,
            )
        if (dialPolicePI != null) {
            builder.addAction(
                R.drawable.ic_notification_message,
                context.getString(R.string.emergency_shortcut_action_police),
                dialPolicePI,
            )
        }

        NotificationManagerCompat.from(context).notify(
            EmergencyShortcutReceiver.NOTIF_ID_EMERGENCY_SHORTCUT,
            builder.build(),
        )
        Timber.d("EmergencyShortcutNotifier: posted shortcut notification (police=%s)", policeEnabled)
    }

    fun cancelShortcut() {
        NotificationManagerCompat.from(context).cancel(
            EmergencyShortcutReceiver.NOTIF_ID_EMERGENCY_SHORTCUT,
        )
        Timber.d("EmergencyShortcutNotifier: cancelled shortcut notification")
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_TRIGGER = 0x54524947 // 'TRIG'
        private const val REQUEST_DIAL_112 = 0x44313132 // 'D112'
        private const val REQUEST_DIAL_POLICE = 0x44504f4c // 'DPOL'
    }
}
