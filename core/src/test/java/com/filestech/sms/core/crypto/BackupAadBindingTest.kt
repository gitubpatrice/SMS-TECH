package com.filestech.sms.core.crypto

import com.filestech.sms.core.result.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Audit F26: KDF parameters MUST be bound to the ciphertext via AAD so flipping `iter` or `salt`
 * after the fact makes decryption fail-closed. This test pins the contract by mutating the AAD
 * and asserting the decrypt outcome is a [Outcome.Failure].
 */
class BackupAadBindingTest {

    @Test fun `decrypt fails when aad is altered`() {
        val cipher = AeadCipher()
        val key = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")
        val aadOriginal = "SMBK|01|salt|iters".toByteArray()
        val ct = (cipher.encrypt(key, "payload".toByteArray(), aadOriginal) as Outcome.Success).value

        // Flip one byte of the AAD on decryption.
        val aadFlipped = aadOriginal.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        val r = cipher.decrypt(key, ct, aadFlipped)
        assertThat(r).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `roundtrip with same aad succeeds`() {
        val cipher = AeadCipher()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val aad = "magic|v01".toByteArray()
        val ct = (cipher.encrypt(key, "Hello, SMS Tech!".toByteArray(), aad) as Outcome.Success).value
        val pt = (cipher.decrypt(key, ct, aad) as Outcome.Success).value
        assertThat(String(pt)).isEqualTo("Hello, SMS Tech!")
    }
}
