package com.filestech.sms.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Business enums for a message, moved out of `data/local/db/entity` (v1.24.0, Étage 2.1).
 *
 * These describe domain concepts — what a message *is* and where it stands — not a storage detail,
 * so they belong in the domain layer. Room stores them as `INTEGER` via
 * [com.filestech.sms.data.local.db.MessageEnumConverters]; the backup format serialises them via
 * the custom serialisers below, which write [rawValue] as an `Int`. Both representations are
 * package-independent, so this move changes nothing on disk or on the wire.
 *
 * `@Serializable(with = …Serializer)` uses a `PrimitiveKind.INT` descriptor, so the JSON stays
 * `"field": 0` exactly as in every prior version.
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
 * Sentinel values for a message's error code when its status is [MessageStatus.FAILED].
 *
 * The distinction matters for retry idempotence (audit M-1): a [WATCHDOG_TIMEOUT] row may actually
 * have been accepted by the radio (we just never received the sent-broadcast), so retrying it can
 * produce a duplicate at the recipient. A [SYNCHRONOUS] row was rejected by `SmsManager` before
 * dispatch and is always safe to re-send.
 *
 * Positive integer values are reserved for OS-provided error codes (`SmsManager.RESULT_ERROR_*`)
 * propagated through `SmsSentReceiver`.
 */
object SendErrorCode {
    /** Synchronous failure: `SmsManager.sendMultipartTextMessage` threw. Safe to retry. */
    const val SYNCHRONOUS: Int = -1

    /** [com.filestech.sms.system.scheduler.TelephonySyncWorker] timed out a stale PENDING row.
     *  The message *may* have reached the recipient — UI should warn on retry. */
    const val WATCHDOG_TIMEOUT: Int = -2
}
