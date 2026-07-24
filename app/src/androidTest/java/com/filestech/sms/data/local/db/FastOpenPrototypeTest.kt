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
 * R&D prototype (2026-07-23): measures the SQLCipher open cost that makes the conversation list
 * appear ~2 s after the splash, and proves a fix.
 *
 * The app's key is a random 32-byte key, yet SQLCipher stretches it with 256 000 PBKDF2 iterations
 * on every open — pointless for a random key, and the dominant cost of the first query. This test
 * times a normal open, then re-keys the file to a raw key (`PRAGMA rekey = "x'…'"`, no PBKDF2) and
 * times opening it that way. If raw-key opens are dramatically faster with the data intact, that is
 * the production fix.
 *
 * Throwaway file only — never the real database.
 */
@RunWith(AndroidJUnit4::class)
class FastOpenPrototypeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbFile: File
        get() = context.getDatabasePath("fast-open-proto.db")

    /** 32-byte random key, as the app has. Hex form for raw-key mode. */
    private val keyBytes = ByteArray(32) { (it * 7 + 3).toByte() }
    private val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
    private val rawKeySpec = "x'$keyHex'"

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
    }

    private fun seedWithPassphrase(rows: Int) {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, keyBytes, null, null).use { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, body TEXT)")
            db.beginTransaction()
            try {
                repeat(rows) { db.execSQL("INSERT INTO t (body) VALUES (?)", arrayOf<Any>("row-$it")) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun timeOpenWithPassphrase(): Long {
        val start = System.nanoTime()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, keyBytes, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM t", null).use { it.moveToFirst() }
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun timeOpenWithRawKey(): Long {
        val start = System.nanoTime()
        // String overload → SQLCipher parses `x'…'` as a raw key, skipping PBKDF2.
        SQLiteDatabase.openDatabase(dbFile.absolutePath, rawKeySpec, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM t", null).use { it.moveToFirst() }
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun rawKeyOpensDramaticallyFaster_withDataIntact() {
        seedWithPassphrase(rows = 1000)

        val passphraseMs = (1..3).minOf { timeOpenWithPassphrase() }

        // Convert the file to raw-key encryption in place.
        SQLiteDatabase.openDatabase(dbFile.absolutePath, keyBytes, null, 0, null).use { db ->
            db.rawExecSQL("PRAGMA rekey = \"$rawKeySpec\";")
        }

        val rawMs = (1..3).minOf { timeOpenWithRawKey() }

        // Data survived the re-key.
        val count = SQLiteDatabase.openDatabase(dbFile.absolutePath, rawKeySpec, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM t", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }
        assertThat(count).isEqualTo(1000)

        // The whole point: raw-key open is a fraction of the passphrase open.
        android.util.Log.w("FastOpenProto", "passphrase open=${passphraseMs}ms  rawKey open=${rawMs}ms")
        assertThat(rawMs).isLessThan(passphraseMs)

        // Old passphrase must no longer open it (it is now raw-keyed).
        val passphraseStillWorks = runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, keyBytes, null, 0, null).use { db ->
                db.rawQuery("SELECT count(*) FROM t", null).use { it.moveToFirst() }
            }
        }.isSuccess
        assertThat(passphraseStillWorks).isFalse()
    }

    /**
     * The integration question: Room uses `SupportOpenHelperFactory(byte[])`, and that byte[] goes
     * to `sqlite3_key` as a passphrase. Can we still open a raw-keyed database through the byte[]
     * path by passing the **ASCII bytes of** `x'…'`? If yes, the app fix is a one-line factory
     * change; if no, we need the SQLiteDatabaseHook route.
     */
    @Test
    fun rawKeyedDb_opensThroughByteArrayPath_withAsciiOfHexSpec() {
        seedWithPassphrase(rows = 100)
        SQLiteDatabase.openDatabase(dbFile.absolutePath, keyBytes, null, 0, null).use { db ->
            db.rawExecSQL("PRAGMA rekey = \"$rawKeySpec\";")
        }

        val asciiOfSpec = rawKeySpec.toByteArray(Charsets.US_ASCII)
        val start = System.nanoTime()
        val count = SQLiteDatabase.openDatabase(dbFile.absolutePath, asciiOfSpec, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM t", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        android.util.Log.w("FastOpenProto", "byteArray-of-spec open=${ms}ms count=$count")
        assertThat(count).isEqualTo(100)
        // Must be fast — proving the byte[] path also skips PBKDF2 for the x'…' form.
        assertThat(ms).isLessThan(100)
    }
}
