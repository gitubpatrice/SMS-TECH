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

    /**
     * v1.28.6 — annule la notification du seul message [messageId], dans la conversation
     * [conversationId].
     *
     * Supprimer UN message laissait sa notification dans le volet, son texte avec elle. Le
     * jumeau du défaut fermé pour la conversation entière — le motif d'asymétrie habituel de ce
     * dépôt, et déjà le sien : la v1.28.5 avait dû rattacher `eraseMessage` aux fichiers de
     * pièces jointes pour la même raison.
     *
     * La conversation est demandée parce qu'elle est nécessaire : les notifications sont postées
     * avec un tag, et une annulation sans tag n'atteint rien.
     */
    fun cancelForMessage(conversationId: Long, messageId: Long)

    /**
     * v1.28.7 — annule les notifications d'un LOT de messages supprimés, groupés par conversation.
     *
     * Existe pour les suppressions de masse qui ne passent pas par la suppression d'une
     * conversation entière : la purge de rétention et la réconciliation de synchronisation.
     * Elles laissaient leurs notifications dans le volet. Un appel par message y serait un appel
     * système par ligne purgée — des milliers pour une rétention ; l'implémentation lit donc une
     * seule fois les notifications actives, et n'annule que celles qui correspondent.
     */
    fun cancelForMessages(messagesByConversation: Map<Long, Collection<Long>>)
}
