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
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.core.ext.stripMmsAddressSuffix
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
 * la perte.
 *
 * 2026-11-06 — ⚠️ le texte promettait « Tap to open MMS settings » et « Check signal coverage and
 * retry ». Il n'existe AUCUN réglage MMS dans l'application, et aucun nouvel essai n'est possible :
 * l'adresse du message n'est gardée nulle part. Une utilisatrice a cherché ce réglage en vain. La
 * notification dit désormais ce qui s'est passé, et son tap ouvre une fenêtre qui explique quoi faire
 * ([MmsFailureExplanationHolder]).
 *
 * Conception :
 *   - Channel réutilisé : [NotificationChannelInitializer.CHANNEL_FAILED] (déjà existant pour
 *     les échecs d'envoi outgoing). Sémantique cohérente côté user : "un message n'a pas pu
 *     transiter, voici pourquoi".
 *   - Tap → MainActivity, action [ACTION_EXPLAIN_MMS_FAILURE] : la fenêtre d'explication du [Reason].
 *     Pas de conversation à ouvrir : le MMS n'existe pas en Room (zéro row, exprès pour ne pas créer
 *     de fantôme).
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

    /**
     * Le motif décide du texte, de la fenêtre et de son bouton. 2026-11-06 — deux motifs ajoutés, parce qu'un
     * seul « échec de téléchargement » disait faux dans deux cas : un média non ENREGISTRÉ (le message est
     * pourtant arrivé), et un refus du MMSC (message expiré ou introuvable : rien à voir avec le réseau).
     * ⚠️ Ajouter en FIN : l'ordinal entre dans l'identifiant de la notification et de son intent.
     */
    enum class Reason {
        /** Plus gros que ce que l'application peut recevoir. */
        TOO_LARGE,

        /** Le téléchargement n'a pas abouti : réseau, MMSC injoignable, réponse inexploitable. */
        DOWNLOAD_FAILED,

        /** Le message est écrit, mais un de ses fichiers n'a pas pu l'être (stockage) ; la reprise réessaie. */
        MEDIA_NOT_SAVED,

        /** Le MMSC a répondu que le message ne peut pas être remis (`X-Mms-Retrieve-Status` d'échec). */
        CARRIER_ERROR,
    }

    /**
     * 2026-11-06 — ce qu'Android a dit d'un téléchargement manqué : son code de résultat
     * (`SmsManager.MMS_ERROR_*`) et, pour une erreur HTTP, le statut rendu par le MMSC. Un utilisateur
     * américain (Visible, réseau Verizon) voit échouer chaque MMS reçu ; la fenêtre d'explication lui montre
     * ces deux nombres, qu'il peut nous transmettre sans adb.
     *
     * Rien que des entiers : la fenêtre les place dans une phrase fixe. Ne s'obtient d'un intent que par
     * [valide].
     */
    data class ErreurAndroid(val code: Int, val statutHttp: Int? = null) {
        companion object {
            /** Les codes publics `SmsManager.MMS_ERROR_*` vont de 1 à 12 (API 37) ; la marge couvre les suivants. */
            private val CODES_ANDROID = 1..99

            /** Les statuts HTTP, de 100 à 599. */
            private val STATUTS_HTTP = 100..599

            /**
             * L'erreur, ou `null`. `MainActivity` est exportée (rôle SMS) : une autre application peut lui
             * envoyer n'importe quels extras. Seuls passent un entier dans [CODES_ANDROID] et, avec lui, un
             * entier dans [STATUTS_HTTP] ; hors plage, le statut seul est écarté. Seul [Reason.DOWNLOAD_FAILED]
             * naît d'un code de résultat d'Android : pour tout autre motif, rien.
             */
            fun valide(reason: Reason, code: Int?, statutHttp: Int?): ErreurAndroid? {
                if (reason != Reason.DOWNLOAD_FAILED || code == null || code !in CODES_ANDROID) return null
                return ErreurAndroid(code, statutHttp?.takeIf { it in STATUTS_HTTP })
            }
        }
    }

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
     *
     * @param codeAndroid 2026-11-06 — le code de résultat rendu par Android pour un téléchargement manqué.
     * @param codeHttp 2026-11-06 — le statut HTTP du MMSC qui l'accompagne, quand Android le joint.
     *   Tous deux passent par [ErreurAndroid.valide] avant d'entrer dans l'intent du tap.
     */
    suspend fun notifyFailure(
        reason: Reason,
        senderAddress: String?,
        sizeBytes: Long? = null,
        codeAndroid: Int? = null,
        codeHttp: Int? = null,
    ) {
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
            // 2026-11-06 — l'adresse vient du PDU, donc de l'extérieur : sans le suffixe de passerelle
            // `/TYPE=PLMN`, qui s'affichait tel quel, ni caractère invisible (Bidi, RLO), comme
            // l'expéditeur d'un MMS reçu (audit SEC-08, `LecteurRetrieveConf.lire`).
            // Les invisibles d'abord : un RLO glissé DANS `/TYPE=PLMN` empêcherait de reconnaître le suffixe.
            CorrespondentVisibilityPolicy.Verdict.NOMMER ->
                senderAddress.orEmpty().stripInvisibleChars().stripMmsAddressSuffix()
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
            Reason.MEDIA_NOT_SAVED -> context.getString(R.string.mms_failure_notification_body_media_not_saved, sender)
            Reason.CARRIER_ERROR -> context.getString(R.string.mms_failure_notification_body_carrier_error, sender)
        }
        // 2026-11-06 — le tap ouvre la fenêtre qui explique CE motif. L'intent ne porte que le motif et, pour un
        // téléchargement manqué, les codes d'Android : ni expéditeur ni taille, rien que la fenêtre aurait à
        // cacher en session leurre.
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_EXPLAIN_MMS_FAILURE
            putExtra(EXTRA_REASON, reason.name)
            ErreurAndroid.valide(reason, codeAndroid, codeHttp)?.let { erreur ->
                putExtra(EXTRA_CODE_ANDROID, erreur.code)
                erreur.statutHttp?.let { putExtra(EXTRA_STATUT_HTTP, it) }
            }
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

        /** 2026-11-06 — tap de la notification : `MainActivity` ouvre la fenêtre d'explication. */
        const val ACTION_EXPLAIN_MMS_FAILURE: String = "com.filestech.sms.action.EXPLAIN_MMS_FAILURE"

        /** Le [Reason], par son nom. */
        const val EXTRA_REASON: String = "com.filestech.sms.extra.MMS_FAILURE_REASON"

        /**
         * Le [Reason] porté par l'intent, ou `null`. `MainActivity` est exportée (rôle SMS) : une autre
         * application peut lui envoyer cette action avec n'importe quelle valeur. Une valeur inconnue ne
         * produit rien ; une valeur connue n'affiche qu'un texte fixe, sans donnée ni écriture — c'est
         * pourquoi ce chemin, à la différence de l'ouverture d'une conversation, ne porte pas de jeton.
         * 2026-11-06 — au plus deux entiers bornés s'y ajoutent, cf. [erreurDepuisExtras].
         */
        fun raisonDepuisExtra(valeur: String?): Reason? = Reason.entries.firstOrNull { it.name == valeur }

        /** 2026-11-06 — [ErreurAndroid.code], en entier. */
        const val EXTRA_CODE_ANDROID: String = "com.filestech.sms.extra.MMS_FAILURE_ANDROID_CODE"

        /** 2026-11-06 — [ErreurAndroid.statutHttp], en entier. */
        const val EXTRA_STATUT_HTTP: String = "com.filestech.sms.extra.MMS_FAILURE_HTTP_STATUS"

        /**
         * 2026-11-06 — l'[ErreurAndroid] portée par l'intent, ou `null`. Même méfiance que [raisonDepuisExtra] :
         * `getIntExtra` rend 0, hors de toute plage, pour un extra absent ou d'un autre type (une chaîne
         * forgée ne s'affiche donc jamais), et [ErreurAndroid.valide] écarte le reste.
         */
        fun erreurDepuisExtras(reason: Reason, intent: Intent): ErreurAndroid? = ErreurAndroid.valide(
            reason = reason,
            code = intent.getIntExtra(EXTRA_CODE_ANDROID, 0),
            statutHttp = intent.getIntExtra(EXTRA_STATUT_HTTP, 0),
        )
    }
}
