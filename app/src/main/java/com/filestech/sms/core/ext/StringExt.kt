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

/**
 * Strips invisible bidi / zero-width / replacement characters often present in spam SMS.
 *
 * **v1.4.1 (SEC-02)** : widened beyond the original zero-width / bidi set to also cover
 * Unicode invisibles that previously slipped through the `looksLikeEmojiOnly` heuristic in
 * [com.filestech.sms.domain.reaction.IncomingReactionDecoder] — a body like
 * `­❤` (soft-hyphen + heart) used to look like a pure-emoji body and could be
 * forged by an attacker to push a reaction badge onto the victim's most recent outgoing
 * message. Cleaning the body upstream (in `SmsDeliverReceiver`) closes the bypass for
 * every consumer at once.
 *
 * Ranges and code-points covered :
 *   - `­`       — SOFT HYPHEN (formerly invisible)
 *   - `͏`       — COMBINING GRAPHEME JOINER
 *   - `؜`       — ARABIC LETTER MARK (bidi)
 *   - `᠎`       — MONGOLIAN VOWEL SEPARATOR (formerly whitespace, now zero-width)
 *   - `​-‏` — zero-width space / non-joiner / joiner / LRM / RLM
 *   - `‪-‮` — bidi explicit formatting (LRE/RLE/PDF/LRO/RLO)
 *   - `⁠-⁤` — word joiner + invisible operators
 *   - `⁦-⁩` — bidi isolates (LRI/RLI/FSI/PDI)
 *   - `﻿`       — ZWNBSP / BOM
 *   - `￼-�` — object replacement + replacement character
 */
fun String.stripInvisibleChars(): String =
    this.replace(INVISIBLE_CHARS_REGEX, "")

/** Compiled once at class-load — never re-allocated per call. */
private val INVISIBLE_CHARS_REGEX = Regex(
    "[\\u00AD\\u034F\\u061C\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\uFFFC\\uFFFD]"
)

/**
 * v1.6.1 (audit QUAL-13, déplacé depuis `ui.components.EmojiReactionPickerSheet`) —
 * découpe une chaîne en clusters de graphèmes Unicode (ZWJ family, drapeau, emoji +
 * variation selector, skin-tone modifier restent ATOMIQUES). Implémentation maison
 * naïve mais suffisante pour les emojis courants : on regroupe surrogate pairs +
 * ZWJ (U+200D) + variation selectors (U+FE00..U+FE0F) + skin-tone modifiers
 * (U+1F3FB..U+1F3FF). Pas de dépendance ICU.
 *
 * Utilisé par le picker emoji multi-sélection (cap à 3 clusters) ET par le cap UX
 * du custom emoji dialog côté ThreadScreen — c'est donc une fonction utilitaire
 * partagée qui appartient à `core/ext/` plutôt qu'à un composant UI.
 */
fun String.splitGraphemeClusters(): MutableList<String> {
    val out = mutableListOf<String>()
    if (isEmpty()) return out
    var i = 0
    while (i < length) {
        val sb = StringBuilder()
        // Base char : surrogate pair (BMP supplementary) ou simple BMP char.
        if (i < length - 1 && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) {
            sb.append(this[i]); sb.append(this[i + 1]); i += 2
        } else {
            sb.append(this[i]); i++
        }
        // Glob ZWJ continuations + variation selectors + further surrogate pairs.
        while (i < length) {
            val c = this[i]
            val code = c.code
            val isZwj = code == 0x200D
            val isVs = code in 0xFE00..0xFE0F
            val isSkinTone = i < length - 1 && c.isHighSurrogate() &&
                this[i + 1].isLowSurrogate() &&
                ((code - 0xD800) * 0x400 + (this[i + 1].code - 0xDC00) + 0x10000) in 0x1F3FB..0x1F3FF
            if (isZwj || isVs) {
                sb.append(c); i++
                if (isZwj && i < length) {
                    if (i < length - 1 && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) {
                        sb.append(this[i]); sb.append(this[i + 1]); i += 2
                    } else {
                        sb.append(this[i]); i++
                    }
                }
            } else if (isSkinTone) {
                sb.append(this[i]); sb.append(this[i + 1]); i += 2
            } else {
                break
            }
        }
        out += sb.toString()
    }
    return out
}
