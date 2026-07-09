package com.filestech.sms.core.ext

/**
 * Pure conversion helper: a raw / national recipient address → its E.164 on-wire form.
 *
 * Kept **Android-free** on purpose so it is unit-testable without Robolectric — the platform
 * `android.telephony.PhoneNumberUtils.formatNumberToE164` is injected as [formatToE164] by the
 * caller ([com.filestech.sms.data.sms.PhoneNumberWireFormatter]).
 *
 * Rationale: when sending from a **foreign SIM** (e.g. a Luxembourg SIM texting a French number
 * stored in national form `06…`), the telephony stack must receive an **international** destination
 * or the network cannot route it and nobody receives the message. Converting to E.164 with the
 * SIM's country as the default region fixes routing, and is idempotent for numbers already in
 * `+CC…` form.
 *
 * Safety contract — the send must never be broken by this step:
 *   - unknown / malformed [regionIso] → return [raw] unchanged;
 *   - [formatToE164] returns null (short code, alphanumeric sender, number invalid for the region)
 *     → return [raw] unchanged;
 *   - [formatToE164] throws → swallowed, return [raw] unchanged.
 *
 * Only the wire address is affected; the caller keeps the user's original string for storage,
 * threading and display.
 */
object WireAddress {

    fun toE164OrRaw(
        raw: String,
        regionIso: String?,
        formatToE164: (number: String, region: String) -> String?,
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return raw
        // ISO 3166-1 alpha-2, uppercased for libphonenumber. Anything else (null, "", "FRA",
        // network-country garbage) means "no reliable region" → leave the number untouched.
        val region = regionIso?.trim()?.uppercase()
        if (region == null || region.length != 2 || !region.all { it in 'A'..'Z' }) return raw
        val e164 = runCatching { formatToE164(trimmed, region) }.getOrNull()
        return e164?.takeIf { it.isNotBlank() } ?: raw
    }
}
