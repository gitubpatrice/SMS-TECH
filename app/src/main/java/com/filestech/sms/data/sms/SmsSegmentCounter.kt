package com.filestech.sms.data.sms

import android.telephony.SmsMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes (length, segmentCount, charactersLeft) for a draft message.
 *
 * Delegates to the system's [SmsMessage.calculateLength] (handles GSM 7-bit ext, UCS-2…).
 */
@Singleton
class SmsSegmentCounter @Inject constructor() {

    data class Stats(
        val length: Int,
        val segmentCount: Int,
        val charsRemainingInCurrentSegment: Int,
        val isUnicode: Boolean,
    )

    fun count(text: String): Stats {
        if (text.isEmpty()) return Stats(0, 0, 0, false)
        val r = SmsMessage.calculateLength(text, /* use7bitOnly */ false)
        val segments = r[0]
        val codeUnitsUsed = r[1]
        val remaining = r[2]
        val codingType = r[3]
        return Stats(
            length = codeUnitsUsed,
            segmentCount = segments,
            charsRemainingInCurrentSegment = remaining,
            isUnicode = codingType == ENCODING_UCS2,
        )
    }

    companion object {
        // android.telephony.SmsConstants.ENCODING_16BIT
        private const val ENCODING_UCS2 = 3
    }
}
