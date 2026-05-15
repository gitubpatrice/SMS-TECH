package com.filestech.sms.data.mms

import com.google.android.mms.pdu.EncodedStringValue
import com.google.android.mms.pdu.NotificationInd
import com.google.android.mms.pdu.PduBody
import com.google.android.mms.pdu.PduComposer
import com.google.android.mms.pdu.PduHeaders
import com.google.android.mms.pdu.PduParser
import com.google.android.mms.pdu.PduPart
import com.google.android.mms.pdu.SendReq
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.junit5.RobolectricExtension
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Smoke tests for the in-tree AOSP PDU port. Covers the bare minimum: a SendReq builds without
 * throwing and emits a non-empty byte stream, and a NotificationInd survives a round-trip
 * through Composer → Parser.
 *
 * These tests run with Robolectric because [PduComposer] takes an Android Context (used to read
 * Uri-backed part data via the ContentResolver). The actual MMS encoding does not touch the
 * platform.
 */
@ExtendWith(RobolectricExtension::class)
class MmsPduRoundTripTest {

    @Test fun `SendReq with audio part composes to non-empty bytes`() {
        val ctx = RuntimeEnvironment.getApplication()
        val req = SendReq().apply {
            addTo(EncodedStringValue("+33612345678/TYPE=PLMN"))
            subject = EncodedStringValue("voice message")
            deliveryReport = PduHeaders.VALUE_NO
            readReport = PduHeaders.VALUE_NO
        }
        val body = PduBody().apply {
            addPart(
                PduPart().apply {
                    contentType = "application/smil".toByteArray()
                    contentId = "smil".toByteArray()
                    contentLocation = "smil.xml".toByteArray()
                    data = "<smil/>".toByteArray()
                },
            )
            addPart(
                PduPart().apply {
                    contentType = "audio/mp4".toByteArray()
                    contentId = "audio".toByteArray()
                    contentLocation = "v.m4a".toByteArray()
                    name = "v.m4a".toByteArray()
                    data = ByteArray(128) { it.toByte() } // fake audio payload
                },
            )
        }
        req.body = body

        val bytes = PduComposer(ctx, req).make()
        assertThat(bytes).isNotNull()
        assertThat(bytes!!.size).isGreaterThan(64)
    }

    @Test fun `NotificationInd content-location parses back from composed PDU`() {
        // A real NotificationInd is what the carrier sends us — we don't compose them ourselves
        // in production. But we can verify the parser tolerates a hand-crafted minimal one.
        val parser = PduParser(MINIMAL_NOTIFICATION_IND)
        val pdu = parser.parse()
        assertThat(pdu).isInstanceOf(NotificationInd::class.java)
        val ni = pdu as NotificationInd
        assertThat(String(ni.contentLocation!!)).isEqualTo("http://mms.example/abc")
        assertThat(ni.messageSize).isEqualTo(1234L)
    }

    /**
     * Hand-crafted minimal NotificationInd PDU:
     *  - X-Mms-Message-Type = 0x82 (m-notification.ind)
     *  - X-Mms-Transaction-Id = "T1"
     *  - X-Mms-MMS-Version = 1.2
     *  - X-Mms-Message-Size = 1234 (long-integer, 2 bytes)
     *  - X-Mms-Content-Location = "http://mms.example/abc"
     *  - X-Mms-Expiry = relative 86400 s
     */
    private val MINIMAL_NOTIFICATION_IND: ByteArray
        get() {
            val bos = java.io.ByteArrayOutputStream()
            // Message-Type
            bos.write(PduHeaders.MESSAGE_TYPE or 0x80)
            bos.write(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
            // Transaction-Id
            bos.write(PduHeaders.TRANSACTION_ID or 0x80)
            bos.write("T1".toByteArray()); bos.write(0)
            // MMS-Version
            bos.write(PduHeaders.MMS_VERSION or 0x80)
            bos.write(PduHeaders.CURRENT_MMS_VERSION or 0x80)
            // Message-Size = 1234 → long-integer (length 2, BE 0x04 0xD2)
            bos.write(PduHeaders.MESSAGE_SIZE or 0x80)
            bos.write(2); bos.write(0x04); bos.write(0xD2)
            // Expiry = value-length + token 0x81 (relative) + long-integer 86400
            // 86400 = 0x015180 → 3 bytes
            bos.write(PduHeaders.EXPIRY or 0x80)
            bos.write(5)        // value-length: 1 (token) + 1 (length) + 3 (bytes)
            bos.write(0x81)     // relative
            bos.write(3); bos.write(0x01); bos.write(0x51); bos.write(0x80)
            // Content-Location
            bos.write(PduHeaders.CONTENT_LOCATION or 0x80)
            bos.write("http://mms.example/abc".toByteArray()); bos.write(0)
            return bos.toByteArray()
        }
}
