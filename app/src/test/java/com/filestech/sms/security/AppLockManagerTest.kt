package com.filestech.sms.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppLockManagerBackoffTest {

    @Test fun `backoff is monotonic non decreasing`() {
        var prev = 0L
        for (i in 0..10) {
            val ms = AppLockManager.backoffMillis(i)
            assertThat(ms).isAtLeast(prev)
            prev = ms
        }
    }

    @Test fun `backoff caps at last step`() {
        val a = AppLockManager.backoffMillis(20)
        val b = AppLockManager.backoffMillis(50)
        assertThat(a).isEqualTo(b)
    }
}
