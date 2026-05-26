package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "date"]),
        Index(value = ["telephony_uri"], unique = true),
        Index(value = ["status"]),
        Index(value = ["read"]),
        Index(value = ["starred"]),
        // Schema v2 (#8 contextual reply). Index the FK so loading a thread and resolving
        // "this message replied to which one?" stays an O(log n) lookup even on long threads.
        Index(value = ["reply_to_message_id"]),
        // Schema v3 (v1.2.6 audit F2 idempotence retry). Indexed because the retry path looks
        // up the previous system-provider row id by Room message id, and (future) the watchdog
        // may reconcile Room ↔ content://mms via mmsSystemId.
        Index(value = ["mms_system_id"]),
        // Schema v4 (v1.3.0 audit P1). Auto-purge filtre par `date < cutoff` sur l'ensemble
        // de la table ; sans index dédié, SQLCipher fait un full scan + déchiffrement page par
        // page (~1 s pour 50 k rows). L'index composite (conversation_id, date) est inopérant
        // ici puisque la purge est inter-conversations. Coût ~8 B / row, négligeable.
        Index(value = ["date"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "telephony_uri") val telephonyUri: String?,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "body") val body: String,
    /**
     * v1.16.0 — type devient `MessageType` enum (était Int). Stockage SQL inchangé via
     * [com.filestech.sms.data.local.db.MessageEnumConverters] : 0=SMS, 1=MMS en base.
     */
    @ColumnInfo(name = "type") val type: MessageType,
    /**
     * v1.16.0 — direction devient `MessageDirection` enum (était Int). 0=INCOMING, 1=OUTGOING.
     */
    @ColumnInfo(name = "direction") val direction: MessageDirection,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "date_sent") val dateSent: Long?,
    @ColumnInfo(name = "read") val read: Boolean = false,
    @ColumnInfo(name = "starred") val starred: Boolean = false,
    /**
     * v1.16.0 — status devient `MessageStatus` enum (était Int). Stockage SQL inchangé via
     * [com.filestech.sms.data.local.db.MessageEnumConverters] : 0=PENDING ... 5=SCHEDULED.
     */
    @ColumnInfo(name = "status") val status: MessageStatus = MessageStatus.PENDING,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long? = null,
    @ColumnInfo(name = "attachments_count") val attachmentsCount: Int = 0,
    /**
     * Schema v2 — contextual reply target (#8). Stores the local Room id of the message the
     * sender chose to quote when composing. NULL for regular messages and for legacy rows
     * imported before v1.2.
     *
     * Deliberately **not** a `ForeignKey`: a quoted message can be deleted (locally or via the
     * system provider) while the reply lives on; we tolerate the dangling reference and the UI
     * falls back to a "Message supprimé" placeholder rather than cascading the delete.
     */
    @ColumnInfo(name = "reply_to_message_id") val replyToMessageId: Long? = null,
    /**
     * Schema v3 — `_id` of the row we inserted into `content://mms` for this outgoing MMS
     * (v1.2.6 audit F2 idempotence retry). NULL for SMS rows, for incoming MMS, and for
     * outgoing MMS where the system writeback never succeeded (e.g. we were not default SMS
     * app at the time of dispatch).
     *
     * Used by [com.filestech.sms.data.mms.MmsSender] to delete the previous OUTBOX/FAILED
     * row before re-inserting a fresh one on retry, so we never leave two system-provider
     * rows for the same Room message even briefly.
     */
    @ColumnInfo(name = "mms_system_id") val mmsSystemId: Long? = null,
    /**
     * Schema v4 — réaction emoji locale posée par l'utilisateur sur ce message (v1.3.0).
     * `null` = pas de réaction. Une seule réaction par message (la mienne), pas envoyée
     * par SMS — c'est purement local côté Room, pas standardisé en SMS/MMS.
     */
    @ColumnInfo(name = "reaction_emoji") val reactionEmoji: String? = null,
)

/**
 * v1.16.0 — Conversion `object` Int constants → `enum class` avec `rawValue: Int`.
 *
 * Avant : `object MessageStatus { const val PENDING = 0; const val SENT = 1; ... }` —
 * primitives Int côté Kotlin, pas d'exhaustivité du compilateur sur les `when`. L'audit
 * K-8 V1.15.0 avait posé un filet de sécurité (test garde-fou) mais le risque latent
 * persistait : ajouter une const X sans toucher au mapper en faisait un cas silencieux.
 *
 * Maintenant : enum class avec `rawValue` pour le stockage Room via TypeConverter
 * ([com.filestech.sms.data.local.db.MessageEnumConverters]). Bénéfices :
 *  - `when (status: MessageStatus) { PENDING, SENT, ... }` exhaustif compile-time
 *  - Type safety : impossible de passer MessageDirection à une signature MessageStatus
 *  - Compatibilité Room : la colonne SQL reste INTEGER NOT NULL, le TypeConverter mappe
 *    `Int ↔ enum`. **Identité schéma identityHash inchangée** (le SQL DDL ne bouge pas).
 *
 * Les noms de constantes (PENDING, SENT, …) sont préservés → toutes les call sites
 * `MessageStatus.PENDING` continuent de résoudre. Le type devient enum, plus Int.
 */
