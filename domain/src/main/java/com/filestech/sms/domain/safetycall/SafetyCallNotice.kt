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
     *
     * # v1.27.3 — [terminal], et le défaut qu'il ferme
     *
     * La notification disait, jusqu'à la dernière alerte incluse : « Appuyez si vous allez bien :
     * **les relances s'arrêteront** ». Après le quatrième message il n'y a plus rien à arrêter, et
     * surtout le Safety call est **déjà désactivé** — le désarmement de fin de séquence est écrit
     * dans la même transaction que la conclusion du dernier envoi.
     *
     * 🔴 Le texte ne le disait pas. Quelqu'un lisant cette notification croyait donc son deadman
     * encore en veille, alors qu'aucune alarme n'était même programmée : `isExpired`,
     * `isInWarningWindow` et `nextWakeUpAt` se taisent tous les trois dès que `isTriggered`. Croire
     * à une protection qui n'existe pas est le pire état que cette fonction puisse produire — pire
     * qu'une fausse alerte, qui au moins se constate.
     *
     * L'état terminal est donc porté explicitement jusqu'à l'afficheur, pour qu'il puisse dire les
     * deux choses vraies : les messages sont partis, **et** la protection est coupée.
     */
    data class Sequence(
        val delivered: Int,
        val total: Int,
        val inFlight: Boolean,
        val terminal: Boolean,
        val triggeredAt: Long,
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
            if (isDecoy) return None
            // 🔴 v1.27.2 (relecture Gemini du 2026-08-05, F-01) — UNE SEQUENCE TERMINEE DOIT
            // RESTER VISIBLE.
            //
            // `!enabled` renvoyait `None` sans distinguer les deux facons d'etre desarme. Or le
            // desarmement de FIN DE SEQUENCE est ecrit dans la meme transaction que la conclusion
            // du dernier envoi : la notification disparaissait donc **a l'instant precis ou la
            // quatrieme alerte partait**.
            //
            // Quelqu'un qui avait oublie son telephone le reprend une heure plus tard et ne voit
            // AUCUNE trace : il ignore que ses quatre contacts ont recu un appel a l'aide et sont
            // peut-etre en train d'appeler les secours. C'est le pire etat possible apres un
            // declenchement — l'alerte est partie, et seul celui qu'elle concerne l'ignore.
            //
            // Elle reste donc affichee tant que la sequence a eu lieu. Un tap la retire, en
            // passant par le meme chemin que « je vais bien » : `withActivityReset` remet
            // `messagesSent` a zero, et cette condition devient fausse d'elle-meme.
            // Sequence en cours OU terminee : dans les deux cas elle reste affichee. Le
            // desarmement de fin de sequence ne doit pas l'effacer — voir ci-dessus.
            val sequenceVisible = cfg.isTriggered &&
                (cfg.hasRelancePending || cfg.isSendInFlight || cfg.messagesDelivered > 0)
            return when {
                sequenceVisible -> Sequence(
                    delivered = cfg.messagesDelivered,
                    total = SafetyCallConfig.TOTAL_MESSAGES,
                    inFlight = cfg.isSendInFlight,
                    // v1.27.3 — `messagesDelivered >= TOTAL_MESSAGES` est **exactement** l'état
                    // terminal, et pas une approximation : `messagesDelivered` vaut
                    // `messagesSent − (bail ? 1 : 0)`, donc l'atteindre impose à la fois que les
                    // quatre créneaux soient consommés et qu'aucun envoi ne soit en vol. Tester
                    // `!hasRelancePending` à la place aurait rendu `true` dès la réservation du
                    // dernier créneau, c'est-à-dire **pendant** l'envoi de la dernière alerte.
                    terminal = cfg.messagesDelivered >= SafetyCallConfig.TOTAL_MESSAGES,
                    // v1.27.3 — l'heure du DÉCLENCHEMENT, pour que la notification l'affiche au
                    // lieu de l'heure de sa dernière publication.
                    //
                    // 🔴 Le défaut : le constructeur de notification met `when` à l'heure courante
                    // par défaut, et la réconciliation se rejoue à chaque démarrage à froid du
                    // processus. La notification était donc réhorodatée à « maintenant »,
                    // remontait en tête du volet et se présentait comme une alerte NEUVE —
                    // mesuré sur appareil le 2026-08-06 : déclenchée à 23:53, elle affichait
                    // 11:01 le lendemain matin. Sur une fonction de sécurité, faire croire à un
                    // second déclenchement qui n'a pas eu lieu est inacceptable.
                    //
                    // Cette valeur est stable sur tout le cycle : elle n'ajoute donc aucune
                    // republication au `distinctUntilChanged` de l'appelant.
                    triggeredAt = cfg.triggeredAt,
                )
                !cfg.enabled -> None
                cfg.isInWarningWindow(nowMs, nowMonoMs) ->
                    Warning(hoursLeft = hoursLeft(cfg, nowMs, nowMonoMs))
                else -> None
            }
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
