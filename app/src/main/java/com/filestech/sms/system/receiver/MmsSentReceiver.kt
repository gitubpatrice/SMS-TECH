package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsSender
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
 * Two responsibilities:
 *  1. Update the matching Room row to SENT or FAILED based on the resultCode.
 *  2. Delete the transient PDU file we wrote in the cache (the OS no longer needs it).
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var mirror: ConversationMirror
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsSender.ACTION_MMS_SENT) return
        val localId = intent.getLongExtra(MmsSender.EXTRA_LOCAL_ID, -1L)
        val pduPath = intent.getStringExtra(MmsSender.EXTRA_PDU_FILE)
        val rc = resultCode
        val pending = goAsync()
        scope.launch {
            try {
                if (localId >= 0) {
                    if (rc == Activity.RESULT_OK) {
                        mirror.updateOutgoingStatus(localId, MessageStatus.SENT)
                    } else {
                        Timber.w("MMS sent failed for id=%d resultCode=%d", localId, rc)
                        mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = rc)
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
