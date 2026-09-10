package com.filestech.sms.core.util

/**
 * v1.28.4 — **un travail qui ne mérite pas d'être refait toutes les quelques secondes.**
 *
 * Mesuré sur le S24 : le miroir des numéros bloqués du système (245 entrées) se rejouait à
 * chaque reprise de l'application, parce que chaque `onResume` demande une synchronisation et
 * que chaque synchronisation commençait par ce miroir. Rien de faux, tout de coûteux.
 *
 * L'étrangleur laisse passer la première demande, puis une par [intervalleMs] — sauf demande
 * FORCÉE, qui passe toujours et remet le compteur. L'horloge est injectable pour les tests.
 */
class Etrangleur(
    private val intervalleMs: Long,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var dernierPassageA: Long = Long.MIN_VALUE

    /** `true` si la demande passe (et le compteur repart), `false` si elle est absorbée. */
    fun autorise(force: Boolean = false): Boolean {
        val maintenant = horloge()
        val dernier = dernierPassageA
        if (!force && dernier != Long.MIN_VALUE && maintenant - dernier < intervalleMs) return false
        dernierPassageA = maintenant
        return true
    }
}
