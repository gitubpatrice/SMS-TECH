package com.filestech.sms.data.contacts

import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.ContactPhone
import com.filestech.sms.domain.model.PhoneAddress

/**
 * 2026-11-27 (issue #109) — une ligne de `ContactsContract.CommonDataKinds.Phone` : UN numéro d'un contact.
 *
 * [e164] : `Phone.NORMALIZED_NUMBER`, la forme internationale que le carnet calcule lui-même, `null` quand il n'a
 * pas su ; [parDefaut] : `Phone.IS_SUPER_PRIMARY`, le numéro que l'utilisateur a choisi par défaut pour ce contact.
 */
internal data class LigneDeNumero(
    val contactId: Long,
    val nom: String?,
    val numero: String,
    val photo: String?,
    val e164: String?,
    val type: Int,
    val libelle: String?,
    val parDefaut: Boolean,
)

/**
 * 2026-11-27 (issue #109) — **regroupe les numéros par contact, sans doublon.**
 *
 * Le fournisseur rend une ligne par numéro. Les contacts gardent l'ordre de leur première ligne (le tri par nom
 * de la requête) ; dans un contact, le numéro par défaut vient en tête, les autres dans l'ordre du fournisseur.
 *
 * Un même numéro revient souvent plusieurs fois dans un contact : chaque compte lié (Google, une messagerie
 * qui synchronise le carnet…) apporte sa propre ligne, parfois écrite autrement (`06 12 34 56 78` et
 * `+33 6 12 34 56 78`). Jusqu'ici seule la première ligne s'affichait, ce qui les masquait ; maintenant que chaque
 * numéro a sa ligne à l'écran, un doublon y apparaîtrait. Un numéro est donc repris une seule fois : même forme
 * internationale calculée par le carnet, ou mêmes chiffres ([PhoneAddress.normalized]). Un numéro vide ne se
 * compose pas et n'est pas repris ; un contact sans aucun numéro utilisable disparaît de la liste.
 */
internal fun regrouperParContact(lignes: List<LigneDeNumero>): List<Contact> {
    val parContact = LinkedHashMap<Long, MutableList<LigneDeNumero>>()
    for (ligne in lignes) {
        if (ligne.numero.isNotBlank()) parContact.getOrPut(ligne.contactId) { ArrayList() } += ligne
    }
    return parContact.map { (id, siennes) ->
        val vus = HashSet<String>()
        // `sortedByDescending` est stable : hors numéro par défaut, l'ordre du fournisseur est gardé.
        val numeros = siennes.sortedByDescending { it.parDefaut }.mapNotNull { ligne ->
            val adresse = PhoneAddress.of(ligne.numero)
            val cles = listOfNotNull(adresse.normalized, ligne.e164?.trim()?.takeIf { it.isNotEmpty() })
            if (cles.any { it in vus }) {
                null
            } else {
                vus += cles
                ContactPhone(adresse, ligne.type, ligne.libelle?.takeIf { it.isNotBlank() })
            }
        }
        val premiere = siennes.first()
        Contact(id = id, displayName = premiere.nom, phones = numeros, photoUri = premiere.photo)
    }
}
