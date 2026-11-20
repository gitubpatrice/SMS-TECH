package com.filestech.sms.system.share

import com.filestech.sms.system.share.IncomingShareHolder.Cible
import com.filestech.sms.system.share.IncomingShareHolder.Pending

/**
 * 2026-11-20 — **ce que [com.filestech.sms.ui.AppRoot] fait d'un dépôt du relais de partage** ([IncomingShareHolder]).
 *
 * Extrait d'AppRoot pour être testé sans écran (`AiguillagePartageTest`) : c'est ici que vivait le défaut. La garde
 * SEC-03 (v1.4.1) effaçait tout dépôt arrivé pendant qu'un fil ou « Nouveau message » était ouvert — transferts
 * compris, lancés justement depuis un fil : le message transféré n'arrivait jamais dans la conversation choisie.
 *
 * Seul un partage venu d'une autre application ([Cible.AChoisir]) est aiguillé ici. Un transfert ou le texte d'un
 * lien `sms:` mène lui-même à sa conversation, qui le reprend ([IncomingShareHolder.consumePour]).
 */
internal object AiguillagePartage {

    enum class Decision {
        /** Ne rien faire : pas de dépôt, verrou, leurre, ou dépôt qui n'est pas un partage à choisir. */
        RIEN,

        /**
         * SEC-03 : partage arrivé alors qu'on est déjà dans un fil ou dans « Nouveau message ». Et tout dépôt expiré
         * (relecture Claude) : il n'irait plus nulle part, il ne doit pas rester dans le relais.
         */
        EFFACER,

        /** Ouvrir « Nouveau message » pour que l'utilisateur choisisse la conversation. */
        NOUVEAU_MESSAGE,
    }

    /**
     * Décide, puis agit : efface le dépôt, ou appelle [ouvrirNouveauMessage]. Hors d'AppRoot, dont la complexité est
     * bornée par detekt.
     */
    fun appliquer(
        relais: IncomingShareHolder,
        route: String?,
        verrouille: Boolean,
        leurre: Boolean,
        ouvrirNouveauMessage: () -> Unit,
    ) {
        when (decider(relais.pending.value, route, verrouille, leurre, System.currentTimeMillis())) {
            Decision.RIEN -> Unit
            Decision.EFFACER -> relais.clear()
            Decision.NOUVEAU_MESSAGE -> ouvrirNouveauMessage()
        }
    }

    fun decider(pending: Pending?, route: String?, verrouille: Boolean, leurre: Boolean, maintenant: Long): Decision {
        if (pending == null) return Decision.RIEN
        if (pending.isExpired(maintenant)) return Decision.EFFACER
        if (pending.cible != Cible.AChoisir || verrouille || leurre) return Decision.RIEN
        val dejaDansUnFil = route?.contains("Compose") == true || route?.contains("Thread") == true
        return if (dejaDansUnFil) Decision.EFFACER else Decision.NOUVEAU_MESSAGE
    }
}
