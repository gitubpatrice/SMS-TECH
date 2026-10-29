package com.filestech.sms.data.mms

import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 (issue #53) — l'accusé d'envoi MMS doit rester MUTABLE : c'est par lui qu'Android
 * transmet la réponse de l'opérateur. En IMMUTABLE, elle arrivait absente (mesuré sur un S24) et
 * aucun refus de l'opérateur ne pouvait être vu. Ce test existe pour qu'un durcissement
 * « de sécurité » bien intentionné ne recasse pas #53 en silence.
 */
class AccuseDEnvoiMmsTest {

    @Test
    fun `Android 12 et plus - mutable, jamais immutable`() {
        listOf(31, 34, 36).forEach { sdk ->
            val drapeaux = drapeauxDeLAccuseDEnvoi(sdk)
            assertThat(drapeaux and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
            assertThat(drapeaux and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
        }
    }

    @Test
    fun `avant Android 12 - jamais immutable (mutable par defaut)`() {
        listOf(26, 30).forEach { sdk ->
            assertThat(drapeauxDeLAccuseDEnvoi(sdk) and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
        }
    }

    @Test
    fun `l'accuse d'une nouvelle tentative remplace l'ancien`() {
        assertThat(drapeauxDeLAccuseDEnvoi(34) and PendingIntent.FLAG_UPDATE_CURRENT).isNotEqualTo(0)
    }
}
