package com.filestech.sms.system.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.filestech.sms.domain.settings.NotificationStyle

/**
 * v1.28.13 — `true` quand une notification postée sur CHACUN des [canaux] peut réellement
 * s'afficher : notifications de l'application autorisées (`POST_NOTIFICATIONS` sous Android 13+,
 * interrupteur global sinon) ET aucun de ces canaux coupé par l'utilisateur. Sinon `notify()` ne
 * poste rien, sans erreur.
 *
 * Chaque fonction a ses PROPRES canaux, qu'Android permet de couper un à un sans toucher à
 * l'interrupteur global (relecture GPT du correctif). Un canal pas encore créé n'est pas « coupé » :
 * l'étape de création est en attente. `minSdk` 26 : les canaux existent toujours.
 *
 * v1.8.0 (bug 3 fix, MEDIUM 3c) — l'ancêtre de cette fonction, membre d'[IncomingMessageNotifier]
 * sans appelant, annonçait un avertissement rouge dans les Réglages qui n'avait JAMAIS existé.
 */
fun canauxVisibles(contexte: Context, canaux: List<String>): Boolean {
    if (!NotificationManagerCompat.from(contexte).areNotificationsEnabled()) return false
    val nm = contexte.getSystemService(NotificationManager::class.java) ?: return true
    return canaux.none { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
}

/** Même vérification, pour un appel qui nomme ses canaux un par un. */
fun canauxVisibles(contexte: Context, vararg canaux: String): Boolean = canauxVisibles(contexte, canaux.asList())

/**
 * Le canal sur lequel part un message entrant, selon le style choisi. Source UNIQUE : le
 * notificateur poste dessus, la bannière des Réglages le vérifie. Audit pré-release 1.28.13 (L2) :
 * la bannière ne lisait que `CHANNEL_INCOMING`, alors que le style « Silencieux » poste sur
 * `CHANNEL_INCOMING_SILENT` — couper ce canal-là ne déclenchait aucun avertissement.
 */
fun canalDesMessages(style: NotificationStyle): String = when (style) {
    NotificationStyle.SILENT -> NotificationChannelInitializer.CHANNEL_INCOMING_SILENT
    NotificationStyle.HEADS_UP, NotificationStyle.BANNER -> NotificationChannelInitializer.CHANNEL_INCOMING
}

/**
 * Le canal du Safety call qui porte l'avertissement avant envoi, la séquence en cours et le signal
 * « armé mais rien ne partira ». Le reçu de fin (`CHANNEL_SAFETY_CALL_RECEIPT`) n'en fait pas
 * partie : le couper n'empêche ni d'être prévenu ni d'arrêter la séquence (audit 1.28.13, L2).
 */
const val CANAL_DU_SAFETY_CALL: String = NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING

/** Le canal du raccourci d'urgence de l'écran verrouillé. */
const val CANAL_DU_RACCOURCI_D_URGENCE: String = NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT
