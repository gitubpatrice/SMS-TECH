package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the v1.25.0 raw-key speed-up ([LegacyZeroKeyRekey.ensureRawKeyed]): a passphrase-encrypted
 * database is converted to raw-key encryption so opening it skips SQLCipher's 256 000 PBKDF2
 * iterations — the ~2 s that left the conversation list blank after the splash.
 *
 * Throwaway files only, never the real database.
 */
@RunWith(AndroidJUnit4::class)
class RawKeyMigrationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val passphrase = ByteArray(32) { (it + 11).toByte() }
    private val rawSpec get() = LegacyZeroKeyRekey.rawKeySpecBytes(passphrase)

    private val dbFile: File
        get() = context.getDatabasePath("raw-key-migration-test.db")

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        LegacyZeroKeyRekey.resetFailuresForTest()
        context.getSharedPreferences("db_repair", Context.MODE_PRIVATE)
            .edit()
            .remove("raw_key_v1250_done_" + dbFile.name)
            .remove("zero_key_repair_v1240_done_" + dbFile.name)
            .commit()
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        val base = dbFile.absolutePath
        listOf("", ".rekeytmp", ".rekeyold").forEach { v ->
            listOf("", "-wal", "-shm", "-journal").forEach { s -> File(base + v + s).delete() }
        }
    }

    private fun seedPassphraseDb(rows: Int) {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, passphrase, null, null).use { db ->
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, body TEXT)")
            db.execSQL("PRAGMA user_version = 7")
            repeat(rows) { db.execSQL("INSERT INTO t (body) VALUES (?)", arrayOf<Any>("m$it")) }
        }
    }

    private fun opensWith(key: ByteArray): Boolean = runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
    }.isSuccess

    private fun countWith(key: ByteArray): Int =
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM t", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }

    @Test
    fun passphraseDb_isConvertedToRawKey_withDataIntact() {
        seedPassphraseDb(rows = 300)
        assertThat(opensWith(passphrase)).isTrue()
        assertThat(opensWith(rawSpec)).isFalse()

        val result = LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)

        assertThat(result).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)
        // Now opens with the raw key…
        assertThat(opensWith(rawSpec)).isTrue()
        assertThat(countWith(rawSpec)).isEqualTo(300)
        // …and the passphrase form no longer opens it.
        assertThat(opensWith(passphrase)).isFalse()
        // user_version carried across (else Room would recreate the tables).
        val version = SQLiteDatabase.openDatabase(dbFile.absolutePath, rawSpec, null, 0, null).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }
        assertThat(version).isEqualTo(7)
    }

    @Test
    fun alreadyRawKeyed_isIdempotent() {
        seedPassphraseDb(rows = 10)

        val first = LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)
        val second = LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)

        assertThat(first).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)
        assertThat(second).isEqualTo(LegacyZeroKeyRekey.Result.ALREADY_CORRECT)
        assertThat(countWith(rawSpec)).isEqualTo(10)
    }

    @Test
    fun freshInstall_isLeftForRoomToCreateRaw() {
        assertThat(dbFile.exists()).isFalse()

        val result = LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)

        assertThat(result).isEqualTo(LegacyZeroKeyRekey.Result.FRESH_INSTALL)
    }

    @Test
    fun noTemporaryFileLeftBehind() {
        seedPassphraseDb(rows = 50)

        LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)

        assertThat(File(dbFile.absolutePath + ".rekeytmp").exists()).isFalse()
        assertThat(File(dbFile.absolutePath + ".rekeyold").exists()).isFalse()
    }

    /**
     * The full production chain: a zero-key database (what a pre-1.24.0 user has) → zero-key repair
     * → raw-key conversion → Room opens through the raw-key factory with rows intact.
     */
    @Test
    fun zeroKeyDb_throughBothMigrations_opensWithRawKeyedRoom() {
        // Seed a real Room v7 database on the all-zero key.
        val zero = ByteArray(32)
        dbFile.parentFile?.mkdirs()
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .openHelperFactory(SupportOpenHelperFactory(zero))
            .addMigrations(*Migrations.ALL)
            .build().apply {
                runBlocking {
                    conversationDao().upsert(
                        com.filestech.sms.data.local.db.entity.ConversationEntity(
                            id = 0, threadId = 7, addressesCsv = "+33600000000",
                            displayName = "Zoé", lastMessageAt = 1_700_000_000_000,
                            lastMessagePreview = "coucou", unreadCount = 0,
                        ),
                    )
                }
                close()
            }

        LegacyZeroKeyRekey.rekeyIfNeeded(context, passphrase, dbFile)
        LegacyZeroKeyRekey.ensureRawKeyed(context, passphrase, dbFile)

        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .openHelperFactory(SupportOpenHelperFactory(rawSpec))
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val conversations = runBlocking { room.conversationDao().listAllIncludingArchived() }
            assertThat(conversations).hasSize(1)
            assertThat(conversations.first().displayName).isEqualTo("Zoé")
        } finally {
            room.close()
        }
        // Neither the zero key nor the passphrase opens it anymore — only the raw key.
        assertThat(opensWith(zero)).isFalse()
        assertThat(opensWith(passphrase)).isFalse()
        assertThat(opensWith(rawSpec)).isTrue()
    }
}
