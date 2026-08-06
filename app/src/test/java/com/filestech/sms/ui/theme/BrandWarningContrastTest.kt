package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.3 — verrouille le choix du premier plan posé sur [BrandWarning] / [BrandBlocked].
 *
 * # Pourquoi ce test existe
 *
 * Le KDoc de [BrandBlocked] a affirmé pendant plusieurs versions que « le blanc posé dessus atteint
 * 4,87:1, au-dessus du seuil AA ». **Ce chiffre était faux** — le blanc n'atteint que 3,79:1 — et il
 * n'était pas décoratif : il a servi à justifier du texte blanc sur cet orange, dans un bandeau
 * d'avertissement dont la phrase porte une règle de sécurité. Une relecture externe l'a relevé le
 * 2026-08-06 (SC-UI-1273-02), à un commit de la publication.
 *
 * Aucun test ne pouvait le voir : la valeur vivait dans un commentaire. Ce test la sort du commentaire
 * et la met dans du code exécutable.
 *
 * # Ce qu'il verrouille, et dans les deux sens
 *
 * Vérifier seulement « le noir passe » laisserait quelqu'un revenir au blanc sans rien casser. Le
 * test affirme donc **aussi** que le blanc échoue : si un jour cette teinte est éclaircie au point de
 * rendre le blanc lisible, ce test tombera et il faudra relire les deux décisions ensemble, plutôt
 * que de découvrir l'une par la couleur et l'autre par un commentaire périmé.
 */
class BrandWarningContrastTest {

    private companion object {
        /** Seuil WCAG 2.1 AA pour du texte de taille normale. Le bandeau utilise `bodyMedium` et
         * `bodySmall` : ni l'un ni l'autre n'entre dans la définition du « grand texte ». */
        const val AA_NORMAL_TEXT = 4.5

        const val TOLERANCE = 0.01
    }

    /** Composante sRGB → linéaire, formule WCAG. */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.040_45) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** Luminance relative, formule WCAG. */
    private fun luminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `le noir sur l'orange d'avertissement respecte le seuil AA`() {
        val ratio = contrast(Color.Black, BrandWarning)

        assertThat(ratio).isGreaterThan(AA_NORMAL_TEXT)
        assertThat(ratio).isWithin(TOLERANCE).of(5.54)
    }

    @Test
    fun `le blanc sur l'orange d'avertissement ECHOUE le seuil AA`() {
        // C'est le constat SC-UI-1273-02, figé ici pour qu'il ne puisse plus être affirmé faux dans
        // un commentaire. 3,79 et non 4,87.
        val ratio = contrast(Color.White, BrandWarning)

        assertThat(ratio).isLessThan(AA_NORMAL_TEXT)
        assertThat(ratio).isWithin(TOLERANCE).of(3.79)
    }

    @Test
    fun `l'avertissement est bien un alias du bloque, pas une seconde valeur`() {
        // Si les deux divergeaient, le calcul ci-dessus ne dirait plus rien du bandeau.
        assertThat(BrandWarning).isEqualTo(BrandBlocked)
    }

    @Test
    fun `la luminance de la teinte est celle documentee`() {
        // La valeur citée dans le KDoc de [BrandBlocked]. Elle y est désormais vérifiable.
        assertThat(luminance(BrandWarning)).isWithin(0.000_01).of(0.227_08)
    }

    // ------------------------------------------------------------------------------------------
    // v1.27.4 — [onBrandContainer] : le premier plan est CALCULÉ, plus écrit au site d'appel.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `le premier plan calcule sur l'orange est le noir`() {
        // Le blanc était écrit en dur sur deux écrans, sur la foi d'un ratio faux.
        assertThat(onBrandContainer(BrandBlocked)).isEqualTo(Color.Black)
    }

    @Test
    fun `le premier plan calcule sur le rouge destructif reste le blanc`() {
        // 🔴 LE TEST QUI COMPTE LE PLUS DE CE FICHIER.
        //
        // Les deux teintes passent par le MÊME composant de confirmation. Passer l'orange au noir
        // en dur — la correction naïve — aurait fait tomber le rouge de 5,62:1 à 3,74:1, soit sous
        // le seuil AA : on aurait réparé un écran en cassant l'autre, sans qu'aucun test existant
        // ne s'en aperçoive. C'est exactement la forme du jumeau asymétrique.
        assertThat(onBrandContainer(BrandDanger)).isEqualTo(Color.White)
    }

    @Test
    fun `les deux ratios du rouge destructif sont ceux mesures`() {
        // Figés pour la même raison que ceux de l'orange : un ratio qui ne vit que dans un
        // commentaire finit par justifier un choix qu'il ne soutient pas.
        assertThat(contrast(Color.White, BrandDanger)).isWithin(TOLERANCE).of(5.62)
        assertThat(contrast(Color.Black, BrandDanger)).isWithin(TOLERANCE).of(3.74)
    }

    @Test
    fun `le premier plan calcule franchit toujours le seuil AA sur les deux teintes de marque`() {
        // L'invariant que les sites d'appel ont le droit de supposer. Si une teinte de marque est un
        // jour retouchée jusqu'à ce que NI le noir NI le blanc ne passent, ce test tombe — et c'est
        // le seul moment où [onBrandContainer] ne suffit plus, puisqu'elle rend le meilleur des
        // deux, pas une garantie de seuil. Le KDoc le dit ; ce test le prouve.
        for (brand in listOf(BrandBlocked, BrandWarning, BrandDanger)) {
            assertThat(contrast(onBrandContainer(brand), brand)).isGreaterThan(AA_NORMAL_TEXT)
        }
    }
}
