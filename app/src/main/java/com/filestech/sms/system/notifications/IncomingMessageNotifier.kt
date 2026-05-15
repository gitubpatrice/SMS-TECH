package com.filestech.sms.system.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.PreviewMode
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ContactRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val contacts: ContactRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun notifyIncomingSms(address: String, body: String, messageId: Long) = withContext(io) {
        // Resolve the contact name so the notification shows "Marie" instead of "+33612345678".
        // Falls back gracefully if READ_CONTACTS is denied or no match exists.
        val senderName = runCatching { contacts.lookupByPhone(address)?.displayName }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: address
        val s = settings.flow.first()
        if (!s.notifications.enabled) return@withContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) return@withContext
        }

        // Audit F15: the legacy "WHEN_UNLOCKED" branch leaked the body in setContentText on some
        // OEMs that disregarded VISIBILITY_PRIVATE. Both WHEN_UNLOCKED and NEVER now ship a
        // placeholder for setContentText; the real body only flows through MessagingStyle, which
        // honours the OS lock-screen redaction.
        val hidePreview = when (s.notifications.previewMode) {
            PreviewMode.ALWAYS -> false
            PreviewMode.WHEN_UNLOCKED, PreviewMode.NEVER -> true
        }
        val redactedPreview = context.getString(R.string.notif_preview_hidden_content)
        val visiblePreview = if (hidePreview) redactedPreview else body

        val person = Person.Builder().setName(senderName).setKey(address).build()
        val notificationId = stableNotificationId(messageId)

        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .setAction("com.filestech.sms.OPEN_CONVERSATION")
                .putExtra("address", address)
                .putExtra("messageId", messageId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, NotificationChannelInitializer.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(senderName)
            .setContentText(visiblePreview)
            .setStyle(
                NotificationCompat.MessagingStyle(person).addMessage(
                    NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), person),
                ),
            )
            .setVisibility(
                when (s.notifications.previewMode) {
                    PreviewMode.WHEN_UNLOCKED -> NotificationCompat.VISIBILITY_PRIVATE
                    PreviewMode.NEVER -> NotificationCompat.VISIBILITY_SECRET
                    PreviewMode.ALWAYS -> NotificationCompat.VISIBILITY_PUBLIC
                },
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .also { b ->
                if (s.notifications.inlineReply) {
                    b.addAction(buildReplyAction(address, messageId, notificationId))
                }
                b.addAction(buildMarkReadAction(address, messageId, notificationId))
            }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notif)
    }

    /**
     * Maps a Room message id to a unique notification id.
     * Fixes audit F38 (the previous `msgId.toInt().or(1)` collided every two messages).
     * Uses XOR to spread bits then OR with [BASE_TAG] to never return 0 (which would be discarded).
     */
    private fun stableNotificationId(messageId: Long): Int {
        val hash = (messageId xor (messageId ushr 32)).toInt() and 0x7FFFFFFF
        return hash or BASE_TAG
    }

    private fun buildReplyAction(
        address: String,
        messageId: Long,
        notificationId: Int,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_label))
            .build()
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            // Audit F8: clamp the intent to our component so even with an action collision
            // (matching string), no other app can receive it.
            component = ComponentName(context, NotificationActionReceiver::class.java)
            action = NotificationActionReceiver.ACTION_REPLY
            `package` = context.packageName
            putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId.xor(REPLY_REQUEST_SALT),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_reply,
            context.getString(R.string.notif_reply_label),
            pi,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()
    }

    private fun buildMarkReadAction(
        address: String,
        messageId: Long,
        notificationId: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            component = ComponentName(context, NotificationActionReceiver::class.java)
            action = NotificationActionReceiver.ACTION_MARK_READ
            `package` = context.packageName
            putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId.xor(MARK_READ_REQUEST_SALT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_check,
            context.getString(R.string.notif_mark_read_label),
            pi,
        ).build()
    }

    fun cancel(messageId: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(stableNotificationId(messageId))
    }

    companion object {
        const val KEY_REPLY = "key_reply_text"
        private const val BASE_TAG = 0x10000 // ensures non-zero notif ids
        private const val REPLY_REQUEST_SALT = 0x52455050 // 'REPL'
        private const val MARK_READ_REQUEST_SALT = 0x4D524541 // 'MREA'
    }
}
