package com.filestech.sms.data.sms

import android.telephony.SmsMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes (length, segmentCount, charactersLeft) for a draft message.
 *
 * Delegates to the system's [SmsMessage.calculateLength] (handles GSM 7-bit ext, UCS-2…).
 *
 * v1.29.2 — quand le système refuse ce calcul (`SecurityException: getGroupIdLevel1`, long texte Unicode,
 * permission Téléphone refusée), le compte vient de [statsUcs2] au lieu de fermer l'application.
 */
@Singleton
class SmsSegmentCounter @Inject constructor() {

    data class Stats(
        val length: Int,
        val segmentCount: Int,
        val charsRemainingInCurrentSegment: Int,
        val isUnicode: Boolean,
    )

    fun count(text: String): Stats = compterSegments(text) { SmsMessage.calculateLength(it, /* use7bitOnly */ false) }

    companion object {
        // android.telephony.SmsConstants.ENCODING_16BIT
        private const val ENCODING_UCS2 = 3

        /**
         * Le compte de [text], le calcul du système étant passé en [calculSysteme] pour être mesurable.
         *
         * Seul le refus du GID1 est rattrapé : le système ne le lit que pour un texte Unicode de plus de 70
         * caractères, cas exact de [statsUcs2]. Toute autre exception reste ce qu'elle était.
         */
        internal fun compterSegments(text: String, calculSysteme: (String) -> IntArray): Stats {
            if (text.isEmpty()) return Stats(0, 0, 0, false)
            val r = try {
                calculSysteme(text)
            } catch (_: SecurityException) {
                return statsUcs2(text)
            }
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
    }
}
