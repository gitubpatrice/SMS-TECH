package com.filestech.sms.data.repository

import com.filestech.sms.domain.notification.ConversationNotificationCanceller

/**
 * v1.28.6 — retient ce qu'on lui demande d'annuler, pour les tests de [ConversationEraser].
 *
 * Partagé par les deux fichiers qui construisent l'effaceur : la même classe écrite deux fois
 * aurait divergé, et c'est précisément le motif de défaut que ces tests surveillent.
 *
 * Aucun `NotificationManager` dans l'équation : ce qu'il faut prouver est que l'effaceur DEMANDE
 * l'annulation quand la ligne locale est partie, et qu'il ne la demande PAS quand il conserve le
 * parent. Ce que le système fait ensuite du tag est déjà tenu par
 * [com.filestech.sms.system.notifications.IncomingMessageNotifier].
 */
internal class AnnulateurDeNotificationsEspion : ConversationNotificationCanceller {

    val conversations = mutableListOf<Long>()
    val messages = mutableListOf<Pair<Long, Long>>()

    override fun cancelAllForConversation(conversationId: Long) {
        conversations += conversationId
    }

    override fun cancelForMessage(conversationId: Long, messageId: Long) {
        messages += conversationId to messageId
    }
}
