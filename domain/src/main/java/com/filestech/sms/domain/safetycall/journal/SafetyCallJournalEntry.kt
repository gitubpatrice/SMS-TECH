package com.filestech.sms.domain.safetycall.journal

/**
 * v1.28.0 — une ligne du journal technique du Safety call.
 *
 * ⚠️ **Non câblé à ce stade** — voir [SafetyCallJournalEvent].
 *
 * # Format : sept champs, une ligne, un fait
 *
 * ```
 * wallMs|elapsedMs|gen|claim|ÉVÉNEMENT|sujet|détails
 * ```
 * ```
 * 1786015774123|53780340|7|142|WAKE|warning|nominal=13:29:34 retard=+7m21s doze=1
 * 1786015774456|53780340|7|142|NEXT|deadline|at=13:44:34
 * 1786017018487|54680340|7|142|SEND|1/2|to=a3f1‥41 ok
 * 1786017018632|54680340|7|142|SEND|2/2|to=7b02‥67 fail=GENERIC_FAILURE
 * ```
 *
 * Même choix de format trivial que `SafetyCallHistoryCodec`, et pour les mêmes raisons : aucune
 * dépendance Android-only, schéma fermé, lisible et débuggable tel quel.
 *
 * # Les deux horloges, obligatoirement
 *
 * [wallMs] **et** [elapsedMs] sur chaque ligne. Le moteur est un deadman à **double horloge** — il
 * n'expire que si les deux le disent, précisément pour qu'avancer l'horloge du système ne suffise
 * pas à déclencher l'alerte. Un journal qui n'en noterait qu'une ne permettrait pas de rejouer sa
 * décision, et l'exercice serait vain.
 *
 * # [generation] et [claimId] sur chaque ligne
 *
 * Le 2026-08-05, comprendre que « 7 messages envoyés » correspondait à **trois séquences distinctes**
 * et non à une séquence trop bavarde a coûté deux corrections successives. Avec ces deux colonnes,
 * la question se répond en triant.
 *
 * # Assainissement : le séparateur et les sauts de ligne ne peuvent pas entrer
 *
 * [sujet][subject] et [détails][details] traversent [SANITIZE] à la mise en forme. Un `|` laissé
 * passer décalerait les champs, et un `\n` couperait la ligne en deux entrées dont l'une serait
 * illisible et l'autre mensongère. Le journal décrit une fonction de sécurité : une ligne qui ment
 * est pire qu'une ligne absente.
 *
 * @param wallMs `System.currentTimeMillis()` au moment du fait.
 * @param elapsedMs `SystemClock.elapsedRealtime()` au même moment. Passé en paramètre plutôt que lu
 *   ici : ce type reste pur et testable sans appareil.
 * @param generation `SafetyCallConfig.generation` — quel cycle parle.
 * @param claimId `SafetyCallConfig.claimId` — quel worker parle, à l'intérieur du cycle.
 * @param event nature du fait.
 * @param subject jeton court qualifiant le fait : `warning`, `deadline`, `relance1`, `1/2`, `armed`.
 * @param details texte libre assaini. Peut être vide.
 */
data class SafetyCallJournalEntry(
    val wallMs: Long,
    val elapsedMs: Long,
    val generation: Long,
    val claimId: Long,
    val event: SafetyCallJournalEvent,
    val subject: String,
    val details: String = "",
) {

    /** La ligne telle qu'elle est écrite dans le fichier, **sans** saut de ligne terminal. */
    fun format(): String = buildString {
        append(wallMs).append(FIELD_SEPARATOR)
        append(elapsedMs).append(FIELD_SEPARATOR)
        append(generation).append(FIELD_SEPARATOR)
        append(claimId).append(FIELD_SEPARATOR)
        append(event.name).append(FIELD_SEPARATOR)
        append(sanitize(subject)).append(FIELD_SEPARATOR)
        append(sanitize(details))
    }

    companion object {

        internal const val FIELD_SEPARATOR = '|'

        /** Nombre de champs du format. Les six premiers sont fermés, le septième est libre. */
        private const val FIELD_COUNT = 7

        /** Champs numériques en tête : `wallMs`, `elapsedMs`, `generation`, `claimId`. */
        private const val NUMERIC_FIELD_COUNT = 4

        /**
         * Tout C0/C1 — sauts de ligne compris — plus le séparateur de champs. Même liste que
         * `SafetyCallHistoryCodec.FORBIDDEN_FIELD_CHARS`, moins le `;` qui n'a pas de rôle ici.
         */
        private val SANITIZE = Regex("[\\u0000-\\u001F\\u007F|]")

        private fun sanitize(value: String): String = value.replace(SANITIZE, "").trim()

        /**
         * Relit une ligne, ou rend `null` si elle est illisible.
         *
         * **Tolérant par construction**, comme `SafetyCallHistoryCodec.decode` : un fichier tronqué
         * par un arrêt brutal, une ligne écrite par une version antérieure du format, ou un octet
         * corrompu ne doivent pas empêcher de lire le reste. Perdre une ligne de diagnostic est
         * bénin ; ne plus pouvoir ouvrir le journal du tout au moment où on en a besoin ne l'est pas.
         *
         * Un événement inconnu fait rejeter la ligne plutôt que de la ranger dans une valeur par
         * défaut : une ligne mal classée est pire qu'une ligne perdue, parce qu'elle serait comptée.
         */
        fun parse(line: String): SafetyCallJournalEntry? {
            // `limit` : le dernier champ garde ses éventuels séparateurs internes plutôt que d'être
            // tronqué. [sanitize] les interdit à l'écriture, mais un fichier venu d'ailleurs, non.
            val parts = line.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
            if (parts.size < FIELD_COUNT) return null
            // Les quatre champs numériques sont validés **ensemble**, par le dénombrement de ceux qui
            // se sont laissé lire : `mapNotNull` écarte les illisibles, donc une taille inférieure au
            // compte attendu signifie « au moins un champ est abîmé ».
            //
            // Cette forme n'est pas un détour gratuit. Écrire les quatre validations en une seule
            // condition dépasse le seuil de `ComplexCondition`, et les échelonner en autant de
            // `?: return null` dépasse celui de `ReturnCount` : les deux règles se referment sur la
            // version naïve. Le dénombrement satisfait les deux **et** dit la même chose — « cette
            // ligne est-elle lisible » — d'un seul tenant, sans `!!` ni valeur de repli mensongère.
            val numbers = parts.take(NUMERIC_FIELD_COUNT).mapNotNull { it.trim().toLongOrNull() }
            val event = SafetyCallJournalEvent.entries.firstOrNull { it.name == parts[4].trim() }
            if (numbers.size < NUMERIC_FIELD_COUNT || event == null) return null
            return SafetyCallJournalEntry(
                wallMs = numbers[0],
                elapsedMs = numbers[1],
                generation = numbers[2],
                claimId = numbers[3],
                event = event,
                subject = parts[5].trim(),
                details = parts[6].trim(),
            )
        }
    }
}
