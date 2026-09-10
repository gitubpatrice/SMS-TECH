package com.filestech.sms.domain.mms

import com.filestech.sms.core.ext.phoneIdentityKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.4 — **un groupe reçu, c'est tout le monde moins nous.**
 *
 * Ce que ces cas fixent : nous sommes retirés quelle que soit la notation ; les doublons entre
 * `To` et `Cc` n'en font pas deux membres ; sans « Mon numéro », pas de groupe ; à un seul autre
 * participant, pas de groupe non plus — c'est une conversation ordinaire.
 */
class GroupMmsMembersTest {

    private companion object {
        const val MOI = "06 07 23 15 41"
        const val PAT = "+33617332729"
        const val MARIE = "+33698765432"
    }

    /** La règle E.164 française, telle que `PhoneIdentity.key` l'applique sur un téléphone en France. */
    private fun frE164(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith('+') && digits.length in 8..15 -> "+$digits"
            digits.length == 10 && digits.startsWith("0") -> "+33" + digits.drop(1)
            else -> null
        }
    }

    private fun cle(raw: String): String = phoneIdentityKey(raw) { frE164(it) }

    private fun membres(from: String, to: List<String>, cc: List<String> = emptyList(), self: String? = MOI) =
        GroupMmsMembers.of(from, to, cc, self, ::cle)

    @Test
    fun `l'expediteur et les autres destinataires, nous exclus, dans l'ordre`() {
        val membres = membres(from = PAT, to = listOf("+33607231541", MARIE))

        assertThat(membres?.map { it.raw }).containsExactly(PAT, MARIE).inOrder()
    }

    /** Le même numéro sous deux notations, dans `To` et `Cc`, n'est qu'un membre. */
    @Test
    fun `les doublons entre To et Cc ne comptent qu'une fois`() {
        val membres = membres(from = PAT, to = listOf("+33607231541", "06 98 76 54 32"), cc = listOf(MARIE))

        assertThat(membres).hasSize(2)
    }

    /** Sans « Mon numéro », on ne peut pas se retirer : pas de groupe, conversation ordinaire. */
    @Test
    fun `sans mon numero, pas de groupe`() {
        assertThat(membres(PAT, listOf("+33607231541", MARIE), self = null)).isNull()
        assertThat(membres(PAT, listOf("+33607231541", MARIE), self = "  ")).isNull()
    }

    /** Contrôle : un MMS adressé à nous seuls n'est pas un groupe, même avec « Mon numéro » connu. */
    @Test
    fun `un seul autre participant n'est pas un groupe`() {
        assertThat(membres(PAT, listOf("+33607231541"))).isNull()
    }
}
