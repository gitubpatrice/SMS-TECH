package com.filestech.sms.domain.model

import com.filestech.sms.data.local.db.entity.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.MessageType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Audit K-8 LIGHT (v1.15.0) — Garde-fou contre l'ajout d'une nouvelle constante Int dans
 * [MessageStatus] / [MessageType] / [MessageDirection] sans mise à jour du mapper domain
 * correspondant ([mapStatus], [mapType], [mapDirection]).
 *
 * Sans ce test, le silent fallback `else -> PENDING` masquait toute nouvelle constante.
 * Le mapper logue désormais via Timber + ce test asserte que toutes les const courantes
 * ont un mapping vers un Message.Status / Type / Direction distinct.
 *
 * Si tu ajoutes `MessageStatus.NEW_THING = 6` sans updater [mapStatus], ce test échoue.
 * Si tu ajoutes `Message.Status.NEW_THING` sans updater [mapStatus], ce test échoue aussi
 * (parce que `expectedStatusValues` ne couvrira pas la nouvelle valeur).
 */
class MessageStatusMappingTest {

    @Test
    @DisplayName("Every MessageStatus Int constant maps to a distinct Message.Status enum value")
    fun statusMappingIsCompleteAndDistinct() {
        // List of (rawInt, expected enum). Si tu ajoutes un MessageStatus.X = N, ajoute la
        // ligne ici. Si tu oublies, ce test échoue (count distinct mismatch).
        val expected = listOf(
            MessageStatus.PENDING to Message.Status.PENDING,
            MessageStatus.SENT to Message.Status.SENT,
            MessageStatus.DELIVERED to Message.Status.DELIVERED,
            MessageStatus.FAILED to Message.Status.FAILED,
            MessageStatus.RECEIVED to Message.Status.RECEIVED,
            MessageStatus.SCHEDULED to Message.Status.SCHEDULED,
        )

        expected.forEach { (rawInt, expectedEnum) ->
            assertThat(mapStatus(rawInt)).isEqualTo(expectedEnum)
        }

        // Les valeurs domain doivent être COUVERTES — si on ajoute Message.Status.NEW, il
        // faudra une entrée correspondante ci-dessus pour ne pas casser ce test.
        val mappedDomainValues = expected.map { it.second }.toSet()
        assertThat(mappedDomainValues).hasSize(Message.Status.entries.size)
        assertThat(mappedDomainValues).containsExactlyElementsIn(Message.Status.entries)
    }

    @Test
    @DisplayName("Every MessageType Int constant maps to a distinct Message.Type enum value")
    fun typeMappingIsCompleteAndDistinct() {
        val expected = listOf(
            MessageType.SMS to Message.Type.SMS,
            MessageType.MMS to Message.Type.MMS,
        )
        expected.forEach { (rawInt, expectedEnum) ->
            assertThat(mapType(rawInt)).isEqualTo(expectedEnum)
        }
        val mapped = expected.map { it.second }.toSet()
        assertThat(mapped).hasSize(Message.Type.entries.size)
        assertThat(mapped).containsExactlyElementsIn(Message.Type.entries)
    }

    @Test
    @DisplayName("Every MessageDirection Int constant maps to a distinct Message.Direction enum value")
    fun directionMappingIsCompleteAndDistinct() {
        val expected = listOf(
            MessageDirection.INCOMING to Message.Direction.INCOMING,
            MessageDirection.OUTGOING to Message.Direction.OUTGOING,
        )
        expected.forEach { (rawInt, expectedEnum) ->
            assertThat(mapDirection(rawInt)).isEqualTo(expectedEnum)
        }
        val mapped = expected.map { it.second }.toSet()
        assertThat(mapped).hasSize(Message.Direction.entries.size)
        assertThat(mapped).containsExactlyElementsIn(Message.Direction.entries)
    }

    @Test
    @DisplayName("Unknown status int falls back to PENDING (audit K-8 LIGHT contract)")
    fun unknownStatusFallsBackToPending() {
        // Contrat documenté du mapper : tout Int hors valeurs connues → PENDING + log Timber.
        // Préserve la sémantique défensive pour les rows Room écrites par une version future
        // qui aurait ajouté un statut côté DB sans bumper le code domain.
        assertThat(mapStatus(999)).isEqualTo(Message.Status.PENDING)
        assertThat(mapStatus(-1)).isEqualTo(Message.Status.PENDING)
        assertThat(mapType(99)).isEqualTo(Message.Type.SMS)
        assertThat(mapDirection(99)).isEqualTo(Message.Direction.INCOMING)
    }
}
