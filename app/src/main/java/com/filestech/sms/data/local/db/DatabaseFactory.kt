package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import com.filestech.sms.core.crypto.wipe
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.2.7 audit Q3 — propagé par [DatabaseFactory.build] quand Room détecte un schéma plus
 * récent que celui que l'APK courante connaît (downgrade non géré). Doit être attrapée en
 * amont par `MainApplication` pour afficher un écran d'erreur explicite plutôt que de laisser
 * l'app crasher en boucle au boot.
 */
class DatabaseDowngradeException(cause: Throwable) : RuntimeException(
    "SMS Tech database schema is newer than this app version. Reinstall the latest APK or " +
        "clear app data to recover.",
    cause,
)

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
        val db = try {
            Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
                .openHelperFactory(factory)
                // Forward migrations live in `Migrations.kt`. They are additive: existing rows
                // are never rewritten and the SQLCipher passphrase is unchanged across the bump,
                // so an `adb install -r` over a v1 install upgrades transparently without
                // re-prompting the user for setup or re-importing SMS.
                .addMigrations(*Migrations.ALL)
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .build()
                .also { it.openHelper.writableDatabase } // force open to surface migration errors NOW
        } catch (t: IllegalStateException) {
            // v1.2.7 audit Q3 — Room throw `IllegalStateException: A migration from N to M was
            // required but not found` quand on installe un APK plus ANCIEN (downgrade) sur une
            // DB plus récente. Sans handler, l'app crash en boucle au boot — irrécupérable
            // sans `pm clear` ou désinstallation.
            //
            // Politique : on log explicitement et on **propage** un nouvel exception type
            // (DatabaseDowngradeException) qui sera attrapée par `MainApplication` pour
            // afficher un écran "Version installée plus ancienne que votre DB — réinstallez
            // la dernière version ou videz les données". On NE wipe PAS la DB en silence
            // pour respecter le principe "pas de destruction sans accord user" qui guide
            // tout le projet sécu (cf. SECURITY.md).
            raw.wipe()
            Timber.e(t, "DatabaseFactory: downgrade detected — aborting open")
            throw DatabaseDowngradeException(t)
        }
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
