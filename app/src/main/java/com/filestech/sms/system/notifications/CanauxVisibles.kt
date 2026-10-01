package com.filestech.sms.system.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * v1.28.13 — `true` quand une notification postée sur CHACUN des [canaux] peut réellement
 * s'afficher : notifications de l'application autorisées (`POST_NOTIFICATIONS` sous Android 13+,
 * interrupteur global sinon) ET aucun de ces canaux coupé par l'utilisateur. Sinon `notify()` ne
 * poste rien, sans erreur.
 *
 * Généralisée après la relecture GPT du correctif : la bannière du Safety call et celle du raccourci
 * d'urgence ne regardaient que l'interrupteur global, alors que ces deux fonctions ont leurs PROPRES
 * canaux, que l'on coupe un à un dans Android — le jumeau du correctif posé pour les messages
 * entrants. Un canal pas encore créé n'est pas « coupé » : l'étape de création est en attente.
 * `minSdk` 26 : les canaux existent toujours.
 */
fun canauxVisibles(contexte: Context, vararg canaux: String): Boolean {
    if (!NotificationManagerCompat.from(contexte).areNotificationsEnabled()) return false
    val nm = contexte.getSystemService(NotificationManager::class.java) ?: return true
    return canaux.none { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
}

/**
 * Les notifications de message entrant. v1.8.0 (bug 3 fix, MEDIUM 3c) — écrite pour un
 * avertissement rouge dans les Réglages qui n'avait JAMAIS existé (membre d'
 * [IncomingMessageNotifier] sans appelant) ; v1.28.13 — branchée par `BanniereNotificationsCoupees`.
 */
fun notificationsDesMessagesVisibles(contexte: Context): Boolean =
    canauxVisibles(contexte, NotificationChannelInitializer.CHANNEL_INCOMING)

/** Le Safety call : avertissement avant envoi, séquence, et accusé de réception. */
fun notificationsDuSafetyCallVisibles(contexte: Context): Boolean = canauxVisibles(
    contexte,
    NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING,
    NotificationChannelInitializer.CHANNEL_SAFETY_CALL_RECEIPT,
)

/** Le raccourci d'urgence de l'écran verrouillé. */
fun raccourciDUrgenceVisible(contexte: Context): Boolean =
    canauxVisibles(contexte, NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT)
