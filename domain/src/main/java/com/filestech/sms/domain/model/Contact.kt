package com.filestech.sms.domain.model

/**
 * Un contact du carnet d'adresses.
 *
 * 2026-11-27 (issue #109) — [phones] porte chacun des numéros du contact, avec son type. « Nouveau message » n'en
 * proposait qu'un, le premier que rendait le fournisseur (`firstPhone`, retiré) : un contact à plusieurs numéros
 * ne pouvait recevoir que sur celui-là. Défaut présent depuis la v1.2.0.
 */
data class Contact(
    val id: Long?,
    val displayName: String?,
    val phones: List<ContactPhone>,
    val photoUri: String? = null,
)

/**
 * 2026-11-27 (issue #109) — un numéro d'un contact, et son type tel que le carnet le donne.
 *
 * [type] : la valeur de `ContactsContract.CommonDataKinds.Phone.TYPE` (domicile, mobile, travail…) ; [TYPE_LIBRE]
 * pour un type personnalisé ou absent, auquel cas seul [label], le libellé saisi par l'utilisateur, a un sens. Le
 * texte affiché se résout dans l'écran, dans la langue de l'application.
 *
 * [ecritures] : les formes normalisées sous lesquelles le carnet connaît ce numéro — la sienne, sa forme
 * internationale, et celles des lignes qui le répétaient autrement (`06 12…` et `+33 6 12…`). La recherche par
 * chiffres les parcourt toutes : le numéro n'est affiché qu'une fois, mais se trouve sous chacune (relecture Claude
 * du 2026-11-27 : seule l'écriture gardée restait cherchable).
 */
data class ContactPhone(
    val address: PhoneAddress,
    val type: Int = TYPE_LIBRE,
    val label: String? = null,
    val ecritures: Set<String> = setOf(address.normalized),
) {
    companion object {
        /** `Phone.TYPE_CUSTOM` d'Android, qui vaut aussi pour un type absent : rien d'autre que [label] à afficher. */
        const val TYPE_LIBRE = 0
    }
}
