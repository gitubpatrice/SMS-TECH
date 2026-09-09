package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StringExtTest {

    @Test fun `normalizePhone keeps leading plus, digits, star, hash`() {
        // Function preserves the original ordering of allowed characters — it strips spaces,
        // parentheses and dashes but does NOT re-sort. Expected value reflects the actual
        // behaviour after dropping " ", "(", ")", "-": +,33,6,12,34,56,*,78,#,9.
        assertThat("+33 (6) 12-34 56*78#9".normalizePhone()).isEqualTo("+336123456*78#9")
    }

    @Test fun `normalizePhone strips a leading plus when not first`() {
        assertThat("06 + 12 34".normalizePhone()).isEqualTo("0612 34".replace(" ", ""))
    }

    @Test fun `avatarInitials returns first letters of first two words`() {
        assertThat("Patrice Haltaya".avatarInitials()).isEqualTo("PH")
        assertThat("alice".avatarInitials()).isEqualTo("A")
        assertThat("".avatarInitials()).isEqualTo("?")
    }

    @Test fun `extractOtp finds 6 digit code`() {
        assertThat("Your code is 482910 please".extractOtp()).isEqualTo("482910")
        assertThat("No code here".extractOtp()).isNull()
    }

    @Test fun `stripInvisibleChars removes bidi controls`() {
        val sneaky = "hello​‮world"
        assertThat(sneaky.stripInvisibleChars()).isEqualTo("helloworld")
    }

    // ──────────── F28 : ZWJ et ZWNJ construisent le texte, ils ne le masquent pas ────────────

    /**
     * v1.28.3 (F28) — relecture externe sur la MR F-Droid !38458.
     *
     * La plage d'origine `​-‏` englobait U+200D ZWJ. Tout emoji composé reçu était
     * donc DÉCOMPOSÉ avant d'être stocké, des deux côtés — `content://sms` et Room — sans
     * qu'aucune copie du transport ne subsiste. Irréversible, et à chaque message.
     */
    @Test fun `stripInvisibleChars garde le ZWJ qui compose un emoji`() {
        // 👨‍👩‍👧 = 👨 ZWJ 👩 ZWJ 👧. Sans le ZWJ, trois personnes cote a cote au lieu d'une famille.
        val famille = "👨‍👩‍👧"
        assertThat(famille.stripInvisibleChars()).isEqualTo(famille)

        // 🏳️‍🌈 = 🏳 VS16 ZWJ 🌈. Meme mecanisme, resultat visuellement tout autre.
        val drapeau = "🏳️‍🌈"
        assertThat(drapeau.stripInvisibleChars()).isEqualTo(drapeau)
    }

    /**
     * v1.28.3 (F28) — le ZWNJ n'est pas décoratif dans les écritures qui l'emploient : il
     * change le mot, pas son style. En persan `می‌روم` (« je vais ») devenait `میروم`.
     */
    @Test fun `stripInvisibleChars garde le ZWNJ des ecritures qui en dependent`() {
        val persan = "می‌روم"
        assertThat(persan.stripInvisibleChars()).isEqualTo(persan)
    }

    /**
     * **Contrôle négatif du test ci-dessus.** Conserver ZWJ et ZWNJ ne doit rien relâcher
     * d'autre : le motif SEC-02 v1.4.1 visait le SOFT HYPHEN, et tous les contrôles bidi —
     * ceux qui permettent d'usurper l'origine visuelle d'un message — restent retirés.
     *
     * Sans ce test, un correctif qui viderait la regex entière passerait pour bon.
     */
    @Test fun `stripInvisibleChars retire toujours le soft hyphen et les controles bidi`() {
        // Le contournement exact que SEC-02 a ferme : soft hyphen + coeur passant pour un
        // corps purement emoji.
        assertThat("­❤".stripInvisibleChars()).isEqualTo("❤")

        // Bidi : LRM, RLM, les cinq formatages explicites, les isolats, le BOM.
        val bidi = "a‎b‏c‪d‮e⁦f⁩g﻿h؜i᠎j͏k​l"
        assertThat(bidi.stripInvisibleChars()).isEqualTo("abcdefghijkl")
    }

    @Test fun `deterministicHue is stable`() {
        val a = "alice".deterministicHue()
        val b = "alice".deterministicHue()
        assertThat(a).isEqualTo(b)
    }

    @Test fun `foldForSearch strips case and accents`() {
        // Un même nom saisi de plusieurs façons se replie vers la même clé → la recherche
        // devient insensible à la casse ET aux accents.
        assertThat("Maïté".foldForSearch()).isEqualTo("maite")
        assertThat("MAITE".foldForSearch()).isEqualTo("maite")
        assertThat("maïté".foldForSearch()).isEqualTo("maite")
        assertThat("Élodie".foldForSearch()).isEqualTo("elodie")
        assertThat("François".foldForSearch()).isEqualTo("francois")
        assertThat("Amélie-Noël".foldForSearch()).isEqualTo("amelie-noel")
    }

    @Test fun `foldForSearch makes a query match an accented name symmetrically`() {
        // Usage réel : les DEUX côtés du contains sont repliés, donc une requête sans accent
        // matche une cible avec accent (et inversement).
        assertThat("Maïté Fructus".foldForSearch()).contains("maite")
        assertThat("Vanessa".foldForSearch()).contains("VaNeSsA".foldForSearch())
    }

    @Test fun `foldForSearch preserves spaces and non-accented text`() {
        assertThat("Jean Dupont".foldForSearch()).isEqualTo("jean dupont")
        assertThat("".foldForSearch()).isEqualTo("")
    }

    @Test fun `foldForSearch leaves unrecomposable ligatures folded to lowercase only`() {
        // Limite connue documentée : `œ`/`æ`/`ß` n'ont pas de décomposition canonique NFD,
        // donc seule la casse est repliée (pas de dépliage vers "oe"/"ae"/"ss").
        assertThat("Œuf".foldForSearch()).isEqualTo("œuf")
        assertThat("CŒUR".foldForSearch()).isEqualTo("cœur")
    }
}
