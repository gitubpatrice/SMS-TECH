package com.filestech.sms.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
internal object Migrations {

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

    /** All migrations registered in [DatabaseFactory]. Append new ones here in version order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
