package com.filestech.sms.core.ext

/**
 * Telephony-friendly normalization of a phone number: keeps leading '+', digits and '*#'.
 * Strips spaces, dashes, parentheses. Does not enforce country format (left to libphonenumber if needed).
 */
fun String.normalizePhone(): String {
    val sb = StringBuilder()
    for ((i, c) in this.withIndex()) {
        when {
            c.isDigit() -> sb.append(c)
            c == '+' && i == 0 -> sb.append(c)
            c == '*' || c == '#' -> sb.append(c)
            else -> Unit // skip
        }
    }
    return sb.toString()
}

/**
 * Permissive phone-number match used by the blocklist (system entry vs mirrored row).
 *
 * Real-world headache: Téléphone / Samsung Messages stores blocked numbers in **international**
 * form (`+33612345678`), but `content://sms` may carry the same correspondent in **national**
 * form (`0612345678`) — same person, two strings, strict equality fails. We don't pull in
 * libphonenumber for this; we compare the **last 8 digits** of each side. 8 digits covers every
 * French mobile/landline significant portion ("06 12 34 56 78" → `12345678`, "+33 6 12 34 56 78"
 * → `12345678`) and absorbs foreign country codes too. False-positive risk is negligible at
 * 8-digit suffix granularity (~1 in 10⁸).
 *
 * Returns `false` when either side has < 8 digits (short codes, partial inputs).
 */
fun phonesMatchLoose(a: String, b: String): Boolean {
    val da = a.filter { it.isDigit() }
    val db = b.filter { it.isDigit() }
    if (da.length < 8 || db.length < 8) return da == db && da.isNotEmpty()
    return da.takeLast(8) == db.takeLast(8)
}

/** Returns the last 8 digits of [this] — the canonical key for blocklist suffix matching. */
fun String.phoneSuffix8(): String {
    val digits = this.filter { it.isDigit() }
    return if (digits.length <= 8) digits else digits.takeLast(8)
}

/**
 * Avatar initials: first letter of first two words, uppercased. Falls back to '?'.
 */
fun String.avatarInitials(maxChars: Int = 2): String {
    if (isBlank()) return "?"
    val parts = trim().split(Regex("\\s+"))
    val sb = StringBuilder()
    for (p in parts) {
        if (p.isEmpty()) continue
        sb.append(p[0])
        if (sb.length >= maxChars) break
    }
    return sb.toString().uppercase()
}

/**
 * Deterministic hash → color hue index in 0..359.
 */
fun String.deterministicHue(): Int {
    var hash = 0
    for (c in this) hash = (hash * 31 + c.code) and 0x7FFFFFFF
    return hash % 360
}

/**
 * 6-digit OTP detector — returns the first plausible 4–8 digit code, or null.
 */
private val OTP_REGEX = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
fun String.extractOtp(): String? = OTP_REGEX.find(this)?.value

/** Strips invisible bidi / zero-width characters often present in spam SMS. */
fun String.stripInvisibleChars(): String =
    this.replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
