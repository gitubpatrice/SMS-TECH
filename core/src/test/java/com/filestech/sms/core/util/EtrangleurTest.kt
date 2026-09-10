package com.filestech.sms.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** v1.28.4 — le miroir des numéros bloqués ne se rejoue plus à chaque reprise ; la règle est ici. */
class EtrangleurTest {

    private var maintenant = 1_000_000L
    private val etrangleur = Etrangleur(intervalleMs = 600_000L) { maintenant }

    @Test
    fun `la premiere demande passe, la suivante dans l'intervalle est absorbee`() {
        assertThat(etrangleur.autorise()).isTrue()
        maintenant += 5_000L
        assertThat(etrangleur.autorise()).isFalse()
    }

    @Test
    fun `l'intervalle ecoule, la demande passe et le compteur repart`() {
        etrangleur.autorise()
        maintenant += 600_000L
        assertThat(etrangleur.autorise()).isTrue()
        maintenant += 1L
        assertThat(etrangleur.autorise()).isFalse()
    }

    /** Contrôle : une demande FORCÉE passe toujours — la resynchronisation complète en a besoin. */
    @Test
    fun `une demande forcee passe toujours`() {
        etrangleur.autorise()
        maintenant += 1L
        assertThat(etrangleur.autorise(force = true)).isTrue()
    }
}
