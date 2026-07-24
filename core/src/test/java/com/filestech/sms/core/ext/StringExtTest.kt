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
