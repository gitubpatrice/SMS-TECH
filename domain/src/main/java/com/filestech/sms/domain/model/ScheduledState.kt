package com.filestech.sms.domain.model

/**
 * Lifecycle state of a scheduled message — a domain concept, moved out of
 * `data/local/db/entity` (v1.24.0, Étage 2.1).
 *
 * **Not `@Serializable`**: a scheduled message is never part of `BackupPayload` (only conversations
 * and messages are), so no custom serialiser is needed and no serialised format changes.
 *
 * Room stores it as `INTEGER` via [com.filestech.sms.data.local.db.MessageEnumConverters], which
 * maps `rawValue ↔ Int`. Package-independent, so this move changes nothing on disk.
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
