package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.4 — **un groupe se reconnaît à ses membres, pas à l'écriture de leurs numéros.**
 *
 * Mesuré sur le S24 : le groupe créé depuis le composeur portait `0617332729;0698765432`, le
 * même groupe reconstitué depuis un PDU reçu portait `+33617332729;+33698765432` — deux CSV,
 * deux conversations. Les 1-à-1 avaient leur repli par clé d'identité depuis la v1.3.3 ; les
 * groupes, non — le commentaire du miroir le justifiait par la crainte d'un suffixe partiel.
 * Ici la règle est stricte : MÊME nombre de membres, et chacun rapproché par la règle E.164.
 */
class FindOrCreateGroupMatchTest {

    private fun conv(id: Long, csv: String) = ConversationEntity(
        id = id,
        threadId = null,
        addressesCsv = csv,
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
    )

    private fun matchFr(a: String, b: String): Boolean =
        com.filestech.sms.core.ext.phoneAddressesMatch(a, b) { raw ->
            val trimmed = raw.trim()
            val digits = trimmed.filter { it.isDigit() }
            when {
                trimmed.startsWith('+') && digits.length in 8..15 -> "+" + digits
                digits.length == 10 && digits.startsWith("0") -> "+33" + digits.drop(1)
                else -> null
            }
        }

    private fun cible(vararg raw: String) = raw.map { PhoneAddress.of(it) }

    private fun cherche(groupes: List<ConversationEntity>, vararg raw: String) =
        ConversationRepositoryImpl.matchGroupByIdentity(groupes, cible(*raw), ::matchFr)

    @Test
    fun `le groupe compose en national est retrouve par le PDU en international, ordre different`() {
        val groupes = listOf(conv(7L, "0617332729;0698765432"))

        val trouve = cherche(groupes, "+33698765432", "+33617332729")

        assertThat(trouve?.id).isEqualTo(7L)
    }

    /** Un membre de plus ou de moins, ce n'est pas le même groupe — pas de confusion par sous-ensemble. */
    @Test
    fun `un groupe a trois n'est pas le groupe a deux`() {
        val groupes = listOf(conv(7L, "0617332729;0698765432;0611111111"))

        val trouve = cherche(groupes, "+33698765432", "+33617332729")

        assertThat(trouve).isNull()
    }

    /** Contrôle : deux groupes de même taille mais un membre différent ne se confondent pas. */
    @Test
    fun `un membre different, un autre groupe`() {
        val groupes = listOf(conv(7L, "0617332729;0698765432"))

        val trouve = cherche(groupes, "+33698765432", "+33622222222")

        assertThat(trouve).isNull()
    }

    @Test
    fun `une cible a un seul membre n'est jamais un groupe`() {
        val groupes = listOf(conv(7L, "0617332729;0698765432"))

        val trouve = cherche(groupes, "+33617332729")

        assertThat(trouve).isNull()
    }
}
