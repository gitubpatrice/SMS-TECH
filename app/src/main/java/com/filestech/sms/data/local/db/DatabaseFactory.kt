package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the SQLCipher-backed [AppDatabase].
 *
 * ⚠️ The raw passphrase is deliberately **kept alive** for the lifetime of the process. It used to
 * be wiped right after `build()`, which silently encrypted every database with 32 zero bytes —
 * SQLCipher stores the array by reference and only reads it when Room lazily opens the connection.
 * See [LegacyZeroKeyRekey] for the full analysis and the field repair. Do not reintroduce a wipe
 * here: SQLCipher needs the key to reopen the database after any `close()`.
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

        // v1.24.0 SEC-CRIT — repair databases still encrypted with the legacy all-zero key.
        // Runs BEFORE Room opens the file, with the very array handed to SQLCipher below.
        // Cf. [LegacyZeroKeyRekey] for the full analysis of the defect.
        LegacyZeroKeyRekey.rekeyIfNeeded(context, raw)

        val factory = SupportOpenHelperFactory(raw)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            // Forward migrations live in `Migrations.kt`. They are additive: existing rows are
            // never rewritten and the SQLCipher passphrase is unchanged across the bump, so an
            // `adb install -r` over a v1 install upgrades transparently without re-prompting
            // the user for setup or re-importing SMS.
            //
            // v1.2.7 audit Q3 NOTE : `fallbackToDestructiveMigrationOnDowngrade(false)` →
            // un user qui installerait un APK plus ANCIEN sur une DB plus récente
            // crashera à l'ouverture (IllegalStateException Room). Politique assumée :
            // on préfère un crash visible (qui pousse à réinstaller la bonne version) plutôt
            // qu'un wipe silencieux de toutes les conversations. La documentation utilisateur
            // précise que les downgrades ne sont pas supportés (cf. SECURITY.md).
            .addMigrations(*Migrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(false)
            .build()
        // ⚠️ NE JAMAIS APPELER `raw.wipe()` ICI — c'était le défaut SEC-CRIT corrigé en v1.24.0.
        //
        // `wipe()` fait `Arrays.fill(this, 0)` : il zéroise le tableau SUR PLACE, il ne libère pas
        // une copie. Or aucun maillon de SQLCipher ne copie ce tableau — vérifié par désassemblage
        // de `sqlcipher-android-4.16.0` : `SupportOpenHelperFactory` stocke la référence
        // (`putfield password:[B`), `SupportHelper` la transmet telle quelle, et `SQLiteOpenHelper`
        // la conserve dans `mPassword`, qu'il ne lit QUE dans `getDatabaseLocked()`, c'est-à-dire
        // à l'ouverture. Room ouvre paresseusement, au premier accès DAO, bien après ce `build()`.
        //
        // Zéroiser ici revenait donc à chiffrer la base avec 32 octets nuls — une clé constante et
        // publique — au lieu de la passphrase scellée par le Keystore. Le bug était invisible car
        // une clé nulle est parfaitement stable d'un lancement à l'autre.
        //
        // SQLCipher conserve la passphrase en mémoire pour toute la durée du process (il doit
        // pouvoir rouvrir la base après un `close()`), donc la zéroiser plus tard n'est pas non
        // plus une option : l'exposition mémoire est inhérente à la conception de la bibliothèque.
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
