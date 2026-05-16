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

    /** All migrations registered in [DatabaseFactory]. Append new ones here in version order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
