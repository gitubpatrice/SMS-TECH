package com.filestech.sms.data.local.db

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the SQLCipher passphrase, derived from a random 32-byte key wrapped by the AndroidKeyStore.
 *
 * File layout:  `<files>/db/master.key` — version(1) || nonce(12) || ct+tag(N)
 *
 * On first run a 32-byte random key is generated, encrypted with the Keystore AES-GCM key alias
 * [KeystoreManager.ALIAS_DB_MASTER], and persisted. Subsequent runs decrypt the blob to recover
 * the raw key, which SQLCipher uses as its passphrase.
 *
 * **Audit F18 / F32 mitigation**: distinguishing real Keystore invalidation (e.g. user changed the
 * lock-screen credential, Samsung Knox OTA reset) from transient decrypt failures avoids the
 * silent data-loss scenario where ANY error would wipe the wrapped key. Callers receive a typed
 * [Failure] subclass and the UI presents a recovery flow instead of auto-wiping.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
) {
    private val secureRandom = SecureRandom()

    private val keyDir: File by lazy {
        File(context.filesDir, "db").apply { if (!exists()) mkdirs() }
    }
    private val keyFile: File by lazy { File(keyDir, "master.key") }

    sealed class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
        /** The Keystore alias is gone or invalidated. Existing wrapped key cannot be recovered. */
        class KeystoreInvalidated(cause: Throwable? = null) :
            Failure("AndroidKeyStore alias was invalidated; existing data unrecoverable", cause)

        /** AEAD decryption failed but the Keystore is healthy — likely file corruption. */
        class WrapCorrupted(cause: Throwable? = null) :
            Failure("wrapped DB key is corrupted on disk", cause)

        /** I/O failure while reading/writing the key blob. */
        class Io(cause: Throwable? = null) : Failure("I/O failure reading the wrapped DB key", cause)
    }

    /** Returns the raw 32-byte SQLCipher key, generating it on first call. */
    @Throws(Failure::class)
    fun getOrCreatePassphrase(): ByteArray =
        if (keyFile.exists()) unwrap() else generateAndWrap()

    /** Forcibly destroys the wrapped key file (used by panic-mode wipe and "reset all"). */
    fun destroyKeyFile() {
        if (keyFile.exists()) keyFile.delete()
    }

    private fun generateAndWrap(): ByteArray {
        val raw = ByteArray(AeadCipher.KEY_BYTES).also(secureRandom::nextBytes)
        val secretKey = try {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_DB_MASTER)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw Failure.KeystoreInvalidated(e)
        }
        val wrapped = when (val r = aead.encrypt(secretKey, raw)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                raw.wipe()
                throw Failure.WrapCorrupted()
            }
        }
        try {
            keyFile.outputStream().use { it.write(wrapped) }
        } catch (e: Throwable) {
            raw.wipe()
            throw Failure.Io(e)
        }
        return raw
    }

    private fun unwrap(): ByteArray {
        val wrapped = try {
            keyFile.readBytes()
        } catch (e: Throwable) {
            throw Failure.Io(e)
        }
        val secretKey = try {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_DB_MASTER)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.e("Keystore key invalidated (likely credential change on this device)")
            throw Failure.KeystoreInvalidated(e)
        } catch (e: UserNotAuthenticatedException) {
            // Currently we do not require user auth on the DB key. Re-throw mapped error for safety.
            throw Failure.KeystoreInvalidated(e)
        }
        return when (val r = aead.decrypt(secretKey, wrapped)) {
            is Outcome.Success -> {
                wrapped.wipe()
                r.value
            }
            is Outcome.Failure -> {
                wrapped.wipe()
                // We DO NOT auto-delete the keyFile here (audit F18): silent wipe = silent data loss.
                // Caller (`DatabaseFactory`) surfaces a recovery UI instead.
                throw Failure.WrapCorrupted()
            }
        }
    }
}
