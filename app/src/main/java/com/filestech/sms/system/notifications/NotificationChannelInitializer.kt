package com.filestech.sms.system.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannelInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureDefaultChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return
        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(LABEL_INCOMING_RES),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(DESC_INCOMING_RES)
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        // v1.8.0 (bug 3 fix) — canal SILENT pour le mode
        // `NotificationStyle.SILENT` (anciennement dead field). IMPORTANCE_LOW
        // garantit qu'aucun heads-up ne s'affiche et le son est masqué côté OS.
        // Routage côté [IncomingMessageNotifier.notifyIncoming] selon
        // `settings.notifications.style`. Le canal reste séparé pour que
        // l'utilisateur puisse régler indépendamment ses préférences son /
        // vibration depuis le système (Paramètres → Apps → SMS Tech →
        // Notifications → "Messages entrants" vs "Messages entrants silencieux").
        val incomingSilent = NotificationChannel(
            CHANNEL_INCOMING_SILENT,
            context.getString(LABEL_INCOMING_SILENT_RES),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(DESC_INCOMING_SILENT_RES)
            enableLights(false)
            enableVibration(false)
            setShowBadge(true)
        }
        val sent = NotificationChannel(
            CHANNEL_SENT,
            context.getString(LABEL_SENT_RES),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(DESC_SENT_RES)
            setShowBadge(false)
        }
        val failed = NotificationChannel(
            CHANNEL_FAILED,
            context.getString(LABEL_FAILED_RES),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(DESC_FAILED_RES)
            enableLights(true)
            enableVibration(true)
        }
        val background = NotificationChannel(
            CHANNEL_BACKGROUND,
            context.getString(LABEL_BACKGROUND_RES),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = context.getString(DESC_BACKGROUND_RES) }
        // v1.9.0 audit fix C3 — canal dédié au warning Safety Call. Sans ça,
        // le warning partageait `CHANNEL_INCOMING` avec les SMS reçus →
        // l'user ne pouvait pas régler son/vibration séparément.
        val safetyCallWarning = NotificationChannel(
            CHANNEL_SAFETY_CALL_WARNING,
            context.getString(LABEL_SAFETY_CALL_WARNING_RES),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(DESC_SAFETY_CALL_WARNING_RES)
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        // 🔴 v1.27.3 — CANAL DÉDIÉ AU REÇU DE FIN DE SÉQUENCE, EN IMPORTANCE BASSE.
        //
        // Constaté par Patrice sur son S24 le 2026-08-06 : la notification de fin de séquence
        // **sonnait** à répétition, plusieurs heures après que l'alerte était partie, et il l'a prise
        // deux fois pour un nouveau déclenchement.
        //
        // La cause exacte de la republication n'est pas établie — un `setOnlyAlertOnce(true)` devrait
        // rendre une mise à jour muette, mais Samsung retire les notifications d'une application mise
        // en sommeil, et l'application en poste alors une VRAIE nouvelle au réveil. Ce canal-ci rend
        // le correctif **indépendant du diagnostic** : sur API 26+ le son est réglé par canal, donc
        // `setSilent()` sur le constructeur n'aurait rien changé.
        //
        // Et c'est la bonne conception de toute façon : une séquence terminée est un **reçu**, pas une
        // alerte. L'avertissement de pré-déclenchement et la séquence en cours doivent réveiller —
        // ils demandent une action. Celui-ci informe, et n'a rien à interrompre.
        val safetyCallReceipt = NotificationChannel(
            CHANNEL_SAFETY_CALL_RECEIPT,
            context.getString(LABEL_SAFETY_CALL_RECEIPT_RES),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(DESC_SAFETY_CALL_RECEIPT_RES)
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        // v1.12.0 — canal dédié pour le raccourci d'urgence (notification
        // persistante posée si l'user active "Bouton URGENCE dans les notifs").
        // IMPORTANCE_LOW : pas de son, pas de heads-up, pas de vibration —
        // c'est un raccourci silencieux, pas une alerte. setShowBadge(false)
        // pour ne pas polluer le badge launcher.
        val emergencyShortcut = NotificationChannel(
            CHANNEL_EMERGENCY_SHORTCUT,
            context.getString(LABEL_EMERGENCY_SHORTCUT_RES),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(DESC_EMERGENCY_SHORTCUT_RES)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannels(
            listOf(
                incoming,
                incomingSilent,
                sent,
                failed,
                background,
                safetyCallWarning,
                safetyCallReceipt,
                emergencyShortcut,
            ),
        )
    }

    companion object {
        const val CHANNEL_INCOMING = "incoming_messages"
        const val CHANNEL_INCOMING_SILENT = "incoming_messages_silent"
        const val CHANNEL_SENT = "sent_messages"
        const val CHANNEL_FAILED = "failed_messages"
        const val CHANNEL_BACKGROUND = "background_tasks"

        /** v1.9.0 — canal dédié au warning Safety Call (avant trigger). */
        const val CHANNEL_SAFETY_CALL_WARNING = "safety_call_warning"

        /**
         * v1.27.3 — canal du **reçu** de fin de séquence, en importance basse.
         *
         * Distinct de [CHANNEL_SAFETY_CALL_WARNING], qui doit réveiller parce qu'il demande une
         * action. Une séquence terminée n'a rien à interrompre : elle informe.
         */
        const val CHANNEL_SAFETY_CALL_RECEIPT = "safety_call_receipt"

        /** v1.12.0 — canal pour le raccourci d'urgence (action URGENCE + action 112). */
        const val CHANNEL_EMERGENCY_SHORTCUT = "emergency_shortcut"

        private val LABEL_INCOMING_RES = com.filestech.sms.R.string.channel_incoming_label
        private val DESC_INCOMING_RES = com.filestech.sms.R.string.channel_incoming_desc
        private val LABEL_INCOMING_SILENT_RES = com.filestech.sms.R.string.channel_incoming_silent_label
        private val DESC_INCOMING_SILENT_RES = com.filestech.sms.R.string.channel_incoming_silent_desc
        private val LABEL_SENT_RES = com.filestech.sms.R.string.channel_sent_label
        private val DESC_SENT_RES = com.filestech.sms.R.string.channel_sent_desc
        private val LABEL_FAILED_RES = com.filestech.sms.R.string.channel_failed_label
        private val DESC_FAILED_RES = com.filestech.sms.R.string.channel_failed_desc
        private val LABEL_BACKGROUND_RES = com.filestech.sms.R.string.channel_background_label
        private val DESC_BACKGROUND_RES = com.filestech.sms.R.string.channel_background_desc
        private val LABEL_SAFETY_CALL_WARNING_RES = com.filestech.sms.R.string.channel_safety_call_warning_label
        private val DESC_SAFETY_CALL_WARNING_RES = com.filestech.sms.R.string.channel_safety_call_warning_desc
        private val LABEL_SAFETY_CALL_RECEIPT_RES = com.filestech.sms.R.string.channel_safety_call_receipt_label
        private val DESC_SAFETY_CALL_RECEIPT_RES = com.filestech.sms.R.string.channel_safety_call_receipt_desc
        private val LABEL_EMERGENCY_SHORTCUT_RES = com.filestech.sms.R.string.channel_emergency_shortcut_label
        private val DESC_EMERGENCY_SHORTCUT_RES = com.filestech.sms.R.string.channel_emergency_shortcut_desc
    }
}
