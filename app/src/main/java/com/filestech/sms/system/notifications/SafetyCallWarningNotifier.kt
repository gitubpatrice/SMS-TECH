package com.filestech.sms.system.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.9.0 — Notification de pré-trigger pour le Safety call.
 *
 * Posée par [com.filestech.sms.system.scheduler.SafetyCallWorker] quand on
 * entre dans la fenêtre de 6h avant expiration ([SafetyCallConfig
 * .WARNING_WINDOW_MS]). Persistante (non swipable), `IMPORTANCE_HIGH` pour
 * qu'elle attire l'attention sans être un canal séparé bruyant.
 *
 * **Audit fix C3** : canal dédié [NotificationChannelInitializer
 * .CHANNEL_SAFETY_CALL_WARNING] (au lieu de partager `CHANNEL_INCOMING` avec
 * les SMS reçus). Permet à l'utilisateur de régler le son / la vibration des
 * warnings indépendamment des notifs SMS normales.
 *
 * **Audit fix SEC-10** : nonce mono-usage [SafetyCallIntentToken] mis en
 * extra `EXTRA_RESET_TOKEN`. `MainActivity` (exported true à cause du rôle
 * SMS) valide le token avant de reset le timer — protège contre une app
 * tierce qui forgerait un intent reset pour neutraliser le deadman.
 *
 * **Tap sur la notif** → ouvre SMS Tech (`MainActivity.ACTION_SAFETY_CALL_RESET`
 * + extra token signé) qui reset le timer après validation.
 *
 * **Dismiss programmatique** : appelé par le worker quand l'user a reset le
 * timer depuis ailleurs (bouton dédié Settings, ou simple ouverture app qui
 * remet `lastActivityAt = now()`). Le tick worker suivant détecte qu'on est
 * hors fenêtre et appelle `dismiss()`.
 */
@Singleton
class SafetyCallWarningNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intentToken: SafetyCallIntentToken,
) {

    /**
     * Affiche / met à jour la notification de warning. Idempotent : si la
     * notif existe déjà, elle est mise à jour avec le nouveau texte (compte
     * à rebours en heures).
     *
     * @param msToExpiryMs millisecondes restantes avant trigger automatique.
     *   Utilisé pour afficher "Plus que ~5h" dans le texte de la notif.
     */
    fun showWarning(msToExpiryMs: Long) {
        if (!hasPostPermission()) {
            Timber.w("SafetyCallWarningNotifier: POST_NOTIFICATIONS not granted, skipping warning")
            return
        }
        val hoursLeft = (msToExpiryMs / 3_600_000L).coerceAtLeast(0L).toInt()
        val title = context.getString(R.string.safety_call_warning_title)
        val body = if (hoursLeft >= 2) {
            context.getString(R.string.safety_call_warning_body_hours, hoursLeft)
        } else {
            context.getString(R.string.safety_call_warning_body_imminent)
        }

        // v1.9.0 audit fix SEC-10 — rote un nouveau nonce et l'embarque
        // dans l'intent. Sans nonce valide, MainActivity rejettera le reset.
        val token = intentToken.rotate()
        val tapIntent = PendingIntent.getActivity(
            context,
            REQUEST_SAFETY_CALL_RESET,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SAFETY_CALL_RESET)
                .putExtra(EXTRA_RESET_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(
            context,
            NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true) // non swipable — l'user DOIT taper pour reset
            .setOnlyAlertOnce(true) // pas de re-son à chaque mise à jour heure
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID_DEADMAN_WARNING, notif)
        Timber.i("SafetyCallWarningNotifier: posted warning (%dh left)", hoursLeft)
    }

    /**
     * Annule la notification de warning si présente. Safe à appeler même si
     * pas de notif active. Appelé par le worker quand l'user a reset le
     * timer (hors fenêtre de warning) ou quand le trigger a été exécuté.
     */
    fun dismiss() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(NOTIF_ID_DEADMAN_WARNING)
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** Action de l'intent posé sur le tap notif → handled by MainActivity. */
        const val ACTION_SAFETY_CALL_RESET = "com.filestech.sms.SAFETY_CALL_RESET"

        /**
         * v1.9.0 audit fix SEC-10 — extra portant le nonce anti-spoofing.
         * Validé par `MainActivity.handleSharedIntent` via [SafetyCallIntentToken.consume].
         */
        const val EXTRA_RESET_TOKEN = "com.filestech.sms.SAFETY_CALL_RESET_TOKEN"

        /** ID de la notification (unique stable pour update/dismiss). */
        private const val NOTIF_ID_DEADMAN_WARNING = 0x44454144 // 'DEAD'
        private const val REQUEST_SAFETY_CALL_RESET = 0x52455354    // 'REST'
    }
}
