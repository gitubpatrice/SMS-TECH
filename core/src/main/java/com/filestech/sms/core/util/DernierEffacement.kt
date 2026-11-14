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
 * le mode leurre doit taire. Le prix : si le processus meurt entre l'effacement et le résultat, la marque est perdue —
 * c'est pourquoi l'effacement annule aussi, DANS LE SYSTÈME, le résultat des téléchargements en cours
 * (`MmsDownloader.annulerLesResultatsEnAttente`). Cette marque est la seconde ligne.
 *
 * L'horloge est `SystemClock.elapsedRealtime()`, passée par l'appelant, des deux côtés (relecture Claude du
 * 2026-11-14) : l'heure murale recule (NTP, réseau, réglage à la main), et un recul après un effacement faisait
 * écarter des MMS reçus ensuite. L'horloge monotone ne recule pas ; elle repart à zéro au redémarrage, comme cette
 * marque et tout ce qu'elle compare.
 */
object DernierEffacement {

    @Volatile
    private var instant: Long = 0L

    /**
     * Posée par l'effacement, AVANT de détruire quoi que ce soit : un résultat qui arrive pendant compte aussi.
     * [maintenant] : `SystemClock.elapsedRealtime()`.
     */
    fun marquer(maintenant: Long) {
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
