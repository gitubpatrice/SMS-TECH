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
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Audit R1/R2 (v1.14.8) — Notification d'échec de réception MMS.
 *
 * Avant : un MMS dont la taille dépassait le cap (1 MB par défaut) OU dont le download
 * échouait (timeout MMSC, PDU corrompu, perte signal) était simplement loggué et silencieusement
 * ignoré. L'utilisateur n'avait aucune information qu'un contact lui avait écrit. Désormais,
 * une notification non-intrusive (canal FAILED, IMPORTANCE_DEFAULT) est postée pour signaler
 * la perte et inviter à un retry contextuel (signal, settings MMS opérateur).
 *
 * Conception :
 *   - Channel réutilisé : [NotificationChannelInitializer.CHANNEL_FAILED] (déjà existant pour
 *     les échecs d'envoi outgoing). Sémantique cohérente côté user : "un message n'a pas pu
 *     transiter, voici pourquoi".
 *   - Tap → MainActivity (default landing) — pas de route deep-link spécifique car le MMS
 *     n'existe pas en Room (zéro row, exprès pour ne pas créer de fantôme).
 *   - Notification IDs stables par (kind, addressHash) pour qu'un MMS retry produise une mise
 *     à jour de la notif existante plutôt qu'une seconde notif identique.
 *   - Respect runtime POST_NOTIFICATIONS (API 33+) — silent no-op si refusé.
 */
@Singleton
class MmsFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    // v1.28.3 (F08) — la politique de confidentialite des notifications, celle-la meme que
    // [IncomingMessageNotifier]. Elle vivait ici en copie privee ; elle est desormais UNE, parce
    // qu'un troisieme notificateur allait en demander une troisieme. Cf.
    // [CorrespondentVisibilityPolicy].
    private val visibilite: CorrespondentVisibilityPolicy,
) {

    enum class Reason { TOO_LARGE, DOWNLOAD_FAILED }

    /**
     * v1.28.3 (F08) — **applique enfin la politique de confidentialite des notifications.**
     *
     * Cette notification construisait son texte avec l'adresse BRUTE de l'expediteur et la
     * posait sans consulter quoi que ce soit : ni `previewMode`, ni `in_vault`, ni la session
     * leurre. Son jumeau entrant, [IncomingMessageNotifier], applique les trois depuis des
     * versions. Ce qui fuitait n'est pas le corps — le MMS n'a justement pas ete telecharge —
     * mais l'IDENTITE du correspondant, sur l'ecran de verrouillage, pour quelqu'un qui avait
     * explicitement demande que les apercus n'y figurent pas.
     *
     * Les trois gardes, dans l'ordre ou ils comptent :
     *
     *  - **session leurre** : rien du tout. Une notification nommant un correspondant du coffre
     *    trahirait devant l'agresseur ce que le mode leurre existe pour cacher ;
     *  - **conversation du coffre** : rien non plus. On ne connait ici qu'une adresse, alors le
     *    rapprochement se fait par elle ; en cas d'ECHEC de lecture on ne notifie pas — meme
     *    repli sur que l'audit H16 a pose sur le jumeau entrant, pour la meme raison : le cout
     *    du repli sur est une notification manquee, celui du repli permissif est le nom d'un
     *    correspondant protege sur un ecran verrouille ;
     *  - **`previewMode`** : l'expediteur devient generique des que l'utilisateur a demande que
     *    les apercus soient masques. Le motif de l'echec, lui, reste visible — c'est
     *    l'information utile, et elle ne designe personne.
     */
    suspend fun notifyFailure(reason: Reason, senderAddress: String?, sizeBytes: Long? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) return
        }
        val anonyme = context.getString(R.string.mms_failure_notification_body_unknown_sender)
        val sender = when (visibilite.verdictPour(senderAddress)) {
            CorrespondentVisibilityPolicy.Verdict.TAIRE -> return
            CorrespondentVisibilityPolicy.Verdict.ANONYMISER -> anonyme
            CorrespondentVisibilityPolicy.Verdict.NOMMER -> senderAddress.orEmpty()
        }
        val body = when (reason) {
            Reason.TOO_LARGE -> context.getString(
                R.string.mms_failure_notification_body_too_large,
                sender,
                ((sizeBytes ?: 0L) / 1024L).toInt(),
            )
            Reason.DOWNLOAD_FAILED -> context.getString(
                R.string.mms_failure_notification_body_download_failed,
                sender,
            )
        }
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            // ID unique par (reason, sender) pour qu'un retry update la notif au lieu d'en
            // empiler une nouvelle. `absoluteValue` car Int.MIN_VALUE.absoluteValue est négatif.
            (reason.ordinal * 31 + (senderAddress?.hashCode() ?: 0)).absoluteValue,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(
            context, NotificationChannelInitializer.CHANNEL_FAILED,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(context.getString(R.string.mms_failure_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // v1.28.3 (audit global D-02) — le jumeau entrant suivait `previewMode` sur l'écran
            // verrouillé depuis SEC-01 ; celui-ci laissait le défaut du framework.
            .setVisibility(visibilite.ecranVerrouille().versNotificationCompat())
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID_BASE + (reason.ordinal * 31 + (senderAddress?.hashCode() ?: 0)).absoluteValue % 10_000,
                notif,
            )
        }
    }

    companion object {
        // Plage dédiée [80_000 .. 89_999] — évite les collisions avec les autres notifiers
        // (incoming = 1xx, sent = 2xx, safety call = 5xx, emergency = 7xx).
        private const val NOTIF_ID_BASE = 80_000
    }
}
