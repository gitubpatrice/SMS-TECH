package com.filestech.sms.domain.location

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * v1.28.13 — le lien de carte du SMS d'urgence dit quand la position est approximative.
 *
 * Avec la seule localisation « approximative » d'Android 12+, la position est décalée d'environ
 * deux kilomètres alors que le lien garde cinq décimales : sans mention, il désignerait une rue
 * précise qui n'est pas la bonne.
 */
class LienCarteTest {

    @Test
    fun `position precise - le lien seul, sans marge`() {
        assertThat(GeoLocation(48.85661, 2.35222, precisionMetres = 12f).lienCarte())
            .isEqualTo("https://maps.google.com/?q=48.85661,2.35222")
        assertThat(GeoLocation(48.85661, 2.35222).lienCarte())
            .isEqualTo("https://maps.google.com/?q=48.85661,2.35222")
    }

    @Test
    fun `position approximative - la marge suit le lien, arrondie au kilometre superieur`() {
        assertThat(GeoLocation(48.85661, 2.35222, precisionMetres = 2_000f).lienCarte())
            .isEqualTo("https://maps.google.com/?q=48.85661,2.35222 (+/-2 km)")
        assertThat(GeoLocation(48.85661, 2.35222, precisionMetres = 1_001f).lienCarte())
            .endsWith(" (+/-2 km)")
    }

    @Test
    fun `le seuil d'un kilometre n'ajoute pas de marge`() {
        assertThat(GeoLocation(48.85661, 2.35222, precisionMetres = 1_000f).lienCarte())
            .doesNotContain("km")
    }

    /**
     * `±` et `~` sortent de l'alphabet GSM-7 de base : un seul suffit à faire passer tout le SMS
     * d'urgence en UCS-2, donc en plusieurs segments — dont un peut se perdre en zone de faible
     * couverture.
     */
    @Test
    fun `la marge reste en ASCII`() {
        val lien = GeoLocation(48.85661, 2.35222, precisionMetres = 3_500f).lienCarte()
        assertThat(lien.all { it.code < 128 }).isTrue()
    }

    /** v1.27.2 — `Locale.ROOT` : en français, `%.5f` écrirait une virgule et Maps ne lirait plus le lien. */
    @Test
    fun `le point decimal ne depend pas de la langue du telephone`() {
        val avant = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertThat(GeoLocation(48.85661, 2.35222, precisionMetres = 2_000f).lienCarte())
                .isEqualTo("https://maps.google.com/?q=48.85661,2.35222 (+/-2 km)")
        } finally {
            Locale.setDefault(avant)
        }
    }
}
