package com.filestech.sms.core.crypto

import com.filestech.sms.core.result.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import javax.crypto.spec.SecretKeySpec

class AeadCipherTest {

    @Test fun `round trip preserves plaintext`() {
        val cipher = AeadCipher()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val pt = "hello SMS Tech".toByteArray()
        val ct = (cipher.encrypt(key, pt) as Outcome.Success).value
        val recovered = (cipher.decrypt(key, ct) as Outcome.Success).value
        assertThat(String(recovered)).isEqualTo("hello SMS Tech")
    }

    @Test fun `decrypt with wrong key fails`() {
        val cipher = AeadCipher()
        val key1 = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val key2 = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val ct = (cipher.encrypt(key1, "secret".toByteArray()) as Outcome.Success).value
        val r = cipher.decrypt(key2, ct)
        assertThat(r).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `decrypt with tampered ciphertext fails`() {
        val cipher = AeadCipher()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val ct = (cipher.encrypt(key, "x".toByteArray()) as Outcome.Success).value
        ct[ct.lastIndex] = (ct[ct.lastIndex].toInt() xor 0x42).toByte()
        val r = cipher.decrypt(key, ct)
        assertThat(r).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test fun `aad is enforced`() {
        val cipher = AeadCipher()
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val ct = (cipher.encrypt(key, "hi".toByteArray(), aad = byteArrayOf(1, 2, 3)) as Outcome.Success).value
        // wrong AAD
        val r = cipher.decrypt(key, ct, aad = byteArrayOf(1, 2, 4))
        assertThat(r).isInstanceOf(Outcome.Failure::class.java)
        // correct AAD
        val r2 = cipher.decrypt(key, ct, aad = byteArrayOf(1, 2, 3))
        assertThat(r2).isInstanceOf(Outcome.Success::class.java)
    }
}
