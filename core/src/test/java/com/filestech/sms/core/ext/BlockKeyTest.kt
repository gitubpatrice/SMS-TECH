package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.25.4 — [blockKey] est la clé **unique** de la liste noire : elle décide à la fois ce qui est
 * enregistré en base, ce que le filtre de réception reconnaît, et ce que la liste signale comme
 * bloqué. Une collision bloque un correspondant légitime ; une non-détection laisse passer un
 * expéditeur qu'on affiche pourtant comme bloqué.
 *
 * Le prédécesseur `blockMatchKey` était testé ici sur des chaînes brutes qu'aucun appelant de
 * production ne lui passait : le stockage réduisait déjà l'expéditeur via `normalizePhone` avant
 * qu'il n'arrive. Les tests passaient sur un chemin qui n'existait pas. Ceux-ci exercent les
 * valeurs telles qu'elles arrivent réellement — la forme brute de l'expéditeur.
 */
class BlockKeyTest {

    @Test
    fun `formes internationale et nationale d'un meme numero se rejoignent`() {
        // Raison d'être du rapprochement : le numéro est bloqué depuis Téléphone en +33…, mais
        // stocké en 0… dans content://sms.
        assertThat("+33612345678".blockKey()).isEqualTo("0612345678".blockKey())
        assertThat("+33 6 12 34 56 78".blockKey()).isEqualTo("06 12 34 56 78".blockKey())
    }

    @Test
    fun `deux mobiles ne differant que par leur prefixe restent distincts`() {
        // Le garde-fou qui rend l'unification sûre. À 8 chiffres significatifs ces deux numéros
        // partageaient leur clé, et bloquer l'un aurait bloqué l'autre — c'est pour cette raison
        // que le rapprochement permissif avait été tenu à l'écart du filtrage réel.
        assertThat("0612345678".blockKey()).isNotEqualTo("0712345678".blockKey())
        assertThat("+33612345678".blockKey()).isNotEqualTo("+33712345678".blockKey())
    }

    @Test
    fun `un expediteur alphanumerique a chiffres ne se confond pas avec un code court`() {
        // Le défaut corrigé : `normalizePhone("SFR 123")` rendait « 123 », donc bloquer l'opérateur
        // bloquait aussi le code court numérique 123 — et réciproquement.
        assertThat("SFR 123".blockKey()).isNotEqualTo("123".blockKey())
        assertThat("M6".blockKey()).isNotEqualTo("6".blockKey())
    }

    @Test
    fun `deux expediteurs alphanumeriques distincts ne se confondent pas`() {
        // `normalizePhone` les réduisait tous à la chaîne vide : bloquer « SFR » marquait aussi
        // « Free », « ORANGE », n'importe lequel.
        assertThat("SFR".blockKey()).isNotEqualTo("Free".blockKey())
        assertThat("SFR".blockKey()).isNotEmpty()
        assertThat("Free".blockKey()).isNotEmpty()
        assertThat("SFR2".blockKey()).isNotEqualTo("Free2".blockKey())
    }

    @Test
    fun `un expediteur alphanumerique est insensible a la casse et aux espaces`() {
        assertThat("SFR".blockKey()).isEqualTo("sfr".blockKey())
        assertThat("  Free  ".blockKey()).isEqualTo("free".blockKey())
    }

    @Test
    fun `un code court garde ses chiffres exacts`() {
        assertThat("123".blockKey()).isEqualTo("123")
        assertThat("36000".blockKey()).isEqualTo("36000")
        // Moins de chiffres que le seuil : aucun rognage, donc pas de collision entre codes courts.
        assertThat("123".blockKey()).isNotEqualTo("1123".blockKey())
    }

    @Test
    fun `une chaine vide ne produit aucune cle`() {
        // Une clé vide est écartée par les appelants : sans ce garde, elle rapprocherait tout.
        assertThat("".blockKey()).isEmpty()
        assertThat("   ".blockKey()).isEmpty()
    }

    @Test
    fun `la ponctuation de saisie n'influe pas sur la cle`() {
        val expected = "0612345678".blockKey()
        assertThat("06.12.34.56.78".blockKey()).isEqualTo(expected)
        assertThat("(06) 12-34-56-78".blockKey()).isEqualTo(expected)
    }

    @Test
    fun `la cle est stable par idempotence sur les numeros`() {
        // Le stockage enregistre `blockKey(raw)` ; toute relecture qui ré-appliquerait la fonction
        // doit retrouver la même valeur, sans quoi une entrée deviendrait introuvable.
        val once = "+33612345678".blockKey()
        assertThat(once.blockKey()).isEqualTo(once)
        val short = "123".blockKey()
        assertThat(short.blockKey()).isEqualTo(short)
    }
}
