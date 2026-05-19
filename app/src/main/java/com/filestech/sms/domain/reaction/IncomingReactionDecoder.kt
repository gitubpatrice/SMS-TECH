package com.filestech.sms.domain.reaction

/**
 * v1.4.1 — pure-Kotlin decoder for incoming SMS bodies that look like a reaction sent
 * back by another SMS Tech instance (or by Apple Messages / Google Messages, which use
 * the same Tapback wire format).
 *
 * Two formats produced by SMS Tech's own [com.filestech.sms.domain.usecase
 * .SendReactionUseCase.buildTapbackBody] are decoded :
 *
 *   - **`Reacted <emoji> to «<preview>»`** — the standard Tapback shape. The preview
 *     may end with `…` if SMS Tech had to truncate the original body to fit the
 *     UCS-2 70-char segment cap. The trailing `…` is stripped before exposing
 *     [DecodedReaction.previewPrefix] so the caller can match it as a `LIKE prefix%`
 *     against stored outgoing message bodies.
 *
 *   - **`Reacted <emoji>`** (no `to «…»` tail) — produced by SMS Tech when reacting to
 *     a message with no text body (a voice MMS / image without caption). The caller
 *     should match the most recent outgoing message in the conversation (ordered by
 *     date desc) as the target.
 *
 * **Pure emojis are NOT decoded.** SMS Tech's "emoji only" reaction mode (cf.
 * `SendingSettings.reactionEmojiOnly`) sends just the emoji byte sequence — but a
 * real text message containing only an emoji ("👍 great job!") is ambiguous and we
 * MUST NOT silently swallow it as a reaction. Users on emoji-only mode trade
 * cross-SMS-Tech badge rendering for legacy-OEM cleanliness.
 *
 * Stateless object, no Android dependency, fully unit-testable.
 */
object IncomingReactionDecoder {

    /**
     * Result of a successful decode.
     *
     * @property emoji  the verbatim emoji string the remote user reacted with. May be a
     *   multi-codepoint cluster (ZWJ family, flag, skin-tone modifier). Caller stores it
     *   in `messages.reaction_emoji` as-is.
     * @property previewPrefix the body prefix of the original message the remote reacted
     *   to, or `null` when the wire format had no `to «…»` segment (target = most recent
     *   outgoing in the conversation). The `…` truncation marker is stripped.
     * @property kind  signals to the caller how strict the matching against an outgoing
     *   message must be. [Kind.Tapback] = full verbose Tapback wire format, always safe
     *   to bind to ANY recent outgoing in the conversation. [Kind.EmojiOnly] = the body
     *   contained nothing but one-to-two emojis, which is ambiguous with a real "❤️"
     *   message — caller MUST require a recent outgoing within a tight time window
     *   (see [EMOJI_ONLY_REACT_WINDOW_MS]) to avoid promoting a genuine emoji message
     *   into a fake reaction.
     */
    data class DecodedReaction(
        val emoji: String,
        val previewPrefix: String?,
        val kind: Kind,
    ) {
        enum class Kind { Tapback, EmojiOnly }
    }

    /**
     * v1.4.1 — time window applied to [Kind.EmojiOnly] candidates : the caller only
     * promotes the SMS to a reaction badge if there is an outgoing message in the
     * conversation strictly younger than this. 5 min covers the realistic UX of a
     * friend reacting right after we typed something, while preventing a "❤️" SMS
     * sent days later (unrelated) from being misread as a reaction to our last DM.
     *
     * `internal` because the only consumer is [com.filestech.sms.data.repository
     * .ConversationMirror.applyIncomingReaction] in the same Gradle module —
     * keeping it out of the public surface follows the project's minimal-visibility
     * convention.
     */
    internal const val EMOJI_ONLY_REACT_WINDOW_MS: Long = 5 * 60 * 1_000L

    /**
     * Max number of UTF-16 code units accepted as a pure-emoji body. 16 covers up
     * to 2 ZWJ clusters (a man-woman-girl family = 7 code units, two of those = 14).
     * Anything longer is unlikely to be a reaction — almost certainly text.
     */
    private const val EMOJI_ONLY_MAX_UNITS: Int = 16

    /**
     * v1.5.1 — defensive cap on decoder input length. A Tapback always fits in a single
     * UCS-2 SMS segment (70 chars) ; even with the longest preview SMS Tech emits, the
     * body stays under 100 chars. We accept up to [MAX_DECODE_INPUT_LENGTH] to be lenient
     * with multi-cluster emojis but reject anything longer outright. This neutralises a
     * theoretical ReDoS on [TAPBACK_WITH_PREVIEW_REGEX] : an attacker sending a multi-part
     * SMS like `Reacted ❤️ to «aaaa…` (no closing guillemet, 3000 chars) would otherwise
     * force the regex engine into catastrophic backtracking on the non-greedy quantifier.
     */
    private const val MAX_DECODE_INPUT_LENGTH: Int = 400

