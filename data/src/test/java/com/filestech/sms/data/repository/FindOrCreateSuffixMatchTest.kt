package com.filestech.sms.data.repository

import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Garde-régression du fix "doublon Nouvelle conversation" ET du fix "mauvais destinataire".
 *
 * Historique en deux temps :
 *
 *  1. Symptôme d'origine : composer un message vers un contact dont le numéro est stocké au
 *     format national (`06 12 34 56 78`) créait une 2ᵉ conversation alors qu'une conversation
 *     existe déjà au format international (`+33612345678`) — `findOrCreate` ne testait que
 *     l'égalité stricte du CSV d'adresses. Le fallback rapprochait alors par les 8 derniers
 *     chiffres (`phoneSuffix8`).
 *
 *  2. v1.27.2 (audit externe 2026-08-04 #1) : les 8 derniers chiffres amputaient celui qui
 *     sépare un `06…` d'un `07…`. `0612345678` et `0712345678` — deux personnes différentes —
 *     partageaient leur clé : composer vers la seconde ouvrait la conversation de la première
 *     et le message partait au mauvais destinataire. Le rapprochement se fait désormais sur
 *     [com.filestech.sms.core.ext.blockKey] (9 chiffres significatifs), comme côté réception
 *     ([ConversationMirror.ensureConversation], v1.26.1 audit H13).
 *
 * Fonction pure testée en JVM : [ConversationRepositoryImpl.matchOneToOneByBlockKey].
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

    // ──────────── Root cause du doublon : pourquoi le fallback existe ────────────

    @Test fun `national and international forms miss exact CSV but share blockKey`() {
        val national = PhoneAddress.of("06 12 34 56 78")
        val international = PhoneAddress.of("+33612345678")

        // L'égalité stricte que faisait `findByAddressesCsv` (comparaison de la forme brute)
        // ÉCHOUE : c'est la cause exacte du doublon.
        assertThat(national.raw).isNotEqualTo(international.raw)

        // Mais la clé canonique à 9 chiffres significatifs concorde : c'est la clé du fallback.
        assertThat(national.raw.blockKey()).isEqualTo("612345678")
        assertThat(international.raw.blockKey()).isEqualTo("612345678")
    }

    // ──────────── Le fallback réunit les deux formes du MÊME numéro ────────────

    @Test fun `composing national number finds existing international conversation`() {
        val existing = listOf(conv(id = 7L, addressesCsv = "+33612345678"))
        val composed = PhoneAddress.of("06 12 34 56 78")

        val match = ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)

        assertThat(match).isNotNull()
        assertThat(match!!.id).isEqualTo(7L)
    }

    @Test fun `composing international number finds existing national conversation`() {
        val existing = listOf(conv(id = 3L, addressesCsv = "0612345678"))
        val composed = PhoneAddress.of("+33 6 12 34 56 78")

        val match = ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)

        assertThat(match?.id).isEqualTo(3L)
    }

    @Test fun `differently formatted same number matches`() {
        val existing = listOf(conv(id = 9L, addressesCsv = "06-12-34-56-78"))
        val composed = PhoneAddress.of("(06) 12 34 56 78")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)?.id)
            .isEqualTo(9L)
    }

    // ──────────── Pas de faux positif ────────────

    @Test fun `v1_27_2 two different subscribers sharing their last 8 digits do NOT match`() {
        // LE défaut du finding #1 : `0612345678` et `0712345678` sont deux personnes
        // différentes. Avec phoneSuffix8 les deux rendaient « 12345678 » et le message
        // composé vers le 07 partait dans la conversation du 06. La clé à 9 chiffres
        // les distingue : « 612345678 » vs « 712345678 ».
        val existing = listOf(conv(id = 1L, addressesCsv = "+33612345678"))
        val composed = PhoneAddress.of("07 12 34 56 78")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed))
            .isNull()
    }

    @Test fun `different number does not match`() {
        val existing = listOf(conv(id = 1L, addressesCsv = "+33612345678"))
        val composed = PhoneAddress.of("06 99 88 77 66")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)).isNull()
    }

    @Test fun `empty conversation list returns null`() {
        val composed = PhoneAddress.of("06 12 34 56 78")
        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(emptyList(), composed)).isNull()
    }

    @Test fun `short code with less than 8 digits never matches`() {
        // Numéros courts (services : 3208, 32900…) : trop peu discriminants pour un
        // rapprochement par suffixe. On ne veut PAS que deux services distincts fusionnent.
        val existing = listOf(conv(id = 5L, addressesCsv = "3208"))
        val composed = PhoneAddress.of("3208")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)).isNull()
    }

    @Test fun `alphanumeric sender label never matches by suffix`() {
        // `blockKey()` rend un libellé en minuscules AVEC ses lettres pour un expéditeur
        // alphanumérique — la garde tout-chiffres doit écarter ce régime : on ne rapproche
        // jamais « SFR 123 » d'un numéro par suffixe.
        val existing = listOf(conv(id = 6L, addressesCsv = "SFR 123"))
        val composed = PhoneAddress.of("SFR 123")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)).isNull()
    }

    // ──────────── Déterminisme ────────────

    @Test fun `returns first matching conversation when several share the key`() {
        val existing = listOf(
            conv(id = 10L, addressesCsv = "+33612345678"),
            conv(id = 20L, addressesCsv = "0612345678"),
        )
        val composed = PhoneAddress.of("06 12 34 56 78")

        assertThat(ConversationRepositoryImpl.matchOneToOneByBlockKey(existing, composed)?.id)
            .isEqualTo(10L)
    }
}
