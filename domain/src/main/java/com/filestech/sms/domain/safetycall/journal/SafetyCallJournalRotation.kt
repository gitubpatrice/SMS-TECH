package com.filestech.sms.domain.safetycall.journal

/**
 * v1.28.0 — borne le journal technique **par cycles entiers**, et non à l'octet.
 *
 * ⚠️ **Non câblé à ce stade** — voir [SafetyCallJournalEvent].
 *
 * # Pourquoi pas une simple troncature à la taille
 *
 * Tronquer à l'octet coupe **au milieu d'une séquence**, et détruit précisément la corrélation qu'on
 * vient chercher : la ligne `TRIGGER` qui ouvre le cycle, les `SEND` qui le composent et la ligne
 * `NEXT` dont l'absence de suite dénonce un moteur mort n'ont de sens qu'ensemble. Un fichier tronqué
 * au mauvais endroit répondrait « il manque des lignes » à la question « qu'a fait le moteur ».
 *
 * Le contrat est donc : **« les N dernières séquences, complètes »**. Un cycle est identifié par sa
 * [SafetyCallJournalEntry.generation], que `SafetyCallConfig.withActivityReset` incrémente à chaque
 * nouveau cycle — l'identité existe déjà, il n'y avait pas à en inventer une.
 *
 * # Le plafond en octets est un garde-fou, et il doit CONVERGER
 *
 * [MAX_BYTES] n'intervient que si un **unique** cycle devient pathologique : une boucle de réveils,
 * par exemple — exactement le genre de défaut que ce journal existe pour diagnostiquer. Le bornage
 * par cycles est alors sans effet, puisqu'il n'y a qu'un cycle à garder.
 *
 * ⚠️ Ce garde-fou s'exprime en **octets**, et non en nombre de lignes, pour une raison de fond : le
 * champ `détails` est de longueur libre, donc **aucun nombre de lignes ne borne une taille**. Une
 * première version plafonnait à 2 000 lignes alors que l'élagage se déclenche à 32 Ko ; 2 000 lignes
 * pesant environ 200 Ko, l'élagage ne ramenait jamais le fichier sous son propre seuil, et **chaque
 * écriture suivante relisait puis réécrivait le fichier entier** — sur le chemin qu'on veut justement
 * laisser tranquille. Une garde qui ne converge pas est pire qu'une garde absente : elle coûte à
 * chaque passage sans jamais rien régler.
 *
 * [MAX_BYTES] doit donc rester **nettement inférieur** au seuil de déclenchement de l'appelant, de
 * sorte qu'un élagage laisse de la marge avant le suivant.
 */
object SafetyCallJournalRotation {

    /**
     * Nombre de cycles conservés. Cinq : assez pour comparer un déclenchement à ceux qui l'ont
     * précédé — c'est en comparant trois séquences entre elles qu'on a compris, le 2026-08-05, que
     * « 7 messages » n'était pas une séquence trop bavarde — et assez peu pour qu'un journal oublié
     * actif ne devienne pas une archive.
     */
    const val MAX_CYCLES: Int = 5

    /**
     * Garde-fou convergent, en octets du texte conservé. Voir la note de classe : doit rester
     * largement sous le seuil de déclenchement côté appelant, et ne jamais mordre en fonctionnement
     * normal — un cycle complet pèse quelques kilo-octets.
     */
    const val MAX_BYTES: Int = 12 * 1024

    /**
     * Rend les lignes à conserver, dans leur ordre d'origine.
     *
     * Les lignes illisibles sont **écartées** : une ligne tronquée par un arrêt brutal ou écrite par
     * un format antérieur ne doit pas occuper une place ni fausser le dénombrement des cycles. C'est
     * la même tolérance que `SafetyCallHistoryCodec.decode`, appliquée ici au fichier entier.
     *
     * @param lines contenu du journal, une ligne par élément.
     * @param maxCycles nombre de cycles à garder, les plus récents.
     * @param maxBytes plafond convergent, garde-fou.
     */
    fun prune(
        lines: List<String>,
        maxCycles: Int = MAX_CYCLES,
        maxBytes: Int = MAX_BYTES,
    ): List<String> {
        val parsed = lines.mapNotNull { line ->
            SafetyCallJournalEntry.parse(line)?.let { entry -> line to entry.generation }
        }
        if (parsed.isEmpty()) return emptyList()
        // Ordre d'**apparition** et non ordre numérique : dans un fichier en ajout seul, les deux
        // coïncident, et l'ordre d'apparition reste juste si un fichier venu d'ailleurs présente des
        // générations dans un ordre inattendu.
        val generations = LinkedHashSet<Long>()
        parsed.forEach { (_, generation) -> generations.add(generation) }
        val keptGenerations = generations.toList().takeLast(maxCycles).toSet()
        val byCycle = parsed
            .filter { (_, generation) -> generation in keptGenerations }
            .map { (line, _) -> line }
        return trimToBudget(byCycle, maxBytes)
    }

    /**
     * Retire les lignes les **plus anciennes** jusqu'à tenir dans le budget.
     *
     * Ce sont les plus récentes qui survivent : sur une boucle de réveils, l'état courant renseigne
     * davantage que son point de départ. C'est le seul endroit où un cycle peut être amputé, et
     * uniquement dans ce cas pathologique — le moindre mal face à une croissance non bornée.
     */
    private fun trimToBudget(lines: List<String>, maxBytes: Int): List<String> {
        if (maxBytes <= 0) return emptyList()
        var budget = maxBytes
        var firstKept = lines.size
        for (index in lines.indices.reversed()) {
            // Octets UTF-8 réels, et non `length` : le jeton de destinataire contient U+2025, qui
            // pèse trois octets pour un seul caractère. Compter des caractères sous-estimerait la
            // taille du fichier — d'un facteur allant jusqu'à trois — et la garde cesserait de
            // converger précisément sur les lignes qui portent des destinataires. L'allocation par
            // ligne est sans conséquence : ce chemin ne s'emprunte qu'à l'élagage.
            //
            // +1 pour le saut de ligne que l'écrivain ajoute à chaque ligne.
            val cost = lines[index].toByteArray(Charsets.UTF_8).size + 1
            if (cost > budget) break
            budget -= cost
            firstKept = index
        }
        return lines.subList(firstKept, lines.size)
    }
}
