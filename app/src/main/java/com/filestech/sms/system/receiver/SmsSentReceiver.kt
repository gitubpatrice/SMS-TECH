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

    /**
     * v1.28.3 — un SMS sortant qui echoue ne le disait a PERSONNE.
     *
     * `MmsFailureNotifier` existe pour un MMS qu'on n'a pas pu recevoir, `IncomingMessageNotifier`
     * pour un message recu ; rien pour un envoi qui echoue. On ecrivait le statut et on
     * s'arretait la : l'utilisateur ne l'apprenait qu'en rouvrant le fil.
     *
     * `Lazy` comme le miroir : ce receveur est instancie sur le fil principal.
     */
    @Inject lateinit var echecNotifierLazy: dagger.Lazy<
        com.filestech.sms.system.notifications.OutgoingFailureNotifier,
        >

    /** v1.28.3 — pour connaitre le destinataire du message en echec. */
    @Inject lateinit var messageDaoLazy: dagger.Lazy<com.filestech.sms.data.local.db.dao.MessageDao>

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
                    // v1.28.3 — on ne notifie QUE si l'ecriture a reellement pris.
                    //
                    // `updateOutgoingStatus` est conditionnelle a deux titres : monotone, et liee
                    // a une tentative. Deux consequences gratuites, et ce sont exactement les deux
                    // qu'il fallait :
                    //
                    //  - un SMS MULTI-PARTIES dont trois accuses d'echec arrivent ne produit
                    //    qu'UNE notification, les deux suivants n'ecrivant rien ;
                    //  - l'accuse TARDIF d'une tentative perimee (F23) n'en produit aucune, alors
                    //    qu'il aurait annonce en echec un message deja renvoye avec succes.
                    val ecrit = mirror.updateOutgoingStatus(
                        localId,
                        MessageStatus.FAILED,
                        errorCode = rc,
                        attempt = attempt,
                    )
                    if (ecrit) {
                        val destinataire = runCatching { messageDaoLazy.get().findById(localId)?.address }
                            .getOrNull()
                        echecNotifierLazy.get().notifierEchec(localId, destinataire)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
