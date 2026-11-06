package com.filestech.sms.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (issue #76, relecture Gemini + Claude) — la géométrie de la visionneuse : l'image affichée en
 * `Fit`, le zoom qui garde le point pincé sous les doigts, et les bornes du déplacement.
 */
class GestesVisionneuseTest {

    private val ecran = Size(1000f, 2000f)

    @Test
    fun `fit - une photo en largeur occupe la largeur, une inconnue l'ecran entier`() {
        assertThat(tailleAffichee(Size(4000f, 3000f), ecran)).isEqualTo(Size(1000f, 750f))
        assertThat(tailleAffichee(null, ecran)).isEqualTo(ecran)
    }

    /** Le point pincé reste sous les doigts : sa position à l'écran ne bouge pas quand l'échelle change. */
    @Test
    fun `zoom autour du point - le point pince reste en place`() {
        val centre = Offset(500f, 1000f)
        val point = Offset(800f, 400f)
        val echelle = 1.5f
        val decalage = Offset(-40f, 60f)
        val facteur = 2f

        val nouveau = zoomAutour(point, ecran, decalage, facteur)

        // Position à l'écran d'un point du contenu : centre + échelle × (p − centre) + décalage.
        val contenu = (point - centre - decalage) / echelle + centre
        val apres = centre + (contenu - centre) * (echelle * facteur) + nouveau
        assertThat(apres.x).isWithin(0.01f).of(point.x)
        assertThat(apres.y).isWithin(0.01f).of(point.y)
    }

    /** Contrôle négatif : sans agrandissement, pas de déplacement ; au centre, le décalage ne change pas. */
    @Test
    fun `zoom neutre et zoom au centre`() {
        assertThat(zoomAutour(Offset(800f, 400f), ecran, Offset(10f, 20f), 1f)).isEqualTo(Offset(10f, 20f))
        assertThat(zoomAutour(Offset(500f, 1000f), ecran, Offset.Zero, 3f)).isEqualTo(Offset.Zero)
    }

    /** Une photo en largeur agrandie 2× déborde en largeur seulement : pas de déplacement vertical dans le noir. */
    @Test
    fun `bornes - l'image affichee, pas l'ecran`() {
        val affichee = Size(1000f, 750f)

        val b = borne(Offset(5000f, 5000f), 2f, affichee, ecran)

        assertThat(b.x).isEqualTo(500f)
        assertThat(b.y).isEqualTo(0f)
        assertThat(borne(Offset(-5000f, 0f), 2f, affichee, ecran).x).isEqualTo(-500f)
    }
}
