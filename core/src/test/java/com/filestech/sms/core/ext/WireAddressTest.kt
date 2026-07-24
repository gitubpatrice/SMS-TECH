package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WireAddressTest {

    /**
     * Fake stand-in for `PhoneNumberUtils.formatNumberToE164`. Emulates only the outcomes the
     * SUT branches on: valid FR/LU national → +CC form, already-international → normalised,
     * everything else (short code, invalid-for-region) → null.
     */
    private val fakeFormatter: (String, String) -> String? = { number, region ->
        val digits = number.filter { it.isDigit() }
        when {
            number.startsWith("+") -> "+" + digits
            region == "FR" && number.startsWith("0") && digits.length == 10 -> "+33" + digits.drop(1)
            region == "LU" && number.startsWith("6") && digits.length in 8..9 -> "+352" + digits
            else -> null // short code / not a valid number for this region
        }
    }

    @Test fun `national FR number becomes plus33 under FR region`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", "FR", fakeFormatter)).isEqualTo("+33612345678")
    }

    @Test fun `LU national mobile becomes plus352 under LU region`() {
        assertThat(WireAddress.toE164OrRaw("621123456", "LU", fakeFormatter)).isEqualTo("+352621123456")
    }

    @Test fun `already international number is left international`() {
        assertThat(WireAddress.toE164OrRaw("+33612345678", "LU", fakeFormatter)).isEqualTo("+33612345678")
    }

    @Test fun `region iso is uppercased before formatting`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", "fr", fakeFormatter)).isEqualTo("+33612345678")
    }

    @Test fun `surrounding whitespace is trimmed before formatting`() {
        assertThat(WireAddress.toE164OrRaw("  0612345678 ", "FR", fakeFormatter)).isEqualTo("+33612345678")
    }

    @Test fun `short code falls back to raw`() {
        assertThat(WireAddress.toE164OrRaw("38600", "FR", fakeFormatter)).isEqualTo("38600")
    }

    @Test fun `foreign national number invalid for the SIM region falls back to raw`() {
        // A French 06… on a Luxembourg SIM is not a valid LU number → formatter returns null →
        // we keep the raw string (unchanged from today's behaviour, never a wrong +352 number).
        assertThat(WireAddress.toE164OrRaw("0612345678", "LU", fakeFormatter)).isEqualTo("0612345678")
    }

    @Test fun `null region returns raw unchanged`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", null, fakeFormatter)).isEqualTo("0612345678")
    }

    @Test fun `blank region returns raw unchanged`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", "", fakeFormatter)).isEqualTo("0612345678")
    }

    @Test fun `malformed region length returns raw unchanged`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", "FRA", fakeFormatter)).isEqualTo("0612345678")
    }

    @Test fun `non-letter region returns raw unchanged`() {
        assertThat(WireAddress.toE164OrRaw("0612345678", "3F", fakeFormatter)).isEqualTo("0612345678")
    }

    @Test fun `blank number returns raw unchanged`() {
        assertThat(WireAddress.toE164OrRaw("   ", "FR", fakeFormatter)).isEqualTo("   ")
    }

    @Test fun `formatter throwing is swallowed and raw returned`() {
        val boom: (String, String) -> String? = { _, _ -> throw RuntimeException("platform boom") }
        assertThat(WireAddress.toE164OrRaw("0612345678", "FR", boom)).isEqualTo("0612345678")
    }

    @Test fun `formatter returning blank falls back to raw`() {
        val blank: (String, String) -> String? = { _, _ -> "" }
        assertThat(WireAddress.toE164OrRaw("0612345678", "FR", blank)).isEqualTo("0612345678")
    }
}
