package com.filestech.sms.ui.screens.about

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (Patrice) — les mains jointes du remerciement ramenées à UNE teinte. La matrice est appliquée ici à la
 * main, comme Android l'applique (R' = m0·R + m1·G + m2·B + m3·A + m4, etc.).
 */
class TeinteMonochromeTest {

    private val teinte = Color(red = 0.4f, green = 0.5f, blue = 0.6f, alpha = 1f)

    private fun applique(couleur: FloatArray): FloatArray {
        val m = teinteMonochrome(teinte).values
        return FloatArray(4) { ligne ->
            (0 until 4).sumOf { colonne -> (m[ligne * 5 + colonne] * couleur[colonne]).toDouble() }.toFloat() +
                m[ligne * 5 + 4]
        }
    }

    @Test
    fun `le blanc devient la teinte`() {
        val r = applique(floatArrayOf(1f, 1f, 1f, 1f))

        assertThat(r[0]).isWithin(EPS).of(teinte.red)
        assertThat(r[1]).isWithin(EPS).of(teinte.green)
        assertThat(r[2]).isWithin(EPS).of(teinte.blue)
    }

    @Test
    fun `le noir reste noir, et deux couleurs de meme luminance donnent la meme teinte`() {
        assertThat(applique(floatArrayOf(0f, 0f, 0f, 1f)).take(3)).containsExactly(0f, 0f, 0f)
        // Rouge pur et un gris de même luminance (0,213) : même résultat, donc plus aucune couleur propre.
        val rouge = applique(floatArrayOf(1f, 0f, 0f, 1f))
        val gris = applique(floatArrayOf(0.213f, 0.213f, 0.213f, 1f))
        for (i in 0 until 3) assertThat(rouge[i]).isWithin(EPS).of(gris[i])
    }

    @Test
    fun `l'alpha est garde tel quel`() {
        assertThat(applique(floatArrayOf(1f, 0.3f, 0.2f, 0.35f))[3]).isWithin(EPS).of(0.35f)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
