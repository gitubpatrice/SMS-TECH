package com.filestech.sms.data.sms

import android.database.Cursor
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — [FilsDuFournisseur] décode les tables du fournisseur qui disent qui est dans un fil.
 *
 * Ce que ces cas fixent : une adresse est nettoyée comme celle d'un PDU (suffixe de passerelle, caractères
 * invisibles) ; un identifiant de destinataire inconnu est ignoré sans faire tomber le fil ; un fil sans aucune
 * adresse est omis ; les messages d'un fil sont rendus sous la forme d'URI qu'écrit l'import.
 */
class FilsDuFournisseurTest {

    /** Un curseur de deux colonnes (Long, String?) sur [lignes]. */
    private fun curseur(vararg lignes: Pair<Long, String?>): Cursor {
        val c = mockk<Cursor>(relaxed = true)
        var i = -1
        every { c.moveToNext() } answers { ++i < lignes.size }
        every { c.getLong(0) } answers { lignes[i].first }
        every { c.getString(1) } answers { lignes[i].second }
        return c
    }

    /** Un curseur (`_id`, `thread_id`) de messages. */
    private fun messages(vararg lignes: Pair<Long, Long>): Cursor {
        val c = mockk<Cursor>(relaxed = true)
        var i = -1
        every { c.moveToNext() } answers { ++i < lignes.size }
        every { c.getLong(0) } answers { lignes[i].first }
        every { c.getLong(1) } answers { lignes[i].second }
        return c
    }

    @Test
    fun `adresses nettoyees, les vides omises`() {
        val adresses = FilsDuFournisseur.adresses(
            curseur(1L to "+15551230001/TYPE=PLMN", 2L to "\u200E+15551230002", 3L to "  ", 4L to null),
        )

        assertThat(adresses).containsExactly(1L, "+15551230001", 2L, "+15551230002")
    }

    @Test
    fun `fil - ses destinataires dans l'ordre, un identifiant inconnu ignore`() {
        val adresses = mapOf(1L to "+15551230001", 2L to "+15551230002")

        val fils = FilsDuFournisseur.fils(curseur(50L to "2 9 1", 12L to "1"), adresses)

        assertThat(fils).containsExactly(50L, listOf("+15551230002", "+15551230001"), 12L, listOf("+15551230001"))
    }

    @Test
    fun `fil sans adresse connue omis`() {
        val fils = FilsDuFournisseur.fils(curseur(50L to "9", 51L to "", 52L to null), mapOf(1L to "+15551230001"))

        assertThat(fils).isEmpty()
    }

    @Test
    fun `messages par fil, forme d'URI de l'import`() {
        val parFil = mutableMapOf<Long, MutableList<String>>()

        FilsDuFournisseur.messages(messages(7L to 50L, 8L to 50L), "content://mms/", parFil)
        FilsDuFournisseur.messages(messages(3L to 50L, 4L to 60L), "content://sms/", parFil)

        assertThat(parFil).containsExactly(
            50L,
            listOf("content://mms/7", "content://mms/8", "content://sms/3"),
            60L,
            listOf("content://sms/4"),
        )
    }
}
