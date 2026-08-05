package com.filestech.sms.data.sync

/**
 * v1.27.2 (audit Codex du 2026-08-05, C-03 / C-04) — **la décision d'effacer**, isolée du monde.
 *
 * # Pourquoi cette extraction
 *
 * `reconcileDeletions` est le **seul chemin automatique de l'application qui supprime des
 * messages**. Il vivait entier dans [TelephonySyncManager], donc derrière un `ContentResolver`,
 * donc hors de portée de tout test JVM — et c'est précisément là que deux défauts ont vécu :
 *
 *  - le garde-fou de proportionnalité ne se déclenchait qu'**au-dessus** de 50 %. Une lecture
 *    partielle rendant 60 identifiants sur 100 donnait 40 % de manquants : aucune vérification, et
 *    **40 messages parfaitement valides étaient effacés définitivement** ;
 *  - la « seconde lecture » censée confirmer une suppression massive n'était pas une observation
 *    indépendante. Même fournisseur, même projection, même processus, quelques millisecondes
 *    d'écart : une troncature déterministe se reproduit à l'identique et validait l'effacement.
 *
 * Les deux venaient de la même erreur de raisonnement : **déduire une suppression de l'absence
 * dans une liste**. Une liste peut être incomplète ; c'est même le mode de défaillance normal
 * d'une lecture paginée.
 *
 * # Le protocole
 *
 *  1. **Candidats** — les lignes miroitées absentes de la lecture globale. À ce stade, ce ne sont
 *     que des *soupçons*, jamais des preuves.
 *  2. **Preuve individuelle** — chaque candidat est interrogé par son identifiant, via [probe].
 *     C'est une question fermée, insensible à la pagination : « celle-ci existe-t-elle ? ».
 *     `null` = on n'a pas pu savoir, et l'on ne touche alors à rien.
 *  3. **Deux passes** — l'absence doit être constatée **deux fois de suite** avant l'effacement.
 *     Ceinture par-dessus les bretelles : un fournisseur momentanément aveugle sur une passe ne
 *     détruit rien.
 *
 * Ne rien supprimer se rattrape toujours à la passe suivante ; supprimer à tort, jamais. Toute
 * ambiguïté penche donc vers la conservation.
 */
internal object DeletionReconciliation {

    /**
     * v1.27.2 (audit Codex final, F-04) — sondes unitaires autorisees par passe.
     *
     * 300 : assez pour absorber d'un coup toute suppression realiste faite depuis une autre
     * application, assez peu pour qu'une lecture globale defaillante sur un historique de plusieurs
     * dizaines de milliers de messages ne bloque pas la synchronisation.
     */
    const val MAX_PROBES_PER_PASS = 300

    /**
     * @param toDelete les URI dont l'absence est **prouvée individuellement et confirmée** sur deux
     *   passes. C'est la seule liste qu'il soit légitime d'effacer.
     * @param nextPending les URI vues absentes à cette passe, à reporter à la suivante.
     * @param unverified nombre de candidats dont l'absence n'a **pas** pu être prouvée. Un nombre
     *   élevé est la signature d'une lecture globale incomplète — à tracer, jamais à ignorer.
     */
    data class Decision(
        val toDelete: List<String>,
        val nextPending: Set<String>,
        val unverified: Int,
        /**
         * Candidats **non sondes** a cette passe, faute de budget. Ils reviendront au tour suivant.
         * Toujours a tracer : un nombre non nul signifie que la reconciliation n'a pas fini.
         */
        val deferred: Int,
    )

    /**
     * @param mirrored URI SMS présentes dans notre miroir.
     * @param aliveUris URI reconstituées depuis la lecture globale du fournisseur.
     * @param pending URI vues absentes à la passe **précédente**.
     * @param probe preuve individuelle : `true` = existe encore, `false` = réellement supprimée,
     *   `null` = indéterminable.
     */
    fun decide(
        mirrored: List<String>,
        aliveUris: Set<String>,
        pending: Set<String>,
        probe: (String) -> Boolean?,
    ): Decision {
        val candidates = mirrored.filterNot { it in aliveUris }
        if (candidates.isEmpty()) return Decision(emptyList(), emptySet(), 0, 0)
        // 🔴 v1.27.2 (audit Codex final du 2026-08-05, F-04) — LE NOMBRE DE SONDES EST BORNE.
        //
        // La preuve individuelle est une requete `ContentResolver` par candidat. Sur un historique
        // de 50 000 messages et un fournisseur vide — ou une lecture globale fortement tronquee —
        // c'etaient 50 000 requetes sequentielles, PUIS 50 000 de plus a la passe suivante, dans
        // le chemin normal de chaque synchronisation. Le mutex retenait les demandes suivantes, et
        // un processus tue en cours de balayage recommencait tout sans jamais converger.
        //
        // On en traite donc un lot par passe. La convergence est plus lente, jamais compromise :
        // les candidats restants sont recalcules au tour suivant. Une reconciliation qui prend
        // quelques passes de plus est sans consequence ; une synchronisation qui bloque plusieurs
        // minutes en a.
        val probed = candidates.take(MAX_PROBES_PER_PASS)
        val absent = probed.filter { probe(it) == false }
        return Decision(
            toDelete = absent.filter { it in pending },
            nextPending = absent.toSet(),
            unverified = probed.size - absent.size,
            deferred = candidates.size - probed.size,
        )
    }
}
