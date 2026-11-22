package com.filestech.sms.system.media

import com.filestech.sms.system.media.OrientationExif.Redressement
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-22 (courriel de Caroline) — la table des huit valeurs EXIF `Orientation`. Les valeurs sont écrites en
 * chiffres, ceux de la norme (TIFF 6.0, 0x0112), et non par les constantes d'Android : le test vérifie la norme, pas
 * la recopie des constantes. Le redressement des pixels lui-même est mesuré par `RedressementPhotoTest`.
 */
class OrientationExifTest {

    @Test
    fun `les trois rotations`() {
        assertThat(OrientationExif.redressementPour(6)).isEqualTo(Redressement(90, miroir = false))
        assertThat(OrientationExif.redressementPour(3)).isEqualTo(Redressement(180, miroir = false))
        assertThat(OrientationExif.redressementPour(8)).isEqualTo(Redressement(270, miroir = false))
    }

    @Test
    fun `les quatre miroirs - rotation puis miroir horizontal`() {
        assertThat(OrientationExif.redressementPour(2)).isEqualTo(Redressement(0, miroir = true))
        assertThat(OrientationExif.redressementPour(4)).isEqualTo(Redressement(180, miroir = true))
        assertThat(OrientationExif.redressementPour(5)).isEqualTo(Redressement(90, miroir = true))
        assertThat(OrientationExif.redressementPour(7)).isEqualTo(Redressement(270, miroir = true))
    }

    /** Contrôle négatif : une image droite, sans étiquette ou à l'étiquette inconnue n'est pas touchée. */
    @Test
    fun `normale, absente ou inconnue - rien a faire`() {
        for (valeur in listOf(1, 0, 9, -1)) {
            assertThat(OrientationExif.redressementPour(valeur)).isNull()
        }
    }
}
