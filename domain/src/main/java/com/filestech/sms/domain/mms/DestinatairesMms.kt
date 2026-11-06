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
 *
 * Les adresses sont rapprochées par leur **clé d'identité** ([of], paramètre `cle`), la règle de
 * [GroupMmsMembers] : un PDU peut écrire `+33612345678` et `06 12 34 56 78` pour la même personne.
 * L'expéditeur, qu'un opérateur recopie parfois dans `To`, n'est pas un destinataire.
 */
object DestinatairesMms {

    /** Séparateur de la colonne `messages.mms_recipients`, le même que celui de `conversations.addresses_csv`. */
    const val SEPARATEUR = ";"

    /**
     * Valeur de la colonne pour un message **vérifié** sans envoi groupé, posée par le rattrapage des MMS
     * importés : `NULL` y veut dire « pas encore regardé ». Se relit comme une liste vide.
     */
    const val VERIFIE_SANS_GROUPE = ""

    /** Jeton réservé d'AOSP, posé à la place d'une adresse que le système complète lui-même. */
    private const val JETON_AOSP = "insert-address-token"

    /**
     * Les destinataires (`To` puis `Cc`) sans doublon ni jeton réservé, ni [expediteur], ou `null` s'il en reste
     * moins de deux : un MMS adressé à une seule personne n'est pas un envoi groupé.
     *
     * @param cle la clé d'identité d'une adresse (`PhoneIdentity.Snapshot.key`) ; par défaut l'adresse elle-même.
     *   Une clé vide (adresse non reconnue) se rabat sur l'adresse.
     */
    fun of(
        to: List<String>,
        cc: List<String>,
        expediteur: String? = null,
        cle: (String) -> String = { it },
    ): List<String>? {
        fun cleDe(adresse: String) = cle(adresse).ifBlank { adresse }
        val cleExpediteur = expediteur?.trim()?.takeIf { it.isNotEmpty() }?.let(::cleDe)
        val vus = LinkedHashMap<String, String>()
        (to.asSequence() + cc.asSequence())
            .map { it.trim() }
            // Une adresse qui contient le séparateur ne se relirait pas : elle est écartée, pas coupée.
            .filter { it.isNotEmpty() && it != JETON_AOSP && SEPARATEUR !in it }
            .forEach { adresse ->
                val k = cleDe(adresse)
                if (k != cleExpediteur) vus.putIfAbsent(k, adresse)
            }
        return vus.values.toList().takeIf { it.size >= 2 }
    }

    /**
     * 2026-11-06 — **les membres d'un fil de groupe du fournisseur Android** (table `threads`), qui en fait une
     * conversation de groupe à l'import. Le fournisseur les tient sans l'utilisateur, sauf quand l'application qui
     * a écrit le fil n'a pas su le reconnaître : [soi] (« Mon numéro ») est donc retiré s'il est connu.
     *
     * Même nettoyage et même rapprochement que [of] ; `null` sous deux membres : le fil n'est pas un groupe. Une
     * adresse qui porte une virgule est écartée, comme celle qui porte le séparateur : `addresses_csv` se coupe
     * sur les deux (`PhoneAddress.list`).
     */
    fun membresDuFil(destinataires: List<String>, soi: String?, cle: (String) -> String = { it }): List<String>? =
        of(destinataires.filterNot { ',' in it }, emptyList(), expediteur = soi, cle = cle)

    /** La valeur de la colonne : `null` hors envoi groupé. */
    fun encoder(destinataires: List<String>?): String? =
        destinataires?.takeIf { it.size >= 2 }?.joinToString(SEPARATEUR)

    /** La liste lue de la colonne ; vide pour `null` comme pour [VERIFIE_SANS_GROUPE]. */
    fun decoder(colonne: String?): List<String> =
        colonne?.split(SEPARATEUR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
