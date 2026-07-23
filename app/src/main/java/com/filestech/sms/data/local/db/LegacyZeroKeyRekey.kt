package com.filestech.sms.data.local.db

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import timber.log.Timber
import java.io.File

/**
 * One-shot recovery for databases that were encrypted with an **all-zero key**.
 *
 * ## The defect being repaired
 *
 * Since the very first commit of [DatabaseFactory], the SQLCipher passphrase was handed to
 * `SupportOpenHelperFactory` and then immediately zeroed:
 *
 * ```kotlin
 * val raw = keyManager.getOrCreatePassphrase()
 * val factory = SupportOpenHelperFactory(raw)
 * val db = Room.databaseBuilder(...).openHelperFactory(factory).build()
 * raw.wipe()   // <-- Arrays.fill(this, 0): zeroes the array IN PLACE
 * ```
 *
 * Verified by disassembling `sqlcipher-android-4.16.0`, **no link in the chain copies it**:
 * `SupportOpenHelperFactory.<init>` stores the reference (`putfield password:[B`), `SupportHelper`
 * forwards it, and `SQLiteOpenHelper` keeps it in `mPassword`, read only inside
 * `getDatabaseLocked()` — that is, **at open time**. Room opens lazily, long after `build()`.
 *
 * Net effect: SQLCipher received 32 zero bytes. Every database in the field is encrypted with a
 * constant, publicly-known key instead of the Keystore-sealed one. The defect was invisible
 * because a zero key is perfectly stable across launches.
 *
 * ## How the repair works, and why it never touches the original
 *
 * Rekeying the user's only copy in place needs a backup to be safe, which brings its own class of
 * failure modes (backup overwritten by a half-rekeyed file, backup discarded after a validation
 * too shallow to notice partial damage, sidecars out of sync with the main file). Instead:
 *
 *  1. checkpoint the WAL into the main file and switch to a single-file journal, so the database
 *     is a self-contained snapshot with no sidecar to keep in sync;
 *  2. **copy** it to a temporary sibling;
 *  3. rekey the *copy* with `changePassword`;
 *  4. validate the copy end to end (`cipher_integrity_check` + per-table row counts against the
 *     source);
 *  5. only then swap it in, moving the original aside and deleting it last.
 *
 * The original stays intact and readable at every instant. A process kill at any point leaves a
 * usable database behind — there is no window in which data can be lost.
 *
 * A SQL `ATTACH … KEY "x'…'"` + `sqlcipher_export` would look tidier but is **wrong here**: that
 * syntax declares a RAW key, bypassing PBKDF2, whereas the app opens the database by handing
 * SQLCipher a `byte[]` *passphrase*. The exported file would be unreadable by the app. Keeping
 * `changePassword(byte[])` guarantees both sides derive the key identically.
 */
internal object LegacyZeroKeyRekey {

    /** Outcome of the probe, for logging and tests. */
    enum class Result {
        /** No database file on disk — first launch. */
        FRESH_INSTALL,

        /** The file already opens with the Keystore-sealed passphrase. */
        ALREADY_CORRECT,

        /** The file was encrypted with the zero key and has been re-encrypted. */
        REKEYED,
    }

    /** The database exists but decrypts with neither the real passphrase nor the legacy zero key. */
    class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /**
     * Marks the repair as settled so later cold starts skip the probe entirely.
     *
     * Without it every launch would pay two PBKDF2 derivations (256 000 iterations each) on the
     * startup path, forever. `SharedPreferences` rather than DataStore on purpose: this is read
     * synchronously from `DatabaseFactory.build()`, which itself runs inside Hilt's eager
     * injection during `Application.onCreate()` — a suspending read is not an option there.
     */
    private const val PREFS = "db_repair"
    private const val KEY_DONE = "zero_key_repair_v1240_done"

    private const val TMP_SUFFIX = ".rekeytmp"
    private const val OLD_SUFFIX = ".rekeyold"
    private val SIDECAR_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    /** Smallest possible SQLite file: one page header. Anything below is not a database. */
    private const val MIN_SQLITE_SIZE = 512L

