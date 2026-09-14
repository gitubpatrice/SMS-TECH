package com.filestech.sms.system.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.9 (relecture GPT 5.2 du code F17, constat 1) — la marque du doublon en mémoire du receveur.
 *
 * Les deux bords : deux SIM ne doivent jamais se prendre l'une pour l'autre — le second MMS serait perdu —,
 * et le rejeu d'une même SIM doit toujours être reconnu — il serait écrit deux fois.
 */
class MarqueDeTransactionTest {

    private val identifiant = "T-0042".toByteArray(Charsets.UTF_8)

    @Test
    fun `deux SIM ne partagent pas la marque d'un meme identifiant`() {
        assertThat(marqueDeTransaction(identifiant, 1)).isNotEqualTo(marqueDeTransaction(identifiant, 2))
        assertThat(marqueDeTransaction(identifiant, 1)).isNotEqualTo(marqueDeTransaction(identifiant, null))
    }

    @Test
    fun `le rejeu d'une meme SIM porte la meme marque`() {
        assertThat(marqueDeTransaction(identifiant, 1)).isEqualTo(marqueDeTransaction("T-0042".toByteArray(), 1))
        assertThat(marqueDeTransaction(identifiant, null)).isEqualTo(marqueDeTransaction(identifiant, null))
    }

    @Test
    fun `sans identifiant il n'y a pas de marque`() {
        assertThat(marqueDeTransaction(null, 1)).isNull()
        assertThat(marqueDeTransaction(ByteArray(0), 1)).isNull()
    }
}
