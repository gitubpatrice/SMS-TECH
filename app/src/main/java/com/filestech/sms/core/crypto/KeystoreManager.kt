package com.filestech.sms.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the AndroidKeyStore for AES-256-GCM keys.
 *
 * One key per logical purpose:
 *  - "db_master"       : SQLCipher master key
 *  - "vault_kek"       : KEK for the secure vault
 *  - "settings_aead"   : encrypts sensitive DataStore entries
 *  - "panic_decoy"     : panic-mode decoy key (separate from db_master)
 *
 * Keys are non-exportable; AES-GCM nonces are caller-provided 12-byte arrays.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun getOrCreateKey(alias: String, userAuthRequired: Boolean = false): SecretKey {
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        return generateKey(alias, userAuthRequired)
    }

    fun deleteKey(alias: String) {
        runCatching { keyStore.deleteEntry(alias) }
            .onFailure { Timber.w(it, "KeystoreManager: failed to delete %s", alias) }
    }

    fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    private fun generateKey(alias: String, userAuthRequired: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // AndroidKeyStore rejects user-supplied IVs when this flag is true. Our [AeadCipher.encrypt]
            // always generates a fresh 96-bit IV via SecureRandom, so randomization is already
            // enforced cryptographically. We just need the OS to let us pass that IV through.
            .setRandomizedEncryptionRequired(false)
            .setUserAuthenticationRequired(userAuthRequired)
            .apply {
                if (userAuthRequired && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256

        const val ALIAS_DB_MASTER = "smstech_db_master"
        const val ALIAS_VAULT_KEK = "smstech_vault_kek"
        const val ALIAS_SETTINGS_AEAD = "smstech_settings_aead"
        const val ALIAS_PANIC_DECOY = "smstech_panic_decoy"
    }
}
