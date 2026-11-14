package com.filestech.sms.core.util

/**
 * 2026-11-14 — **l'instant du dernier effacement d'urgence**, tenu en mémoire du processus et nulle part ailleurs.
 *
 * Relu par la reprise des MMS reçus (`RelanceMms`) : un téléchargement lancé AVANT un effacement peut aboutir, ou
 * échouer, APRÈS lui — le service MMS d'Android attend parfois le réseau trente minutes. Sans cette marque, son
 * résultat écrivait le message dans la base qu'on venait de vider, ou reposait une notification nominative et
 * réarmait des essais (relectures Claude, GPT et audit de sécurité du 2026-11-14).
 *
 * Pourquoi en mémoire seulement : une marque écrite sur le disque serait une trace qu'un effacement a eu lieu, ce que
 * le mode leurre doit taire. Le prix : si le processus meurt entre l'effacement et le résultat, la marque est perdue.
 * Les essais automatiques restent alors tenus par leur propre porte (la notification de leur message, que
 * l'effacement a annulée) ; seul un téléchargement déjà en vol peut encore aboutir — comme avant cette marque.
 */
object DernierEffacement {

    @Volatile
    private var instant: Long = 0L

    /** Posée par l'effacement, AVANT de détruire quoi que ce soit : un résultat qui arrive pendant compte aussi. */
    fun marquer(maintenant: Long = System.currentTimeMillis()) {
        instant = maxOf(instant, maintenant)
    }

    /**
     * Ce qui a commencé à [debut] précède-t-il le dernier effacement de ce processus ? `false` sans effacement.
     * Un [debut] inconnu (0) avec un effacement : `true` — dans le doute, on ne ressuscite rien.
     */
    fun anterieur(debut: Long): Boolean {
        val dernier = instant
        return dernier > 0L && debut <= dernier
    }

    /** Pour les tests seulement : le processus de test enchaîne les cas. */
    fun oublierPourLesTests() {
        instant = 0L
    }
}
