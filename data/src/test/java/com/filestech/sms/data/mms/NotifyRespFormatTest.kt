package com.filestech.sms.data.mms

import com.filestech.sms.pdu.NotifyRespInd
import com.filestech.sms.pdu.PduComposer
import com.filestech.sms.pdu.PduHeaders
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — la réponse au MMSC (`m-notifyresp.ind`) sort OCTET POUR OCTET ce qu'écrit le composeur
 * d'AOSP.
 *
 * `PduComposer` est une réécriture de celui d'AOSP, pas une copie, et il en avait déjà divergé sur
 * `m-send.req` (issue #53, refus 0xE2). Les références ci-dessous ont été produites le 2026-11-06 par
 * `com.google.android.mms.pdu.NotifyRespInd` et `PduComposer` d'AOSP (sources du SDK android-36.1),
 * compilés sur un JDK nu avec `~/.claude/tools/pdu-aosp/stubs.py` — même méthode que
 * [MmsBuilderFormatTest]. Notre composeur, passé par le même programme, a rendu les mêmes octets.
 *
 * Lecture d'une référence : `8c 83` type NotifyResp, `98 … 00` Transaction-Id (texte terminé par 0,
 * précédé de `7f` si son premier octet dépasse 0x7F), `8d 92` version 1.2, `95 81|82` X-Mms-Status.
 */
class NotifyRespFormatTest {

    private fun compose(transactionId: ByteArray, statut: Int): String {
        val pdu = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, transactionId, statut)
        return checkNotNull(PduComposer(mockk(relaxed = true), pdu).make()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `retrieved - les octets d'AOSP`() {
        assertThat(compose("T0".toByteArray(Charsets.US_ASCII), PduHeaders.STATUS_RETRIEVED))
            .isEqualTo("8c83985430008d929581")
    }

    @Test
    fun `rejected - les octets d'AOSP`() {
        assertThat(compose("T0".toByteArray(Charsets.US_ASCII), PduHeaders.STATUS_REJECTED))
            .isEqualTo("8c83985430008d929582")
    }

    @Test
    fun `identifiant long - les octets d'AOSP`() {
        assertThat(compose("Tx-1234abcd".toByteArray(Charsets.US_ASCII), PduHeaders.STATUS_RETRIEVED))
            .isEqualTo("8c839854782d3132333461626364008d929581")
    }

    /** Le cas qu'une réécriture rate le plus facilement : la citation d'un texte qui commence haut. */
    @Test
    fun `identifiant dont le premier octet depasse 0x7F - cite comme AOSP`() {
        assertThat(compose(byteArrayOf(0xE9.toByte(), 0x74), PduHeaders.STATUS_RETRIEVED))
            .isEqualTo("8c83987fe974008d929581")
    }

    /** Contrôle négatif : les références distinguent bien les deux statuts. */
    @Test
    fun `les deux statuts donnent deux PDU differents`() {
        val tid = "T0".toByteArray(Charsets.US_ASCII)

        assertThat(compose(tid, PduHeaders.STATUS_RETRIEVED)).isNotEqualTo(compose(tid, PduHeaders.STATUS_REJECTED))
    }
}
