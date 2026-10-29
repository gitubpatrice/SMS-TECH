package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 2026-10-29 (issue #53) — un refus de l'opérateur se stocke dans `error_code` en gardant son
 * statut, sans pouvoir être confondu avec un autre code.
 */
class SendErrorCodeTest {

    @Test
    fun `le statut se retrouve tel qu'il a ete stocke`() {
        (0x81..0xFF).forEach { statut ->
            assertThat(SendErrorCode.mmscRefusalStatus(SendErrorCode.mmscRefused(statut))).isEqualTo(statut)
        }
    }

    @Test
    fun `aucun autre code n'est pris pour un refus de l'operateur`() {
        val autres = listOf(
            null,
            SendErrorCode.SYNCHRONOUS,
            SendErrorCode.WATCHDOG_TIMEOUT,
            SendErrorCode.RECIPIENT_BLOCKED,
            0,
            1,
            8,
            -1000,
            -1128,
            -1256,
        )
        autres.forEach { assertThat(SendErrorCode.mmscRefusalStatus(it)).isNull() }
    }

    @Test
    fun `un statut d'acceptation ne se stocke pas comme un refus`() {
        assertThrows<IllegalArgumentException> { SendErrorCode.mmscRefused(0x80) }
    }
}
