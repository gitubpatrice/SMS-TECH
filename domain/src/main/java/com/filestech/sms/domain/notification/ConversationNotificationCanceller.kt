package com.filestech.sms.domain.notification

/**
 * Port domaine : annule toutes les notifications d'une conversation.
 *
 * [com.filestech.sms.data.repository.ConversationRepositoryImpl] l'utilise quand une conversation
 * est supprimée / vidée, sans dépendre de la couche `system` (le module `:data` ne peut pas
 * dépendre de `:app`). L'implémentation [com.filestech.sms.system.notifications.IncomingMessageNotifier]
 * pilote le `NotificationManager` Android.
 */
interface ConversationNotificationCanceller {

    /** Annule toutes les notifications postées pour la conversation [conversationId]. */
    fun cancelAllForConversation(conversationId: Long)
}
