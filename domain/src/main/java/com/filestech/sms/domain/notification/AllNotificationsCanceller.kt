package com.filestech.sms.domain.notification

/**
 * Port domaine : annule TOUTES les notifications postées par l'application.
 *
 * v1.28.6 — existe pour « Supprimer toutes mes données », et pour elle seule.
 * [ConversationNotificationCanceller] annule les notifications d'UNE conversation, et c'est ce
 * qu'il faut sur une suppression ordinaire. Une purge totale, elle, ne peut pas se contenter de
 * balayer les conversations qu'elle a pu énumérer : si la lecture de la base échoue, elle
 * n'énumère rien, détruit tout de même le fichier, et le volet garde son contenu. Elle porte en
 * outre des notifications qui n'appartiennent à aucune conversation — échec d'envoi, veille du
 * Safety call, et le raccourci d'urgence, qui est `ongoing` donc **non balayable à la main**.
 *
 * **Ce que `cancelAll()` ne retire pas** (relecture sécurité du delta final, S4) : la notification
 * d'un service au premier plan actif — ici `KeepAliveService`, le mode « résistant ». Android la
 * garde tant que le service tourne. Elle part par un autre chemin : la purge remet les réglages
 * avancés aux défauts, `keepAliveService` y vaut `false`, et l'observateur de `MainApplication`
 * arrête alors le service. Même effet dans les deux sessions, qui remettent toutes deux ce bloc
 * aux défauts.
 *
 * Deux ports plutôt qu'une méthode ajoutée au premier : le contrat n'est pas le même, et le nom
 * du premier dit « conversation ». Un `cancelAll()` posé dessus aurait menti sur sa portée.
 *
 * L'implémentation vit dans `:app` ([com.filestech.sms.system.notifications.AllNotificationsCancellerImpl]) :
 * `:data` ne touche jamais le `NotificationManager` Android, et la purge n'est pas l'endroit où
 * percer cette frontière.
 */
interface AllNotificationsCanceller {

    /** Annule toutes les notifications de l'application, quelle que soit leur origine. */
    fun cancelAll()
}