    /**
     * Tries to decode [body] as a reaction-back. Returns `null` when [body] is plain
     * text — caller falls back to the regular "store as incoming SMS" path.
     */
    fun decode(body: String): DecodedReaction? {
        if (body.length > MAX_DECODE_INPUT_LENGTH) return null
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        // Tapback with preview : `Reacted <emoji> to «<preview>»`. The `«` and `»` are
        // the Unicode guillemets that SMS Tech emits (forces UCS-2 single-segment).
        TAPBACK_WITH_PREVIEW_REGEX.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[1].trim()
            val rawPreview = m.groupValues[2]
            val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
            if (emoji.isEmpty() || preview.isEmpty()) return null
            return DecodedReaction(
                emoji = emoji,
                previewPrefix = preview,
                kind = DecodedReaction.Kind.Tapback,
            )
        }

        // Tapback without preview : `Reacted <emoji>` (original message had no text body,
        // e.g. voice MMS / image-only). Caller matches the most recent outgoing message
        // in the conversation.
        //
        // The captured token MUST :
        //   - contain no whitespace (a real emoji never does ; "Reacted to your post"
        //     would otherwise be misread as emoji="to your post"),
        //   - contain at least one non-ASCII codepoint (a real emoji always does ;
        //     "Reacted hello" would otherwise be misread as emoji="hello").
        TAPBACK_NO_PREVIEW_REGEX.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[1]
            if (emoji.isEmpty() || emoji.all { it.code < 128 }) return null
            return DecodedReaction(
                emoji = emoji,
                previewPrefix = null,
                kind = DecodedReaction.Kind.Tapback,
            )
        }

        // v1.4.1 — emoji-only candidate (SendingSettings.reactionEmojiOnly = true). The
        // body contains nothing but one or two emojis. We tag it [Kind.EmojiOnly] so
        // the caller knows to require a tight time window before promoting it to a
        // reaction badge — a stand-alone "❤️" SMS is also a perfectly valid real
        // message and must not be silently folded onto an unrelated past outgoing.
        if (looksLikeEmojiOnly(trimmed)) {
            return DecodedReaction(
                emoji = trimmed,
                previewPrefix = null,
                kind = DecodedReaction.Kind.EmojiOnly,
            )
        }

        return null
    }

    /**
     * Pure-emoji heuristic — no third-party emoji regex dep. Accepted iff :
     *   - non-empty, no whitespace anywhere (a sentence with an emoji is rejected),
     *   - length ≤ [EMOJI_ONLY_MAX_UNITS] UTF-16 units (≈ 2 ZWJ clusters max),
     *   - every UTF-16 unit is non-ASCII (real emojis live above U+007F ; the only
     *     ASCII chars that ever appear in emoji clusters are the digit/asterisk/hash
     *     keycap bases like `1️⃣` which are still preceded by the variation selector
     *     U+FE0F — those are not single-tap reactions in practice and skipping them
     *     is a fair trade for safety).
     */
    private fun looksLikeEmojiOnly(text: String): Boolean {
        if (text.isEmpty() || text.length > EMOJI_ONLY_MAX_UNITS) return false
        for (c in text) {
            if (c.isWhitespace() || c.code < 128) return false
        }
        return true
    }

    /** Lone Unicode horizontal ellipsis SMS Tech appends when the preview was truncated. */
    private const val TRUNCATION_MARKER: String = "…"

    /**
     * Matches `Reacted <emoji> to «<preview>»` with both standard Unicode guillemets
     * `«»` (what SMS Tech always emits) and a defensive ASCII fallback using the plain
     * double-quote `"..."` — covers carriers / OEMs that occasionally strip non-ASCII
     * punctuation from SMS bodies. The emoji group is non-greedy and the preview group
     * accepts any char (including new lines, defensively) because SMS Tech sanitises
     * but a third-party SMS app on the other end might inject CRLF that the parser
     * should still survive.
     */
    private val TAPBACK_WITH_PREVIEW_REGEX = Regex(
        """^Reacted\s+(.+?)\s+to\s+[«"](.+?)[»"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Matches `Reacted <emoji>` with no trailing `to «…»` segment. The capture group
     * is `\S+` (single token, no whitespace) so a casual sentence like
     * `"Reacted to your post earlier today."` cannot slip through ; the `decode`
     * function further rejects pure-ASCII tokens (real emoji always carry at least
     * one non-ASCII codepoint).
     */
    private val TAPBACK_NO_PREVIEW_REGEX = Regex("""^Reacted\s+(\S+)$""")
}
