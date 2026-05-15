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
        nm.createNotificationChannels(listOf(incoming, sent, failed, background))
    }

    companion object {
        const val CHANNEL_INCOMING = "incoming_messages"
        const val CHANNEL_SENT = "sent_messages"
        const val CHANNEL_FAILED = "failed_messages"
        const val CHANNEL_BACKGROUND = "background_tasks"

        private val LABEL_INCOMING_RES = com.filestech.sms.R.string.channel_incoming_label
        private val DESC_INCOMING_RES = com.filestech.sms.R.string.channel_incoming_desc
        private val LABEL_SENT_RES = com.filestech.sms.R.string.channel_sent_label
        private val DESC_SENT_RES = com.filestech.sms.R.string.channel_sent_desc
        private val LABEL_FAILED_RES = com.filestech.sms.R.string.channel_failed_label
        private val DESC_FAILED_RES = com.filestech.sms.R.string.channel_failed_desc
        private val LABEL_BACKGROUND_RES = com.filestech.sms.R.string.channel_background_label
        private val DESC_BACKGROUND_RES = com.filestech.sms.R.string.channel_background_desc
    }
}
