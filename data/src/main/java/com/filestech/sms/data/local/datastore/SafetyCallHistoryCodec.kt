package com.filestech.sms.data.local.datastore

import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTriggerRecord

/**
 * v1.27.3 — sérialise/désérialise l'historique des déclenchements du Safety call pour stockage dans
 * DataStore (clé `K.safetyCallHistory`).
 *
 * **Format, une entrée par ligne** :
 * ```
 * 1785966836000|4|4|Maman;Papa
 * 1785941397000|2|4|0607231541
 * ```
 * `triggeredAt|messagesDelivered|totalMessages|destinataires séparés par ';'`
 *
 * Même choix de format trivial que [SafetyCallContactCodec], et pour les mêmes raisons : pas de
 * dépendance Android-only (`org.json` n'est pas pure JVM), schéma fermé à quatre champs, lisible et
 * débuggable tel quel.
 *
 * # Tolérant par construction
 *
 * Une entrée mal formée est **ignorée**, jamais fatale, et [decode] ne lève pas : un DataStore
 * restauré depuis une sauvegarde tierce ou tronqué par un arrêt brutal ne doit pas empêcher
 * l'application de lire le reste de ses réglages. Perdre une ligne d'historique est bénin ; perdre
 * la liste des contacts d'urgence parce qu'un codec a levé ne l'est pas.
 *
 * ⚠️ Le chiffrement n'entre pas ici : DataStore Preferences n'est pas chiffré, et ces libellés sont
 * les mêmes que ceux déjà stockés en clair par [SafetyCallContactCodec]. L'historique n'ajoute donc
 * aucune exposition — c'est le **mode leurre** qui le masque, au niveau de l'accès.
 */
internal object SafetyCallHistoryCodec {

    private const val RECORD_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = '|'
    private const val RECIPIENT_SEPARATOR = ';'

    /**
     * Caractères interdits dans un libellé de destinataire : tout C0/C1 plus les deux séparateurs
     * du format. Strippés à l'encodage — un nom de contact contenant `|` ou `;` décalerait les
     * champs de toutes les entrées suivantes.
     */
    private val FORBIDDEN_FIELD_CHARS = Regex("[\\u0000-\\u001F\\u007F|;]")

    fun encode(history: List<SafetyCallTriggerRecord>): String =
        history.takeLast(SafetyCallConfig.MAX_HISTORY).joinToString(RECORD_SEPARATOR) { r ->
            val recipients = r.recipients
                .map { it.replace(FORBIDDEN_FIELD_CHARS, "").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(RECIPIENT_SEPARATOR.toString())
            "${r.triggeredAt}$FIELD_SEPARATOR${r.messagesDelivered}" +
                "$FIELD_SEPARATOR${r.totalMessages}$FIELD_SEPARATOR$recipients"
        }

    fun decode(raw: String?): List<SafetyCallTriggerRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line -> decodeRecord(line) }
            // Filet de dernier recours : un fichier gonflé par un bug antérieur ne doit pas
            // ressusciter une liste non bornée en mémoire.
            .toList()
            .takeLast(SafetyCallConfig.MAX_HISTORY)
    }

    private fun decodeRecord(line: String): SafetyCallTriggerRecord? {
        // `limit = 4` : le dernier champ garde ses éventuels séparateurs internes plutôt que d'être
        // tronqué. Ils ne peuvent pas s'y trouver après [encode], mais un fichier édité à la main
        // ou restauré d'ailleurs, si.
        val parts = line.split(FIELD_SEPARATOR, limit = 4)
        if (parts.size < 4) return null
        // Les trois validations sont regroupées plutôt qu'échelonnées en autant de retours : ce qui
        // rend une entrée illisible forme une seule condition, et la lire d'un bloc dit mieux ce
        // qu'est une entrée valide.
        //
        //  - une date nulle ou négative n'est pas affichable — c'est la seule information que
        //    l'utilisateur reconnaît, et mieux vaut perdre l'entrée qu'afficher « 1 janvier 1970 » ;
        //  - un compte nul décrirait un déclenchement n'ayant alerté personne, alors qu'une entrée
        //    n'est archivée que parce qu'au moins un message est parti ;
        //  - un total nul rendrait `isComplete` absurde et l'affichage « 2 sur 0 ».
        val triggeredAt = parts[0].trim().toLongOrNull()?.takeIf { it > 0L }
        val delivered = parts[1].trim().toIntOrNull()?.takeIf { it > 0 }
        val total = parts[2].trim().toIntOrNull()?.takeIf { it > 0 }
        if (triggeredAt == null || delivered == null || total == null) return null
        return SafetyCallTriggerRecord(
            triggeredAt = triggeredAt,
            // Borné au total : un fichier annonçant « 9 sur 4 » afficherait un compte impossible,
            // et [SafetyCallTriggerRecord.isComplete] resterait juste de toute façon.
            messagesDelivered = delivered.coerceAtMost(total),
            totalMessages = total,
            recipients = parts[3]
                .split(RECIPIENT_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }
}
