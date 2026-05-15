package com.filestech.sms.security

import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.datastore.LockMode
import com.filestech.sms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lock/unlock state of the app. Stores only salted PBKDF2-SHA512 hashes; never the PIN.
 *
 * Lockout policy: monotonic exponential backoff after a streak of failures.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val securityStore: SecurityStore,
    private val settings: SettingsRepository,
    private val kdf: PasswordKdf,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Initial state is [LockState.Locked] (fail-closed). Any subsequent observer must wait for
     * [resolveInitialState] to flip to [LockState.Disabled] if the user has disabled the lock.
     * This closes F1: the NavHost cannot show the conversation list during the cold-start window
     * (50-300 ms) before settings are loaded from DataStore.
     */
    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    sealed interface LockState {
        /** Lock is configured OFF in settings — UI is always visible. */
        data object Disabled : LockState
        /** Lock is configured ON and user has not unlocked yet. UI must be hidden. */
        data object Locked : LockState
        /** Too many failed attempts — UI shows the countdown, no PIN accepted yet. */
        data class LockedOut(val until: Long) : LockState
        /** User has unlocked successfully. */
        data object Unlocked : LockState
        /** Panic-code unlock — UI is visible but the vault must remain hidden. */
        data object PanicDecoy : LockState
    }

    /** True iff the UI should be reachable (real unlock OR decoy OR lock disabled). */
    fun isOpenForUi(state: LockState): Boolean = when (state) {
        LockState.Unlocked, LockState.PanicDecoy, LockState.Disabled -> true
        LockState.Locked -> false
        is LockState.LockedOut -> false
    }

    /**
     * Latched once [resolveInitialState] has flipped `_state` away from the fail-closed default.
     * Read by [ensureResolved] to make the resolution idempotent across cold-start contention
     * (Application.onCreate may kick it off asynchronously, while a broadcast receiver fired in
     * the same process may need to wait for the result before consulting the state).
     */
    private val resolvedLatch = AtomicBoolean(false)
    private val resolveMutex = Mutex()

    suspend fun resolveInitialState(): LockState = withContext(io) {
        val s = settings.flow.first()
        val resolved = if (s.security.lockMode == LockMode.OFF) LockState.Disabled else LockState.Locked
        _state.value = resolved
        resolvedLatch.set(true)
        resolved
    }

    /**
     * Idempotent variant of [resolveInitialState]. Safe to call concurrently from multiple
     * coroutines — only the first one actually queries `DataStore`; subsequent callers return
     * immediately once the latch is set.
     *
     * Audit P-P0-5: receivers / services that need a correct `_state` at cold-start (Notification
     * reply, Headless SMS send service) call this before reading [state] / [isOpenForUi]. That
     * way [MainApplication.onCreate] no longer has to block the main thread with `runBlocking` —
     * the receivers each pay the resolution cost lazily in their own coroutine context, and only
     * once per process lifetime.
     */
    suspend fun ensureResolved() {
        if (resolvedLatch.get()) return
        resolveMutex.withLock {
            if (resolvedLatch.get()) return@withLock
            resolveInitialState()
        }
    }

    /**
     * Sets the user's PIN. The [newPin] CharArray is wiped on exit. NEVER round-trips through
     * `toByteArray(UTF-8).toCharArray()` — that was the F3 entropy bug. PBKDF2-HMAC-SHA512 handles
     * the UTF-8 encoding of CharArray internally and preserves the full Unicode range.
     */
    suspend fun setPin(newPin: CharArray): Unit = withContext(io) {
        val salt = kdf.newSalt()
        val iters = kdf.calibrate()
        try {
            val hash = kdf.derive(newPin, salt, iters)
            securityStore.setPinHash(salt, hash, iters)
        } finally {
            newPin.wipe()
        }
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.PIN)) }
        _state.value = LockState.Locked
    }

    suspend fun clearPin() = withContext(io) {
        securityStore.clearPin()
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.OFF)) }
        _state.value = LockState.Disabled
    }

    /**
     * Promotes the lock mode to [LockMode.BIOMETRIC] **on top of an existing PIN**. The PIN is
     * kept as the fallback secret of record — if the biometric becomes unavailable (re-enrolled
     * empties the key, dirty sensor, hardware failure) the user can still unlock with the PIN
     * they configured. Refuses to switch when no PIN is set: a biometric-only mode would
     * lock the user out the moment their fingerprint enrollment changes.
     */
    suspend fun enableBiometric(): Boolean = withContext(io) {
        if (securityStore.pinSnapshot() == null) return@withContext false
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.BIOMETRIC)) }
        true
    }

    /** Drops back from BIOMETRIC to PIN (keeps the PIN). No-op outside BIOMETRIC. */
    suspend fun disableBiometric() = withContext(io) {
        val s = settings.flow.first()
        if (s.security.lockMode == LockMode.BIOMETRIC) {
            settings.update { it.copy(security = it.security.copy(lockMode = LockMode.PIN)) }
        }
    }

    suspend fun attemptUnlock(candidate: CharArray): LockState = withContext(io) {
        val now = System.currentTimeMillis()
        val lockoutUntil = securityStore.lockoutUntil.first()
        if (lockoutUntil > now) {
            return@withContext LockState.LockedOut(lockoutUntil).also { _state.value = it }
        }

        // Audit P1-3 (v1.2.0): both PIN and panic snapshots are evaluated and the failure
        // counter is incremented **once** per attempt if neither matches. Earlier the panic
        // branch returned before the failure path, letting an attacker brute-force a 4-digit
        // panic code while the long PIN absorbed the lockout — ~10 000 panic probes possible
        // without ever tripping the exponential cool-down.
        val panicSnap = securityStore.panicSnapshot()
        val panicMatches = panicSnap != null &&
            matches(candidate, panicSnap.salt, panicSnap.hash, panicSnap.iterations)
        if (panicMatches) {
            _state.value = LockState.PanicDecoy
            securityStore.setFailCount(0)
            securityStore.setLastUnlock(now)
            return@withContext LockState.PanicDecoy
        }

        val snap = securityStore.pinSnapshot()
            ?: return@withContext LockState.Disabled.also { _state.value = it }

        if (matches(candidate, snap.salt, snap.hash, snap.iterations)) {
            securityStore.setFailCount(0)
            securityStore.setLockoutUntil(0L)
            securityStore.setLastUnlock(now)
            _state.value = LockState.Unlocked
            LockState.Unlocked
        } else {
            val newFail = (securityStore.failCount.first() + 1).coerceAtMost(MAX_FAIL_TRACKED)
            securityStore.setFailCount(newFail)
            if (newFail >= LOCKOUT_THRESHOLD) {
                val delayMs = backoffMillis(newFail - LOCKOUT_THRESHOLD)
                val until = now + delayMs
                securityStore.setLockoutUntil(until)
                _state.value = LockState.LockedOut(until)
                LockState.LockedOut(until)
            } else {
                _state.value = LockState.Locked
                LockState.Locked
            }
        }
    }

    /**
     * Forces the app back to its locked state. Idempotent on [LockState.Disabled].
     * PanicDecoy is also re-locked: the next unlock attempt re-evaluates panic vs primary PIN.
     */
    fun forceLock() {
        val current = _state.value
        if (current != LockState.Disabled) _state.value = LockState.Locked
    }

    // -------- Biometric handshake (fixes F2 audit finding) ------------------------------------
    // A one-shot, single-use challenge token is issued by [beginBiometricChallenge], passed to the
    // BiometricPrompt's CryptoObject success callback site, and verified by [markBiometricUnlocked].
    // Without a valid live challenge, [markBiometricUnlocked] is a no-op — making the function safe
    // even if accidentally called from a regression / hostile code path.
    //
    // Audit P1-2 (v1.2.0): challenge stored in an [AtomicReference] so that two concurrent
    // begin/mark calls (rotation race, double-tap on the lock screen) cannot end up with a stale
    // token. The previous `@Volatile var` had atomic read/write *each*, but the swap-and-return
    // pair in [markBiometricUnlocked] was not atomic across the two — a second `begin` between
    // the read and the reset of the first call would have caused either path to swallow the
    // unlock silently. `getAndSet(null)` makes the consume both atomic and one-shot.
    private val biometricChallenge = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val biometricRng = java.security.SecureRandom()

    fun beginBiometricChallenge(): ByteArray {
        val token = ByteArray(BIO_CHALLENGE_BYTES).also(biometricRng::nextBytes)
        biometricChallenge.set(token)
        return token.copyOf()
    }

    /**
     * Promotes the session to [LockState.Unlocked] **only** when the current state is
     * [LockState.Locked]. Refuses to act in any other state — in particular [LockState.LockedOut]
     * (audit S-P0-3: a biometric success must not bypass the exponential cool-down imposed on PIN
     * failures) and [LockState.PanicDecoy] (a biometric scan from the panic session must never
     * unseal it). [LockState.Unlocked] and [LockState.Disabled] are likewise no-ops.
     *
     * The state check is sufficient — `_state` is the authoritative live value, kept up to date
     * by [attemptUnlock] / [forceLock] / [resolveInitialState]. We deliberately do not re-read
     * [SecurityStore.lockoutUntil] here: it would force the callback (BiometricPrompt success) to
     * become suspending for no real gain, and the conservative path of waiting for the user to
     * tap PIN — which DOES go through [attemptUnlock] and refreshes the state — handles the rare
     * "lockout has just expired but state is stale" edge cleanly.
     */
    fun markBiometricUnlocked(challenge: ByteArray) {
        // Atomic consume: the challenge is single-use. If a second prompt fires before the
        // first completes, only one of the two can succeed; the other gets `null` and bails.
        val expected = biometricChallenge.getAndSet(null) ?: return
        if (!java.security.MessageDigest.isEqual(expected, challenge)) return
        if (_state.value !is LockState.Locked) return
        _state.value = LockState.Unlocked
    }

    private fun matches(candidate: CharArray, salt: ByteArray, expected: ByteArray, iters: Int): Boolean {
        val derived = kdf.derive(candidate, salt, iters)
        return try {
            constantTimeEquals(derived, expected)
        } finally {
            derived.wipe()
        }
    }

    companion object {
        const val LOCKOUT_THRESHOLD = 5
        const val MAX_FAIL_TRACKED = 100
        const val BIO_CHALLENGE_BYTES = 32

        /**
         * Backoff after [LOCKOUT_THRESHOLD] failures (fixes F39). Starts at 5 s — long enough to
         * make 4-digit PIN brute-force costly without being punitive on a typo. Caps at 5 minutes.
         * 1000 PINs × 5 s = ~83 minutes minimum if the user keeps hammering through every step.
         */
        private val BACKOFF_STEPS_MS = longArrayOf(
            5_000, 10_000, 30_000, 60_000, 120_000, 300_000,
        )
        fun backoffMillis(stepIndex: Int): Long =
            BACKOFF_STEPS_MS[stepIndex.coerceIn(0, BACKOFF_STEPS_MS.size - 1)]

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var r = 0
            for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
            return r == 0
        }
    }
}
