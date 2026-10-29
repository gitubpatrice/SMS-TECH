package com.filestech.sms.system.receiver

import android.app.Activity
import com.filestech.sms.domain.model.SendErrorCode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 (issue #53) — ce que devient un MMS sortant au retour d'Android. `RESULT_OK` ne veut
 * dire que « remis au serveur de l'opérateur » ; seul le m-send-conf dit s'il l'a accepté.
 */
class IssueEnvoiMmsTest {

    private fun octets(vararg valeurs: Int) = ByteArray(valeurs.size) { valeurs[it].toByte() }

    /** m-send-conf minimal : type (0x8C 0x81) puis statut (0x92). */
    private fun sendConf(statut: Int) = octets(0x8C, 0x81, 0x92, statut)

    @Test
    fun `RESULT_OK et operateur d'accord - envoye`() {
        assertThat(issueDeLEnvoiMms(Activity.RESULT_OK, sendConf(0x80))).isEqualTo(IssueEnvoiMms.Envoye)
    }

    @Test
    fun `RESULT_OK mais operateur qui refuse - echec qui garde le statut`() {
        assertThat(issueDeLEnvoiMms(Activity.RESULT_OK, sendConf(0xE2)))
            .isEqualTo(IssueEnvoiMms.Echec(SendErrorCode.mmscRefused(0xE2)))
    }

    @Test
    fun `RESULT_OK sans reponse lisible - envoye, comme avant`() {
        assertThat(issueDeLEnvoiMms(Activity.RESULT_OK, null)).isEqualTo(IssueEnvoiMms.Envoye)
        assertThat(issueDeLEnvoiMms(Activity.RESULT_OK, octets(0x13, 0x37))).isEqualTo(IssueEnvoiMms.Envoye)
    }

    @Test
    fun `echec d'Android - son code, quelle que soit la reponse`() {
        assertThat(issueDeLEnvoiMms(1, sendConf(0x80))).isEqualTo(IssueEnvoiMms.Echec(1))
        assertThat(issueDeLEnvoiMms(8, null)).isEqualTo(IssueEnvoiMms.Echec(8))
    }
}
