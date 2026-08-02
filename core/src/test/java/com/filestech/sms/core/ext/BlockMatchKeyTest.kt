package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.25.3 — [blockMatchKey] décide quelles conversations sont signalées comme bloquées dans la
 * liste. Une collision teinte en rouge des correspondants qui ne le sont pas ; une non-détection
 * laisse un fil bloqué se fondre parmi les autres. Les deux erreurs sont visibles à l'écran.
 */
class BlockMatchKeyTest {

    @Test
    fun `formes internationale et nationale d'un meme numero se rejoignent`() {
        // C'est la raison d'être du rapprochement par suffixe : le numéro est bloqué depuis
        // Téléphone en +33…, mais stocké en 0… dans content://sms.
        assertThat("+33612345678".blockMatchKey()).isEqualTo("0612345678".blockMatchKey())
    }

    @Test
    fun `deux expediteurs alphanumeriques distincts ne se confondent pas`() {
        // Le vrai défaut corrigé : `normalizePhone` les réduisait tous à la chaîne vide, donc
        // bloquer « SFR » marquait aussi « Free », « ORANGE », etc.
        assertThat("SFR".blockMatchKey()).isNotEqualTo("Free".blockMatchKey())
        assertThat("SFR".blockMatchKey()).isNotEmpty()
        assertThat("Free".blockMatchKey()).isNotEmpty()
    }

    @Test
    fun `un expediteur alphanumerique est insensible a la casse`() {
        assertThat("SFR".blockMatchKey()).isEqualTo("sfr".blockMatchKey())
        assertThat("  Free  ".blockMatchKey()).isEqualTo("free".blockMatchKey())
    }

    @Test
    fun `un expediteur alphanumerique contenant un chiffre ne se reduit pas a ce chiffre`() {
        // Sans la bascule sur « contient une lettre », « SFR2 » se réduirait au seul chiffre
        // « 2 » et entrerait en collision avec n'importe quel numéro finissant par 2.
        assertThat("SFR2".blockMatchKey()).isNotEqualTo("0612345672".blockMatchKey())
        assertThat("SFR2".blockMatchKey()).isEqualTo("sfr2")
    }

    @Test
    fun `un code court garde ses chiffres`() {
        assertThat("123".blockMatchKey()).isEqualTo("123")
    }

    @Test
    fun `une chaine vide ne produit aucune cle`() {
        // Une clé vide est écartée par l'appelant : sans ce garde, elle rapprocherait tout.
        assertThat("".blockMatchKey()).isEmpty()
        assertThat("   ".blockMatchKey()).isEmpty()
    }
}
