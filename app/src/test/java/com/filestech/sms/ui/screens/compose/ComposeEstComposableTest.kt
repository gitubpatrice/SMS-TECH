package com.filestech.sms.ui.screens.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global, X-06 — mesuré sur le S9) — **un nom tapé n'est pas un destinataire.**
 * « Pat » saisi puis ajouté partait vers l'adresse « Pat » : `RESULT_ERROR_NULL_PDU`.
 */
class ComposeEstComposableTest {

    @Test
    fun `un nom n'est pas composable`() {
        assertThat(ComposeViewModel.estComposable("Pat")).isFalse()
        assertThat(ComposeViewModel.estComposable("Free")).isFalse()
        assertThat(ComposeViewModel.estComposable("")).isFalse()
        assertThat(ComposeViewModel.estComposable("06 12 AB")).isFalse()
    }

    /** Contrôle POSITIF : les formes réelles d'un numéro passent, numéro court compris. */
    @Test
    fun `les formes usuelles d'un numero sont composables`() {
        assertThat(ComposeViewModel.estComposable("0607231541")).isTrue()
        assertThat(ComposeViewModel.estComposable("+33 6 07 23 15 41")).isTrue()
        assertThat(ComposeViewModel.estComposable("(06) 07-23.15.41")).isTrue()
        assertThat(ComposeViewModel.estComposable("36665")).isTrue()
    }
}
