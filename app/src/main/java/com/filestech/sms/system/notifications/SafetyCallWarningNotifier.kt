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
import com.filestech.sms.domain.safetycall.SafetyCallNotice
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.9.0 — Notification de pré-trigger pour le Safety call.
 *
 * Posée par [com.filestech.sms.system.scheduler.SafetyCallWorker] quand on
 * entre dans la fenêtre de 6h avant expiration ([SafetyCallConfig
 * .WARNING_WINDOW_MS]). Persistante (non swipable), `IMPORTANCE_HIGH` pour
 * qu'elle attire l'attention sans être un canal séparé bruyant.
 *
 * **Audit fix C3** : canal dédié [NotificationChannelInitializer
 * .CHANNEL_SAFETY_CALL_WARNING] (au lieu de partager `CHANNEL_INCOMING` avec
 * les SMS reçus). Permet à l'utilisateur de régler le son / la vibration des
 * warnings indépendamment des notifs SMS normales.
 *
 * **Audit fix SEC-10** : nonce mono-usage [SafetyCallIntentToken] mis en
 * extra `EXTRA_RESET_TOKEN`. `MainActivity` (exported true à cause du rôle
 * SMS) valide le token avant de reset le timer — protège contre une app
 * tierce qui forgerait un intent reset pour neutraliser le deadman.
 *
 * **Tap sur la notif** → ouvre SMS Tech (`MainActivity.ACTION_SAFETY_CALL_RESET`
 * + extra token signé) qui reset le timer après validation.
 *
 * **Dismiss programmatique** : appelé par le worker quand l'user a reset le
 * timer depuis ailleurs (bouton dédié Settings, ou simple ouverture app qui
 * remet `lastActivityAt = now()`). Le tick worker suivant détecte qu'on est
 * hors fenêtre et appelle `dismiss()`.
 */
