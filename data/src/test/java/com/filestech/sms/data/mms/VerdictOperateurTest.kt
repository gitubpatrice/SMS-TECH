package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 (issue #53) — le verdict de l'opérateur, lu dans le m-send-conf joint au résultat d'un
 * envoi MMS. Seul un refus EXPLICITE est un refus : tout ce qui est absent ou illisible rend
 * `Inconnu`, pour ne jamais fabriquer de faux échec chez ceux pour qui l'envoi fonctionne.
 *
 * Les PDU sont écrits à la main, octet par octet, d'après OMA-MMS-ENC : un m-send-conf réel est
 * court (type, identifiant de transaction, version, statut, parfois un texte et un Message-ID).
 */
class VerdictOperateurTest {

    private companion object {
        const val MESSAGE_TYPE = 0x8C
        const val SEND_CONF = 0x81
        const val NOTIFICATION_IND = 0x82
        const val TRANSACTION_ID = 0x98
        const val MMS_VERSION = 0x8D
        const val RESPONSE_STATUS = 0x92
        const val RESPONSE_TEXT = 0x93
        const val MESSAGE_ID = 0x8B
    }

    private fun octets(vararg valeurs: Int) = ByteArray(valeurs.size) { valeurs[it].toByte() }

    private fun texte(s: String) = s.toByteArray(Charsets.US_ASCII) + 0.toByte()

    /** m-send-conf réaliste : type, transaction, version 1.2, statut, puis d'éventuels en-têtes. */
    private fun sendConf(statut: Int?, vararg apres: ByteArray): ByteArray {
        var pdu = octets(MESSAGE_TYPE, SEND_CONF, TRANSACTION_ID) + texte("T1a2b") + octets(MMS_VERSION, 0x92)
        if (statut != null) pdu += octets(RESPONSE_STATUS, statut)
        apres.forEach { pdu += it }
        return pdu
    }

    @Test
    fun `statut 0x80 - accepte`() {
        assertThat(lireVerdictOperateur(sendConf(0x80, octets(MESSAGE_ID) + texte("mid-1"))))
            .isEqualTo(VerdictOperateur.Accepte)
    }

    @Test
    fun `contenu non accepte - refus qui garde son statut`() {
        assertThat(lireVerdictOperateur(sendConf(0x87))).isEqualTo(VerdictOperateur.Refuse(0x87))
    }

    @Test
    fun `refus permanent avec un texte - le texte ne gene pas la lecture du statut`() {
        val pdu = sendConf(0xE3, octets(RESPONSE_TEXT) + texte("Address unresolved"))
        assertThat(lireVerdictOperateur(pdu)).isEqualTo(VerdictOperateur.Refuse(0xE3))
    }

    @Test
    fun `statut place apres le texte - lu quand meme`() {
        val pdu = octets(MESSAGE_TYPE, SEND_CONF, RESPONSE_TEXT) + texte("Rejected") + octets(RESPONSE_STATUS, 0xC2)
        assertThat(lireVerdictOperateur(pdu)).isEqualTo(VerdictOperateur.Refuse(0xC2))
    }

    @Test
    fun `aucune reponse - on ne sait pas`() {
        assertThat(lireVerdictOperateur(null)).isEqualTo(VerdictOperateur.Inconnu)
        assertThat(lireVerdictOperateur(ByteArray(0))).isEqualTo(VerdictOperateur.Inconnu)
    }

    @Test
    fun `send-conf sans statut - on ne sait pas`() {
        assertThat(lireVerdictOperateur(sendConf(null))).isEqualTo(VerdictOperateur.Inconnu)
    }

    @Test
    fun `statut hors norme sous 0x80 - PDU mal forme, on ne sait pas`() {
        assertThat(lireVerdictOperateur(sendConf(0x05))).isEqualTo(VerdictOperateur.Inconnu)
    }

    @Test
    fun `autre type de PDU - on ne sait pas`() {
        val pdu = octets(MESSAGE_TYPE, NOTIFICATION_IND, RESPONSE_STATUS, 0x87)
        assertThat(lireVerdictOperateur(pdu)).isEqualTo(VerdictOperateur.Inconnu)
    }

    @Test
    fun `octets quelconques - on ne sait pas, sans lever`() {
        assertThat(lireVerdictOperateur(octets(0x00, 0xFF, 0x13, 0x37))).isEqualTo(VerdictOperateur.Inconnu)
        assertThat(lireVerdictOperateur(octets(MESSAGE_TYPE))).isEqualTo(VerdictOperateur.Inconnu)
    }
}
