package com.filestech.sms.core.crypto

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password-based key derivation. PBKDF2-HMAC-SHA512 (universally available on Android, no native lib —
 * keeps the binary F-Droid friendly).
 *
 * **CRITICAL contract** (fixes F3 audit finding): callers MUST pass the user's password as a
 * [CharArray] that mirrors the original keystrokes (UTF-16 code units). Any intermediate
 * `toByteArray(UTF-8).toCharArray()` round-trip is FORBIDDEN — it narrows each UTF-8 byte to a
 * Latin-1 char and corrupts the entropy of any non-ASCII password (FR accents, kanji, emoji…).
 * The [PBEKeySpec] constructor and PBKDF2-HMAC-SHA512 internally handle the UTF-8 encoding of the
 * char array correctly on every supported platform.
 *
 * Iterations are calibrated at first run on the host device (target ~300 ms on low-end).
 * Minimum floor is OWASP 2024 mobile recommended baseline for PBKDF2-SHA512.
 */
@Singleton
class PasswordKdf @Inject constructor() {

    private val secureRandom = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also(secureRandom::nextBytes)

    /**
     * The only supported KDF entry point. Pass the original CharArray, never a re-encoded one.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int = 32): ByteArray {
        require(salt.size == SALT_LEN) { "salt must be $SALT_LEN bytes" }
        require(iterations >= MIN_ITERATIONS) { "iterations $iterations < MIN_ITERATIONS $MIN_ITERATIONS" }
        require(keyBytes in 16..64) { "keyBytes out of range" }
        val spec = PBEKeySpec(password, salt, iterations, keyBytes * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Calibrate iteration count for ~300 ms target. Floor at [MIN_ITERATIONS] (OWASP 2024 baseline),
     * cap at [MAX_ITERATIONS] so the calibration loop is bounded.
     */
    fun calibrate(targetMillis: Long = TARGET_CALIBRATION_MS): Int {
        val pw = CharArray(16) { 'x' }
        val salt = newSalt()
        var iter = MIN_ITERATIONS
        var elapsed: Long
        do {
            val t0 = System.nanoTime()
            derive(pw, salt, iter)
            elapsed = (System.nanoTime() - t0) / 1_000_000
            if (elapsed < targetMillis) iter = (iter * 1.7).toInt()
        } while (elapsed < targetMillis && iter < MAX_ITERATIONS)
        return iter.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    }

    companion object {
        const val SALT_LEN = 16
        /** OWASP Mobile 2024 recommended floor for PBKDF2-HMAC-SHA512 (Audit F17). */
        const val MIN_ITERATIONS = 210_000
        const val MAX_ITERATIONS = 4_000_000
        const val TARGET_CALIBRATION_MS = 300L
    }
}
