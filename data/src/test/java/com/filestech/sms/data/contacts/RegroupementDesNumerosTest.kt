package com.filestech.sms.data.contacts

import com.filestech.sms.domain.model.ContactPhone
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #109) — « If a contact has multiple phone numbers, only the last one can be selected. »
 *
 * [regrouperParContact] rend, pour chaque contact, CHACUN de ses numéros : c'est ce qui permet à « Nouveau message »
 * d'en proposer une ligne par numéro. Ces tests tiennent la règle : tous les numéros, celui par défaut en tête,
 * sans doublon d'écriture, ni numéro vide.
 */
class RegroupementDesNumerosTest {

    private fun ligne(
        contact: Long,
        numero: String,
        nom: String = "Contact $contact",
        e164: String? = null,
        type: Int = ContactPhone.TYPE_LIBRE,
        libelle: String? = null,
        parDefaut: Boolean = false,
    ) = LigneDeNumero(contact, nom, numero, photo = null, e164, type, libelle, parDefaut)

    @Test
    fun `un contact a trois numeros les garde tous, dans l'ordre du carnet`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "06 11 11 11 11", nom = "Alice", type = MOBILE),
                ligne(1, "01 22 22 22 22", nom = "Alice", type = TRAVAIL),
                ligne(1, "+44 7700 900123", nom = "Alice", type = DOMICILE),
            ),
        )

        val alice = contacts.single()
        assertThat(alice.displayName).isEqualTo("Alice")
        assertThat(alice.phones.map { it.address.raw })
            .containsExactly("06 11 11 11 11", "01 22 22 22 22", "+44 7700 900123").inOrder()
        assertThat(alice.phones.map { it.type }).containsExactly(MOBILE, TRAVAIL, DOMICILE).inOrder()
    }

    @Test
    fun `le numero par defaut vient en tete, les autres gardent leur ordre`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "0611111111"),
                ligne(1, "0622222222"),
                ligne(1, "0633333333", parDefaut = true),
                ligne(1, "0644444444"),
            ),
        )

        assertThat(contacts.single().phones.map { it.address.raw })
            .containsExactly("0633333333", "0611111111", "0622222222", "0644444444").inOrder()
    }

    /** Deux comptes liés au même contact apportent chacun la ligne du même numéro, parfois écrite autrement. */
    @Test
    fun `un meme numero ecrit deux fois n'est repris qu'une fois`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "06 12 34 56 78", e164 = "+33612345678", type = MOBILE),
                // Même forme internationale, autre écriture (compte d'une messagerie).
                ligne(1, "+33 6 12 34 56 78", e164 = "+33612345678"),
                // Mêmes chiffres, sans forme internationale calculée.
                ligne(1, "06-12-34-56-78"),
                ligne(1, "07 12 34 56 78", e164 = "+33712345678"),
            ),
        )

        val numeros = contacts.single().phones
        assertThat(numeros.map { it.address.raw }).containsExactly("06 12 34 56 78", "07 12 34 56 78").inOrder()
        // La première écriture rencontrée est gardée, avec son type.
        assertThat(numeros.first().type).isEqualTo(MOBILE)
        // Et le numéro se cherche sous chacune de ses écritures (relecture Claude du 2026-11-27).
        assertThat(numeros.first().ecritures).containsExactly("0612345678", "+33612345678")
    }

    /**
     * Relecture Claude du 2026-11-27 : une ligne écartée gardait ses clés pour elle. La troisième écriture ne partage
     * rien avec la première, seulement ses chiffres avec la deuxième, déjà écartée : elle doit l'être aussi.
     */
    @Test
    fun `un doublon de doublon est reconnu`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "06 12 34 56 78", e164 = "+33612345678"),
                ligne(1, "0033 6 12 34 56 78", e164 = "+33612345678"),
                ligne(1, "0033612345678"),
            ),
        )

        val numero = contacts.single().phones.single()
        assertThat(numero.address.raw).isEqualTo("06 12 34 56 78")
        assertThat(numero.ecritures).containsExactly("0612345678", "+33612345678", "0033612345678")
    }

    @Test
    fun `les lignes d'un contact se regroupent meme separees, les contacts gardent leur ordre`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(2, "0611111111", nom = "Bruno"),
                ligne(1, "0622222222", nom = "Chloé"),
                ligne(2, "0633333333", nom = "Bruno"),
            ),
        )

        assertThat(contacts.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(contacts.first().phones.map { it.address.raw }).containsExactly("0611111111", "0633333333").inOrder()
    }

    /** Un « numéro » sans au moins deux chiffres ne se compose pas : sa ligne ouvrirait une conversation sans issue. */
    @Test
    fun `un numero qui ne se compose pas est ecarte, un contact sans numero utilisable disparait`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "  "),
                ligne(1, "N/A"),
                ligne(1, "voir mail"),
                ligne(1, "7"),
                ligne(2, ""),
                ligne(2, "0611111111"),
                // Un numéro court reste : deux chiffres suffisent (urgences, services).
                ligne(2, "15"),
            ),
        )

        assertThat(contacts.map { it.id }).containsExactly(2L)
        assertThat(contacts.single().phones.map { it.address.raw }).containsExactly("0611111111", "15").inOrder()
    }

    @Test
    fun `le libelle personnalise suit son numero, un libelle vide n'en est pas un`() {
        val contacts = regrouperParContact(
            listOf(
                ligne(1, "0611111111", libelle = "Chalet"),
                ligne(1, "0622222222", libelle = "   "),
            ),
        )

        assertThat(contacts.single().phones.map { it.label }).containsExactly("Chalet", null).inOrder()
    }

    private companion object {
        // Valeurs de `ContactsContract.CommonDataKinds.Phone.TYPE_*`.
        const val DOMICILE = 1
        const val MOBILE = 2
        const val TRAVAIL = 3
    }
}
