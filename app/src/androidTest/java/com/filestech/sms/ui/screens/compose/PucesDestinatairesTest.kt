package com.filestech.sms.ui.screens.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-14 (signalé par Patrice) — à la création d'un groupe, au troisième contact, le numéro se mettait à la
 * verticale et un quatrième n'apparaissait plus : les puces vivaient dans une `Row`, qui ne passe jamais à la ligne.
 *
 * Mesuré sur la largeur la plus étroite que l'application vise (320 dp, Galaxy A03s, Redmi 9A) : chaque puce garde
 * la largeur de son numéro et une seule ligne, et la dernière ajoutée est en vue. Avec l'ancienne `Row`, la
 * quatrième puce a une largeur nulle et ce test échoue.
 */
class PucesDestinatairesTest {

    @get:Rule val compose = createComposeRule()

    private val numeros = (1..6).map { "+3361234567$it" }

    @Test
    fun sixDestinatairesSurEcranEtroitChaquePuceEntiereSurUneLigne() {
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                PucesDestinataires(destinataires = numeros.map { PhoneAddress.of(it) }, onRetirer = {})
            }
        }
        compose.waitForIdle()

        val reference = compose.onNodeWithText(numeros.first()).getUnclippedBoundsInRoot()
        numeros.forEach { numero ->
            val bornes = compose.onNodeWithText(numero).getUnclippedBoundsInRoot()
            // Même longueur, chiffres de même chasse : même largeur, même hauteur d'une ligne.
            assertThat(bornes.width.value).isAtLeast(reference.width.value * 0.9f)
            assertThat(bornes.height.value).isAtMost(reference.height.value * 1.1f)
        }
        assertThat(reference.width.value).isGreaterThan(40f)
        // La dernière puce ajoutée est amenée en vue, même quand la zone défile.
        compose.onNodeWithText(numeros.last()).assertIsDisplayed()
    }

    @Test
    fun toucherUnePuceLaRetire() {
        val retires = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                PucesDestinataires(
                    destinataires = numeros.take(4).map { PhoneAddress.of(it) },
                    onRetirer = { retires += it.raw },
                )
            }
        }

        compose.onNodeWithText(numeros[3]).performScrollTo().performClick()

        assertThat(retires).containsExactly(numeros[3])
    }
}
