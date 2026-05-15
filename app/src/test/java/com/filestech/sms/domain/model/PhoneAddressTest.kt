package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhoneAddressTest {

    @Test fun `of normalizes raw string`() {
        val a = PhoneAddress.of("+33 6 12 34 56 78")
        assertThat(a.normalized).isEqualTo("+33612345678")
        assertThat(a.raw).isEqualTo("+33 6 12 34 56 78")
    }

    @Test fun `list parses semicolon and comma separated csv`() {
        val l = PhoneAddress.list("+33612;+44 7700;0612345678")
        assertThat(l).hasSize(3)
    }

    @Test fun `list returns empty for blank csv`() {
        assertThat(PhoneAddress.list("")).isEmpty()
    }
}
