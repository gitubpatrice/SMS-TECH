package com.filestech.sms.ui.screens.settings

import com.filestech.sms.R
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 — le récapitulatif du mode urgence ne promet une position que si le SMS l'emportera.
 * Le cas qui compte est le deuxième : réglage actif, permission refusée — l'écran disait
 * « Position GPS incluse » et le SMS partait sans coordonnées.
 */
class EmergencyArmedLocationTest {

    @Test
    fun `reglage coupe - aucune position, quelle que soit la permission`() {
        assertThat(libelleDePositionArmee(inclure = false, accordee = true, exacte = true))
            .isEqualTo(R.string.settings_emergency_armed_no_location)
        assertThat(libelleDePositionArmee(inclure = false, accordee = false, exacte = false))
            .isEqualTo(R.string.settings_emergency_armed_no_location)
    }

    @Test
    fun `reglage actif sans permission - la position n'est PAS annoncee`() {
        assertThat(libelleDePositionArmee(inclure = true, accordee = false, exacte = false))
            .isEqualTo(R.string.settings_emergency_armed_location_denied)
    }

    @Test
    fun `permission approximative - zone d'environ 2 km`() {
        assertThat(libelleDePositionArmee(inclure = true, accordee = true, exacte = false))
            .isEqualTo(R.string.settings_emergency_armed_location_approximate)
    }

    @Test
    fun `permission exacte - position incluse`() {
        assertThat(libelleDePositionArmee(inclure = true, accordee = true, exacte = true))
            .isEqualTo(R.string.settings_emergency_armed_location)
    }
}
