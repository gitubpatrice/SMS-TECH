package com.filestech.sms.system.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.usecase.SendSmsUseCase
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles inline notification actions (reply / mark-read).
 *
 * **Audit F7 mitigation**: actions are refused while the app lock is held — typing a reply from
 * a locked phone shouldn't ship an SMS in the user's name. The notification visibility settings
 * already restrict who can see the body, but the *write* path is the dangerous side.
 *
 * **Audit F38 mitigation**: the notification id used by [NotificationManagerCompat.cancel] now
 * comes from a dedicated extra ([EXTRA_NOTIFICATION_ID]) populated by [IncomingMessageNotifier].
 * The `msgId.toInt().or(1)` hack caused collisions between two consecutive message ids.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var sendSms: SendSmsUseCase
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var appLock: AppLockManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val pending = goAsync()
        scope.launch {
            try {
                // Audit P-P0-5: previously the lock check ran synchronously on the main thread,
                // which forced [MainApplication.onCreate] to `runBlocking` on a DataStore read
                // (50-200 ms) to keep this branch correct on cold start. Now we lazily wait for
                // the resolution inside the receiver's own coroutine context — the cost is paid
                // once per process and the main thread is freed entirely.
                appLock.ensureResolved()
                if (!appLock.isOpenForUi(appLock.state.value)) {
                    Timber.i("NotificationActionReceiver: refused while app is locked")
                    return@launch
                }
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(IncomingMessageNotifier.KEY_REPLY)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: return@launch
                        sendSms.invoke(listOf(PhoneAddress.of(address)), text)
                        if (notificationId >= 0) NotificationManagerCompat.from(context).cancel(notificationId)
                    }
                    ACTION_MARK_READ -> {
                        val conv = conversationRepo.findOrCreate(listOf(PhoneAddress.of(address)))
                        if (conv is Outcome.Success) {
                            conversationRepo.markRead(conv.value.id)
                        }
                        if (notificationId >= 0) NotificationManagerCompat.from(context).cancel(notificationId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.filestech.sms.action.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.filestech.sms.action.NOTIF_MARK_READ"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
