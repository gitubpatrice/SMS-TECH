package com.filestech.sms.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-10-29 — [MessageTextSize] dans une vraie composition. Les tests JVM couvrent les fonctions
 * pures ; celui-ci couvre le composable : ce qu'il pose, et surtout ce qu'il NE pose PAS au défaut.
 *
 * La neutralité se prouve par l'IDENTITÉ des couleurs de sélection, pas par la typographie : un
 * `MaterialTheme` imbriqué au coefficient 1 rendrait une typographie identique, mais recréerait ses
 * `TextSelectionColors`. Contrôle négatif fait le 2026-10-29 (court-circuit retiré → rouge).
 *
 * ⚠️ JUnit 4 : corps en bloc, jamais `fun f() = …` qui rendrait autre chose que `Unit`.
 */
class MessageTextSizeCompositionTest {

    @get:Rule val compose = createComposeRule()

    private class Lu(
        val typographie: Typography,
        val style: TextStyle,
        val selection: TextSelectionColors,
        val couleurs: ColorScheme,
    )

    private var dehors: Lu? = null
    private var dedans: Lu? = null
    private var imbrique: Lu? = null

    private fun composer(facteur: Float) {
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                dehors = lire()
                CompositionLocalProvider(LocalMessageTextFactor provides facteur) {
                    MessageTextSize {
                        dedans = lire()
                        MessageTextSize { imbrique = lire() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun lire() = Lu(
        typographie = MaterialTheme.typography,
        style = LocalTextStyle.current,
        selection = LocalTextSelectionColors.current,
        couleurs = MaterialTheme.colorScheme,
    )

    @Test
    fun auCoefficient1RienNEstPose() {
        composer(1f)
        val avant = checkNotNull(dehors)
        val apres = checkNotNull(dedans)
        assertThat(apres.typographie).isSameInstanceAs(avant.typographie)
        assertThat(apres.style).isSameInstanceAs(avant.style)
        // Le témoin d'un thème imbriqué : il recréerait ces couleurs.
        assertThat(apres.selection).isSameInstanceAs(avant.selection)
    }

    @Test
    fun unCoefficientAgranditLaTypographieEtLeStyleCourant() {
        composer(1.5f)
        val avant = checkNotNull(dehors)
        val apres = checkNotNull(dedans)
        assertThat(apres.typographie.bodyLarge.fontSize).isEqualTo(avant.typographie.bodyLarge.fontSize * 1.5f)
        assertThat(apres.typographie.labelSmall.fontSize).isEqualTo(avant.typographie.labelSmall.fontSize * 1.5f)
        assertThat(apres.style.fontSize).isEqualTo(avant.style.fontSize * 1.5f)
        // Seule la taille change : la palette reste celle du thème de l'application.
        assertThat(apres.couleurs).isSameInstanceAs(avant.couleurs)
    }

    @Test
    fun unPerimetreImbriqueNeMultipliePasDeuxFois() {
        composer(1.5f)
        val avant = checkNotNull(dehors)
        val deuxFois = checkNotNull(imbrique)
        assertThat(deuxFois.typographie.bodyLarge.fontSize).isEqualTo(avant.typographie.bodyLarge.fontSize * 1.5f)
        assertThat(deuxFois.style.fontSize).isEqualTo(avant.style.fontSize * 1.5f)
    }
}
