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
 * Raccourci d'urgence : notification persistante visible sur l'écran
 * verrouillé. Sert de pont entre la situation d'urgence et l'écran in-app.
 *
 * **Historique** :
 *  - v1.12.0 = 2 actions (URGENCE + 112). URGENCE = 1 tap → SMS aux contacts.
 *  - v1.14.1 = ajout `setContentIntent` : tap sur le corps de la notif ouvre
 *    la page Mode urgence in-app (au lieu d'une action seule).
 *  - v1.14.2 HOTFIX = **quick action URGENCE RETIRÉE**. Risque mistap trop
 *    élevé sur lock-screen (1 tap = SMS broadcasté aux contacts SafetyCall).
 *    Le bouton URGENCE in-app (`EmergencyHoldButton`) reste, protégé par
 *    hold-3s + drag detection. Pour déclencher URGENCE depuis lock-screen :
 *    tap le corps de la notif → page in-app → hold 3 s sur le gros bouton.
 *
 * **Actions actuelles** : 112 (toujours visible) + 17 Police FR (opt-in
 * `emergencyCallPoliceEnabled`). Les deux utilisent `ACTION_DIAL` (composeur
 * pré-rempli, l'user confirme en appuyant sur le bouton vert du dialer —
 * pas d'auto-call, pas de permission CALL_PHONE).
 *
 * **Politique d'affichage** :
 *  - Canal [NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT]
 *    = `IMPORTANCE_LOW` : pas de heads-up, pas de son, pas de vibration.
 *  - `setOngoing(true)` : impossible à dismiss par swipe.
 *  - `setVisibility(VISIBILITY_PUBLIC)` : actions visibles sur lock screen.
 *  - `setLocalOnly(true)` : pas de mirroring vers Wear OS / autre device.
 *  - `setShowWhen(false)` : pas de timestamp (raccourci permanent).
 *
 * **Sécurité** :
 *  - `setContentIntent` cible `MainActivity::class.java` explicitement,
 *    action `ACTION_OPEN_EMERGENCY` (constante), `FLAG_IMMUTABLE`.
 *  - Quick actions 112 / 17 = broadcast vers `EmergencyShortcutReceiver`
 *    (`exported=false`). Aucune app tierce ne peut déclencher.
 *  - Numéros 112 et 17 hardcodés dans `EmergencyCallHelper.ALLOWED_NUMBERS`
 *    whitelist stricte.
 *
 * **Cycle de vie** :
 *  - Posée par [MainApplication] au démarrage si `emergencyShortcutEnabled = true`.
 *  - Re-posée par [com.filestech.sms.system.receiver.BootReceiver] après reboot.
 *  - Cancelée immédiatement par `MainApplication.combine` quand l'user
 *    désactive le toggle OU entre en PanicDecoy. Cascade-disable via
 *    `EmergencyViewModel.disableEmergencyMode()` (v1.14.2 hotfix).
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
        // v1.14.2 hotfix CRITIQUE — la quick action "URGENCE" qui déclenchait
        // un envoi SMS direct sur 1 tap a été RETIRÉE. Risque : mistap accidentel
        // (notif lock-screen, pocket-tap, dismiss confondu avec action) faisait
        // partir un SMS à TOUS les contacts SafetyCall. Bug user remonté
        // 2026-05-22 ("beaucoup de mms sans rien faire"). Le hold-3s
        // anti-pocket-dial existe sur l'écran in-app (EmergencyHoldButton) mais
        // pas sur une notif (notif actions = single tap par design Android).
        // Solution : pour déclencher URGENCE depuis lock-screen, l'user tape
        // le CORPS de la notif → ouvre la page in-app (setContentIntent,
        // ACTION_OPEN_EMERGENCY) → hold 3s sur le gros bouton URGENCE. Trois
        // gestes délibérés au lieu d'un mistap. Les 112/17 quick actions
        // restent (ACTION_DIAL ouvre composeur, user confirme dans dialer).
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

        // v1.14.1 — Tap sur le corps de la notif (hors actions) → ouvre la page
        // Mode urgence in-app. PendingIntent vers MainActivity avec un extra
        // `ACTION_OPEN_EMERGENCY` que MainActivity.onNewIntent route vers
        // `PendingNavHolder.set(openEmergency=true)` → AppRoot navigate Emergency.
        val openEmergencyIntent = android.content.Intent(
            context,
            com.filestech.sms.MainActivity::class.java,
        ).apply {
            action = EmergencyShortcutReceiver.ACTION_OPEN_EMERGENCY
            // SINGLE_TOP + CLEAR_TOP : si MainActivity est déjà en haut, on évite
            // de l'empiler. Si une autre Activity SMS Tech est en haut, on la
            // pop et on revient sur MainActivity (qui re-route Emergency).
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openEmergencyPI = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_EMERGENCY,
            openEmergencyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(
            context,
            NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.emergency_shortcut_notif_title))
            .setContentText(context.getString(R.string.emergency_shortcut_notif_text))
            .setContentIntent(openEmergencyPI)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // v1.14.2 hotfix CRITIQUE — quick action URGENCE retirée (cf. KDoc
            // au-dessus). Pour déclencher l'urgence depuis le lock-screen,
            // l'user tape le CORPS de la notif → setContentIntent ouvre la
            // page in-app → hold 3s sur EmergencyHoldButton. Trois gestes
            // délibérés au lieu d'un mistap dangereux. Les actions DIAL_112
            // et DIAL_POLICE restent (ACTION_DIAL = composeur, user confirme).
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

        // Audit lint v1.14.8 — `@SuppressLint("MissingPermission")` justifié : `hasPostPermission()`
        // est vérifié au début de [postShortcut] (early-return si denied). Lint ne suit pas la
        // helper, faux positif documenté Google issuetracker.google.com/138141627.
        @android.annotation.SuppressLint("MissingPermission")
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
        // v1.14.2 — `REQUEST_TRIGGER` retiré : quick action URGENCE supprimée
        // de la notif (cf. KDoc + postShortcut). Les codes restants sont
        // les seules entrées légitimes vers `EmergencyShortcutReceiver`.
        private const val REQUEST_DIAL_112 = 0x44313132 // 'D112'
        private const val REQUEST_DIAL_POLICE = 0x44504f4c // 'DPOL'
        // v1.14.1 — content-intent request code, distinct des actions DIAL.
        private const val REQUEST_OPEN_EMERGENCY = 0x4f50454e // 'OPEN'
    }
}
