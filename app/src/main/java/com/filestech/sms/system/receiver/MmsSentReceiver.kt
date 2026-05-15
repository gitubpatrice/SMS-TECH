package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Receives the dispatch result of an outgoing MMS sent via [SmsManager.sendMultimediaMessage].
 *
 * Three responsibilities:
 *  1. Update the matching Room row to SENT or FAILED based on the resultCode.
 *  2. Flip the system-provider outbox row to SENT (or delete it on failure) so the MMS shows
 *     up correctly in other SMS apps AND survives an SMS Tech reinstall — Samsung One UI's
 *     `SmsManager.sendMultimediaMessage` does NOT writeback to `content://mms` on its own.
 *  3. Delete the transient PDU file we wrote in the cache (the OS no longer needs it).
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var mirror: ConversationMirror
    @Inject lateinit var systemWriteback: MmsSystemWriteback
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsSender.ACTION_MMS_SENT) return
        // v1.2.3 audit F5: defense-in-depth. The PendingIntent is built with explicit
        // `setClass()` and the receiver is `exported = false`, so an external broadcast
        // already cannot reach us. We still verify that the resolved component package
        // matches ours — guards against any future drift where the receiver gets exported
        // accidentally and a forged broadcast could mutate row state via mmsSystemId.
        val component = intent.component
        if (component != null && component.packageName != context.packageName) {
            Timber.w("MmsSentReceiver: rejecting broadcast for foreign package %s", component.packageName)
            return
        }
        val localId = intent.getLongExtra(MmsSender.EXTRA_LOCAL_ID, -1L)
        val pduPath = intent.getStringExtra(MmsSender.EXTRA_PDU_FILE)
        val mmsSystemId = intent.getLongExtra(MmsSender.EXTRA_MMS_SYSTEM_ID, -1L)
        val rc = resultCode
        val pending = goAsync()
        scope.launch {
            try {
                if (localId >= 0) {
                    if (rc == Activity.RESULT_OK) {
                        mirror.updateOutgoingStatus(localId, MessageStatus.SENT)
                        if (mmsSystemId > 0L) {
                            runCatching { systemWriteback.markSent(mmsSystemId) }
                                .onFailure { Timber.w(it, "markSent(%d) failed", mmsSystemId) }
                        }
                    } else {
                        Timber.w("MMS sent failed for id=%d resultCode=%d", localId, rc)
                        mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = rc)
                        if (mmsSystemId > 0L) {
                            runCatching { systemWriteback.delete(mmsSystemId) }
                                .onFailure { Timber.w(it, "delete(%d) failed", mmsSystemId) }
                        }
                    }
                }
                // The OS keeps a copy of the PDU through its own pipeline; ours is no longer needed.
                pduPath?.let { runCatching { File(it).delete() } }
            } finally {
                pending.finish()
            }
        }
    }
}
