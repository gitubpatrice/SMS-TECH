package com.filestech.sms.domain.mms

/**
 * 2026-11-06 (issue #76) — **les destinataires d'un MMS adressé à plusieurs**, gardés avec le message.
 *
 * Un MMS de groupe porte dans son en-tête tous ses destinataires (`To`, `Cc`), l'utilisateur compris. Quand
 * le mode « MMS de groupe » est coupé (le défaut) ou que « Mon numéro » manque, le message est rangé dans la
 * conversation de son expéditeur ([GroupMmsMembers] rend `null`) : les autres destinataires n'étaient alors
 * gardés NULLE PART, puisque le PDU, seul à les porter, est effacé après traitement. L'utilisateur ne pouvait
 * pas savoir que le message était parti à plusieurs, ni à qui.
 *
 * La liste est gardée telle que l'en-tête la porte, l'utilisateur compris : c'est l'écran qui le reconnaît
 * quand il connaît son numéro. Retirer ici un numéro qu'on ne sait pas toujours identifier rendrait une
 * liste tantôt complète, tantôt amputée, sans que rien ne le dise.
 */
object DestinatairesMms {

    /** Séparateur de la colonne `messages.mms_recipients`, le même que celui de `conversations.addresses_csv`. */
    const val SEPARATEUR = ";"

    /** Jeton réservé d'AOSP, posé à la place d'une adresse que le système complète lui-même. */
    private const val JETON_AOSP = "insert-address-token"

    /**
     * Les destinataires (`To` puis `Cc`), sans doublon ni jeton réservé, ou `null` s'il en reste moins de
     * deux : un MMS adressé à une seule personne n'est pas un envoi groupé.
     */
    fun of(to: List<String>, cc: List<String>): List<String>? {
        val vus = LinkedHashSet<String>()
        for (brut in to + cc) {
            val adresse = brut.trim()
            // Une adresse qui contient le séparateur ne se relirait pas : elle est écartée, pas coupée.
            if (adresse.isEmpty() || adresse == JETON_AOSP || SEPARATEUR in adresse) continue
            vus += adresse
        }
        return vus.toList().takeIf { it.size >= 2 }
    }

    /** La valeur de la colonne : `null` hors envoi groupé. */
    fun encoder(destinataires: List<String>?): String? =
        destinataires?.takeIf { it.size >= 2 }?.joinToString(SEPARATEUR)

    /** La liste lue de la colonne ; vide pour `null`. */
    fun decoder(colonne: String?): List<String> =
        colonne?.split(SEPARATEUR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
