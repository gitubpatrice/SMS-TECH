package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end proof that a database left by a pre-1.24.0 install is repaired **and then usable by
 * Room**, migrations included.
 *
 * [LegacyZeroKeyRekeyTest] covers the repair in isolation; this test closes the remaining question:
 * once rebuilt, does Room actually open the file, run [Migrations], and read the user's rows back?
 * A repair that produced a technically-valid SQLCipher file Room could not open would be just as
 * much of a data loss.
 *
 * The database is seeded at schema v7 — the version shipped by 1.23.4, i.e. what a real upgrading
 * user has on disk.
 */
@RunWith(AndroidJUnit4::class)
class ZeroKeyRepairIntegrationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val passphrase = ByteArray(32) { (it + 7).toByte() }
    private val zeroKey = ByteArray(32)

    /** Deliberately NOT `AppDatabase.DATABASE_NAME`. */
    private val dbName = "zero-key-integration-test.db"
    private val dbFile: File
        get() = context.getDatabasePath(dbName)

    /** Creates databases at an arbitrary past schema, on the real SQLCipher open helper. */
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(ByteArray(32)),
    )

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        // La mémoïsation d'échec est portée par l'objet : sans reset, un test d'échec
        // ferait lever tous les suivants du même processus.
        LegacyZeroKeyRekey.resetFailuresForTest()
        context.getSharedPreferences("db_repair", Context.MODE_PRIVATE)
            .edit().remove("zero_key_repair_v1240_done_" + dbFile.name).commit()
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        val base = dbFile.absolutePath
        listOf("", ".rekeytmp", ".rekeyold").forEach { variant ->
            listOf("", "-wal", "-shm", "-journal").forEach { s -> File(base + variant + s).delete() }
        }
    }

    /**
     * Builds a database that looks exactly like one written by 1.23.4: full Room schema at v7,
     * populated, and encrypted with the all-zero key the defect produced.
     */
    private fun seedLegacyDatabase() {
        dbFile.parentFile?.mkdirs()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(zeroKey))
            .addMigrations(*Migrations.ALL)
            .build()
        runBlocking {
            db.conversationDao().upsert(
                com.filestech.sms.data.local.db.entity.ConversationEntity(
                    id = 0,
                    threadId = 42,
                    addressesCsv = "+33612345678",
                    displayName = "Alice",
                    lastMessageAt = 1_700_000_000_000,
                    lastMessagePreview = "salut",
                    unreadCount = 1,
                ),
            )
        }
        db.close()
        // Sanity: the seed really is on the broken key.
        assertThat(opensWith(zeroKey)).isTrue()
        assertThat(opensWith(passphrase)).isFalse()
    }

    private fun opensWith(key: ByteArray): Boolean = runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, 0, null).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
    }.isSuccess

    @Test
    fun legacyDatabase_isRepaired_thenOpenedByRoom_withRowsIntact() {
        seedLegacyDatabase()

        val result = LegacyZeroKeyRekey.rekeyIfNeeded(context, passphrase, dbFile)
        assertThat(result).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)

        // The real question: can Room open what the repair produced?
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val conversations = runBlocking { room.conversationDao().listAllIncludingArchived() }
            assertThat(conversations).hasSize(1)
            assertThat(conversations.first().displayName).isEqualTo("Alice")
            assertThat(conversations.first().threadId).isEqualTo(42)
        } finally {
            room.close()
        }

        // And the zero key is definitively out.
        assertThat(opensWith(zeroKey)).isFalse()
    }

    /**
     * The case the previous version of this test claimed to cover but did not.
     *
     * Seeding through `Room.databaseBuilder` creates the database directly at
     * [AppDatabase.SCHEMA_VERSION], so reopening it runs **zero** migrations — the assertion was
     * true but vacuous. A real user upgrading from an older release has an older `user_version` on
     * disk, and the repair must leave the migration path intact: rebuild first, then let Room
     * migrate 6 → 7 over the rebuilt file.
     */
    @Test
    fun repairedDatabase_stillMigratesFromAnOlderSchema() {
        // A v6 database encrypted with the legacy zero key — exactly what a 1.11.0-era install has.
        migrationHelper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES (1, 42, '+33612345678', 'Alice', 1700000000000, 'salut', 1, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }
        assertThat(opensWith(zeroKey)).isTrue()

        val result = LegacyZeroKeyRekey.rekeyIfNeeded(context, passphrase, dbFile)
        assertThat(result).isEqualTo(LegacyZeroKeyRekey.Result.REKEYED)

        // Room now opens the rebuilt file and must apply MIGRATION_6_7 over it.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val conversations = runBlocking { room.conversationDao().listAllIncludingArchived() }
            assertThat(conversations).hasSize(1)
            assertThat(conversations.first().displayName).isEqualTo("Alice")
            // Column added by MIGRATION_6_7 — proof the migration actually ran on the rebuilt file.
            assertThat(conversations.first().bubbleColorArgb).isNull()
        } finally {
            room.close()
        }

        val version = SQLiteDatabase.openDatabase(dbFile.absolutePath, passphrase, null, 0, null)
            .use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
        assertThat(version).isEqualTo(AppDatabase.SCHEMA_VERSION)
    }

    @Test
    fun repairedDatabase_keepsItsSchemaVersion_soRoomDoesNotRecreateTables() {
        seedLegacyDatabase()

        LegacyZeroKeyRekey.rekeyIfNeeded(context, passphrase, dbFile)

        val version = SQLiteDatabase.openDatabase(dbFile.absolutePath, passphrase, null, 0, null)
            .use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
        assertThat(version).isEqualTo(AppDatabase.SCHEMA_VERSION)
    }
}
