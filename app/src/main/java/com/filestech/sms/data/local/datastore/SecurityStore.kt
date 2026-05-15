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
        val failCount = intPreferencesKey("auth.fail")
        val lockoutUntil = longPreferencesKey("auth.lockoutUntil")
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
