package com.filestech.sms.domain.clipboard

/**
 * Port domaine : retire du presse-papiers du téléphone ce que l'application y a mis.
 *
 * v1.28.6 — existe pour « Supprimer toutes mes données ». Cette version ajoute la copie d'un
 * EXTRAIT de message : on sélectionne un bout de texte, on copie, puis on purge — et le texte
 * restait dans le presse-papiers, lisible par n'importe quelle application.
 *
 * Le presse-papiers est déjà hors du périmètre du coffre (`THREAT-MODEL.md`, I7/N4) : cette limite
 * est assumée et écrite. Ce qui ne l'était pas, c'est qu'une purge se disant irréversible le laisse
 * garni. Elle ne peut pas tenir la promesse *à la place* du système, mais elle peut ne pas ajouter
 * elle-même une copie qui survit.
 *
 * L'implémentation vit dans `:app`, comme celle de
 * [com.filestech.sms.domain.notification.AllNotificationsCanceller] et pour la même raison :
 * `:data` ne touche pas aux services d'interface du système.
 */
interface ClipboardCleaner {

    /** Retire le contenu du presse-papiers. Sans effet si le système le refuse (voir l'impl). */
    fun clear()
}
