package com.filestech.sms.system.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.3 — **un SMS sortant qui échoue le disait à personne.**
 *
 * # Le trou, mesuré
 *
 * [MmsFailureNotifier] existe pour un MMS qu'on n'a pas pu recevoir, [IncomingMessageNotifier]
 * pour un message reçu. Rien, nulle part, pour un SMS **envoyé** qui échoue :
 * `SmsSentReceiver` écrivait le statut `FAILED` et s'arrêtait là. Un utilisateur dont le message
 * échoue application fermée ne l'apprenait qu'en rouvrant le fil — c'est-à-dire, souvent, jamais,
 * ou trop tard.
 *
 * C'est ce vide que le réglage `retryFailedAutomatically` prétendait combler. Ce réglage ne
 * faisait rien du tout (finding F26) et a été retiré ; le câbler aurait voulu dire renvoyer
 * automatiquement, avec ses doublons facturés et reçus deux fois — exactement ce que le verrou
 * d'envoi refuse par ailleurs (F20). Le besoin réel n'était pas « relancer tout seul », c'était
 * **savoir**. La notification le donne, et l'action « Renvoyer » laisse le geste à l'utilisateur.
 *
 * # Ce qui borne cette notification
 *
 * - **Elle n'est postée que si le statut a réellement changé.** L'appelant ne la déclenche que
 *   lorsque l'écriture monotone a pris. Deux conséquences gratuites : un SMS multi-parties dont
 *   trois accusés d'échec arrivent ne produit qu'UNE notification — les deux suivants n'écrivent
 *   rien —, et l'accusé tardif d'une tentative périmée (F23) n'en produit aucune, puisqu'il
 *   n'écrit rien non plus.
 * - **La politique de confidentialité est celle des autres notificateurs**, pas une quatrième
 *   copie : cf. [CorrespondentVisibilityPolicy]. Session leurre et conversation du coffre ne
 *   produisent rien ; aperçus masqués anonymisent le destinataire.
 * - **L'action « Renvoyer » n'est jamais proposée pour un échec incertain.** Elle ne l'est que
 *   depuis ce chemin-ci, c'est-à-dire un refus de la pile téléphonie, où le message n'est
 *   certainement pas parti. Les échecs du chien de garde — `WATCHDOG_TIMEOUT`, où le message a
 *   PEUT-ÊTRE atteint son destinataire — ne passent pas par ici, et l'écran continue d'exiger une
 *   confirmation pour ceux-là. Une action à une tape ne doit jamais court-circuiter un
 *   avertissement de doublon.
 */
@Singleton
class OutgoingFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val visibilite: CorrespondentVisibilityPolicy,
) {

    /**
     * @param messageId id Room du message en échec — porté par l'action « Renvoyer ».
     * @param destinataire adresse brute, soumise à [CorrespondentVisibilityPolicy].
     */
    suspend fun notifierEchec(messageId: Long, destinataire: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val anonyme = context.getString(R.string.send_failure_notification_unknown_recipient)
        val cible = when (visibilite.verdictPour(destinataire)) {
            CorrespondentVisibilityPolicy.Verdict.TAIRE -> return
            CorrespondentVisibilityPolicy.Verdict.ANONYMISER -> anonyme
            CorrespondentVisibilityPolicy.Verdict.NOMMER -> destinataire.orEmpty()
        }
        val corps = context.getString(R.string.send_failure_notification_body, cible)
        val idNotification = NOTIF_ID_BASE + notificationIdFor(messageId) % 10_000

        val ouvrir = PendingIntent.getActivity(
            context,
            idNotification,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // v1.28.3 — `data` distinct par message, leçon de F19 : `PendingIntent.filterEquals`
        // ignore les extras, donc deux actions « Renvoyer » de deux messages differents seraient
        // le MEME `PendingIntent`, et `FLAG_UPDATE_CURRENT` réécrirait les extras du premier. On
        // relancerait alors le mauvais message.
        val renvoyer = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_RETRY_SEND
            data = android.net.Uri.parse("smstech://notification/retry/$messageId")
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, idNotification)
        }
        val actionRenvoyer = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_message,
            context.getString(R.string.action_retry),
            PendingIntent.getBroadcast(
                context,
                idNotification,
                renvoyer,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        val notif = NotificationCompat.Builder(context, NotificationChannelInitializer.CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.send_failure_notification_title))
            .setContentText(corps)
            .setStyle(NotificationCompat.BigTextStyle().bigText(corps))
            .setContentIntent(ouvrir)
            .addAction(actionRenvoyer)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(idNotification, notif) }
            .onFailure { Timber.w(it, "Notification d'echec d'envoi non postee pour %d", messageId) }
    }

    private companion object {
        /**
         * Plage dédiée, à la manière de [MmsFailureNotifier] : entrant = 1xx, envoyé = 2xx,
         * appel de sécurité = 5xx, urgence = 7xx, échec MMS = 80 000. Celle-ci prend 90 000 pour
         * qu'une notification d'échec d'ENVOI ne remplace jamais celle d'un échec de RÉCEPTION.
         */
        const val NOTIF_ID_BASE = 90_000
    }
}
