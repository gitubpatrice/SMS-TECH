package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logical "secure vault": conversations marked as `in_vault=true` are hidden from the main UI
 * and only readable while the app is fully unlocked AND the vault has been opened in this
 * session.
 *
 * # Threat model — what this gives you today (audit S-P1-1)
 *
 *  - The whole Room DB is encrypted at rest by SQLCipher with a key wrapped in the Android
 *    Keystore, so anyone reading the on-device files (adb pull, USB debugging, root) sees
 *    only ciphertext.
 *  - On top of that, the vault adds **three layered UI / data gates** so that a coerced
 *    "open the app" scenario (panic-decoy unlock, shoulder surfing) does not expose hidden
 *    conversations:
 *    1. [ConversationRepositoryImpl.observeVault]/`observeOne`/`observeMessages` return
 *       empty when `lockState is PanicDecoy` and the conversation is in the vault.
 *    2. [com.filestech.sms.ui.AppRoot] redirects any nav to the Vault route while in decoy.
 *    3. [com.filestech.sms.ui.screens.conversations.ConversationsScreen] hides the vault
 *       top-bar entry point in decoy.
 *
 * # What this does **not** give you (yet)
 *
 *  - A separate cryptographic envelope. Vault messages share the same SQLCipher key as
 *    regular messages, so an attacker who already has the master key (rooted device with
 *    Keystore access) reads everything in one pass.
 *  - Per-session re-authentication of vault content. Once the master PIN is correct, the
 *    Keystore-wrapped key decrypts every row.
 *
 * A real second envelope using [com.filestech.sms.core.crypto.KeystoreManager.ALIAS_VAULT_KEK]
 * with `setUserAuthenticationRequired = true` is reserved for v1.1.1 because it requires a
 * Room schema migration to add an encrypted-body column and a biometric / device-credential
 * UX. The alias is already created at install time so the migration path is forward-compatible.
 */
@Singleton
class VaultManager @Inject constructor(
    private val keystore: KeystoreManager,
    private val conversationRepo: ConversationRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile private var sessionUnlocked: Boolean = false

    val isVaultUnlockedInSession: Boolean get() = sessionUnlocked

    /** Marks the vault as opened for this app session. Caller must already be authenticated. */
    fun markUnlocked() { sessionUnlocked = true }

    /** Forces the vault to be locked again (e.g. on background or panic). */
    fun lock() { sessionUnlocked = false }

    /**
     * Moves a conversation **into** the vault.
     *
     * Audit S-P0-2: this operation must require the vault to be unlocked in the current session,
     * symmetrically with [moveOutOfVault]. Without the guard, a panic-decoy session — which
     * intentionally leaves the rest of the UI usable — could be tricked into bulk-hiding the
     * user's regular conversations behind a vault the legitimate user can no longer reach
     * (because they don't know the decoy's "primary" PIN, since there isn't one — decoy unlocks
     * only the decoy view). Even if no malicious flow exists today, the asymmetry was a latent
     * footgun.
     */
    suspend fun moveToVault(conversationId: Long): Outcome<Unit> = withContext(io) {
        if (!sessionUnlocked) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, true)
        Outcome.Success(Unit)
    }

    suspend fun moveOutOfVault(conversationId: Long): Outcome<Unit> = withContext(io) {
        if (!sessionUnlocked) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, false)
        Outcome.Success(Unit)
    }

    /** Ensures the underlying Keystore alias exists. Called at first vault use. */
    fun ensureKey() {
        keystore.getOrCreateKey(KeystoreManager.ALIAS_VAULT_KEK)
    }
}
