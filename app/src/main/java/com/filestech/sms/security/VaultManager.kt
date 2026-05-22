package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
    private val appLock: AppLockManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // v1.11.0 audit SEC-V2 — AtomicBoolean au lieu de `@Volatile Boolean`.
    // Sémantique correcte pour un flag partagé entre coroutines IO et UI :
    // get/set atomiques garantis (la JVM peut tearer un boolean Volatile sur
    // certaines architectures 32-bit, et le compareAndSet n'est pas disponible
    // sur un primitive Volatile). Le flag continue à servir de simple
    // mémoire d'état (pas de logique transactionnelle CAS) — mais le
    // contrat est désormais formel.
    private val sessionUnlocked = AtomicBoolean(false)

    val isVaultUnlockedInSession: Boolean get() = sessionUnlocked.get()

    /** Marks the vault as opened for this app session. Caller must already be authenticated. */
    fun markUnlocked() { sessionUnlocked.set(true) }

    /** Forces the vault to be locked again (e.g. on background or panic). */
    fun lock() { sessionUnlocked.set(false) }

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
        if (!sessionUnlocked.get()) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, true)
        Outcome.Success(Unit)
    }

    suspend fun moveOutOfVault(conversationId: Long): Outcome<Unit> = withContext(io) {
        if (!sessionUnlocked.get()) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, false)
        Outcome.Success(Unit)
    }

    /**
     * v1.11.0 — move-in/out depuis l'extérieur de [com.filestech.sms.ui.screens.vault.VaultScreen]
     * (long-press conv liste, overflow ThreadScreen). Préserve le guard
     * [sessionUnlocked] historique tout en débloquant l'UX manquante : l'user
     * authentifié principal (non-decoy) doit pouvoir déplacer une conv dans le
     * coffre depuis n'importe où, et inversement depuis le VaultScreen quand
     * sessionUnlocked est déjà true.
     *
     * **Politique de sécurité** :
     *  - Refuse si [AppLockManager.LockState.PanicDecoy] — un agresseur en
     *    decoy NE DOIT PAS pouvoir bulk-hider les conv légitimes (S-P0-2).
     *    Defense in depth : l'UI doit DÉJÀ masquer le menu en decoy.
     *  - Refuse si [AppLockManager.LockState.Locked] — état impossible côté
     *    UI (l'écran de lock bloque la nav) mais filet de sécurité.
     *  - Sinon : auto-`markUnlocked()` puis délègue à [moveToVault]/[moveOutOfVault].
     *    L'auto-unlock est cohérent avec l'intention explicite de l'user qui
     *    a cliqué sur "Déplacer vers le coffre" depuis un menu authenticated.
     */
    suspend fun requestMoveToVault(
        conversationId: Long,
        intoVault: Boolean,
    ): Outcome<Unit> = withContext(io) {
        val lockState = appLock.state.value
        when (lockState) {
            is AppLockManager.LockState.PanicDecoy -> return@withContext Outcome.Failure(AppError.Locked())
            is AppLockManager.LockState.Locked -> return@withContext Outcome.Failure(AppError.Locked())
            else -> Unit // Unlocked or Disabled — proceed
        }
        // v1.11.0 audit S2 — re-check juste avant la mutation pour bloquer
        // une race où PanicDecoy aurait été activé entre la 1ère évaluation
        // et l'exécution de la coroutine sur IO. Sans ce filet, une notif
        // OS poussant PanicDecoy juste avant `moveToVault` cacherait des
        // conv légitimes sous une session decoy (bulk-hiding latent).
        val postCheck = appLock.state.value
        if (postCheck is AppLockManager.LockState.PanicDecoy) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        sessionUnlocked.set(true)
        conversationRepo.moveToVault(conversationId, intoVault)
        Outcome.Success(Unit)
    }

    /** Ensures the underlying Keystore alias exists. Called at first vault use. */
    fun ensureKey() {
        keystore.getOrCreateKey(KeystoreManager.ALIAS_VAULT_KEK)
    }
}
