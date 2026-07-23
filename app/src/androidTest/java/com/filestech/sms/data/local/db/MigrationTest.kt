package com.filestech.sms.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the invariant [Migrations] claims but never verified: every migration is **additive**, so
 * a row written under the previous schema stays readable byte-for-byte afterwards.
 *
 * The helper is wired with SQLCipher's [SupportOpenHelperFactory] on purpose. With Room's default
 * `FrameworkSQLiteOpenHelperFactory` these tests would run against plaintext SQLite and prove
 * nothing about the encrypted path the app actually uses — `ALTER TABLE` behaves identically, but
 * the whole point is to exercise the real open helper.
 *
 * The passphrase is a fixed test constant: [DatabaseKeyManager] is not involved here, and
 * critically the array is **never wiped** — that was the SEC-CRIT defect repaired in v1.24.0
 * (cf. [LegacyZeroKeyRekey]).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
        val TEST_PASSPHRASE = ByteArray(32) { (it + 1).toByte() }
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(TEST_PASSPHRASE),
    )

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
    }

    /** Inserts one conversation and one message using only columns that exist in schema v1. */
    private fun seedV1Row(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO conversations
                (id, thread_id, addresses_csv, display_name, last_message_at,
                 last_message_preview, unread_count, pinned, archived, muted, in_vault)
            VALUES (1, 42, '+33612345678', 'Alice', 1700000000000, 'salut', 1, 0, 0, 0, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO messages
                (id, conversation_id, telephony_uri, address, body, type, direction,
                 date, read, starred, status, attachments_count)
            VALUES (1, 1, 'content://sms/1', '+33612345678', 'salut', 0, 0,
                    1700000000000, 0, 0, 0, 0)
            """.trimIndent(),
        )
    }

    @Test
    fun migrateAll_v1ToCurrent_preservesUserData() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            seedV1Row(db)
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            AppDatabase.SCHEMA_VERSION,
            true,
            *Migrations.ALL,
        )

        migrated.query("SELECT body, address FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("salut")
            assertThat(c.getString(1)).isEqualTo("+33612345678")
        }
        migrated.query("SELECT display_name, unread_count FROM conversations WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Alice")
            assertThat(c.getInt(1)).isEqualTo(1)
        }
    }

    @Test
    fun migrate1To2_addsNullableReplyColumn_withoutTouchingRows() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT reply_to_message_id FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
    }

    @Test
    fun migrate2To3_addsNullableMmsSystemId() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        db.query("SELECT mms_system_id FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
    }

    @Test
    fun migrate3To5_addsReactionColumnAndDateIndex() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
        )

        db.query("SELECT reaction_emoji FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_messages_date'")
            .use { c -> assertThat(c.moveToFirst()).isTrue() }
    }

    @Test
    fun migrate5To6_dropsTheUnusedOverridesTable() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='conversation_overrides'")
            .use { c -> assertThat(c.moveToFirst()).isFalse() }
        // The user's messages are untouched by the cleanup.
        db.query("SELECT body FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("salut")
        }
    }

    @Test
    fun migrate6To7_addsPerContactAppearanceColumns() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
            Migrations.MIGRATION_5_6,
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7)

        db.query("SELECT bubble_color_argb, avatar_uri FROM conversations WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
            assertThat(c.isNull(1)).isTrue()
        }
    }
}