    /**
     * Repairs [dbFile] if it is still on the legacy zero key.
     *
     * Must be called **before** Room opens the database, and with the very [passphrase] instance
     * that will be handed to SQLCipher. Never wipes [passphrase], never deletes user data.
     *
     * [dbFile] is injectable so tests can point at a throwaway file — running them against the
     * real `smstech.db` would destroy the device's messages.
     *
     * @throws Failure when the file exists but decrypts with neither key.
     */
    fun rekeyIfNeeded(
        context: Context,
        passphrase: ByteArray,
        dbFile: File = context.getDatabasePath(AppDatabase.DATABASE_NAME),
    ): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return Result.ALREADY_CORRECT

        // A leftover `.rekeyold` means the swap was interrupted after the original was moved
        // aside. The live file is the freshly exported one; keep it and drop the stale original.
        File(dbFile.absolutePath + OLD_SUFFIX).let { if (it.exists() && dbFile.exists()) it.delete() }
        discardTemp(dbFile)

        if (!dbFile.exists() || dbFile.length() < MIN_SQLITE_SIZE) {
            // Nothing to repair: fresh install, or a truncated stub Room will recreate anyway.
            recoverInterruptedSwap(dbFile)
            if (!dbFile.exists() || dbFile.length() < MIN_SQLITE_SIZE) {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                return Result.FRESH_INSTALL
            }
        }

        if (canOpen(dbFile, passphrase)) {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            discardOld(dbFile)
            return Result.ALREADY_CORRECT
        }

        val legacyKey = ByteArray(passphrase.size)
        if (!canOpen(dbFile, legacyKey)) {
            // Doctrine (audit F18): a silent wipe is silent data loss. Surface, never delete.
            throw Failure(
                "database ${dbFile.name} decrypts with neither the Keystore passphrase " +
                    "nor the legacy zero key",
            )
        }

