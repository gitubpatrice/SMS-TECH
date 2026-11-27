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
 * internationale calculée par le carnet, ou mêmes chiffres ([PhoneAddress.normalized]) — cf. [numerosSansDoublon].
 * Limite : deux écritures nationale et internationale dont le carnet n'a pas calculé la forme internationale (pays
 * inconnu à l'enregistrement) restent deux lignes.
 *
 * Un « numéro » de moins de [CHIFFRES_MIN] chiffres (vide, « N/A », « voir mail ») ne se compose pas : il n'est pas
 * repris, comme la saisie libre ne propose pas un nom tapé (`ComposeViewModel.estComposable`, X-06) ; un contact sans
 * aucun numéro utilisable disparaît de la liste. Jusqu'ici, un tel premier numéro ouvrait une conversation dont
 * chaque envoi échouait.
 */
internal fun regrouperParContact(lignes: List<LigneDeNumero>): List<Contact> {
    val parContact = LinkedHashMap<Long, MutableList<LigneDeNumero>>()
    for (ligne in lignes) {
        if (ligne.numero.count(Char::isDigit) >= CHIFFRES_MIN) {
            parContact.getOrPut(ligne.contactId) { ArrayList() } += ligne
        }
    }
    return parContact.map { (id, siennes) ->
        val premiere = siennes.first()
        Contact(id = id, displayName = premiere.nom, phones = numerosSansDoublon(siennes), photoUri = premiere.photo)
    }
}

/** Un numéro composable a au moins deux chiffres : la règle de la saisie libre (X-06), numéros courts compris. */
private const val CHIFFRES_MIN = 2

/**
 * Les numéros d'UN contact, celui par défaut en tête, les autres dans l'ordre du fournisseur (`sortedByDescending`
 * est stable), chacun une fois.
 *
 * Une ligne est un doublon dès qu'une de ses clés — ses chiffres, sa forme internationale — a déjà été vue ; ses
 * clés rejoignent alors celles du numéro qu'elle répète ([ContactPhone.ecritures]). Ainsi une troisième écriture qui
 * ne partage une clé qu'avec la deuxième, écartée, est reconnue elle aussi, et la recherche trouve le numéro sous
 * chacune de ses écritures (relecture Claude du 2026-11-27 : les clés d'une ligne écartée étaient perdues).
 */
private fun numerosSansDoublon(siennes: List<LigneDeNumero>): List<ContactPhone> {
    val gardes = ArrayList<ContactPhone>()
    val rangDe = HashMap<String, Int>()
    for (ligne in siennes.sortedByDescending { it.parDefaut }) {
        val adresse = PhoneAddress.of(ligne.numero)
        val cles = listOfNotNull(adresse.normalized, ligne.e164?.trim()?.takeIf { it.isNotEmpty() })
        val rang = cles.firstNotNullOfOrNull { rangDe[it] }
        if (rang == null) {
            gardes += ContactPhone(adresse, ligne.type, ligne.libelle?.takeIf { it.isNotBlank() }, cles.toSet())
            cles.forEach { rangDe[it] = gardes.lastIndex }
        } else {
            gardes[rang] = gardes[rang].copy(ecritures = gardes[rang].ecritures + cles)
            cles.forEach { rangDe.putIfAbsent(it, rang) }
        }
    }
    return gardes
}
