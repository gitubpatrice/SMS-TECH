package com.filestech.sms.system.notifications

import android.app.NotificationManager
import android.content.Context
import com.filestech.sms.domain.notification.AllNotificationsCanceller
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.6 — implémentation de [AllNotificationsCanceller] : un seul appel système.
 *
 * `NotificationManager.cancelAll()` et non une boucle sur `activeNotifications` filtrée par tag,
 * comme le fait [IncomingMessageNotifier.cancelAllForConversation] : ici on veut justement ce
 * qu'aucun filtre ne connaît — les notifications des autres notificateurs, et celles qu'un
 * composant futur posterait sans que personne ne pense à cette liste.
 *
 * L'échec est journalisé et avalé : la purge ne s'interrompt pas parce que le volet résiste.
 */
@Singleton
class AllNotificationsCancellerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AllNotificationsCanceller {

    override fun cancelAll() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            ?: return
        runCatching { manager.cancelAll() }
            .onFailure { Timber.w(it, "wipe: annulation des notifications") }
    }
}