@Singleton
class SafetyCallWarningNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intentToken: SafetyCallIntentToken,
) {

    /**
     * v1.27.2 (audit Codex du 2026-08-05, P-05) — **le seul point d'entrée** de l'affichage du
     * Safety call.
     *
     * # Le défaut que ce point unique ferme
     *
     * Les deux notifications — avertissement de pré-déclenchement et suivi de séquence — portaient
     * des identifiants distincts et personne ne possédait les deux. À l'expiration, la branche
     * d'envoi ne retirait jamais l'avertissement : la séquence se publiait à côté, et les deux
     * restaient affichées. `isInWarningWindow` devenant faux n'y changeait rien — c'est un
     * prédicat, pas un effet de retrait.
     *
     * 🔴 Pire : à l'entrée en **mode leurre**, seule la séquence était retirée. L'avertissement
     * survivait à la session sous contrainte et révélait à l'agresseur qu'une fonction d'alerte
     * existait — exactement ce que le mode leurre doit rendre invisible.
     *
     * # L'ordre des opérations n'est pas négociable
     *
     * On **retire d'abord**, on publie ensuite. L'inverse laisserait une fraction de seconde avec
     * les deux notifications à l'écran, ce qui rouvrirait le défaut.
     *
     * Les deux publications partagent volontairement le **même code de requête** de
     * [PendingIntent] : il n'existe donc qu'un seul intent vivant à la fois, porteur du nonce
     * courant. Leur donner des codes distincts recréerait deux intents dont l'un serait invalidé
     * par la rotation du nonce de l'autre — le tap de la notification affichée ne réinitialiserait
     * alors plus rien.
     */
    fun reconcile(notice: SafetyCallNotice) {
        when (notice) {
            SafetyCallNotice.None -> {
                dismissWarning()
                dismissSequence()
            }
            is SafetyCallNotice.Warning -> {
                dismissSequence()
                showWarning(notice.hoursLeft)
            }
            is SafetyCallNotice.Sequence -> {
                dismissWarning()
                showSequence(notice)
            }
        }
    }

    /**
     * Affiche / met à jour la notification de warning. Idempotent : si la
     * notif existe déjà, elle est mise à jour avec le nouveau texte (compte
     * à rebours en heures).
     *
     * @param hoursLeft heures restantes avant trigger automatique, déjà arrondies par
     *   [SafetyCallNotice.decide] — c'est la seule granularité que la notification affiche, et
     *   c'est sur elle que le réconciliateur déduplique.
     */
    private fun showWarning(hoursLeft: Int) {
        if (!hasPostPermission()) {
            Timber.w("SafetyCallWarningNotifier: POST_NOTIFICATIONS not granted, skipping warning")
            return
        }
        val title = context.getString(R.string.safety_call_warning_title)
        val body = if (hoursLeft >= 2) {
            context.getString(R.string.safety_call_warning_body_hours, hoursLeft)
        } else {
            context.getString(R.string.safety_call_warning_body_imminent)
        }

        // v1.9.0 audit fix SEC-10 — rote un nouveau nonce et l'embarque
        // dans l'intent. Sans nonce valide, MainActivity rejettera le reset.
        val tapIntent = resetActivityIntent(intentToken.rotate())

        val notif = NotificationCompat.Builder(
            context,
            NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING,
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // v1.27.4 — ⚠️ CE DRAPEAU NE REND PLUS RIEN NON-BALAYABLE, et le commentaire qu'il
            // portait l'affirmait depuis v1.9.0 : « non swipable — l'user DOIT taper pour reset ».
            // Depuis Android 14, une notification `ongoing` se balaie. Mesuré sur le S24 (Android
            // 16) le 2026-08-06.
            //
            // Il est conservé parce qu'il reste utile — la notification ne se ferme pas au tap et
            // reste en tête du volet — mais **on ne s'appuie plus dessus comme sur une garantie**.
            // Aucun `setDeleteIntent` ici, délibérément : le balayage de l'avertissement est INERTE,
            // et il doit l'être. Un `deleteIntent` sur cet état donnerait à qui tient le téléphone
            // un coupe-circuit du deadman en un geste depuis le volet, écran verrouillé, sans le
            // code de l'application. Le tap, lui, passe par `startActivity` et exige un
            // déverrouillage.
            //
            // Conséquence assumée : un balayage ne change pas l'état, donc la réconciliation
            // suivante republie l'avertissement. Il **échoue du bon côté** — l'information revient
            // au lieu d'être perdue. Cf. [SafetyCallReceiptDismissReceiver] pour le seul état où
            // acquitter par balayage est sûr.
            .setOngoing(true)
            .setOnlyAlertOnce(true) // pas de re-son à chaque mise à jour heure
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .build()

        // Audit lint v1.14.8 — `hasPostPermission()` early-return en début de [showWarning],
        // faux positif lint (ne suit pas la helper).
        @android.annotation.SuppressLint("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIF_ID_DEADMAN_WARNING, notif)
        Timber.i("SafetyCallWarningNotifier: posted warning (%dh left)", hoursLeft)
    }

    /**
     * v1.27.2 — notification **pendant la séquence de relances**, posée dès le premier message
     * envoyé et maintenue jusqu'au dernier.
     *
     * # Pourquoi elle est indispensable
     *
     * Avant, l'avertissement était retiré au déclenchement et **rien ne le remplaçait**. Or la
     * séquence dure désormais trois quarts d'heure : sans notification, le seul moyen d'arrêter les
     * relances était d'ouvrir l'application et d'aller dans les Réglages. Quelqu'un qui veut
     * simplement dire « je vais bien » et couper l'alerte devait donc naviguer dans un menu — au
     * pire moment possible.
     *
     * Le tap emprunte **exactement le même chemin** que le bouton des Réglages
     * ([ACTION_SAFETY_CALL_RESET], nonce compris) qui, depuis l'audit Codex, désactive le deadman
     * et clôt la séquence quand l'alerte est déjà partie.
     *
     * ⚠️ Rien n'y révèle l'identité des contacts, et le canal reste `PRIVATE` : sous contrainte, la
     * notification dit qu'une alerte est partie, jamais à qui.
     *
     * # v1.27.2 (audit Codex du 2026-08-05, P-06) — elle n'annonce plus un envoi qui n'a pas eu lieu
     *
     * Elle lisait `messagesSent`, qui compte les créneaux **réservés**. Or la réservation est
     * écrite avant le premier `SmsManager.send` — c'est elle qui empêche deux workers d'envoyer le
     * même message. La notification affirmait donc « alerte envoyée, 1 message sur 4 » à un instant
     * où **rien n'était parti**, et cette affirmation survivait à une mort du processus.
     *
     * [delivered] ne compte désormais que les envois **conclus**. Tant qu'il vaut zéro, le texte
     * dit « envoi en cours » et n'affirme rien. Faire croire à quelqu'un que ses proches sont
     * prévenus alors que rien n'est parti est le pire retour possible sur une fonction de sécurité.
     */
    private fun showSequence(notice: SafetyCallNotice.Sequence) {
        if (!hasPostPermission()) {
            Timber.w("SafetyCallWarningNotifier: POST_NOTIFICATIONS not granted, skipping sequence")
            return
        }
        // ⚠️ v1.27.3 — UN SEUL nonce pour tous les intents de cette publication.
        //
        // [SafetyCallIntentToken.consume] est mono-usage et `rotate()` invalide le précédent.
        // Appeler `rotate()` une fois par intent aurait donc laissé le corps de la notification et
        // l'action « Réactiver » porter des jetons périmés : seul le dernier construit aurait
        // fonctionné, les autres se seraient fait rejeter en silence par `MainActivity`.
        val token = intentToken.rotate()
        val tapIntent = resetActivityIntent(token)
        // Rien de conclu : on décrit ce qui se passe réellement, sans rien affirmer.
        val sending = notice.delivered == 0
        val title = when {
            sending -> context.getString(R.string.safety_call_sequence_title_sending)
            notice.terminal -> context.getString(R.string.safety_call_sequence_title_done)
            else -> context.getString(R.string.safety_call_sequence_title)
        }
        val body = when {
            sending -> context.getString(R.string.safety_call_sequence_body_sending)
            notice.terminal -> context.resources.getQuantityString(
                R.plurals.safety_call_sequence_body_done,
                notice.delivered,
                notice.delivered,
                notice.total,
            )
            else -> context.resources.getQuantityString(
                R.plurals.safety_call_sequence_body,
                notice.delivered,
                notice.delivered,
                notice.total,
            )
        }
        val builder = NotificationCompat.Builder(
            context,
            // 🔴 v1.27.3 — LE REÇU NE SONNE PLUS.
            //
            // Constaté sur le S24 : cette notification sonnait à répétition des heures après le
            // départ de l'alerte, et a été prise deux fois pour un nouveau déclenchement. Sur
            // API 26+ le son se règle **par canal** : ni `setOnlyAlertOnce` ni `setSilent` ne
            // pouvaient y suffire, il fallait un canal.
            //
            // Une séquence terminée est un reçu ; l'avertissement de pré-déclenchement et la
            // séquence en cours, eux, demandent une action et gardent `IMPORTANCE_HIGH`.
            if (notice.terminal) {
                NotificationChannelInitializer.CHANNEL_SAFETY_CALL_RECEIPT
            } else {
                NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING
            },
        )
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // v1.27.4 — une séquence EN COURS reste accrochée ; un REÇU se balaie.
            //
            // Tant que des relances peuvent partir, cette notification est le seul geste facile
            // pour dire « je vais bien » : elle doit rester en place. Une fois la séquence close,
            // elle ne demande plus rien — c'est un reçu, et le maintenir accroché n'a plus d'objet.
            .setOngoing(!notice.terminal)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)

        // 🔴 v1.27.3 — L'HEURE AFFICHÉE ÉTAIT CELLE DE LA DERNIÈRE PUBLICATION, PAS DU
        // DÉCLENCHEMENT.
        //
        // Sans `setWhen`, le constructeur met l'heure courante. Or la réconciliation se rejoue à
        // chaque démarrage à froid du processus : la notification était réhorodatée, remontait en
        // tête du volet et se présentait comme une alerte NEUVE. Mesuré sur le S24 le 2026-08-06 —
        // déclenchement à 23:53, notification affichant « 11h01 » le lendemain matin, prise pour un
        // second déclenchement.
        //
        // Avec l'instant du déclenchement, une republication devient invisible : même contenu, même
        // horodatage. Et le proche qui arrive lit l'heure à laquelle l'alerte est réellement partie,
        // ce qui est tout l'intérêt de laisser cette notification affichée.
        if (notice.triggeredAt > 0L) {
            builder.setWhen(notice.triggeredAt).setShowWhen(true)
        }

        // v1.27.4 — LE BALAYAGE DU REÇU VAUT « J'AI VU », et seulement du reçu.
        //
        // Sans cela, balayer retirait l'affichage sans rien changer, et la notification revenait au
        // démarrage à froid suivant : le cycle n'était pas archivé, donc `decide` rendait encore
        // `Sequence(terminal = true)`. Constaté par Patrice le 2026-08-06.
        //
        // ⚠️ La garde `notice.terminal` n'est pas une commodité, c'est la condition de sûreté :
        // hors état terminal, ce même `deleteIntent` serait un coupe-circuit du deadman atteignable
        // depuis le volet. Toute la démonstration est dans
        // [SafetyCallReceiptDismissReceiver] — la lire avant d'élargir cette condition.
        if (notice.terminal) {
            builder.setDeleteIntent(receiptSeenIntent())
        }

        // v1.27.3 — « Réactiver », et SEULEMENT dans l'état terminal.
        //
        // Avant la fin de la séquence, le geste utile est « je vais bien » : il est déjà porté par
        // le corps de la notification. Une fois la séquence close, le Safety call s'est désactivé
        // tout seul, et le geste utile devient l'inverse — se remettre sous protection.
        //
        // ⚠️ L'action passe par `startActivity`, JAMAIS par un `BroadcastReceiver` qui basculerait
        // le réglage directement. Une action de notification est atteignable depuis le volet : un
        // récepteur ferait de ce bouton un coupe-circuit du deadman en un geste, sans le code de
        // l'application. Le réarmement, lui, est sans risque dans ce sens — il ne peut que remettre
        // la protection en marche.
        // v1.27.3 (relecture Codex, SC-1273-02) — `canRearm` : sans destinataire, le réarmement ne
        // pourrait pas aboutir, et l'afficher quand même produisait un échec silencieux qui retirait
        // la notification comme un succès.
        if (notice.terminal && notice.canRearm) {
            builder.addAction(
                R.drawable.ic_notification_message,
                context.getString(R.string.safety_call_notif_action_rearm),
                rearmActivityIntent(token),
            )
        }

        @android.annotation.SuppressLint("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIF_ID_SEQUENCE, builder.build())
        Timber.i(
            "SafetyCallWarningNotifier: posted sequence notice (%d/%d, enVol=%s, terminal=%s)",
            notice.delivered,
            notice.total,
            notice.inFlight,
            notice.terminal,
        )
    }

    /**
     * Intent « je vais bien » : remet le minuteur à zéro, et désarme si l'alerte est déjà partie.
     * Partagé par la notification d'avertissement et celle de séquence — ils utilisent volontairement
     * le **même code de requête**, pour qu'il n'existe qu'un seul intent vivant à la fois.
     */
    private fun resetActivityIntent(token: Long): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SAFETY_CALL_RESET,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SAFETY_CALL_RESET)
            .putExtra(EXTRA_RESET_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * v1.27.3 — intent de **réarmement**, porteur du même nonce que le tap du corps.
     *
     * Code de requête distinct de [REQUEST_SAFETY_CALL_RESET], sans quoi les deux intents
     * s'écraseraient l'un l'autre. Cela crée bien deux `PendingIntent` vivants dont l'un portera un
     * jeton périmé après la prochaine rotation — mais l'action n'existe que sur la notification
     * terminale, et [reconcile] la retire avant toute autre publication. Aucun bouton périmé n'est
     * donc atteignable.
     */
    private fun rearmActivityIntent(token: Long): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SAFETY_CALL_REARM,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SAFETY_CALL_REARM)
            .putExtra(EXTRA_RESET_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * v1.27.4 — intent de **rejet** du reçu de fin de séquence, posé en `deleteIntent`.
     *
     * `getBroadcast` vers un récepteur `exported="false"` : inatteignable depuis l'extérieur, donc
     * sans nonce — en poser un invaliderait celui du corps et du bouton « Réactiver », puisque
     * [SafetyCallIntentToken.consume] est mono-usage. Le raisonnement complet, et la raison pour
     * laquelle ce chemin n'existe que dans l'état terminal, sont dans
     * [SafetyCallReceiptDismissReceiver].
     *
     * Code de requête propre : partager celui du reset ou du réarmement les ferait s'écraser
     * mutuellement, `FLAG_UPDATE_CURRENT` réécrivant l'intent en place.
     */
    private fun receiptSeenIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_SAFETY_CALL_RECEIPT_SEEN,
        Intent(context, SafetyCallReceiptDismissReceiver::class.java)
            .setAction(SafetyCallReceiptDismissReceiver.ACTION_SAFETY_CALL_RECEIPT_SEEN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Annule la notification d'avertissement si présente. Safe à appeler même si aucune notif n'est
     * active.
     */
    private fun dismissWarning() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(NOTIF_ID_DEADMAN_WARNING)
    }

    /**
     * v1.27.2 (audit Codex, C-07 / C-08) — retire la notification de SEQUENCE, distincte de
     * l'avertissement de pre-declenchement.
     *
     * Les deux partageaient un identifiant, ce qui les faisait se remplacer l'une l'autre et
     * obligeait un seul appelant a arbitrer entre elles. Separees, elles ont un unique
     * proprietaire — [reconcile] — qui garantit leur exclusion mutuelle.
     */
    private fun dismissSequence() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(NOTIF_ID_SEQUENCE)
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** Action de l'intent posé sur le tap notif → handled by MainActivity. */
        const val ACTION_SAFETY_CALL_RESET = "com.filestech.sms.SAFETY_CALL_RESET"

        /**
         * v1.27.3 — action du bouton « Réactiver » de la notification terminale.
         *
         * Distincte de [ACTION_SAFETY_CALL_RESET] parce que le sens est **inverse** : celle-ci
         * remet le Safety call en marche, l'autre le désarme. Les confondre aurait produit le
         * jumeau asymétrique habituel — un bouton dont le libellé promet une chose et dont le
         * chemin fait l'autre.
         */
        const val ACTION_SAFETY_CALL_REARM = "com.filestech.sms.SAFETY_CALL_REARM"

        /**
         * v1.9.0 audit fix SEC-10 — extra portant le nonce anti-spoofing.
         * Validé par `MainActivity.handleSharedIntent` via [SafetyCallIntentToken.consume].
         */
        const val EXTRA_RESET_TOKEN = "com.filestech.sms.SAFETY_CALL_RESET_TOKEN"

        /** ID de la notification (unique stable pour update/dismiss). */
        private const val NOTIF_ID_DEADMAN_WARNING = 0x44454144 // 'DEAD'

        /** v1.27.2 — la notification de sequence est INDEPENDANTE de l'avertissement. */
        private const val NOTIF_ID_SEQUENCE = 0x53455151 // 'SEQQ'
        private const val REQUEST_SAFETY_CALL_RESET = 0x52455354    // 'REST'

        /** v1.27.3 — code distinct, sans quoi l'intent de réarmement écraserait celui de reset. */
        private const val REQUEST_SAFETY_CALL_REARM = 0x52454152    // 'REAR'

        /** v1.27.4 — code du `deleteIntent` du reçu ; distinct des deux précédents. */
        private const val REQUEST_SAFETY_CALL_RECEIPT_SEEN = 0x5345454E // 'SEEN'
    }
}
