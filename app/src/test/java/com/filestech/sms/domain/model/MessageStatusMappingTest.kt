package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.mapper.mapDirection
import com.filestech.sms.data.local.db.mapper.mapStatus
import com.filestech.sms.data.local.db.mapper.mapType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Audit K-8 (v1.15.0 LIGHT → v1.16.0 enum class) — Garde-fou contre l'ajout d'une nouvelle
 * valeur dans [MessageStatus] / [MessageType] / [MessageDirection] sans mise à jour du mapper
 * domain correspondant.
 *
 * v1.16.0 — Les anciens `object Int constants` ont été convertis en `enum class` avec
 * `rawValue: Int`. Les mappers [mapStatus] / [mapType] / [mapDirection] acceptent désormais
 * directement l'enum DB → enum domain. Le `when` est COMPILE-TIME EXHAUSTIF — si on ajoute
 * `MessageStatus.NEW_THING` sans mettre à jour le mapper, le compilateur Kotlin refuse de
 * compiler. Ce test reste comme filet historique + validation explicite des correspondances
 * (1-to-1 mapping entre DB enum et domain enum).
 *
 * Le test du fallback Int inconnu (filet [MessageStatus.fromRaw]) est ajouté pour couvrir
 * la lecture d'une row Room écrite par un build futur avec une valeur hors range.
 */
class MessageStatusMappingTest {

    @Test
    @DisplayName("Every MessageStatus DB enum maps to a distinct Message.Status domain enum")
    fun statusMappingIsCompleteAndDistinct() {
        val expected = listOf(
            MessageStatus.PENDING to Message.Status.PENDING,
            MessageStatus.SENT to Message.Status.SENT,
            MessageStatus.DELIVERED to Message.Status.DELIVERED,
            MessageStatus.FAILED to Message.Status.FAILED,
            MessageStatus.RECEIVED to Message.Status.RECEIVED,
            MessageStatus.SCHEDULED to Message.Status.SCHEDULED,
        )

        expected.forEach { (dbEnum, expectedDomain) ->
            assertThat(mapStatus(dbEnum)).isEqualTo(expectedDomain)
        }

        // Toutes les valeurs domain DOIVENT être couvertes par le mapping. Si on ajoute
        // Message.Status.NEW_THING sans mettre à jour [mapStatus], le compilateur refuse
        // de compiler (when exhaustif) — ce test reste comme double-check explicite.
        val mappedDomainValues = expected.map { it.second }.toSet()
        assertThat(mappedDomainValues).hasSize(Message.Status.entries.size)
        assertThat(mappedDomainValues).containsExactlyElementsIn(Message.Status.entries)

        // Symétrique : toutes les valeurs DB sont couvertes côté mapping.
        val mappedDbValues = expected.map { it.first }.toSet()
        assertThat(mappedDbValues).hasSize(MessageStatus.entries.size)
        assertThat(mappedDbValues).containsExactlyElementsIn(MessageStatus.entries)
    }

    @Test
    @DisplayName("Every MessageType DB enum maps to a distinct Message.Type domain enum")
    fun typeMappingIsCompleteAndDistinct() {
        val expected = listOf(
            MessageType.SMS to Message.Type.SMS,
            MessageType.MMS to Message.Type.MMS,
        )
        expected.forEach { (dbEnum, expectedDomain) ->
            assertThat(mapType(dbEnum)).isEqualTo(expectedDomain)
        }
        assertThat(expected.map { it.second }.toSet()).containsExactlyElementsIn(Message.Type.entries)
        assertThat(expected.map { it.first }.toSet()).containsExactlyElementsIn(MessageType.entries)
    }

    @Test
    @DisplayName("Every MessageDirection DB enum maps to a distinct Message.Direction domain enum")
    fun directionMappingIsCompleteAndDistinct() {
        val expected = listOf(
            MessageDirection.INCOMING to Message.Direction.INCOMING,
            MessageDirection.OUTGOING to Message.Direction.OUTGOING,
        )
        expected.forEach { (dbEnum, expectedDomain) ->
            assertThat(mapDirection(dbEnum)).isEqualTo(expectedDomain)
        }
        assertThat(expected.map { it.second }.toSet()).containsExactlyElementsIn(Message.Direction.entries)
        assertThat(expected.map { it.first }.toSet()).containsExactlyElementsIn(MessageDirection.entries)
    }

    @Test
    @DisplayName("MessageStatus.fromRaw : valid raw values map correctly, unknown falls back to PENDING")
    fun fromRawHandlesKnownAndUnknown() {
        // Valid range : tous les rawValue connus retournent le bon enum
        MessageStatus.entries.forEach { status ->
            assertThat(MessageStatus.fromRaw(status.rawValue)).isEqualTo(status)
        }
        // Unknown : fallback PENDING (avec log Timber documenté)
        assertThat(MessageStatus.fromRaw(999)).isEqualTo(MessageStatus.PENDING)
        assertThat(MessageStatus.fromRaw(-1)).isEqualTo(MessageStatus.PENDING)
    }

    @Test
    @DisplayName("MessageType.fromRaw : valid raw values map correctly, unknown falls back to SMS")
    fun typeFromRawHandlesKnownAndUnknown() {
        MessageType.entries.forEach { type ->
            assertThat(MessageType.fromRaw(type.rawValue)).isEqualTo(type)
        }
        assertThat(MessageType.fromRaw(99)).isEqualTo(MessageType.SMS)
    }

    @Test
    @DisplayName("MessageDirection.fromRaw : valid raw values map correctly, unknown falls back to INCOMING")
    fun directionFromRawHandlesKnownAndUnknown() {
        MessageDirection.entries.forEach { direction ->
            assertThat(MessageDirection.fromRaw(direction.rawValue)).isEqualTo(direction)
        }
        assertThat(MessageDirection.fromRaw(99)).isEqualTo(MessageDirection.INCOMING)
    }
}
