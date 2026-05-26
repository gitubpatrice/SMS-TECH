package com.filestech.sms.data.local.db.entity

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * v1.16.0 audit BACK-C1 — Garde-fou contre la régression de format JSON .smsbk.
 *
 * Le format historique (v1.5 → v1.15.2) sérialisait `MessageEntity.status` en Int :
 * `"status": 0`. La conversion en enum class v1.16.0 aurait par défaut sérialisé en
 * String (`"status": "PENDING"`) — breaking change cassant tous les backups antérieurs.
 *
 * Les 3 KSerializer custom (`MessageStatusSerializer`, `MessageTypeSerializer`,
 * `MessageDirectionSerializer`) forcent la sérialisation via `rawValue: Int`.
 *
 * Ce test vérifie deux contrats :
 *   1) **Format JSON Int préservé** : sérialiser un MessageStatus produit un Int JSON, pas
 *      une String. Sécurise la rétrocompat forward (backup v1.16.0 lisible par v1.15.2).
 *   2) **Roundtrip Int→enum→serialize→deserialize** : un JSON `"status": 0` est lu comme
 *      MessageStatus.PENDING et reécrit identique. Sécurise la rétrocompat backward
 *      (backup v1.15.2 lisible par v1.16.0).
 */
class MessageEnumSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    @DisplayName("MessageStatus serializes to Int (not String) — preserves v1.15.2 backup format")
    fun statusSerializesAsInt() {
        // Sérialiser chaque enum value → l'output doit être l'Int de rawValue, pas le nom.
        MessageStatus.entries.forEach { status ->
            val jsonOutput = json.encodeToString(MessageStatus.serializer(), status)
            assertThat(jsonOutput).isEqualTo(status.rawValue.toString())
        }
    }

    @Test
    @DisplayName("MessageType serializes to Int (not String) — preserves v1.15.2 backup format")
    fun typeSerializesAsInt() {
        MessageType.entries.forEach { type ->
            val jsonOutput = json.encodeToString(MessageType.serializer(), type)
            assertThat(jsonOutput).isEqualTo(type.rawValue.toString())
        }
    }

    @Test
    @DisplayName("MessageDirection serializes to Int (not String) — preserves v1.15.2 backup format")
    fun directionSerializesAsInt() {
        MessageDirection.entries.forEach { direction ->
            val jsonOutput = json.encodeToString(MessageDirection.serializer(), direction)
            assertThat(jsonOutput).isEqualTo(direction.rawValue.toString())
        }
    }

    @Test
    @DisplayName("Backward compat : Int JSON values from v1.15.2 backups deserialize correctly")
    fun deserializesIntJsonFromOlderBackups() {
        // Simule la lecture d'un backup créé par v1.15.2 : champs en Int JSON.
        assertThat(json.decodeFromString(MessageStatus.serializer(), "0")).isEqualTo(MessageStatus.PENDING)
        assertThat(json.decodeFromString(MessageStatus.serializer(), "1")).isEqualTo(MessageStatus.SENT)
        assertThat(json.decodeFromString(MessageStatus.serializer(), "5")).isEqualTo(MessageStatus.SCHEDULED)
        assertThat(json.decodeFromString(MessageType.serializer(), "0")).isEqualTo(MessageType.SMS)
        assertThat(json.decodeFromString(MessageType.serializer(), "1")).isEqualTo(MessageType.MMS)
        assertThat(json.decodeFromString(MessageDirection.serializer(), "0")).isEqualTo(MessageDirection.INCOMING)
        assertThat(json.decodeFromString(MessageDirection.serializer(), "1")).isEqualTo(MessageDirection.OUTGOING)
    }

    @Test
    @DisplayName("Unknown raw values from forged or future backup fall back gracefully")
    fun unknownRawValueFallsBackToDefault() {
        // Si une row d'un backup forgé / futur build contient un Int inconnu, le fallback
        // documenté (PENDING / SMS / INCOMING) s'applique sans crash.
        assertThat(json.decodeFromString(MessageStatus.serializer(), "999")).isEqualTo(MessageStatus.PENDING)
        assertThat(json.decodeFromString(MessageType.serializer(), "99")).isEqualTo(MessageType.SMS)
        assertThat(json.decodeFromString(MessageDirection.serializer(), "99")).isEqualTo(MessageDirection.INCOMING)
    }

    @Test
    @DisplayName("Roundtrip enum → JSON → enum preserves value for all known cases")
    fun roundtripPreservesValue() {
        MessageStatus.entries.forEach { original ->
            val encoded = json.encodeToString(MessageStatus.serializer(), original)
            val decoded = json.decodeFromString(MessageStatus.serializer(), encoded)
            assertThat(decoded).isEqualTo(original)
        }
        MessageType.entries.forEach { original ->
            val encoded = json.encodeToString(MessageType.serializer(), original)
            val decoded = json.decodeFromString(MessageType.serializer(), encoded)
            assertThat(decoded).isEqualTo(original)
        }
        MessageDirection.entries.forEach { original ->
            val encoded = json.encodeToString(MessageDirection.serializer(), original)
            val decoded = json.decodeFromString(MessageDirection.serializer(), encoded)
            assertThat(decoded).isEqualTo(original)
        }
    }
}
