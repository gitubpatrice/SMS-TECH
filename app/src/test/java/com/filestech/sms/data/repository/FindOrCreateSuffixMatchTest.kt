package com.filestech.sms.data.repository

import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Garde-régression du fix "doublon Nouvelle conversation".
 *
 * Symptôme : composer un message vers un contact dont le numéro est stocké au format
 * national (`06 12 34 56 78`) créait une 2ᵉ conversation alors qu'une conversation existe
 * déjà au format international (`+33612345678`, tel que reçu du système) — parce que
 * `findOrCreate` ne testait que l'égalité stricte du CSV d'adresses (forme brute).
 *
 * Le fix ajoute un fallback par les 8 derniers chiffres (déjà éprouvé côté
 * [ConversationMirror.ensureConversation]), extrait ici en fonction pure
 * [ConversationRepositoryImpl.matchOneToOneBySuffix8] pour être testable en JVM.
 */
class FindOrCreateSuffixMatchTest {

    private fun conv(id: Long, addressesCsv: String) = ConversationEntity(
        id = id,
        threadId = 0L,
        addressesCsv = addressesCsv,
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
    )

    // ──────────── Root cause : pourquoi le doublon existait ────────────

    @Test fun `national and international forms miss exact CSV but share suffix8`() {
        val national = PhoneAddress.of("06 12 34 56 78")
        val international = PhoneAddress.of("+33612345678")

        // L'égalité stricte que faisait `findByAddressesCsv` (comparaison de la forme brute)
        // ÉCHOUE : c'est la cause exacte du doublon.
        assertThat(national.raw).isNotEqualTo(international.raw)

        // Mais les 8 derniers chiffres concordent : c'est la clé du fallback.
        assertThat(national.raw.phoneSuffix8()).isEqualTo("12345678")
        assertThat(international.raw.phoneSuffix8()).isEqualTo("12345678")
    }

    // ──────────── Le fallback réunit les deux formes ────────────

    @Test fun `composing national number finds existing international conversation`() {
        val existing = listOf(conv(id = 7L, addressesCsv = "+33612345678"))
        val composed = PhoneAddress.of("06 12 34 56 78")

        val match = ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)

        assertThat(match).isNotNull()
        assertThat(match!!.id).isEqualTo(7L)
    }

    @Test fun `composing international number finds existing national conversation`() {
        val existing = listOf(conv(id = 3L, addressesCsv = "0612345678"))
        val composed = PhoneAddress.of("+33 6 12 34 56 78")

        val match = ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)

        assertThat(match?.id).isEqualTo(3L)
    }

    @Test fun `differently formatted same number matches`() {
        val existing = listOf(conv(id = 9L, addressesCsv = "06-12-34-56-78"))
        val composed = PhoneAddress.of("(06) 12 34 56 78")

        assertThat(ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)?.id)
            .isEqualTo(9L)
    }

    // ──────────── Pas de faux positif ────────────

    @Test fun `different number does not match`() {
        val existing = listOf(conv(id = 1L, addressesCsv = "+33612345678"))
        val composed = PhoneAddress.of("06 99 88 77 66")

        assertThat(ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)).isNull()
    }

    @Test fun `empty conversation list returns null`() {
        val composed = PhoneAddress.of("06 12 34 56 78")
        assertThat(ConversationRepositoryImpl.matchOneToOneBySuffix8(emptyList(), composed)).isNull()
    }

    @Test fun `short code with less than 8 digits never matches`() {
        // Numéros courts (services : 3208, 32900…) : trop peu discriminants pour un
        // rapprochement par suffixe. On ne veut PAS que deux services distincts fusionnent.
        val existing = listOf(conv(id = 5L, addressesCsv = "3208"))
        val composed = PhoneAddress.of("3208")

        assertThat(ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)).isNull()
    }

    // ──────────── Déterminisme ────────────

    @Test fun `returns first matching conversation when several share the suffix`() {
        val existing = listOf(
            conv(id = 10L, addressesCsv = "+33612345678"),
            conv(id = 20L, addressesCsv = "0612345678"),
        )
        val composed = PhoneAddress.of("06 12 34 56 78")

        assertThat(ConversationRepositoryImpl.matchOneToOneBySuffix8(existing, composed)?.id)
            .isEqualTo(10L)
    }
}
