package com.filestech.sms.domain.safetycall

/**
 * v1.27.2 (audit Codex du 2026-08-05, P-05 / P-06) — **ce que le Safety call doit afficher**, à un
 * instant donné, sous forme de trois états mutuellement exclusifs.
 *
 * # Le défaut que ce type ferme
 *
 * Deux notifications distinctes coexistaient — l'avertissement de pré-déclenchement
 * (`NOTIF_ID_DEADMAN_WARNING`) et le suivi de séquence (`NOTIF_ID_SEQUENCE`) — sans que personne ne
 * possède les deux :
 *
 *  1. le worker posait l'avertissement dans la fenêtre de pré-déclenchement ;
 *  2. à l'expiration, il déclenchait l'envoi **sans jamais retirer cet avertissement** ;
 *  3. le réconciliateur publiait alors la séquence sous son propre identifiant ;
 *  4. les deux restaient affichées, `isInWarningWindow` devenant faux ne retirant rien — c'est un
 *     prédicat, pas un effet ;
 *  5. 🔴 à l'entrée en **mode leurre**, le réconciliateur ne retirait que la séquence.
 *     L'avertissement, lui, survivait à la session sous contrainte — révélant à l'agresseur qu'une
 *     fonction d'alerte existait et courait.
 *
 * Le point 5 est la raison d'être de ce fichier. Un mode leurre qui laisse une trace n'est pas un
 * mode leurre.
 *
 * # Pourquoi la décision vit dans le domaine
 *
 * Elle vivait dans `MainApplication`, donc hors de portée des tests — et c'est exactement pour ça
 * que les cinq points ci-dessus sont passés. Fonction **pure** : elle se teste sur les états réels
 * que produit `TriggerSafetyCallUseCase`, sans appareil ni gestionnaire de notifications.
 *
 * # L'invariant que le porteur doit tenir
 *
 * Les trois états sont exclusifs *par construction* : [decide] n'en rend qu'un. À charge de
 * l'afficheur de **retirer l'autre identifiant avant de publier**, jamais l'inverse — sans quoi une
 * fraction de seconde à deux notifications rouvrirait le défaut.
 */
sealed interface SafetyCallNotice {

    /** Rien à afficher : désarmé, hors fenêtre, séquence close, ou session leurre. */
    data object None : SafetyCallNotice

    /**
     * Fenêtre de pré-déclenchement : « confirmez que vous allez bien ».
     *
     * [hoursLeft] est **arrondi à l'heure**, et pas un nombre de millisecondes, parce que c'est
     * exactement ce que la notification affiche. Le réconciliateur déduplique sur cette valeur :
     * porter les millisecondes brutes republierait la notification à chaque écriture de
     * configuration, y compris le jalon horaire qui n'a rien changé de visible.
     */
    data class Warning(val hoursLeft: Int) : SafetyCallNotice

    /**
     * Séquence d'alerte en cours : « alerte envoyée, N message(s) sur M ».
     *
     * [delivered] compte les envois **conclus**, jamais les créneaux réservés — voir
     * [SafetyCallConfig.messagesDelivered]. [inFlight] dit qu'un envoi est en cours : tant qu'il
     * est vrai avec `delivered == 0`, rien n'est encore parti et la notification ne doit rien
     * affirmer.
     */
    data class Sequence(
        val delivered: Int,
        val total: Int,
        val inFlight: Boolean,
    ) : SafetyCallNotice

    companion object {

        /**
         * Décide de l'affichage à partir de l'état persisté et de l'état du verrou.
         *
         * L'ordre des cas est l'ordre de priorité, et il est délibéré :
         *
         *  1. **mode leurre** d'abord, et il gagne sur tout — c'est la garde de sécurité ;
         *  2. **séquence ouverte** ensuite : l'alerte est partie, l'avertissement qui l'annonçait
         *     n'a plus d'objet ;
         *  3. **fenêtre de pré-déclenchement** enfin ;
         *  4. sinon rien.
         *
         * La condition de séquence inclut `isSendInFlight` : sur le tout dernier créneau,
         * `hasRelancePending` devient faux **dès la réservation**, et la notification aurait
         * disparu pendant l'envoi du dernier message — privant l'utilisateur de son seul moyen de
         * l'arrêter.
         */
        fun decide(
            cfg: SafetyCallConfig,
            isDecoy: Boolean,
            nowMs: Long,
            nowMonoMs: Long,
        ): SafetyCallNotice {
            if (isDecoy || !cfg.enabled) return None
            val sequenceRunning = cfg.isTriggered && (cfg.hasRelancePending || cfg.isSendInFlight)
            if (sequenceRunning) {
                return Sequence(
                    delivered = cfg.messagesDelivered,
                    total = SafetyCallConfig.TOTAL_MESSAGES,
                    inFlight = cfg.isSendInFlight,
                )
            }
            if (!cfg.isInWarningWindow(nowMs, nowMonoMs)) return None
            return Warning(hoursLeft = hoursLeft(cfg, nowMs, nowMonoMs))
        }

        /**
         * Heures restantes avant déclenchement, sur **la plus lointaine des deux horloges**.
         *
         * Le deadman n'expire que quand la murale **et** la monotone ont expiré ; annoncer la seule
         * murale promettrait un déclenchement que le compteur monotone retarde encore — par exemple
         * après un redémarrage, où le segment non jalonné est perdu.
         */
        private fun hoursLeft(cfg: SafetyCallConfig, nowMs: Long, nowMonoMs: Long): Int {
            val wallRemaining = (cfg.lastActivityAt + cfg.timeoutMs) - nowMs
            val monoRemaining = cfg.timeoutMs - cfg.monoElapsedMs(nowMonoMs)
            val remaining = maxOf(wallRemaining, monoRemaining).coerceAtLeast(0L)
            return (remaining / 3_600_000L).toInt()
        }
    }
}
