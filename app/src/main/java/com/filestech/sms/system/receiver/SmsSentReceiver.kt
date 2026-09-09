package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.sms.SmsSenderImpl
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SmsSentReceiver : BroadcastReceiver() {

    // v1.24.0 SEC-CRIT — `Lazy` : ce collaborateur atteint un DAO, donc `AppDatabase`, donc la
    // réparation zéro-clé. L'injection de champ Hilt précède le corps de `onReceive`, sur le main
    // thread : en eager, la reconstruction de la base y tournait sous un timeout ANR de 10 s.
    @Inject lateinit var mirrorLazy: dagger.Lazy<OutgoingMessageMirror>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSenderImpl.ACTION_SMS_SENT) return
        val localId = intent.getLongExtra(SmsSenderImpl.EXTRA_LOCAL_ID, -1L)
        if (localId < 0) return
        // v1.28.3 (F23) — la tentative dont provient cet accuse. Absente des `PendingIntent`
        // crees par une version anterieure et encore en vol : `0` est alors le bon repli, c'est
        // le numero que porte toute ligne existante apres la migration 10 -> 11.
        val attempt = intent.getIntExtra(SmsSenderImpl.EXTRA_ATTEMPT, 0)
        val rc = resultCode
        val pending = goAsync()
        scope.launch {
            try {
                val mirror = mirrorLazy.get()
                if (rc == Activity.RESULT_OK) {
                    mirror.updateOutgoingStatus(localId, MessageStatus.SENT, attempt = attempt)
                } else {
                    Timber.w("SMS sent failed for id=%d attempt=%d resultCode=%d", localId, attempt, rc)
                    mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = rc, attempt = attempt)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
