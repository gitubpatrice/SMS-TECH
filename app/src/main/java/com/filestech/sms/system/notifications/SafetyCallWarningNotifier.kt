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

        // Audit lint v1.14.8 — `hasPostPermission()` early-return en début de [showWarning],
        // faux positif lint (ne suit pas la helper).
        @android.annotation.SuppressLint("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIF_ID_DEADMAN_WARNING, notif)
        Timber.i("SafetyCallWarningNotifier: posted warning (%dh left)", hoursLeft)
    }

    /**
     * v1.27.2 — notification **pendant la séquence de relances**, posée dès le premier message
     * envoyé et maintenue jusqu'au dernier.
     *
     * # Pourquoi elle est indispensable
     *
     * Avant, l'avertissement était retiré au déclenchement et **rien ne le remplaçait**. Or la
     * séquence dure désormais trois quarts d'heure : sans notification, le seul moyen d'arrêter les
     * relances était d'ouvrir l'application et d'aller dans les Réglages. Quelqu'un qui veut
     * simplement dire « je vais bien » et couper l'alerte devait donc naviguer dans un menu — au
     * pire moment possible.
     *
     * Le tap emprunte **exactement le même chemin** que le bouton des Réglages
     * ([ACTION_SAFETY_CALL_RESET], nonce compris) qui, depuis l'audit Codex, désactive le deadman
     * et clôt la séquence quand l'alerte est déjà partie.
     *
     * Même identifiant de notification que l'avertissement : elle le **remplace** proprement, sans
     * jamais laisser les deux cohabiter.
     *
     * ⚠️ Rien n'y révèle l'identité des contacts, et le canal reste `PRIVATE` : sous contrainte, la
     * notification dit qu'une alerte est partie, jamais à qui.
     */
    fun showSequenceActive(messagesSent: Int, totalMessages: Int) {
        if (!hasPostPermission()) {
            Timber.w("SafetyCallWarningNotifier: POST_NOTIFICATIONS not granted, skipping sequence")
            return
        }
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
        val body = context.getString(
            R.string.safety_call_sequence_body,
            messagesSent,
            totalMessages,
        )
        val notif = NotificationCompat.Builder(
            context,
            NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.safety_call_sequence_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .build()

        @android.annotation.SuppressLint("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIF_ID_SEQUENCE, notif)
        Timber.i(
            "SafetyCallWarningNotifier: posted sequence notice (%d/%d)",
            messagesSent,
            totalMessages,
        )
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

    /**
     * v1.27.2 (audit Codex, C-07 / C-08) — retire la notification de SEQUENCE, distincte de
     * l'avertissement de pre-declenchement.
     *
     * Les deux partageaient un identifiant, ce qui les faisait se remplacer l'une l'autre et
     * obligeait un seul appelant a arbitrer entre elles. Separees, chacune a son proprietaire :
     * l'avertissement au worker, la sequence au reconciliateur de `MainApplication`. Elles ne
     * peuvent de toute facon pas coexister — `isInWarningWindow` rend `false` des que la
     * sequence est ouverte.
     */
    fun dismissSequence() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(NOTIF_ID_SEQUENCE)
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

        /** v1.27.2 — la notification de sequence est INDEPENDANTE de l'avertissement. */
        private const val NOTIF_ID_SEQUENCE = 0x53455151 // 'SEQQ'
        private const val REQUEST_SAFETY_CALL_RESET = 0x52455354    // 'REST'
    }
}
