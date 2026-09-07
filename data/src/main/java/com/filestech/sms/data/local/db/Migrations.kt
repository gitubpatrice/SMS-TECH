package com.filestech.sms.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filestech.sms.data.sms.canonicalTelephonyUri

/**
 * Room migrations for the SQLCipher-backed [AppDatabase].
 *
 * Each migration is **additive** and **idempotent** — a row already imported into the previous
 * schema must remain readable byte-for-byte after the migration runs. We never drop or rename
 * existing columns; new columns are nullable or default-valued so legacy rows project cleanly.
 *
 * SQLCipher caveat: `ALTER TABLE` runs through the cipher layer exactly like a normal SQL
 * statement — no special handling required. The migration is wrapped in a transaction by Room.
 */
object Migrations {

    /**
     * v1 → v2 (2026-05-15) — contextual reply feature (#8).
     *
     *  - Adds `messages.reply_to_message_id INTEGER` (nullable). Default NULL = "this message
     *    is not a reply", which matches both legacy rows and freshly imported ones from the
     *    system provider (Telephony.Sms has no concept of reply targeting).
     *  - Adds the matching index so a UI lookup "did anyone reply to this message?" stays cheap.
     *
     * No data migration: legacy rows simply carry NULL.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_message_id INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_reply_to_message_id ON messages(reply_to_message_id)")
        }
    }

    /**
     * v2 → v3 (2026-05-16, v1.2.6 audit F2 idempotence retry).
     *
     *  - Adds `messages.mms_system_id INTEGER` (nullable). NULL for legacy rows; the new
     *    [com.filestech.sms.data.mms.MmsSender] populates it after a successful writeback.
     *  - Adds the matching index so the retry-path lookup "previous mms_system_id for this
     *    Room message" stays O(log n).
     *
     * Strictly additive — legacy rows are not touched.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mms_system_id INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_mms_system_id ON messages(mms_system_id)")
        }
    }

    /**
     * v3 → v4 (2026-05-16, v1.3.0).
     *
     *  - Ajoute `messages.reaction_emoji TEXT` (nullable). NULL pour les legacy rows.
     *  - Pas d'index — la colonne n'est jamais filtrée. L'index `index_messages_date`
     *    nécessaire à l'auto-purge a été extrait dans une migration v4→v5 dédiée pour
     *    absorber proprement les users qui ont reçu un build v1.3.0 intermédiaire avec
     *    schema v4 sans cet index.
     *
     * Strictement additive — `ALTER TABLE ADD COLUMN`, aucune row touchée.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction_emoji TEXT")
        }
    }

    /**
     * v4 → v5 (2026-05-16, v1.3.0 audit P1).
     *
     *  - Crée `index_messages_date` pour que l'auto-purge `WHERE date < cutoff` (tick
     *    `TelephonySyncWorker`) ne fasse plus de full scan SQLCipher (~1 s sur 50 k rows
     *    chiffrés). L'index composite existant `(conversation_id, date)` est inopérant ici
     *    puisque la purge est inter-conversations.
     *  - `IF NOT EXISTS` rend l'opération idempotente : un user qui aurait reçu une variante
     *    intermédiaire ayant déjà créé cet index voit la migration ne rien faire, sans crash.
     *
     * Strictement additive — pas de row touchée.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_date ON messages(date)")
        }
    }

    /**
     * v5 → v6 (2026-05-17, v1.3.7 G4 audit).
     *
     * **Cleanup uniquement** — drop la table `conversation_overrides` (entity + DAO existaient
     * mais aucun consommateur métier ; vérifié par grep transversal v1.3.5 → v1.3.7). La table
     * était vide pour 100 % des utilisateurs (jamais d'INSERT du côté code), donc le DROP ne
     * supprime aucune donnée utilisateur. Si la table a été créée par Room sur une version
     * antérieure (ce qui est le cas pour tout install ≥ v1.0), elle est nettoyée ; sinon
     * `IF EXISTS` rend l'opération idempotente sur les rares installations où elle aurait été
     * absente (Room compatible mode, builds custom).
     *
     * Pas de rollback nécessaire — la table était déjà invisible côté code Kotlin (entity et
     * DAO sont supprimés dans le même commit v1.3.7).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS conversation_overrides")
        }
    }

    /**
     * v6 → v7 (2026-05-22, v1.11.0) — apparence par contact (Sujet 5).
     *
     *  - Ajoute `conversations.bubble_color_argb INTEGER` (nullable). NULL pour
     *    les legacy rows = utilise le bleu marque par défaut pour la bulle
     *    sortante. L'user choisit une couleur dans la palette WCAG-safe via
     *    le dialog "Personnaliser apparence".
     *  - Ajoute `conversations.avatar_uri TEXT` (nullable). NULL = fallback à
     *    l'avatar contact Android natif. URI `content://` persistée via
     *    `takePersistableUriPermission` au pick.
     *
     * Strictement additive — `ALTER TABLE ADD COLUMN` × 2, aucune row touchée.
     * Downgrade v7 → v6 safe : Room ignore les colonnes inconnues, données
     * préservées dans la DB tant qu'on ne ré-écrit pas la row.
     *
     * **NOTE atomicité** (v1.11.0 audit SEC-V7) : `ALTER TABLE ADD COLUMN`
     * n'est PAS idempotente en SQLite (pas de `IF NOT EXISTS` pour cette
     * directive). Room wrappe le `migrate()` entier dans une transaction
     * SQLite via `db.beginTransaction()` / `setTransactionSuccessful()`. Si
     * le process est killé entre les deux `execSQL`, SQLite rollback la
     * transaction (WAL) — la version reste 6 et la migration sera ré-exécutée
     * intégralement au prochain démarrage. Re-exécution partielle impossible.
     * NE PAS ajouter de guard `IF NOT EXISTS` ici (syntax-error garantie).
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN bubble_color_argb INTEGER")
            db.execSQL("ALTER TABLE conversations ADD COLUMN avatar_uri TEXT")
        }
    }

    /**
     * v7 → v8 (2026-09-07, v1.27.11) — **normalise `messages.telephony_uri`**, et supprime les
     * doublons que sa divergence de forme a produits. Aucun changement de schema : c'est une
     * migration de DONNEES.
     *
     * # Le defaut, reproduit avant d'etre corrige
     *
     * Selon la version d'Android, `ContentResolver.insert` rend `content://sms/sent/<id>`
     * (mesure : Galaxy S9 / Android 10) ou `content://sms/<id>` (Galaxy S24 / Android 16).
     * `TelephonyReader` enregistrait la forme rendue, telle quelle, pour tout message que
     * SMS Tech ecrit lui-meme ; l'import depuis le systeme, lui, a toujours construit la forme
     * canonique. L'index `UNIQUE(telephony_uri)` compare des CHAINES : les deux ecritures
     * designant la meme ligne systeme ne se rencontraient jamais. Une resynchronisation
     * complete — le bouton « Resynchroniser », qui remet le curseur a zero — reimportait donc
     * chaque message ecrit par l'application **en double**.
     *
     * # Pourquoi supprimer, et laquelle des deux lignes
     *
     * Normaliser sans supprimer violerait l'index des la premiere collision, et la migration
     * echouerait — la base resterait en v7 a chaque demarrage. On supprime donc d'abord.
     *
     * **La ligne conservee est celle que l'APPLICATION a ecrite**, celle qui porte le dossier,
     * et jamais celle venue de l'import. Ce n'est pas arbitraire : elle seule porte la reaction
     * emoji, la citation (`reply_to_message_id`), le favori, l'etat d'envoi et l'identifiant
     * MMS. Elle appartient surtout a la conversation d'ORIGINE — qui peut etre dans le coffre,
     * la ou son jumeau importe se serait pose en clair. Conserver l'import aurait donc pu sortir
     * un message du coffre.
     *
     * # Pourquoi une boucle Kotlin plutot qu'un `UPDATE` unique
     *
     * La forme canonique se calcule par une regle — cf. [canonicalTelephonyUri] — qui s'ecrit
     * mal en SQLite et se relit encore plus mal. La boucle porte sur les seules lignes au
     * format `content://…/<dossier>/<id>`, qui sont peu nombreuses au regard de la table, et
     * Room execute deja tout `migrate()` dans une transaction : soit l'ensemble passe, soit
     * rien ne bouge et la migration sera rejouee au prochain demarrage.
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val aNormaliser = mutableListOf<Pair<Long, String>>()
            db.query(
                "SELECT id, telephony_uri FROM messages " +
                    "WHERE telephony_uri IS NOT NULL AND telephony_uri LIKE 'content://%/%/%'",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = cursor.getString(1) ?: continue
                    val canonique = canonicalTelephonyUri(uri)
                    if (canonique != uri) aNormaliser += id to canonique
                }
            }
            for ((id, canonique) in aNormaliser) {
                // Le jumeau importe s'efface AVANT la normalisation, sans quoi l'index refuse.
                db.execSQL(
                    "DELETE FROM messages WHERE telephony_uri = ? AND id <> ?",
                    arrayOf<Any>(canonique, id),
                )
                db.execSQL(
                    "UPDATE messages SET telephony_uri = ? WHERE id = ?",
                    arrayOf<Any>(canonique, id),
                )
            }
        }
    }

    /** All migrations registered in [DatabaseFactory]. Append new ones here in version order. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
    )
}
