package com.filestech.sms.domain.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 2026-11-11 (issue #89) — cf. [HorodatageSmsRecu]. */
class HorodatageSmsRecuTest {

    @Test
    fun `un SMS recu est date a son arrivee, et garde l heure du reseau comme heure d envoi`() {
        // Le réseau est en retard d'une heure : un décalage horaire non mis à jour au changement d'heure.
        val h = HorodatageSmsRecu.de(horlogeTelephone = MAINTENANT, horodatageReseau = MAINTENANT - UNE_HEURE)

        assertThat(h.date).isEqualTo(MAINTENANT)
        assertThat(h.dateSent).isEqualTo(MAINTENANT - UNE_HEURE)
    }

    @Test
    fun `une horloge non reglee laisse la place a l heure du reseau`() {
        val horlogeNonReglee = HorodatageSmsRecu.HORLOGE_REGLEE_DES_MS - 1L

        val h = HorodatageSmsRecu.de(horlogeTelephone = horlogeNonReglee, horodatageReseau = MAINTENANT)

        assertThat(h.date).isEqualTo(MAINTENANT)
        assertThat(h.dateSent).isEqualTo(MAINTENANT)
    }

    @Test
    fun `une horloge a la borne exacte est reglee`() {
        val borne = HorodatageSmsRecu.HORLOGE_REGLEE_DES_MS

        assertThat(HorodatageSmsRecu.de(horlogeTelephone = borne, horodatageReseau = MAINTENANT).date).isEqualTo(borne)
    }

    @Test
    fun `sans heure du reseau, l horloge sert meme non reglee, et l heure d envoi reste vide`() {
        assertThat(HorodatageSmsRecu.de(horlogeTelephone = MAINTENANT, horodatageReseau = 0L))
            .isEqualTo(HorodatageSmsRecu(date = MAINTENANT, dateSent = null))
        assertThat(HorodatageSmsRecu.de(horlogeTelephone = 5L, horodatageReseau = -1L))
            .isEqualTo(HorodatageSmsRecu(date = 5L, dateSent = null))
    }

    private companion object {
        /** 2026-11-11T20:41:13Z, l'heure de l'issue #89. */
        const val MAINTENANT = 1_794_429_673_000L
        const val UNE_HEURE = 3_600_000L
    }
}
