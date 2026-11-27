package com.filestech.sms.system.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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
 *   produisent rien ; aperçus masqués anonymisent le destinataire — et, depuis l'audit global
 *   du 2026-09-09 (D-02), décident aussi de ce qui paraît sur l'écran verrouillé, ce que le
 *   premier jet avait laissé au défaut du framework.
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
    // 2026-11-15 (v1.30.1) — le verrou de l'app décide de l'action « Renvoyer », cf. [notifierEchec].
    private val settings: SettingsRepository,
) : com.filestech.sms.domain.notification.NotificationDEchecDEnvoi {

    /**
     * 2026-11-16 (v1.31.0) — retire la notification d'échec de [messageId] : une alerte repartie par l'autre SIM a
     * prévenu ce proche, et la notification aurait dit le contraire, avec un bouton Renvoyer.
     */
    override fun retirer(messageId: Long) {
        val idNotification = NOTIF_ID_BASE + notificationIdFor(messageId) % PLAGE_IDS
        runCatching { NotificationManagerCompat.from(context).cancel(idNotification) }
    }

    /**
     * @param messageId id Room du message en échec — porté par l'action « Renvoyer ».
     * @param destinataire adresse brute, soumise à [CorrespondentVisibilityPolicy].
     * @param conversationId conversation à laquelle la ligne est rattachée, quand on la connaît :
     *   elle fait foi avant l'adresse (audit global A-01).
     */
    suspend fun notifierEchec(messageId: Long, destinataire: String?, conversationId: Long? = null) {
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
        val cible = when (visibilite.verdictPour(destinataire, conversationId)) {
            CorrespondentVisibilityPolicy.Verdict.TAIRE -> return
            CorrespondentVisibilityPolicy.Verdict.ANONYMISER -> anonyme
            CorrespondentVisibilityPolicy.Verdict.NOMMER -> destinataire.orEmpty()
        }
        val corps = context.getString(R.string.send_failure_notification_body, cible)
        val idNotification = NOTIF_ID_BASE + notificationIdFor(messageId) % PLAGE_IDS

        val ouvrir = PendingIntent.getActivity(
            context,
            idNotification,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, NotificationChannelInitializer.CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.send_failure_notification_title))
            .setContentText(corps)
            .setStyle(NotificationCompat.BigTextStyle().bigText(corps))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(visibilite.ecranVerrouille().versNotificationCompat())
            // 2026-11-15 (v1.30.1) — le message (et de quoi refaire le verdict de confidentialité) reste dans la
            // notification, pour refaire l'action quand le verrou de l'app change (cf. [reappliquerActions]).
            .addExtras(
                android.os.Bundle().apply {
                    putLong(CLE_MESSAGE, messageId)
                    destinataire?.let { putString(CLE_DESTINATAIRE, it) }
                    conversationId?.let { putLong(CLE_CONVERSATION, it) }
                },
            )
            .also { b ->
                // Sous verrou, pas de « Renvoyer » : le receveur le refuserait (il envoie un message).
                if (!verrouAppActif(settings.hydratedOrNull())) b.addAction(actionRenvoyer(messageId, idNotification))
            }
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(idNotification, notif) }
            .onFailure { Timber.w(it, "Notification d'echec d'envoi non postee pour %d", messageId) }
    }

    // v1.28.3 — `data` distinct par message, leçon de F19 : `PendingIntent.filterEquals`
    // ignore les extras, donc deux actions « Renvoyer » de deux messages differents seraient
    // le MEME `PendingIntent`, et `FLAG_UPDATE_CURRENT` réécrirait les extras du premier. On
    // relancerait alors le mauvais message.
    private fun actionRenvoyer(messageId: Long, idNotification: Int): NotificationCompat.Action {
        val renvoyer = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_RETRY_SEND
            data = "smstech://notification/retry/$messageId".toUri()
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, idNotification)
        }
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_message,
            context.getString(R.string.action_retry),
            PendingIntent.getBroadcast(
                context,
                idNotification,
                renvoyer,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
    }

    /**
     * 2026-11-15 (v1.30.1) — met l'action « Renvoyer » des notifications d'échec AFFICHÉES en accord avec le verrou
     * de l'app : posée verrou désactivé, elle restait proposée verrou activé, et le receveur la refusait sans un mot.
     * Appelé par `MainApplication` avec la valeur émise, comme `IncomingMessageNotifier.reappliquerActions`.
     */
    suspend fun reappliquerActions(verrouApp: Boolean) = reposer(verrouApp, motif = false) { true }

    /** 2026-11-15 (v1.30.1) — « Renvoyer » vient d'être refusé sous verrou : la notification le dit. */
    suspend fun signalerRefus(idNotification: Int) =
        reposer(verrouAppActif(settings.hydratedOrNull()), motif = true) { it.id == idNotification }

    /**
     * Même règles que `IncomingMessageNotifier.reposer` : copie sans second son, présence revérifiée, une pose par
     * [IncomingMessageNotifier.INTERVALLE_REPOSE_MS] au plus, rien quand rien ne change, ligne de refus effacée
     * sans motif. Le verdict de confidentialité est REFAIT : correspondant passé au Coffre, ou session leurre →
     * la notification est retirée. Une notification d'une version antérieure (sans extras) est seulement durcie :
     * sous verrou, elle perd « Renvoyer ».
     */
    private suspend fun reposer(
        verrouApp: Boolean,
        motif: Boolean,
        cible: (android.service.notification.StatusBarNotification) -> Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val actives = runCatching { nm.activeNotifications.toList() }.getOrDefault(emptyList())
        val sousTexte = if (motif) context.getString(R.string.send_failure_refused_locked) else null
        var reposees = 0
        for (sbn in actives) {
            if (estUnEchecDEnvoi(sbn) && cible(sbn) && reposerUne(nm, sbn, verrouApp, sousTexte, reposees > 0)) {
                reposees++
            }
        }
    }

    /** @return vrai si [sbn] a été reposée. Cf. [reposer]. */
    private suspend fun reposerUne(
        nm: NotificationManager,
        sbn: android.service.notification.StatusBarNotification,
        verrouApp: Boolean,
        sousTexte: String?,
        attendre: Boolean,
    ): Boolean {
        val extras = sbn.notification.extras
        val messageId = extras?.takeIf { it.containsKey(CLE_MESSAGE) }?.getLong(CLE_MESSAGE)
        if (messageId != null && verdictActuel(extras) == CorrespondentVisibilityPolicy.Verdict.TAIRE) {
            runCatching { nm.cancel(sbn.id) }
            return false
        }
        val renvoyerActuel = NotificationCompat.getActionCount(sbn.notification) > 0
        // Ancienne version sans extras : on ne peut que retirer l'action, jamais la refaire.
        val renvoyerVoulu = !verrouApp && (messageId != null || renvoyerActuel)
        val ligneActuelle = NotificationCompat.getSubText(sbn.notification)?.toString()
        if (renvoyerVoulu == renvoyerActuel && ligneActuelle == sousTexte) return false
        if (attendre) delay(IncomingMessageNotifier.INTERVALLE_REPOSE_MS)
        val toujoursAffichee = runCatching { nm.activeNotifications.any { it.key == sbn.key } }.getOrDefault(false)
        if (toujoursAffichee) {
            val b = NotificationCompat.Builder(context, sbn.notification)
                .setOnlyAlertOnce(true)
                .setSubText(sousTexte)
            if (!renvoyerVoulu) b.clearActions()
            if (renvoyerVoulu && messageId != null) b.clearActions().addAction(actionRenvoyer(messageId, sbn.id))
            runCatching { nm.notify(sbn.id, b.build()) }
                .onFailure { Timber.w(it, "Notification d'echec d'envoi non reposee") }
        }
        return toujoursAffichee
    }

    /** Le verdict de confidentialité d'aujourd'hui (Coffre, session leurre) ; illisible : on se tait. */
    private suspend fun verdictActuel(extras: android.os.Bundle): CorrespondentVisibilityPolicy.Verdict {
        val conversationId = extras.takeIf { it.containsKey(CLE_CONVERSATION) }?.getLong(CLE_CONVERSATION)
        return runCatchingCancellable { visibilite.verdictPour(extras.getString(CLE_DESTINATAIRE), conversationId) }
            .getOrDefault(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    /** Une notification d'échec d'ENVOI : sans étiquette, dans la plage [NOTIF_ID_BASE] et sur le canal des échecs. */
    private fun estUnEchecDEnvoi(sbn: android.service.notification.StatusBarNotification): Boolean =
        sbn.tag == null &&
            sbn.id in NOTIF_ID_BASE until NOTIF_ID_BASE + PLAGE_IDS &&
            NotificationCompat.getChannelId(sbn.notification) == NotificationChannelInitializer.CHANNEL_FAILED

    private companion object {
        /**
         * Plage dédiée, à la manière de [MmsFailureNotifier] : entrant = 1xx, envoyé = 2xx,
         * appel de sécurité = 5xx, urgence = 7xx, échec MMS = 80 000. Celle-ci prend 90 000 pour
         * qu'une notification d'échec d'ENVOI ne remplace jamais celle d'un échec de RÉCEPTION.
         */
        const val NOTIF_ID_BASE = 90_000

        /** Taille de la plage : l'identifiant vaut [NOTIF_ID_BASE] + (hash du message modulo cette taille). */
        const val PLAGE_IDS = 10_000

        /** 2026-11-15 — extras d'une notification d'échec d'envoi : de quoi refaire l'action et le verdict. */
        const val CLE_MESSAGE = "com.filestech.sms.notif.echec.message"
        const val CLE_DESTINATAIRE = "com.filestech.sms.notif.echec.destinataire"
        const val CLE_CONVERSATION = "com.filestech.sms.notif.echec.conversation"
    }
}
