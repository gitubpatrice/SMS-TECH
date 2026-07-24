package com.filestech.sms.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secStore by preferencesDataStore(name = "sms_tech_security")

/**
 * Stores opaque blobs (hashed PIN, salts, panic codes…). Never stores cleartext credentials.
 */
@Singleton
class SecurityStore @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun setPinHash(salt: ByteArray, hash: ByteArray, iterations: Int) {
        context.secStore.edit { p ->
            p[K.pinSalt] = salt
            p[K.pinHash] = hash
            p[K.pinIters] = iterations
        }
    }

    suspend fun clearPin() = context.secStore.edit { p ->
        p.remove(K.pinSalt); p.remove(K.pinHash); p.remove(K.pinIters)
    }

    suspend fun pinSnapshot(): PinSnapshot? {
        val p = context.secStore.data.first()
        val salt = p[K.pinSalt] ?: return null
        val hash = p[K.pinHash] ?: return null
        val iters = p[K.pinIters] ?: return null
        return PinSnapshot(salt, hash, iters)
    }

    val failCount: Flow<Int> = context.secStore.data.map { it[K.failCount] ?: 0 }
    suspend fun setFailCount(n: Int) = context.secStore.edit { it[K.failCount] = n.coerceIn(0, 1000) }
    val lockoutUntil: Flow<Long> = context.secStore.data.map { it[K.lockoutUntil] ?: 0L }

    /**
     * Audit P1-1 (v1.2.0): clamp the lockout horizon to 24 hours forward. Without this, a
     * malicious DataStore mutation (tainted backup restore, future migration bug) could write
     * `Long.MAX_VALUE` and lock the user out of their own app forever. The 24 h cap is well
     * above the legitimate exponential-backoff ceiling (5 minutes) and short enough that a
     * real lockout ages off naturally if anything went wrong.
     */
    suspend fun setLockoutUntil(ts: Long) = context.secStore.edit {
        val now = System.currentTimeMillis()
        val maxHorizon = now + 24L * 60L * 60L * 1_000L
        it[K.lockoutUntil] = ts.coerceIn(0L, maxHorizon)
    }

    /**
     * Audit R7 (v1.14.8) — Lockout monotonic baseline. Avant : seul [lockoutUntil] (wall clock)
     * était stocké → un user pouvant manipuler l'horloge système (Android 8-9 sans root, devices
     * rootés, fuseau horaire avancé) pouvait expirer le lockout en quelques secondes et brute-force
     * un PIN 4 chiffres. Maintenant on persiste aussi `setAtElapsed` (`SystemClock.elapsedRealtime`
     * au moment du write) + `durationMs` (durée originelle). [isLockoutActive] croise les deux
     * horloges et exige que les DEUX soient expirées pour libérer.
     *
     * `elapsedRealtime` est immune aux manipulations user (seul un reboot la réinitialise — c'est
     * géré par la guard `nowElapsed < setAtElapsed` dans [isLockoutActive] qui fallback alors sur
     * la wall clock seule, comportement raisonnable post-reboot).
     */
    suspend fun setLockout(untilWall: Long, durationMs: Long, nowElapsed: Long) = context.secStore.edit {
        val now = System.currentTimeMillis()
        val maxHorizon = now + 24L * 60L * 60L * 1_000L
        it[K.lockoutUntil] = untilWall.coerceIn(0L, maxHorizon)
        it[K.lockoutSetAtElapsed] = nowElapsed.coerceAtLeast(0L)
        it[K.lockoutDurationMs] = durationMs.coerceIn(0L, 24L * 60L * 60L * 1_000L)
    }

    suspend fun clearLockout() = context.secStore.edit {
        it[K.lockoutUntil] = 0L
        it[K.lockoutSetAtElapsed] = 0L
        it[K.lockoutDurationMs] = 0L
    }

    /** Snapshot des 3 champs lockout en une seule lecture DataStore (vs 3 .first()). */
    suspend fun lockoutSnapshot(): LockoutSnapshot {
        val p = context.secStore.data.first()
        return LockoutSnapshot(
            untilWall = p[K.lockoutUntil] ?: 0L,
            setAtElapsed = p[K.lockoutSetAtElapsed] ?: 0L,
            durationMs = p[K.lockoutDurationMs] ?: 0L,
        )
    }

    data class LockoutSnapshot(val untilWall: Long, val setAtElapsed: Long, val durationMs: Long) {
        /**
         * Audit R7 — Lockout actif si l'une des deux horloges (wall OU mono) dit "pas encore
         * expiré". Mono est autoritaire contre la manipulation fwd de l'horloge système.
         * Si mono baseline > nowElapsed → reboot détecté → mono check invalidé → fallback wall.
         */
        fun isLockoutActive(nowMs: Long, nowElapsed: Long): Boolean {
            val wallLocked = untilWall > 0L && untilWall > nowMs
            val monoLocked = setAtElapsed > 0L && durationMs > 0L
                && nowElapsed >= setAtElapsed
                && (nowElapsed - setAtElapsed) < durationMs
            return wallLocked || monoLocked
        }
    }

    suspend fun setPanicCode(salt: ByteArray, hash: ByteArray, iterations: Int) {
        context.secStore.edit { p ->
            p[K.panicSalt] = salt
            p[K.panicHash] = hash
            p[K.panicIters] = iterations
        }
    }
    suspend fun clearPanic() = context.secStore.edit { p ->
        p.remove(K.panicSalt); p.remove(K.panicHash); p.remove(K.panicIters)
    }
    suspend fun panicSnapshot(): PinSnapshot? {
        val p = context.secStore.data.first()
        val salt = p[K.panicSalt] ?: return null
        val hash = p[K.panicHash] ?: return null
        val iters = p[K.panicIters] ?: return null
        return PinSnapshot(salt, hash, iters)
    }

    /**
     * v1.13.0 — PIN distinct pour le coffre (second-factor). PBKDF2-HMAC-SHA512
     * indépendant du PIN d'app : compromis du PIN d'app sous coercion (Locked
     * → tape PIN d'app sous menace, OK) ne donne PAS accès au coffre tant que
     * le PIN coffre n'est pas révélé. Schéma à plat : 3 prefs idempotentes,
     * `clear` enlève les 3, `snapshot` retourne null si toute clé manque.
     *
     * **Crypto** : pas de second envelope SQLCipher — le coffre reste dans la
     * même base chiffrée que l'app (clé Keystore commune). Le second-factor
     * est UN GATE UI : tant que le hash candidat ne matche pas le stocké, on
     * refuse `markUnlocked()`. C'est de la défense contre l'attaque "j'ai vu
     * ton PIN d'app, je vais lire ton coffre", pas contre un forensique avec
     * accès Keystore (qui reste protégé par lockscreen Android).
     */
    suspend fun setVaultPinHash(salt: ByteArray, hash: ByteArray, iterations: Int) {
        context.secStore.edit { p ->
            p[K.vaultSalt] = salt
            p[K.vaultHash] = hash
            p[K.vaultIters] = iterations
        }
    }
    suspend fun clearVaultPin() = context.secStore.edit { p ->
        p.remove(K.vaultSalt); p.remove(K.vaultHash); p.remove(K.vaultIters)
    }
    suspend fun vaultPinSnapshot(): PinSnapshot? {
        val p = context.secStore.data.first()
        val salt = p[K.vaultSalt] ?: return null
        val hash = p[K.vaultHash] ?: return null
        val iters = p[K.vaultIters] ?: return null
        return PinSnapshot(salt, hash, iters)
    }

    /** Last time the user successfully unlocked the app. Used by auto-lock. */
    suspend fun setLastUnlock(ts: Long) = context.secStore.edit { it[K.lastUnlock] = ts }
    val lastUnlock: Flow<Long> = context.secStore.data.map { it[K.lastUnlock] ?: 0L }

    private object K {
        val pinSalt = byteArrayPreferencesKey("pin.salt")
        val pinHash = byteArrayPreferencesKey("pin.hash")
        val pinIters = intPreferencesKey("pin.iters")
        val panicSalt = byteArrayPreferencesKey("panic.salt")
        val panicHash = byteArrayPreferencesKey("panic.hash")
        val panicIters = intPreferencesKey("panic.iters")
        // v1.13.0 — PIN distinct coffre (second-factor).
        val vaultSalt = byteArrayPreferencesKey("vault.salt")
        val vaultHash = byteArrayPreferencesKey("vault.hash")
        val vaultIters = intPreferencesKey("vault.iters")
        val failCount = intPreferencesKey("auth.fail")
        val lockoutUntil = longPreferencesKey("auth.lockoutUntil")
        // v1.14.8 R7 — anti-wallclock-manipulation : baseline mono + durée d'origine.
        val lockoutSetAtElapsed = longPreferencesKey("auth.lockoutSetAtElapsed")
        val lockoutDurationMs = longPreferencesKey("auth.lockoutDurationMs")
        val lastUnlock = longPreferencesKey("auth.lastUnlock")
    }

    data class PinSnapshot(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PinSnapshot) return false
            return salt.contentEquals(other.salt) && hash.contentEquals(other.hash) && iterations == other.iterations
        }
        override fun hashCode(): Int {
            var r = salt.contentHashCode()
            r = 31 * r + hash.contentHashCode()
            r = 31 * r + iterations
            return r
        }
    }
}
