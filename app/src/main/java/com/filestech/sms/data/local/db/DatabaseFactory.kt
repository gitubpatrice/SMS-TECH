package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import com.filestech.sms.core.crypto.wipe
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the SQLCipher-backed [AppDatabase]. The raw passphrase is wiped from JVM memory
 * immediately after Room consumes the factory.
 *
 * The SQLCipher native library must be loaded once before the first connection is opened —
 * `System.loadLibrary("sqlcipher")` accomplishes this. SQLCipher's own `loadLibs()` helper used to
 * exist on older releases but its signature has changed across minor versions, so we call the
 * loader directly.
 */
@Singleton
class DatabaseFactory @Inject constructor(
    private val keyManager: DatabaseKeyManager,
) {

    fun build(context: Context): AppDatabase {
        loadNativeOnce()
        val raw = keyManager.getOrCreatePassphrase()
        val factory = SupportOpenHelperFactory(raw)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            // Forward migrations live in `Migrations.kt`. They are additive: existing rows are
            // never rewritten and the SQLCipher passphrase is unchanged across the bump, so an
            // `adb install -r` over a v1 install upgrades transparently without re-prompting
            // the user for setup or re-importing SMS.
            .addMigrations(*Migrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(false)
            .build()
        raw.wipe()
        return db
    }

    @Synchronized
    private fun loadNativeOnce() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }

    companion object {
        @Volatile private var loaded = false
    }
}
