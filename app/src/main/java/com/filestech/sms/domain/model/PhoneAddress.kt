package com.filestech.sms.domain.model

import com.filestech.sms.core.ext.normalizePhone

/**
 * A normalized phone address. The [raw] form is kept for display; [normalized] is used for matching.
 */
@JvmInline
value class PhoneAddress private constructor(private val pair: Pair<String, String>) {

    val raw: String get() = pair.first
    val normalized: String get() = pair.second

    override fun toString(): String = raw

    companion object {
        fun of(raw: String): PhoneAddress {
            val trimmed = raw.trim()
            // For alphanumeric senders ("Free", "Orange", "INFO"…) the digit-only normalization
            // collapses to empty. Fall back to the trimmed raw form so downstream lookups + the
            // UI title still have something to match on.
            val normalized = trimmed.normalizePhone().ifEmpty { trimmed }
            return PhoneAddress(trimmed to normalized)
        }

        fun list(csv: String): List<PhoneAddress> =
            if (csv.isBlank()) emptyList()
            else csv.split(';', ',').map { of(it) }.filter { it.raw.isNotEmpty() }
        fun List<PhoneAddress>.toCsv(): String = joinToString(separator = ";") { it.raw }
    }
}
