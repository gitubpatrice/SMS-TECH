package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Fires when an SMS is delivered to this app (as the default SMS app).
 *
 * Responsibility:
 *  1. Reconstruct SmsMessage(s) from the PDU array
 *  2. Drop if the sender is in our blocklist
 *  3. Insert into the system inbox (the OS doesn't do it for us anymore — default SMS app's job)
 *  4. Mirror to Room
 *  5. Trigger a notification
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {

    @Inject lateinit var telephonyReader: TelephonyReader
    @Inject lateinit var mirror: ConversationMirror
    @Inject lateinit var blockedRepo: BlockedNumberRepository
    @Inject lateinit var notifier: IncomingMessageNotifier
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        scope.launch {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: emptyArray()
                if (messages.isEmpty()) return@launch
                val address = messages.first().displayOriginatingAddress?.stripInvisibleChars()
                    ?: return@launch
                val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                // Audit F22: strip bidi overrides + zero-width chars that would let a spam SMS
                // spoof its visible origin or sneak past content moderation.
                val body = buildString {
                    messages.forEach { sm ->
                        append(sm.displayMessageBody.orEmpty())
                    }
                }.stripInvisibleChars()
                if (blockedRepo.isBlocked(address)) {
                    Timber.i("Dropping incoming SMS from blocked sender")
                    return@launch
                }
                val uri = telephonyReader.insertInboxSms(address, body, ts)
                val msgId = mirror.upsertIncomingSms(
                    address = address,
                    body = body,
                    date = ts,
                    telephonyUri = uri?.toString(),
                )
                notifier.notifyIncomingSms(address = address, body = body, messageId = msgId)
            } catch (t: Throwable) {
                Timber.w(t, "SmsDeliverReceiver failed")
            } finally {
                pending.finish()
            }
        }
    }
}
