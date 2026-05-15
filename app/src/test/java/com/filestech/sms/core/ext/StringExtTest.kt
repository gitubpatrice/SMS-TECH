package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StringExtTest {

    @Test fun `normalizePhone keeps leading plus, digits, star, hash`() {
        assertThat("+33 (6) 12-34 56*78#9".normalizePhone()).isEqualTo("+33612345678*#9")
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
}
