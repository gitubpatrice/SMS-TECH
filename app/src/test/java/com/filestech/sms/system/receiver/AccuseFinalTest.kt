package com.filestech.sms.system.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0) — **un SMS en plusieurs parties n'est « envoyé » qu'à l'accusé de sa dernière partie.**
 *
 * Le premier accusé positif marquait le message entier comme parti. L'alerte qui attend ce verdict pour basculer sur
 * l'autre SIM le prenait pour acquis, alors qu'une partie suivante pouvait encore échouer (relectures Gemini et Claude
 * du 2026-11-16).
 */
class AccuseFinalTest {

    @Test
    fun `un SMS d'une seule partie est envoye a son accuse`() {
        assertThat(accuseFinal(partie = 0, parties = 1)).isTrue()
    }

    @Test
    fun `seule la derniere partie marque le message envoye`() {
        assertThat(accuseFinal(partie = 0, parties = 3)).isFalse()
        assertThat(accuseFinal(partie = 1, parties = 3)).isFalse()
        assertThat(accuseFinal(partie = 2, parties = 3)).isTrue()
    }

    @Test
    fun `une intention d'avant ces extras, ou incoherente, vaut une seule partie`() {
        assertThat(accuseFinal(partie = 0, parties = 0)).isTrue()
        assertThat(accuseFinal(partie = 5, parties = 2)).isTrue()
    }
}
