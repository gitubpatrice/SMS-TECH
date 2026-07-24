package com.filestech.sms.system.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.NotificationStyle
import com.filestech.sms.domain.settings.PreviewMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val contacts: ContactRepository,
    private val activeConversationTracker: ActiveConversationTracker,
    private val conversationDao: ConversationDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ConversationNotificationCanceller {

    suspend fun notifyIncoming(
        address: String,
        body: String,
        messageId: Long,
        conversationId: Long,
    ) = withContext(io) {
        // v1.11.0 — Vault gate. Si la conversation est dans le coffre, on
        // n'affiche AUCUNE notification — ni nom, ni preview, ni heads-up,
        // ni icône silencieuse. Cohérent avec la promesse Vault "les conv
        // déplacées disparaissent ET des notifications". Le badge unread
        // côté Vault reste comptabilisé (via Room) pour le récap à l'ouverture
        // explicite du coffre.
        //
        // Lecture synchrone Room (~1-3 ms) acceptable sur SMS entrant — bien
        // moins coûteuse qu'un round-trip DataStore (10-15 ms) qu'on évite
        // déjà via `settings.state.value` ci-dessous.
        val inVault = runCatching { conversationDao.findById(conversationId)?.inVault == true }
            .onFailure { Timber.w(it, "IncomingMessageNotifier: inVault lookup failed, defaulting to false") }
            .getOrDefault(false)
        // v1.11.0 audit S3 — pas de log explicite "conv #N suppressed in vault"
        // pour éviter qu'un ReleaseTree mal configuré (Crashlytics bêta, OEM
        // logcat persistant) corrèle le conversationId avec le fait qu'il
        // s'agit d'une conv vault. Le silence du return suffit côté debug.
        if (inVault) return@withContext

        // Resolve the contact name so the notification shows "Marie" instead of "+33612345678".
        // Falls back gracefully if READ_CONTACTS is denied or no match exists.
        val senderName = runCatching { contacts.lookupByPhone(address)?.displayName }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: address
        // v1.6.1 (audit PERF-11) — lecture synchrone via le snapshot chaud StateFlow
        // hydraté par [SettingsRepository.state] au boot. Avant : `flow.first()` =
        // ouverture DataStore + désérialisation proto sur CHAQUE SMS entrant (5-15 ms
        // sous charge), retardant la notif d'autant.
        val s = settings.state.value
        if (!s.notifications.enabled) return@withContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) return@withContext
        }

        // v1.8.0 (bug 3 fix) — route vers le canal silent quand l'utilisateur a
        // explicitement choisi `NotificationStyle.SILENT` dans Paramètres →
        // Notifications. Le canal silent est IMPORTANCE_LOW → pas de heads-up,
        // son masqué par le système. L'utilisateur garde l'icône de notif dans
        // le shade (utile pour ne pas rater un message) mais sans dérangement
        // sonore ni visuel intrusif.
        //
        // Pour `BANNER` (Heads-up désactivé mais son conservé), on reste sur le
        // canal HIGH mais on coupe le son côté NotificationCompat — Android
        // tente d'afficher un heads-up pour les notifs HIGH "qui font du bruit",
        // donc en désamorçant le son on neutralise effectivement le pop-up tout
        // en conservant le badge + icône shade. Compromis volontairement
        // documenté dans le toggle UI ([R.string.settings_notif_style_desc]).
        val channelId = when (s.notifications.style) {
            NotificationStyle.SILENT -> NotificationChannelInitializer.CHANNEL_INCOMING_SILENT
            NotificationStyle.HEADS_UP, NotificationStyle.BANNER ->
                NotificationChannelInitializer.CHANNEL_INCOMING
        }

        // Audit F15: the legacy "WHEN_UNLOCKED" branch leaked the body in setContentText on some
        // OEMs that disregarded VISIBILITY_PRIVATE. Both WHEN_UNLOCKED and NEVER now ship a
        // placeholder for setContentText; the real body only flows through MessagingStyle, which
        // honours the OS lock-screen redaction.
        val hidePreview = when (s.notifications.previewMode) {
            PreviewMode.ALWAYS -> false
            PreviewMode.WHEN_UNLOCKED, PreviewMode.NEVER -> true
        }
        val redactedPreview = context.getString(R.string.notif_preview_hidden_content)
        val visiblePreview = if (hidePreview) redactedPreview else body

        val person = Person.Builder().setName(senderName).setKey(address).build()
        val notificationId = stableNotificationId(messageId)

        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CONVERSATION)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
                // v1.8.0 (bug 4 fix) — on passe le `conversationId` en extra pour
                // que MainActivity.handleSharedIntent puisse le déposer dans
                // PendingNavHolder sans avoir à résoudre `address → conversationId`
                // de manière asynchrone (qui aurait nécessité une requête Room
                // côté MainActivity, source potentielle de race avec l'import).
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // v1.3.9 — si l'utilisateur est ACTUELLEMENT en train de regarder cette
        // conversation au foreground (cf. [ActiveConversationTracker]), on pose
        // quand même la notification (son + heads-up éphémère = signal sonore
        // préservé : le user "entend" le nouveau message) mais on demande à Android
        // de la cancel automatiquement après [ACTIVE_CONV_TIMEOUT_MS] ms — l'icone
        // ne persiste pas dans le shade puisque l'utilisateur voit déjà le message
        // arriver en temps réel dans la conv ouverte. `setTimeoutAfter` est l'API
        // officielle Android 8+ (API 26+, dans notre minSdk) pour ce besoin précis,
        // sans timer/coroutine custom côté app (Android s'en charge atomiquement).
        val isActiveConversation = activeConversationTracker.isActive(conversationId)

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(senderName)
            .setContentText(visiblePreview)
            .setStyle(
                // v1.6.1 (audit SEC-01) — Passe `visiblePreview` (redacté quand
                // `previewMode` est NEVER / WHEN_UNLOCKED) au lieu de `body` brut.
                // L'ancien code laissait fuiter le contenu via `MessagingStyle.Message`
                // sur certains OEMs (Xiaomi MIUI / HyperOS, Samsung One UI < 5) qui
                // ignorent `VISIBILITY_SECRET` pour MessagingStyle. Désormais le body
                // sensible n'est exposé QUE quand l'utilisateur a explicitement choisi
                // PreviewMode.ALWAYS.
                NotificationCompat.MessagingStyle(person).addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        visiblePreview,
                        System.currentTimeMillis(),
                        person,
                    ),
                ),
            )
            .setVisibility(
                when (s.notifications.previewMode) {
                    PreviewMode.WHEN_UNLOCKED -> NotificationCompat.VISIBILITY_PRIVATE
                    PreviewMode.NEVER -> NotificationCompat.VISIBILITY_SECRET
                    PreviewMode.ALWAYS -> NotificationCompat.VISIBILITY_PUBLIC
                },
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // v1.3.3 bug #6 — Z4 audit fix : on N'utilise PAS `setGroup(...)` (qui peut
            // créer un summary phantome sur OneUI/MIUI et complique le cleanup). À la
            // place, on poste les notifs avec un TAG dérivé du conversationId. Lookup
            // côté [cancelAllForConversation] = `for (sbn in activeNotifications)
            // if (sbn.tag == tag) nm.cancel(tag, sbn.id)`. Simple, fiable, pas de
            // summary à gérer.
            .also { b ->
                if (s.notifications.inlineReply) {
                    b.addAction(buildReplyAction(address, messageId, notificationId))
                }
                b.addAction(buildMarkReadAction(address, messageId, notificationId))
                if (isActiveConversation) {
                    b.setTimeoutAfter(ACTIVE_CONV_TIMEOUT_MS)
                }
                // v1.8.0 (bug 3 fix) — mode BANNER : neutralise le son côté notif
                // pour empêcher Android d'inférer un heads-up. Le canal reste HIGH
                // (conservé pour ne pas perturber les users en HEADS_UP existants),
                // mais sans son la notif retombe à un simple badge dans le shade.
                // En SILENT, le canal LOW se charge déjà de masquer son + heads-up,
                // donc pas besoin de setSound ici.
                if (s.notifications.style == NotificationStyle.BANNER) {
                    b.setSound(null)
                    b.setDefaults(0)
                }
            }
            .build()
        // Z4 audit fix — tag-based notif : permet à `cancelAllForConversation` de
        // tout cancel via `nm.cancel(tag, id)` sans toucher à un éventuel summary.
        NotificationManagerCompat.from(context).notify(
            conversationTag(conversationId),
            notificationId,
            notif,
        )
    }

    /**
     * Maps a Room message id to a unique notification id.
     * Fixes audit F38 (the previous `msgId.toInt().or(1)` collided every two messages).
     * Uses XOR to spread bits then OR with [BASE_TAG] to never return 0 (which would be discarded).
     */
    private fun stableNotificationId(messageId: Long): Int {
        val hash = (messageId xor (messageId ushr 32)).toInt() and 0x7FFFFFFF
        return hash or BASE_TAG
    }

    private fun buildReplyAction(
        address: String,
        messageId: Long,
        notificationId: Int,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_label))
            .build()
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            // Audit F8: clamp the intent to our component so even with an action collision
            // (matching string), no other app can receive it.
            component = ComponentName(context, NotificationActionReceiver::class.java)
            action = NotificationActionReceiver.ACTION_REPLY
            `package` = context.packageName
            putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId.xor(REPLY_REQUEST_SALT),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_reply,
            context.getString(R.string.notif_reply_label),
            pi,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()
    }

    private fun buildMarkReadAction(
        address: String,
        messageId: Long,
        notificationId: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            component = ComponentName(context, NotificationActionReceiver::class.java)
            action = NotificationActionReceiver.ACTION_MARK_READ
            `package` = context.packageName
            putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId.xor(MARK_READ_REQUEST_SALT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_check,
            context.getString(R.string.notif_mark_read_label),
            pi,
        ).build()
    }

    fun cancel(messageId: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)
            ?.cancel(stableNotificationId(messageId))
    }

    /**
     * v1.8.0 (bug 3 fix, MEDIUM 3c) — détecte si l'utilisateur a désactivé soit
     * les notifications de l'app au global, soit le canal `incoming_messages`
     * spécifiquement dans les Paramètres système Android. Dans ce cas
     * `NotificationManagerCompat.notify()` poste silencieusement sans rien
     * afficher — confusion garantie côté utilisateur ("Tiens, je ne reçois
     * plus de notifs alors que SMS Tech est activée…").
     *
     * Appelé depuis le SettingsScreen pour afficher un warning rouge + bouton
     * deeplink vers les réglages système quand l'état est dégradé. Le check
     * est read-only et idempotent — safe à appeler à chaque recomposition.
     *
     * Retourne `true` quand les notifs incoming peuvent réellement s'afficher,
     * `false` quand elles sont muettes (app globale désactivée OU canal
     * désactivé par l'utilisateur dans Paramètres → Apps → SMS Tech →
     * Notifications → Messages entrants).
     */
    fun isIncomingChannelEffectivelyEnabled(): Boolean {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager? ?: return true
        val channel = nm.getNotificationChannel(NotificationChannelInitializer.CHANNEL_INCOMING)
            ?: return true // not created yet = ensure step pending, not "disabled"
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * v1.3.3 bug #6 — cancel TOUTES les notifications affichées qui appartiennent à la
     * conversation [conversationId]. Appelé depuis `ConversationRepositoryImpl.markRead`
     * pour que l'ouverture de la thread depuis l'app efface le pavé de notifs cumulées
     * (qui n'était précédemment effacé QUE par les actions Reply / MarkAsRead émises
     * directement depuis la notif).
     *
     * Z4 audit fix — implémentation **tag-based** : chaque notif est postée avec
     * `tag = conversationTag(id)`. Cancel = itère `activeNotifications`, filtre par
     * tag, `nm.cancel(tag, id)`. Pas de `setGroup` → pas de risque de summary phantome
     * non-cancellé sur OneUI/MIUI. Pas de cache RAM (qui se viderait au kill process
     * et raterait les notifs déposées en background) — on demande au système la liste
     * à chaque appel.
     */
    override fun cancelAllForConversation(conversationId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            ?: return
        val tag = conversationTag(conversationId)
        runCatching {
            for (sbn in nm.activeNotifications) {
                if (sbn.tag == tag) {
                    nm.cancel(sbn.tag, sbn.id)
                }
            }
        }
    }

    /**
     * v1.3.3 — tag stable pour toutes les notifs d'une conversation donnée. Inclut le
     * préfixe `com.filestech.sms.conv.` pour rendre le tag auto-explicatif dans les
     * outils debug (`dumpsys notification`) et éviter toute collision avec d'éventuels
     * tags posés par d'autres composants futurs.
     */
    private fun conversationTag(conversationId: Long): String =
        "com.filestech.sms.conv.$conversationId"

    companion object {
        const val KEY_REPLY = "key_reply_text"

        /**
         * v1.8.0 (bug 4 fix) — constantes partagées avec [com.filestech.sms.MainActivity]
         * et les tests, pour éviter la duplication littérale source de bugs (typo
         * silencieuse type "OPEN_CONVERSARTION" → handler jamais déclenché, etc.).
         */
        const val ACTION_OPEN_CONVERSATION = "com.filestech.sms.OPEN_CONVERSATION"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        private const val BASE_TAG = 0x10000 // ensures non-zero notif ids
        private const val REPLY_REQUEST_SALT = 0x52455050 // 'REPL'
        private const val MARK_READ_REQUEST_SALT = 0x4D524541 // 'MREA'

        /**
         * v1.3.9 — durée après laquelle Android cancel automatiquement la notification
         * d'un message arrivant sur la conv actuellement ouverte au foreground. 1500 ms
         * laisse jouer le son + le heads-up (peak ≈ 800 ms sur la plupart des OEMs)
         * tout en garantissant que la notification ne persiste pas dans le shade
         * (l'utilisateur voit déjà le message en temps réel dans la conv ouverte).
         */
        private const val ACTIVE_CONV_TIMEOUT_MS: Long = 1500L
    }
}
