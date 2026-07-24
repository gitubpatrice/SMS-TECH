package com.filestech.sms.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Audit F3: PBKDF2 MUST preserve the full entropy of a Unicode password. Previously the
 * `pwBytes.toCharArrayUnsafe()` round-trip narrowed each UTF-8 byte to a Latin-1 char and
 * destroyed the entropy of any non-ASCII input. These tests pin the contract.
 */
class PasswordKdfUnicodeTest {

    private val kdf = PasswordKdf()

    @Test fun `derive is deterministic for same input`() {
        val salt = ByteArray(PasswordKdf.SALT_LEN) { it.toByte() }
        val pw = "correct horse battery staple".toCharArray()
        val h1 = kdf.derive(pw.copyOf(), salt, PasswordKdf.MIN_ITERATIONS)
        val h2 = kdf.derive(pw.copyOf(), salt, PasswordKdf.MIN_ITERATIONS)
        assertThat(h1).isEqualTo(h2)
    }

    @Test fun `derive differs when a single character changes`() {
        val salt = ByteArray(PasswordKdf.SALT_LEN) { it.toByte() }
        val a = kdf.derive("hunter2".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS)
        val b = kdf.derive("hunter3".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `derive differs for FR accented passwords`() {
        // Audit F3: previously, "été" and "ete" would map to the same Latin-1 char sequence and
        // therefore hash to the same digest. They must NOT.
        val salt = ByteArray(PasswordKdf.SALT_LEN) { it.toByte() }
        val ascii = kdf.derive("ete".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS)
        val accented = kdf.derive("été".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS)
        assertThat(ascii).isNotEqualTo(accented)
    }

    @Test fun `derive differs for emoji passwords`() {
        val salt = ByteArray(PasswordKdf.SALT_LEN) { it.toByte() }
        val a = kdf.derive("pass👍".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS) // thumbs up
        val b = kdf.derive("pass👎".toCharArray(), salt, PasswordKdf.MIN_ITERATIONS) // thumbs down
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `min_iterations meets OWASP 2024 floor`() {
        // Audit F17: PBKDF2-HMAC-SHA512 floor is 210 000 in OWASP Mobile 2024 guidance.
        assertThat(PasswordKdf.MIN_ITERATIONS).isAtLeast(210_000)
    }

    @Test fun `derive refuses below the floor`() {
        val salt = ByteArray(PasswordKdf.SALT_LEN) { 0 }
        val tooLow = 1_000
        try {
            kdf.derive("x".toCharArray(), salt, tooLow)
            assertThat(false).isTrue() // unreachable
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("iterations")
        }
    }
}
