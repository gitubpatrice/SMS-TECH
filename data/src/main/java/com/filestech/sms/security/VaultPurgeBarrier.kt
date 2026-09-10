package com.filestech.sms.security

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.4 (F13, suite de la relecture externe de la MR F-Droid !38458) — **la barrière d'écriture
 * pendant une purge du coffre.**
 *
 * La purge relit le coffre APRÈS sa boucle (F09) et ne retire le PIN que s'il est démontrablement
 * vide. Mais entre cette relecture et le retrait du PIN, rien n'empêchait une conversation
 * d'**entrer** au coffre : le PIN partait alors alors qu'une conversation protégée existait —
 * le coffre s'ouvrait sans second facteur. La relecture ne rend pas la fin de purge atomique
 * contre un écrivain concurrent, c'est le mot pour mot du constat.
 *
 * Une transaction ne peut pas couvrir ce cas — le PIN ne vit pas en base, et la purge appelle le
 * fournisseur système, qu'on ne tient pas sous un verrou SQLite. La barrière ferme la seule
 * porte qui compte : **on n'entre pas au coffre pendant qu'on le vide.** En sortir reste permis,
 * une sortie ne peut que réduire ce que la purge doit détruire.
 *
 * En mémoire, volontairement : une purge ne survit pas au processus, une barrière durable
 * resterait levée après une mort du processus et bloquerait le coffre pour rien.
 */
@Singleton
class VaultPurgeBarrier @Inject constructor() {

    private val purgeEnCours = AtomicBoolean(false)

    val enCours: Boolean get() = purgeEnCours.get()

    /**
     * Exécute [bloc] barrière levée, et l'abaisse **quoi qu'il arrive** — une purge qui lève
     * laisserait sinon le coffre fermé aux entrées jusqu'au prochain démarrage.
     * Une purge déjà en cours refuse la seconde : deux balayages entrelacés compteraient double.
     */
    suspend fun <T> pendant(bloc: suspend () -> T): T {
        check(purgeEnCours.compareAndSet(false, true)) { "purge du coffre deja en cours" }
        try {
            return bloc()
        } finally {
            purgeEnCours.set(false)
        }
    }
}
