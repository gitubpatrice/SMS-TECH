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
        // v1.28.1 — `MigrationTestHelper` pose son verrou `.lck` A COTE du fichier de base, sans
        // creer le dossier `databases/`. Sur un appareil ou l'application vient d'etre installee
        // et jamais lancee — l'etat exact que produit `connectedAndroidTest`, qui desinstalle
        // apres chaque campagne — le dossier n'existe pas, et les DIX tests de ce fichier
        // echouent sur `ENOENT` avant d'avoir rien mesure. La cause n'a rien a voir avec les
        // migrations : elle les rendait toutes illisibles.
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getDatabasePath(TEST_DB)
            .parentFile
            ?.mkdirs()
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

    /**
     * v1.27.11 — la migration `7 → 8` normalise `messages.telephony_uri` et efface les doublons
     * que sa divergence de forme a produits.
     *
     * La base de depart reproduit ce qu'un appareil affecte contient reellement :
     *  - **id 1** : le message tel que l'APPLICATION l'a ecrit, sous `content://sms/sent/9164`,
     *    avec ce que lui seul porte — ici une reaction et un favori ;
     *  - **id 2** : le meme message tel que l'IMPORT l'a recree apres une resynchronisation,
     *    sous `content://sms/9164`, nu ;
     *  - **id 3** : un message sans rapport, deja canonique, qui ne doit pas bouger.
     *
     * Ce que le test exige va au-dela de « il ne reste qu'une ligne » : il exige que ce soit
     * **la bonne**. Conserver l'import aurait perdu la reaction et le favori — et, sur un vrai
     * appareil, aurait pu sortir le message du coffre, l'import l'ayant pose dans la
     * conversation en clair.
     */
    @Test
    fun migrate7To8_normaliseLUriEtSupprimeLeDoublonEnGardantLaLigneDeLApplication() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
            Migrations.MIGRATION_5_6,
            Migrations.MIGRATION_6_7,
        ).use { db ->
            db.execSQL("DELETE FROM messages")
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversation_id, telephony_uri, address, body, type, direction,
                     date, date_sent, read, starred, status, error_code, sub_id,
                     scheduled_at, attachments_count, reaction_emoji)
                VALUES
                    (1, 1, 'content://sms/sent/9164', '+33612345678', 'coucou',
                     0, 1, 1700000000000, 1700000000000, 1, 1, 2, NULL, NULL, NULL, 0, '❤️'),
                    (2, 1, 'content://sms/9164', '+33612345678', 'coucou',
                     0, 1, 1700000000000, 1700000000000, 1, 0, 2, NULL, NULL, NULL, 0, NULL),
                    (3, 1, 'content://sms/7000', '+33612345678', 'sans rapport',
                     0, 0, 1700000001000, 1700000001000, 1, 0, 2, NULL, NULL, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8)

        // Le doublon a disparu, et c'est bien la ligne de l'import qui est partie.
        db.query("SELECT id, telephony_uri, starred, reaction_emoji FROM messages ORDER BY id")
            .use { c ->
                assertThat(c.count).isEqualTo(2)

                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(1L)
                assertThat(c.getString(1)).isEqualTo("content://sms/9164")
                assertThat(c.getInt(2)).isEqualTo(1)
                assertThat(c.getString(3)).isEqualTo("❤️")

                // Le message etranger n'a pas ete touche.
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(3L)
                assertThat(c.getString(1)).isEqualTo("content://sms/7000")
            }
    }

    /**
     * v1.28.1 (revue externe !38458, 3e passe) — **une collision d'URI n'est pas une preuve de
     * doublon**, et la v1.27.11 la prenait pour telle.
     *
     * Le cas est celui que decrit le testeur, et il n'a rien de theorique : la restauration
     * d'avant la v1.27.10 recopiait les `telephony_uri` du telephone SOURCE. `content://sms/9164`
     * peut donc designer, dans une meme base, un message d'ici et un message venu d'ailleurs. La
     * migration supprimait le second sans comparer un seul champ.
     *
     * Ce que ce test exige tient en une phrase : **rien n'est touche**. Les deux messages sont
     * la, chacun avec SA liaison.
     *
     * ⚠ Une premiere version de ce test exigeait que la ligne en collision passe a `telephony_uri
     * = NULL`. C'etait un defaut, trouve en relecture externe le 2026-09-08 : dans cette base,
     * `NULL` signifie « ce message n'a jamais eu de copie systeme », et `SystemCopyEraser.erase`
     * en conclut « rien ne survit ». Effacer une liaison legitime aurait donc fait declarer partie
     * une copie systeme bien vivante. Le test exigeait le mauvais comportement — et il aurait ete
     * VERT dessus.
     */
    @Test
    fun migrate7To8_deuxMessagesDIFFERENTSSousLaMemeUri_sontTousLesDeuxConserves() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
            Migrations.MIGRATION_5_6,
            Migrations.MIGRATION_6_7,
        ).use { db ->
            db.execSQL("DELETE FROM messages")
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversation_id, telephony_uri, address, body, type, direction,
                     date, date_sent, read, starred, status, error_code, sub_id,
                     scheduled_at, attachments_count, reaction_emoji)
                VALUES
                    (1, 1, 'content://sms/sent/9164', '+33612345678', 'ecrit ici',
                     0, 1, 1700000000000, 1700000000000, 1, 1, 2, NULL, NULL, NULL, 0, NULL),
                    (2, 1, 'content://sms/9164', '+33698765432', 'restaure d''un autre telephone',
                     0, 0, 1500000000000, 1500000000000, 1, 0, 2, NULL, NULL, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8)

        db.query("SELECT id, telephony_uri, body FROM messages ORDER BY id").use { c ->
            // Le point du test : RIEN n'a ete supprime, et RIEN n'a change de main.
            assertThat(c.count).isEqualTo(2)

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1L)
            // Pas normalisee : la place canonique est occupee par une ligne non prouvee
            // identique, et on ne la lui prend pas.
            assertThat(c.getString(1)).isEqualTo("content://sms/sent/9164")

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(2L)
            assertThat(c.getString(1)).isEqualTo("content://sms/9164")
            assertThat(c.getString(2)).isEqualTo("restaure d'un autre telephone")
        }
    }

    /**
     * v1.28.1 (relecture externe GPT, point 7) — deux copies identiques peuvent vivre dans DEUX
     * conversations, l'une dans le coffre et l'autre en clair. Les fusionner reviendrait a
     * choisir a l'aveugle laquelle des deux visibilites survit, et laisserait la conversation
     * perdante avec un apercu et un compteur faux. La conversation fait donc partie de la preuve.
     */
    @Test
    fun migrate7To8_deuxCopiesIdentiquesDansDeuxConversations_neSontPasFusionnees() {
        helper.createDatabase(TEST_DB, 1).use { db -> seedV1Row(db) }
        helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5,
            Migrations.MIGRATION_5_6,
            Migrations.MIGRATION_6_7,
        ).use { db ->
            db.execSQL("DELETE FROM messages")
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversation_id, telephony_uri, address, body, type, direction,
                     date, date_sent, read, starred, status, error_code, sub_id,
                     scheduled_at, attachments_count, reaction_emoji)
                VALUES
                    (1, 1, 'content://sms/sent/9164', '+33612345678', 'coucou',
                     0, 1, 1700000000000, 1700000000000, 1, 0, 2, NULL, NULL, NULL, 0, NULL),
                    (2, 2, 'content://sms/9164', '+33612345678', 'coucou',
                     0, 1, 1700000000000, 1700000000000, 1, 0, 2, NULL, NULL, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8)

        db.query("SELECT id, conversation_id, telephony_uri FROM messages ORDER BY id").use { c ->
            assertThat(c.count).isEqualTo(2)
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(2)).isEqualTo("content://sms/sent/9164")
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(2)).isEqualTo("content://sms/9164")
        }
    }

    // ──────────────────────────── v8 → v9 (F01) ────────────────────────────

    /**
     * v1.28.3 (F01) — la migration convertit en `NULL` **les deux** sentinelles « pas de fil
     * système » qui ont existé en base, et ne touche pas à un `thread_id` AOSP réel.
     *
     * Le `0L` venait de la composition et de la réception ; les valeurs négatives venaient de
     * `BackupService`, qui les fabriquait depuis la v1.15.2 pour contourner ce même piège sur
     * le chemin de la restauration. Les deux disent « inconnu » et doivent finir `NULL`.
     */
    @Test
    fun migrate8To9_convertitLesDeuxSentinellesEnNull_etPreserveUnVraiFilSysteme() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES
                    (1, 0,           '+33600000001', 'Locale',      1700000000000, 'a', 0, 0, 0, 0, 0),
                    (2, -1757000000, '+33600000002', 'Restauree',   1700000000000, 'b', 0, 0, 0, 0, 0),
                    (3, 42,          '+33600000003', 'Importee',    1700000000000, 'c', 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        db.query("SELECT id, thread_id, display_name FROM conversations ORDER BY id").use { c ->
            assertThat(c.count).isEqualTo(3)
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.getString(2)).isEqualTo("Locale")
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.getString(2)).isEqualTo("Restauree")
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getLong(1)).isEqualTo(42L)
            assertThat(c.getString(2)).isEqualTo("Importee")
        }
    }

    /**
     * **Contrôle de la cascade.** [Migrations.MIGRATION_8_9] est la seule migration du projet à
     * exécuter un `DROP TABLE` sur une table référencée. `messages.conversation_id` pointe
     * `conversations(id)` en `ForeignKey.CASCADE` : si les clés étrangères étaient actives à ce
     * moment-là, le `DROP` exécuterait un `DELETE FROM` implicite et **effacerait tous les
     * messages** de l'utilisateur.
     *
     * Room n'active `PRAGMA foreign_keys` que dans `onOpen`, donc après les migrations. Ce test
     * le VÉRIFIE au lieu de s'y fier, et il vérifie du même coup que les `conversation_id`
     * continuent de désigner la même conversation après la recréation de table — les `id` sont
     * recopiés, jamais régénérés.
     */
    @Test
    fun migrate8To9_laRecreationDeTable_neCascadePasSurLesMessages() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES (7, 0, '+33612345678', 'Alice', 1700000000000, 'salut', 1, 0, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversation_id, telephony_uri, address, body, type, direction,
                     date, date_sent, read, starred, status, error_code, sub_id,
                     scheduled_at, attachments_count, reaction_emoji)
                VALUES (1, 7, 'content://sms/1', '+33612345678', 'a ne pas perdre',
                        0, 0, 1700000000000, NULL, 0, 0, 1, NULL, NULL, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        db.query("SELECT conversation_id, body FROM messages").use { c ->
            assertThat(c.count).isEqualTo(1)
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(7L)
            assertThat(c.getString(1)).isEqualTo("a ne pas perdre")
        }
    }

    /**
     * Après la migration, l'index UNIQUE doit avoir été recréé — `DROP TABLE` l'emporte — et
     * accepter plusieurs `NULL`, ce qui est toute la raison d'être du changement : SQLite tient
     * deux `NULL` pour distincts sous un index UNIQUE.
     */
    @Test
    fun migrate8To9_lIndexEstRecree_etAccepteAutantDeNullQueVoulu() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES (1, 0, '+33600000001', 'Locale', 1700000000000, 'a', 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        // Deux conversations locales de plus : c'est le scénario que F01 rendait impossible.
        db.execSQL(
            """
            INSERT INTO conversations
                (id, thread_id, addresses_csv, display_name, last_message_at,
                 last_message_preview, unread_count, pinned, archived, muted, in_vault)
            VALUES
                (2, NULL, '+33600000002', 'B', 1700000000000, 'b', 0, 0, 0, 0, 0),
                (3, NULL, '+33600000003', 'C', 1700000000000, 'c', 0, 0, 0, 0, 0)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM conversations WHERE thread_id IS NULL").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(3)
        }

        // L'index tient toujours sur les valeurs réelles.
        db.execSQL(
            """
            INSERT INTO conversations
                (id, thread_id, addresses_csv, display_name, last_message_at,
                 last_message_preview, unread_count, pinned, archived, muted, in_vault)
            VALUES (4, 99, '+33600000004', 'D', 1700000000000, 'd', 0, 0, 0, 0, 0)
            """.trimIndent(),
        )
        val doublon = runCatching {
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES (5, 99, '+33600000005', 'E', 1700000000000, 'e', 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }
        assertThat(doublon.isFailure).isTrue()
    }

    /**
     * v1.28.3 (F20) — 9 → 10, retour à une migration additive : `scheduled_messages.claimed_at`.
     *
     * Ce qui compte n'est pas que la colonne apparaisse — `runMigrationsAndValidate` le vérifie
     * seul — mais **la valeur qu'y prennent les lignes déjà là**. Une ligne déjà `SENDING` (4)
     * au moment de la mise à jour est un envoi revendiqué par une exécution qui n'existe plus,
     * puisque la base vient d'être rouverte. Son `claimed_at` doit valoir `NULL`, que
     * `markInterruptedIfStale` lit comme un bail expiré : c'est ce qui débloque les envois
     * restés coincés en vol par les versions précédentes, au lieu de les y laisser à vie.
     */
    @Test
    fun migrate9To10_ajouteLeBail_etLesLignesDejaEnVolLeTrouventExpire() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO scheduled_messages
                    (id, conversation_id, addresses_csv, body, scheduled_at, state, created_at)
                VALUES
                    (1, NULL, '+33600000001', 'En attente', 1700000000000, 0, 1700000000000),
                    (2, NULL, '+33600000002', 'Revendique', 1700000000000, 4, 1700000000000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, Migrations.MIGRATION_9_10)

        db.query("SELECT id, state, claimed_at FROM scheduled_messages ORDER BY id").use { c ->
            assertThat(c.count).isEqualTo(2)
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getInt(1)).isEqualTo(0)
            assertThat(c.isNull(2)).isTrue()
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getInt(1)).isEqualTo(4)
            assertThat(c.isNull(2)).isTrue()
        }

        // La requête qui conclut : `NULL` compte comme expiré, donc la ligne revendiquée passe
        // en `INTERRUPTED` (5) — et celle qui est simplement en attente n'est pas touchée.
        db.execSQL(
            "UPDATE scheduled_messages SET state = 5 " +
                "WHERE state = 4 AND (claimed_at IS NULL OR claimed_at <= 0)",
        )
        db.query("SELECT id, state FROM scheduled_messages ORDER BY id").use { c ->
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getInt(1)).isEqualTo(0)
            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getInt(1)).isEqualTo(5)
        }
    }

    /**
     * v1.28.3 (F23) — 10 → 11, additive : `messages.send_attempt INTEGER NOT NULL DEFAULT 0`.
     *
     * `NOT NULL` sans valeur par défaut serait refusé par SQLite sur une table déjà peuplée ; le
     * `DEFAULT 0` est donc obligatoire ici, et il doit être **répété sur le `@ColumnInfo`** de
     * l'entité, faute de quoi `runMigrationsAndValidate` — qui compare la table réelle au schéma
     * exporté — rejetterait l'écart. C'est ce que cet appel vérifie au passage.
     *
     * Sur le fond : toute ligne existante devient « tentative 0 », ce qui est exactement son
     * état, aucune n'ayant été relancée sous une version qui comptait. Un `PendingIntent` créé
     * avant la mise à jour et encore en vol ne porte pas l'extra ; les receveurs lisent alors
     * `0`, et son accusé s'applique donc normalement.
     */
    @Test
    fun migrate10To11_ajouteLeCompteurDeTentative_aZeroSurLesLignesExistantes() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault)
                VALUES (1, 7, '+33600000001', 'Alice', 1700000000000, 'a', 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversation_id, telephony_uri, address, body, type, direction, date,
                     date_sent, read, starred, status, attachments_count)
                VALUES (1, 1, 'content://sms/1', '+33600000001', 'Bonjour', 0, 1, 1700000000000,
                        NULL, 1, 0, 0, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, Migrations.MIGRATION_10_11)

        db.query("SELECT id, body, status, send_attempt FROM messages").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(1)).isEqualTo("Bonjour")
            assertThat(c.getInt(2)).isEqualTo(0)
            assertThat(c.getInt(3)).isEqualTo(0)
        }

        // La conséquence utile, celle qui justifie le `DEFAULT 0` : l'accusé d'un `PendingIntent`
        // créé AVANT la mise à jour ne porte pas d'extra, les receveurs lisent `0` — et cet
        // accusé-là doit encore s'appliquer à la ligne migrée.
        db.execSQL("UPDATE messages SET status = 1 WHERE id = 1 AND status < 1 AND send_attempt = 0")
        db.query("SELECT status FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }

        // Contrôle inverse : l'accusé d'une tentative qui n'a jamais eu lieu ne passe pas.
        db.execSQL("UPDATE messages SET status = 2 WHERE id = 1 AND status < 2 AND send_attempt = 5")
        db.query("SELECT status FROM messages WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }
}
