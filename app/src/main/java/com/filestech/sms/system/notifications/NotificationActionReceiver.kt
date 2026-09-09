package com.filestech.sms.system.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    // v1.24.0 SEC-CRIT — `Lazy` : atteint un DAO donc `AppDatabase` donc la réparation zéro-clé.
    // L'injection de champ Hilt précède le corps, sur le main thread.
    @Inject lateinit var sendSmsLazy: dagger.Lazy<SendSmsUseCase>

    @Inject lateinit var conversationRepoLazy: dagger.Lazy<ConversationRepository>

    /** v1.28.3 — action « Renvoyer » de la notification d'echec d'envoi. `Lazy` : cf. ci-dessus. */
    @Inject lateinit var retrySendLazy: dagger.Lazy<com.filestech.sms.domain.usecase.RetrySendUseCase>

    @Inject lateinit var appLock: AppLockManager

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // v1.28.3 — l'adresse n'est exigee que des actions qui en ont besoin. `ACTION_RETRY_SEND`
        // ne connait qu'un id de message : la sortir en tete l'aurait rendue inatteignable.
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (address == null && intent.action != ACTION_RETRY_SEND) return
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
                // v1.3.3 Z4 audit fix — la fermeture de la notif passe désormais
                // exclusivement par `markRead` → `cancelAllForConversation` (tag-based).
                // `findOrCreate` retourne la conv (la même qu'à la réception) ; `markRead`
                // efface l'unread count + clear toutes les notifs taggées pour cette conv.
                // Pas de cancel manuel ici → cohérence avec le pattern ouverture-depuis-app.
                when (intent.action) {
                    ACTION_REPLY -> {
                        // `address` est nullable depuis que `ACTION_RETRY_SEND`, qui n'en a pas
                        // besoin, partage ce receveur. Les deux actions qui l'exigent la lient ici.
                        val adresse = address ?: return@launch
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(IncomingMessageNotifier.KEY_REPLY)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: return@launch
                        sendSmsLazy.get().invoke(listOf(PhoneAddress.of(adresse)), text)
                        // Marquer comme lu (et donc clear notifs via le notifier câblé
                        // dans markRead). Cohérent : répondre = avoir vu le message.
                        markReadAndCancelNotifs(adresse)
                    }
                    ACTION_MARK_READ -> {
                        markReadAndCancelNotifs(address ?: return@launch)
                    }
                    // v1.28.3 — « Renvoyer » depuis la notification d'echec d'envoi.
                    //
                    // Le geste est celui de la bulle rouge dans le fil, et il passe par le MEME
                    // use case : liste noire respectee, nouvelle tentative ouverte et numerotee
                    // (F23), issue remontee. Rien n'est renvoye automatiquement — c'est tout
                    // l'objet de cette action, et la raison pour laquelle
                    // `retryFailedAutomatically` a ete retire plutot que cable.
                    //
                    // Elle n'est proposee que sur un refus de la pile telephonie, ou le message
                    // n'est CERTAINEMENT pas parti. Les echecs du chien de garde, eux, peuvent
                    // avoir atteint leur destinataire : l'ecran continue d'exiger une
                    // confirmation pour ceux-la, et une action a une tape ne doit jamais
                    // court-circuiter un avertissement de doublon.
                    ACTION_RETRY_SEND -> {
                        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
                        if (messageId <= 0L) return@launch
                        when (val issue = retrySendLazy.get().invoke(messageId)) {
                            is Outcome.Success ->
                                Timber.i("Renvoi demande depuis la notification : message %d", messageId)
                            is Outcome.Failure ->
                                Timber.w("Renvoi refuse pour le message %d : %s", messageId, issue.error)
                        }
                        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                        if (notificationId > 0) {
                            runCatching {
                                androidx.core.app.NotificationManagerCompat.from(context)
                                    .cancel(notificationId)
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun markReadAndCancelNotifs(address: String) {
        val conv = conversationRepoLazy.get().findOrCreate(listOf(PhoneAddress.of(address)))
        if (conv is Outcome.Success) {
            conversationRepoLazy.get().markRead(conv.value.id)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.filestech.sms.action.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.filestech.sms.action.NOTIF_MARK_READ"

        /** v1.28.3 — relance d'un envoi en echec, depuis sa notification. */
        const val ACTION_RETRY_SEND = "com.filestech.sms.action.NOTIF_RETRY_SEND"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
