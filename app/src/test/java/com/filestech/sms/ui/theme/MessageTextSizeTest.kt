package com.filestech.sms.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.filestech.sms.domain.settings.TextScale
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 — taille du texte des messages : les coefficients et ce qu'ils multiplient. La
 * NEUTRALITÉ du cran par défaut est l'invariant principal — sans elle, livrer ce réglage changerait
 * le rendu de tous les utilisateurs qui ne l'ont jamais touché.
 */
class MessageTextSizeTest {

    private val styles: List<(Typography) -> TextStyle> = listOf(
        Typography::displayLarge, Typography::displayMedium, Typography::displaySmall,
        Typography::headlineLarge, Typography::headlineMedium, Typography::headlineSmall,
        Typography::titleLarge, Typography::titleMedium, Typography::titleSmall,
        Typography::bodyLarge, Typography::bodyMedium, Typography::bodySmall,
        Typography::labelLarge, Typography::labelMedium, Typography::labelSmall,
    )

    @Test
    fun `le cran par defaut vaut exactement 1`() {
        assertThat(TextScale.MEDIUM.factor()).isEqualTo(1f)
    }

    @Test
    fun `les crans croissent strictement dans leur ordre de declaration`() {
        val coefficients = TextScale.entries.map { it.factor() }
        assertThat(coefficients).isInStrictOrder()
        assertThat(coefficients.first()).isLessThan(1f)
        assertThat(coefficients.last()).isEqualTo(1.75f)
    }

    @Test
    fun `au coefficient 1 rien n'est recopie`() {
        val style = AppTypography.bodyLarge
        assertThat(style.scaledBy(1f)).isSameInstanceAs(style)
        assertThat(AppTypography.scaledBy(1f)).isSameInstanceAs(AppTypography)
    }

    @Test
    fun `seuls les sp sont multiplies`() {
        val sp = TextStyle(fontSize = 16.sp, lineHeight = 20.sp).scaledBy(1.5f)
        assertThat(sp.fontSize).isEqualTo(24.sp)
        assertThat(sp.lineHeight).isEqualTo(30.sp)

        // Un `em` suit déjà la police agrandie : le multiplier l'agrandirait deux fois.
        val em = TextStyle(fontSize = 16.sp, lineHeight = 1.4.em).scaledBy(1.5f)
        assertThat(em.lineHeight).isEqualTo(1.4.em)

        val inconnu = TextStyle().scaledBy(1.5f)
        assertThat(inconnu.fontSize).isEqualTo(TextUnit.Unspecified)
        assertThat(inconnu.lineHeight).isEqualTo(TextUnit.Unspecified)
    }

    @Test
    fun `les quinze styles de la typographie sont agrandis`() {
        val agrandie = AppTypography.scaledBy(1.75f)
        styles.forEach { style ->
            val avant = style(AppTypography).fontSize
            assertThat(style(agrandie).fontSize).isEqualTo(avant * 1.75f)
        }
    }

    @Test
    fun `les autres attributs du style sont conserves`() {
        val avant = AppTypography.titleSmall
        val apres = avant.scaledBy(1.2f)
        assertThat(apres.fontWeight).isEqualTo(avant.fontWeight)
        assertThat(apres.fontFamily).isEqualTo(avant.fontFamily)
    }
}
