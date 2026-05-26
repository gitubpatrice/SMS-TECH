package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["scheduled_at"])],
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long?,
    @ColumnInfo(name = "addresses_csv") val addressesCsv: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String? = null,
    // v1.17.0 audit BACK-M1 — `state` typé enum (était Int). Stockage SQL inchangé via
    // [com.filestech.sms.data.local.db.MessageEnumConverters] qui mappe rawValue ↔ Int.
    // Cohérent avec MessageStatus/Type/Direction convertis en v1.16.0.
    @ColumnInfo(name = "state") val state: ScheduledState = ScheduledState.PENDING,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * v1.17.0 — Conversion `object Int constants` → `enum class` avec `rawValue: Int`. Cohérence
 * avec [MessageStatus] / [MessageType] / [MessageDirection] convertis en v1.16.0.
 *
 * **Pas de @Serializable** : [ScheduledMessageEntity] n'est pas inclus dans `BackupPayload`
 * (seuls conversations + messages le sont). Donc pas besoin de KSerializer custom — la
 * conversion enum ne modifie aucun format sérialisé existant.
 *
 * Schéma SQL inchangé (colonne `state INTEGER NOT NULL`). Le TypeConverter Room dans
 * [com.filestech.sms.data.local.db.MessageEnumConverters] gère le binding.
 */
enum class ScheduledState(val rawValue: Int) {
    PENDING(0),
    SENT(1),
    FAILED(2),
    CANCELLED(3);
    companion object {
        fun fromRaw(rawValue: Int): ScheduledState = entries.firstOrNull { it.rawValue == rawValue }
            ?: PENDING.also { timber.log.Timber.w("Unknown ScheduledState int %d — defaulting to PENDING", rawValue) }
    }
}