        Timber.w("LegacyZeroKeyRekey: legacy zero-key database detected — exporting to a new file")
        exportToNewKey(dbFile, legacyKey, passphrase)
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        Timber.i("LegacyZeroKeyRekey: database re-encrypted with the Keystore passphrase")
        return Result.REKEYED
    }

    /**
     * Exports [dbFile] into a sibling encrypted with [passphrase], validates it end to end, then
     * swaps it in. The original is only removed once the replacement is proven good.
     */
    private fun exportToNewKey(dbFile: File, legacyKey: ByteArray, passphrase: ByteArray) {
        val tmp = File(dbFile.absolutePath + TMP_SUFFIX)
        require(dbFile.usableSpace > dbFile.length() * 2) {
            "not enough free space to rebuild ${dbFile.name} (${dbFile.length()} bytes needed twice)"
        }

        val sourceCounts: Map<String, Long>
        try {
            // Fold any WAL content back into the main file and switch to a single-file journal, so
            // the copy below is a complete, self-contained snapshot with no sidecar to keep in
            // sync. Room restores its own journal mode when it reopens the database.
            SQLiteDatabase.openDatabase(dbFile.absolutePath, legacyKey, null, 0, null).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
                sourceCounts = rowCounts(db)
            }
            dbFile.copyTo(tmp, overwrite = true)
            // `changePassword` takes the same `byte[]` the app opens the database with, so the KDF
            // treatment is identical on both sides. A SQL `ATTACH ... KEY "x'…'"` would NOT be
            // equivalent: that syntax means a RAW key, bypassing PBKDF2, and the resulting file
            // would be unreadable through `openDatabase(path, passphrase)`.
            SQLiteDatabase.openDatabase(tmp.absolutePath, legacyKey, null, 0, null).use { db ->
                db.changePassword(passphrase)
            }
        } catch (t: Throwable) {
            discardTemp(dbFile)
            throw Failure("rebuild of ${dbFile.name} failed; original left untouched", t)
        }

        validateExport(tmp, passphrase, sourceCounts, dbFile)
        swapIn(dbFile, tmp)
    }

    /**
     * Proves the exported file is whole before anything is swapped.
     *
     * `cipher_integrity_check` verifies every page HMAC — it is the only check that actually
     * covers "everything was re-encrypted". A plain `SELECT count(*) FROM sqlite_master` would
     * pass on a file whose data pages are damaged.
     */
    private fun validateExport(
        tmp: File,
        passphrase: ByteArray,
        sourceCounts: Map<String, Long>,
        dbFile: File,
    ) {
        try {
            SQLiteDatabase.openDatabase(tmp.absolutePath, passphrase, null, 0, null).use { db ->
                db.rawQuery("PRAGMA cipher_integrity_check", null).use { c ->
                    check(!c.moveToFirst()) { "cipher_integrity_check reported damaged pages" }
                }
                val exported = rowCounts(db)
                sourceCounts.forEach { (table, expected) ->
                    val actual = exported[table]
                    check(actual == expected) {
                        "row count mismatch on `$table`: expected $expected, exported $actual"
                    }
                }
            }
        } catch (t: Throwable) {
            discardTemp(dbFile)
            throw Failure("exported copy of ${dbFile.name} failed validation; original kept", t)
        }
    }

    /**
     * Moves the validated export into place. Ordered so that a kill at any step leaves either the
     * original or the replacement in position — never nothing.
     */
    private fun swapIn(dbFile: File, tmp: File) {
        val old = File(dbFile.absolutePath + OLD_SUFFIX)
        if (!dbFile.renameTo(old)) {
            discardTemp(dbFile)
            throw Failure("could not move ${dbFile.name} aside; original kept")
        }
        if (!tmp.renameTo(dbFile)) {
            old.renameTo(dbFile) // put the original back
            discardTemp(dbFile)
            throw Failure("could not move the rebuilt database into place; original restored")
        }
        // Sidecars belong to the old file and are encrypted with the zero key — they must not
        // survive next to the new one.
        SIDECAR_SUFFIXES.drop(1).forEach { File(dbFile.absolutePath + it).delete() }
        discardOld(dbFile)
    }

    /** Restores the original when a swap was interrupted before the replacement landed. */
    private fun recoverInterruptedSwap(dbFile: File) {
        val old = File(dbFile.absolutePath + OLD_SUFFIX)
        if (old.exists() && !dbFile.exists()) {
            Timber.w("LegacyZeroKeyRekey: interrupted swap detected — restoring the original")
            old.renameTo(dbFile)
        }
    }

    /** Row counts of the user tables, used to prove the export lost nothing. */
    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE '%_fts%' AND name NOT LIKE 'android_metadata'",
            null,
        ).use { c -> while (c.moveToNext()) tables += c.getString(0) }
        return tables.associateWith { table ->
            db.rawQuery("SELECT count(*) FROM `$table`", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
    }

    /**
     * True when [key] decrypts the database.
     *
     * Opened **read-write** (flag 0, no `CREATE_IF_NECESSARY`): a read-only open cannot replay a
     * dirty `-wal`, which would make a perfectly healthy WAL database look undecryptable and send
     * the caller down the failure path.
     *
     * Only a genuine "wrong key" answer counts as `false`. Any other error — I/O, OOM, native
     * loader — is rethrown: swallowing it would let a transient fault masquerade as corruption,
     * and the caller reacts to that by throwing on the startup path.
     */
    @Suppress("SwallowedException") // A wrong key is an expected answer here, not a failure.
    private fun canOpen(dbFile: File, key: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (e: SQLiteNotADatabaseException) {
        // SQLCipher's dedicated "wrong key / not a database" signal. Everything else propagates.
        false
    }

    private fun discardTemp(dbFile: File) {
        SIDECAR_SUFFIXES.forEach { File(dbFile.absolutePath + TMP_SUFFIX + it).delete() }
        File(dbFile.absolutePath + TMP_SUFFIX).delete()
    }

    private fun discardOld(dbFile: File) {
        SIDECAR_SUFFIXES.forEach { File(dbFile.absolutePath + OLD_SUFFIX + it).delete() }
        File(dbFile.absolutePath + OLD_SUFFIX).delete()
    }
}
