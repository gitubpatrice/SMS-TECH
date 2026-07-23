package com.filestech.sms.data.local.db

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the SEC-CRIT repair of [LegacyZeroKeyRekey].
 *
 * Until v1.23.4 the SQLCipher passphrase was zeroed in place before Room ever opened the database,
 * so **every** install ended up encrypted with 32 zero bytes instead of the Keystore-sealed key.
 * These tests reproduce that exact on-disk state and assert the repair rebuilds it without losing
 * a single row — the whole point, since a naive fix would make every existing database unreadable.
 *
 * ⚠️ Every test targets a **throwaway** file, never `AppDatabase.DATABASE_NAME`. Pointing them at
 * the real `smstech.db` would delete the device's messages — which is exactly why
 * `LegacyZeroKeyRekey.rekeyIfNeeded` takes the database file as a parameter.
 */
@RunWith(AndroidJUnit4::class)
class LegacyZeroKeyRekeyTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The passphrase a correct install would use — arbitrary but non-zero. */
    private val realPassphrase = ByteArray(32) { (it + 1).toByte() }

    /** What SQLCipher actually received before the fix. */
    private val zeroKey = ByteArray(32)

    /** Deliberately NOT the production database. */
    private val dbFile: File
        get() = context.getDatabasePath("rekey-probe-test.db")

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        // The repair short-circuits on a persisted per-file marker; clear ours so each test
        // exercises the real probe rather than the fast path left by the previous one. The marker
        // is keyed by database file name, so this never touches the real `smstech.db` entry.
        context.getSharedPreferences("db_repair", Context.MODE_PRIVATE)
            .edit().remove("zero_key_repair_v1240_done_" + dbFile.name).commit()
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        val base = dbFile.absolutePath
        listOf("", ".rekeytmp", ".rekeyold").forEach { variant ->
            listOf("", "-wal", "-shm", "-journal").forEach { sidecar ->
                File(base + variant + sidecar).delete()
            }
        }
    }

    /** Creates a database encrypted with [key] holding [rows] identifiable messages. */
    private fun seedDatabase(key: ByteArray, marker: String, rows: Int = 1, wal: Boolean = false) {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, key, null, null).use { db ->
            if (wal) db.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            db.execSQL("PRAGMA user_version = 7")
            repeat(rows) { i ->
                db.execSQL("INSERT INTO probe (body) VALUES (?)", arrayOf<Any>("$marker-$i"))
            }
        }
    }

    /** Reads the first probe row, or null when [key] cannot decrypt the database. */
    private fun readMarker(key: ByteArray): String? = runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT body FROM probe ORDER BY id LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }
    }.getOrNull()

    private fun countRows(key: ByteArray): Long =
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM probe", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }

    private fun userVersion(key: ByteArray): Long =
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }

    private fun repair() = LegacyZeroKeyRekey.rekeyIfNeeded(context, realPassphrase, dbFile)

    @Test
    fun freshInstall_isLeftAlone() {
        assertThat(dbFile.exists()).isFalse()

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.FRESH_INSTALL)

        assertThat(dbFile.exists()).isFalse()
    }

    @Test
    fun legacyZeroKeyDatabase_isRebuilt_andEveryRowSurvives() {
        seedDatabase(zeroKey, marker = "message-utilisateur", rows = 500)
        // Sanity: this is genuinely the broken state.
        assertThat(readMarker(realPassphrase)).isNull()
        assertThat(readMarker(zeroKey)).isEqualTo("message-utilisateur-0")

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)

        assertThat(readMarker(realPassphrase)).isEqualTo("message-utilisateur-0")
        assertThat(countRows(realPassphrase)).isEqualTo(500)
        // The security property being restored: the public key no longer opens it.
        assertThat(readMarker(zeroKey)).isNull()
    }

    /**
     * `sqlcipher_export` copies schema and rows but not `user_version`. Losing it would make Room
     * believe the database is at v0 and run `createAllTables` over populated data.
     */
    @Test
    fun userVersion_isCarriedOverToTheRebuiltDatabase() {
        seedDatabase(zeroKey, marker = "version")

        repair()

        assertThat(userVersion(realPassphrase)).isEqualTo(7)
    }

    @Test
    fun walDatabase_withDirtySidecars_isRebuiltIntact() {
        seedDatabase(zeroKey, marker = "wal", rows = 200, wal = true)

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)

        assertThat(countRows(realPassphrase)).isEqualTo(200)
        // Sidecars belonged to the zero-key file — they must not survive next to the new one.
        assertThat(File(dbFile.absolutePath + "-wal").exists()).isFalse()
        assertThat(File(dbFile.absolutePath + "-shm").exists()).isFalse()
    }

    @Test
    fun alreadyCorrectDatabase_isNotTouched() {
        seedDatabase(realPassphrase, marker = "deja-correct")

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.ALREADY_CORRECT)

        assertThat(readMarker(realPassphrase)).isEqualTo("deja-correct-0")
    }

    @Test
    fun repairIsIdempotent_acrossRepeatedColdStarts() {
        seedDatabase(zeroKey, marker = "idempotence")

        val first = repair()
        val second = repair()
        val third = repair()

        assertThat(first).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)
        assertThat(second).isEqualTo(LegacyZeroKeyRekey.Result.ALREADY_CORRECT)
        assertThat(third).isEqualTo(LegacyZeroKeyRekey.Result.ALREADY_CORRECT)
        assertThat(readMarker(realPassphrase)).isEqualTo("idempotence-0")
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        seedDatabase(zeroKey, marker = "pas-de-residu")

        repair()

        assertThat(File(dbFile.absolutePath + ".rekeytmp").exists()).isFalse()
        assertThat(File(dbFile.absolutePath + ".rekeyold").exists()).isFalse()
    }

    @Test
    fun databaseReadableByNeitherKey_failsLoudly_withoutDeletingAnything() {
        seedDatabase(ByteArray(32) { 0x7F }, marker = "cle-inconnue")

        val thrown = runCatching { repair() }.exceptionOrNull()

        // Doctrine: a silent wipe is silent data loss. We surface, we never delete.
        assertThat(thrown).isInstanceOf(LegacyZeroKeyRekey.Failure::class.java)
        assertThat(dbFile.exists()).isTrue()
        assertThat(readMarker(ByteArray(32) { 0x7F })).isEqualTo("cle-inconnue-0")
    }

    /**
     * The original is only moved aside once a validated replacement exists, so an interrupted swap
     * can always be rolled back to a readable database.
     */
    @Test
    fun interruptedSwap_restoresTheOriginal() {
        seedDatabase(zeroKey, marker = "reprise-apres-crash")
        // Simulate a kill after the original was moved aside but before the export landed.
        dbFile.renameTo(File(dbFile.absolutePath + ".rekeyold"))

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)

        assertThat(readMarker(realPassphrase)).isEqualTo("reprise-apres-crash-0")
    }

    @Test
    fun truncatedStub_isTreatedAsFreshInstall_notAsCorruption() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(16))

        assertThat(repair()).isEqualTo(LegacyZeroKeyRekey.Result.FRESH_INSTALL)
    }
}
