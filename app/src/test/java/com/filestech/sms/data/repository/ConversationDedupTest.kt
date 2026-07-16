package com.filestech.sms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Garde de la logique pure de dédup des conversations du même numéro
 * ([planSameNumberMerges]). La correctness E.164 elle-même est du ressort de
 * [com.filestech.sms.core.ext.WireAddress] ; ici on teste le regroupement + le choix du
 * survivant avec une clé canonique simulée.
 */
class ConversationDedupTest {

    /** Clé « E.164 » simulée : réduit quelques formes connues à leur canonique. */
    private val fakeKey: (String) -> String? = { raw ->
        when (raw) {
            "0612345678", "+33612345678", "06 12 34 56 78" -> "+33612345678"
            // même suffix-8 (12345678) que le 06… ci-dessus mais NUMÉRO DIFFÉRENT
            "0712345678", "+33712345678" -> "+33712345678"
            "0699887766" -> "+33699887766"
            else -> null // non normalisable (short code, expéditeur alphanumérique…)
        }
    }

    private fun cand(id: Long, raw: String, lastAt: Long) = DedupCandidate(id, raw, lastAt)

    @Test fun `two forms of same number merge into one plan`() {
        val plans = planSameNumberMerges(
            listOf(cand(1, "+33612345678", 100), cand(2, "0612345678", 200)),
            fakeKey,
        )
        assertThat(plans).hasSize(1)
        // Survivant = plus récemment actif (id 2, lastAt 200) ; victime = id 1.
        assertThat(plans[0].survivorId).isEqualTo(2L)
        assertThat(plans[0].victimIds).containsExactly(1L)
    }

    @Test fun `different numbers sharing suffix8 are NOT merged`() {
        // 06…12345678 et 07…12345678 partagent leurs 8 derniers chiffres (le suffix-8 les aurait
        // fusionnés à tort) mais ont des E.164 distinctes → aucun plan. C'est le gain de sûreté.
        val plans = planSameNumberMerges(
            listOf(cand(1, "0612345678", 10), cand(2, "0712345678", 20)),
            fakeKey,
        )
        assertThat(plans).isEmpty()
    }

    @Test fun `unnormalizable numbers are ignored`() {
        val plans = planSameNumberMerges(
            listOf(cand(1, "3208", 10), cand(2, "3208", 20)), // fakeKey → null
            fakeKey,
        )
        assertThat(plans).isEmpty()
    }

    @Test fun `three duplicates produce one plan with two victims`() {
        val plans = planSameNumberMerges(
            listOf(
                cand(1, "+33612345678", 300),
                cand(2, "0612345678", 100),
                cand(3, "06 12 34 56 78", 200),
            ),
            fakeKey,
        )
        assertThat(plans).hasSize(1)
        assertThat(plans[0].survivorId).isEqualTo(1L) // lastAt 300 = max
        assertThat(plans[0].victimIds).containsExactly(2L, 3L)
    }

    @Test fun `tie on lastMessageAt picks lowest id as survivor`() {
        val plans = planSameNumberMerges(
            listOf(cand(9, "+33612345678", 50), cand(4, "0612345678", 50)),
            fakeKey,
        )
        assertThat(plans[0].survivorId).isEqualTo(4L)
        assertThat(plans[0].victimIds).containsExactly(9L)
    }

    @Test fun `single conversation is not a duplicate`() {
        assertThat(planSameNumberMerges(listOf(cand(1, "+33699887766", 10)), fakeKey)).isEmpty()
    }

    @Test fun `empty input yields no plan`() {
        assertThat(planSameNumberMerges(emptyList(), fakeKey)).isEmpty()
    }
}
