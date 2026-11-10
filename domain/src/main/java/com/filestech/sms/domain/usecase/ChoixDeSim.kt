package com.filestech.sms.domain.usecase

import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SimActive

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — les deux règles du choix de SIM, sans Android.
 *
 * Avant la 1.30.1, `SendingSettings.defaultSubId` était lu par chaque voie d'envoi, mais aucun écran ne
 * l'avait jamais écrit (historique Git relu le 2026-11-10) et la sauvegarde ne le restaure pas : en
 * pratique, tout partait par la SIM SMS d'Android. On répondait donc par la SIM 1 à un message reçu sur la
 * SIM 2 (courriel d'un utilisateur à deux SIM, 2026-11-10), alors que `PERMISSIONS.md`, l'écran
 * « À propos » et le site annonçaient le choix de la SIM.
 */
object ChoixDeSim {

    /**
     * La SIM d'envoi d'une conversation, ou `null` quand il n'y a rien à choisir (moins de deux SIM
     * actives) : Android décide alors, comme avant.
     *
     * Avec deux SIM ou plus, toujours une SIM précise, pour que celle qu'affiche le composeur soit celle
     * qui part. Dans l'ordre :
     *  1. la SIM du message le plus récent qui en porte une encore active ([historique], du plus récent au
     *     plus ancien) : on répond par la SIM qui a reçu, et un choix fait au composeur se retrouve sur le
     *     message envoyé, donc à la visite suivante ;
     *  2. [reglage] (« SIM d'envoi par défaut »), s'il désigne une SIM active ;
     *  3. la SIM SMS d'Android ([simAndroid]), si elle est active ;
     *  4. la première SIM par emplacement — Android est alors réglé sur « toujours demander ».
     */
    fun pourConversation(
        actives: List<SimActive>,
        historique: List<Int?>,
        reglage: Int?,
        simAndroid: Int?,
    ): Int? {
        if (actives.size < 2) return null
        val ids = actives.map { it.subId }.toSet()
        return historique.firstOrNull { it != null && it in ids }
            ?: reglage?.takeIf { it in ids }
            ?: simAndroid?.takeIf { it in ids }
            ?: actives.minBy { it.emplacement }.subId
    }

    /** Les SIM des [messages] (dans n'importe quel ordre), du plus récent au plus ancien : l'historique de [pourConversation]. */
    fun historique(messages: List<Message>): List<Int?> = messages.sortedByDescending { it.date }.map { it.subId }

    /**
     * Le `subId` à donner à `SmsManager` : [demande] s'il désigne une SIM active, sinon `null` (la SIM
     * SMS d'Android).
     *
     * Une SIM retirée depuis le réglage, ou depuis l'enregistrement d'un envoi programmé, ferait sinon
     * échouer l'envoi — y compris un SMS d'urgence. Quand la liste est inconnue ([actives] vide :
     * permission refusée), la demande passe telle quelle : on ne sait pas la contredire.
     */
    fun valider(demande: Int?, actives: List<SimActive>): Int? {
        if (demande == null || actives.isEmpty()) return demande
        return demande.takeIf { id -> actives.any { it.subId == id } }
    }
}
