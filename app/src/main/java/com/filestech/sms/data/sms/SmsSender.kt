package com.filestech.sms.data.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the Android [SmsManager] API. Caller is expected to be the default SMS app.
 *
 * Pending intent extras encode the local Room message id so receivers can update the DB.
 */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
    ): Outcome<Unit> {
        return try {
            val manager = subscriptionAwareManager(subId)
            val parts = manager.divideMessage(text)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sentIntents += buildPendingIntent(ACTION_SMS_SENT, localMessageId, i, parts.size)
                deliveredIntents += buildPendingIntent(ACTION_SMS_DELIVERED, localMessageId, i, parts.size)
            }
            manager.sendMultipartTextMessage(
                destination,
                /* scAddress = */ null,
                parts,
                sentIntents,
                if (requestDeliveryReport) deliveredIntents else null,
            )
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Outcome.Failure(AppError.Telephony("sendMultipartTextMessage failed", t))
        }
    }

    /**
     * Resolves the right [SmsManager] for the given [subId] (multi-SIM support). On API ≥ 31
     * (S) we route through the system-service factory; on older releases we fall back to the
     * legacy static helpers. `SmsManager.getDefault()` itself is deprecated everywhere, so the
     * `subId == null` branch also prefers the system-service form when available.
     */
    private fun subscriptionAwareManager(subId: Int?): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sm = context.getSystemService(SmsManager::class.java)
            return if (subId == null) sm else sm.createForSubscriptionId(subId)
        }
        return when {
            subId == null -> @Suppress("DEPRECATION") SmsManager.getDefault()
            else -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                } else subId,
            )
        }
    }

    private fun buildPendingIntent(action: String, localId: Long, partIndex: Int, total: Int): PendingIntent {
        // Audit F36: derive the request code from a 64-bit mix so two distinct (localId, partIndex)
        // pairs cannot collide on Int wraparound when localId > Int.MAX_VALUE / MAX_PARTS.
        //
        // Audit P0-2 (v1.2.0): Intent **explicit** via `setClass`. The previous `setPackage`-only
        // implicit form was silently dropped on Android 14+ — the SmsSent / SmsDelivered receivers
        // never fired → outgoing SMS rows stuck in PENDING forever (only the 15 min watchdog in
        // TelephonySyncWorker promoted them to FAILED, hiding the real bug). Explicit targeting
        // also removes any need for an `<intent-filter>` on the receiver, keeping it `exported=false`.
        val targetClass = when (action) {
            ACTION_SMS_SENT -> com.filestech.sms.system.receiver.SmsSentReceiver::class.java
            ACTION_SMS_DELIVERED -> com.filestech.sms.system.receiver.SmsDeliveredReceiver::class.java
            else -> throw IllegalArgumentException("unknown action: $action")
        }
        val intent = Intent(action).setClass(context, targetClass).apply {
            putExtra(EXTRA_LOCAL_ID, localId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_PART_COUNT, total)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val mix = (localId xor (localId ushr 32)) * MAX_PARTS + partIndex.toLong()
        val request = (mix xor action.hashCode().toLong()).toInt() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(context, request, intent, flags)
    }

    companion object {
        const val ACTION_SMS_SENT = "com.filestech.sms.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.filestech.sms.action.SMS_DELIVERED"
        const val EXTRA_LOCAL_ID = "com.filestech.sms.extra.LOCAL_ID"
        const val EXTRA_PART_INDEX = "com.filestech.sms.extra.PART_INDEX"
        const val EXTRA_PART_COUNT = "com.filestech.sms.extra.PART_COUNT"
        private const val MAX_PARTS = 256L
    }
}