/**
 * v1.16.0 — Sérialisation des enums via leur `rawValue: Int` pour préserver le format JSON
 * .smsbk historique (v1.5 à v1.15.2 écrivaient `"status": 0` en Int). Sans KSerializer custom,
 * kotlinx.serialization écrirait `"status": "PENDING"` (nom enum String) → BREAKING CHANGE
 * sur tous les backups antérieurs, qui deviendraient illisibles à la mise à jour. Audit
 * BACK-C1 v1.16.0 — preuve : restore d'un .smsbk v1.15.2 sur v1.16.0 sans ce serializer
 * lèverait `SerializationException` ("expected String, got Int") → AppError.Validation →
 * perte d'accès au backup au pire moment (changement de téléphone, réinstall).
 *
 * Les 3 serializers ci-dessous écrivent/lisent l'enum via [rawValue]. Le format JSON reste
 * `"field": 0` (Int), strictement identique à v1.15.2 et antérieures.
 */
@Serializable(with = MessageTypeSerializer::class)
enum class MessageType(val rawValue: Int) {
    SMS(0),
    MMS(1);
    companion object {
        fun fromRaw(rawValue: Int): MessageType = entries.firstOrNull { it.rawValue == rawValue }
            ?: SMS.also { timber.log.Timber.w("Unknown MessageType int %d — defaulting to SMS", rawValue) }
    }
}

object MessageTypeSerializer : KSerializer<MessageType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageType", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: MessageType) = encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder): MessageType = MessageType.fromRaw(decoder.decodeInt())
}

@Serializable(with = MessageDirectionSerializer::class)
enum class MessageDirection(val rawValue: Int) {
    INCOMING(0),
    OUTGOING(1);
    companion object {
        fun fromRaw(rawValue: Int): MessageDirection = entries.firstOrNull { it.rawValue == rawValue }
            ?: INCOMING.also { timber.log.Timber.w("Unknown MessageDirection int %d — defaulting to INCOMING", rawValue) }
    }
}

object MessageDirectionSerializer : KSerializer<MessageDirection> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageDirection", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: MessageDirection) = encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder): MessageDirection = MessageDirection.fromRaw(decoder.decodeInt())
}

@Serializable(with = MessageStatusSerializer::class)
enum class MessageStatus(val rawValue: Int) {
    PENDING(0),
    SENT(1),
    DELIVERED(2),
    FAILED(3),
    RECEIVED(4),
    SCHEDULED(5);
    companion object {
        fun fromRaw(rawValue: Int): MessageStatus = entries.firstOrNull { it.rawValue == rawValue }
            ?: PENDING.also { timber.log.Timber.w("Unknown MessageStatus int %d — defaulting to PENDING", rawValue) }
    }
}

object MessageStatusSerializer : KSerializer<MessageStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageStatus", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: MessageStatus) = encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder): MessageStatus = MessageStatus.fromRaw(decoder.decodeInt())
}

/**
 * Sentinel values for [MessageEntity.errorCode] when [MessageEntity.status] is [MessageStatus.FAILED].
 *
 * The distinction matters for retry idempotence (audit M-1): a [WATCHDOG_TIMEOUT] row may
 * actually have been accepted by the radio (we just never received the sent-broadcast), so
 * retrying it can produce a duplicate at the recipient. A [SYNCHRONOUS] row was rejected by
 * `SmsManager` before dispatch and is always safe to re-send.
 *
 * Positive integer values are reserved for OS-provided error codes
 * (`SmsManager.RESULT_ERROR_*`) propagated through `SmsSentReceiver`.
 */
object SendErrorCode {
    /** Synchronous failure: `SmsManager.sendMultipartTextMessage` threw. Safe to retry. */
    const val SYNCHRONOUS: Int = -1

    /** [com.filestech.sms.system.scheduler.TelephonySyncWorker] timed out a stale PENDING row.
     *  The message *may* have reached the recipient — UI should warn on retry. */
    const val WATCHDOG_TIMEOUT: Int = -2
}
