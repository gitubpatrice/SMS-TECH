package com.filestech.sms.data.local.db

import androidx.room.TypeConverter
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.ScheduledState

/**
 * v1.16.0 — TypeConverter Room pour les enums `MessageStatus` / `MessageType` /
 * `MessageDirection` ↔ Int stocké en base.
 *
 * **Schéma Room** : les colonnes restent `INTEGER NOT NULL` (cf. v7 schéma). Le converter
 * mappe seulement le rendu Kotlin. Aucune migration nécessaire — `identityHash` du schéma
 * Room calculé sur le DDL SQL pur, indépendamment du type Kotlin.
 *
 * **Tolérance aux Int inconnus** : si une row d'un futur build (forward compat) ou d'un
 * fichier de restore custom contient une valeur Int hors enum, [fromRaw] tombe sur le
 * fallback documenté (PENDING / SMS / INCOMING) + log Timber.w. Pas de crash sur lecture.
 *
 * **Pourquoi pas de mapper inline dans l'entité** : Room demande des méthodes top-level
 * statiques (`@TypeConverter`), donc une classe dédiée déclarée via `@TypeConverters` sur
 * l'AppDatabase. Pattern recommandé Google Codelab.
 */
class MessageEnumConverters {
    @TypeConverter
    fun statusFromRaw(rawValue: Int): MessageStatus = MessageStatus.fromRaw(rawValue)

    @TypeConverter
    fun statusToRaw(status: MessageStatus): Int = status.rawValue

    @TypeConverter
    fun typeFromRaw(rawValue: Int): MessageType = MessageType.fromRaw(rawValue)

    @TypeConverter
    fun typeToRaw(type: MessageType): Int = type.rawValue

    @TypeConverter
    fun directionFromRaw(rawValue: Int): MessageDirection = MessageDirection.fromRaw(rawValue)

    @TypeConverter
    fun directionToRaw(direction: MessageDirection): Int = direction.rawValue

    // v1.17.0 audit BACK-M1 — ScheduledState (PENDING/SENT/FAILED/CANCELLED).
    @TypeConverter
    fun scheduledStateFromRaw(rawValue: Int): ScheduledState = ScheduledState.fromRaw(rawValue)

    @TypeConverter
    fun scheduledStateToRaw(state: ScheduledState): Int = state.rawValue
}
