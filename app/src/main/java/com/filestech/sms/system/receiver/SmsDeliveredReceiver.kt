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
import javax.inject.Inject

@AndroidEntryPoint
class SmsDeliveredReceiver : BroadcastReceiver() {

    // v1.24.0 SEC-CRIT — `Lazy` : ce collaborateur atteint un DAO, donc `AppDatabase`, donc la
    // réparation zéro-clé. L'injection de champ Hilt précède le corps de `onReceive`, sur le main
    // thread : en eager, la reconstruction de la base y tournait sous un timeout ANR de 10 s.
    @Inject lateinit var mirrorLazy: dagger.Lazy<ConversationMirror>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_SMS_DELIVERED) return
        val localId = intent.getLongExtra(SmsSender.EXTRA_LOCAL_ID, -1L)
        if (localId < 0) return
        if (resultCode != Activity.RESULT_OK) return
        val pending = goAsync()
        scope.launch {
            try {
                val mirror = mirrorLazy.get()
                mirror.updateOutgoingStatus(localId, MessageStatus.DELIVERED)
            } finally {
                pending.finish()
            }
        }
    }
}
