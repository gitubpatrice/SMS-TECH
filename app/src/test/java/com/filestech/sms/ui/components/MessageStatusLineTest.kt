package com.filestech.sms.ui.components

import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendErrorCode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 (issue #53) — ce que dit la ligne sous une bulle. Les deux cas qui comptent : un MMS
 * refusé par l'opérateur montre le code, et un MMS en échec ne promet plus « appuyer pour
 * réessayer » (un MMS ne se relance pas). Le libellé d'un SMS, lui, ne change pas.
 */
class MessageStatusLineTest {

    private fun message(type: Message.Type, status: Message.Status, errorCode: Int? = null) = Message(
        id = 1L,
        conversationId = 1L,
        address = "+33600000000",
        body = "",
        type = type,
        direction = Message.Direction.OUTGOING,
        date = 0L,
        dateSent = null,
        read = true,
        starred = false,
        status = status,
        errorCode = errorCode,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    @Test
    fun `MMS refuse par l'operateur - le code s'affiche`() {
        val refus = message(Message.Type.MMS, Message.Status.FAILED, SendErrorCode.mmscRefused(0x87))
        assertThat(libelleDeStatut(refus))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_refused_by_carrier, "0x87"))
    }

    @Test
    fun `MMS en echec - non envoye, sans promesse de relance`() {
        assertThat(libelleDeStatut(message(Message.Type.MMS, Message.Status.FAILED, errorCode = 1)))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_failed_mms))
    }

    @Test
    fun `SMS en echec - libelle inchange`() {
        assertThat(libelleDeStatut(message(Message.Type.SMS, Message.Status.FAILED, errorCode = 1)))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_failed))
    }

    @Test
    fun `statuts ordinaires - libelles inchanges`() {
        assertThat(libelleDeStatut(message(Message.Type.MMS, Message.Status.SENT)))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_sent))
        assertThat(libelleDeStatut(message(Message.Type.SMS, Message.Status.DELIVERED)))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_delivered))
        assertThat(libelleDeStatut(message(Message.Type.SMS, Message.Status.PENDING)))
            .isEqualTo(LibelleDeStatut(R.string.thread_status_pending))
        assertThat(libelleDeStatut(message(Message.Type.SMS, Message.Status.RECEIVED))).isNull()
    }
}
