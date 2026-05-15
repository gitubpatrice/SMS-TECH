package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.SmsSender
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var mirror: ConversationMirror
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_SMS_SENT) return
        val localId = intent.getLongExtra(SmsSender.EXTRA_LOCAL_ID, -1L)
        if (localId < 0) return
        val rc = resultCode
        val pending = goAsync()
        scope.launch {
            try {
                if (rc == Activity.RESULT_OK) {
                    mirror.updateOutgoingStatus(localId, MessageStatus.SENT)
                } else {
                    Timber.w("SMS sent failed for id=%d resultCode=%d", localId, rc)
                    mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = rc)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
