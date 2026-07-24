package com.filestech.sms.domain.reaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.4.1 — locks down the wire compatibility between
 * [com.filestech.sms.domain.usecase.SendReactionUseCase.buildTapbackBody] (encoder) and
 * [IncomingReactionDecoder.decode] (decoder).
 *
 * Each scenario corresponds to a real shape the encoder is documented to emit, so any
 * future refactor of the Tapback wire format MUST keep the round-trip green.
 */
class IncomingReactionDecoderTest {

    // ──────────────── Tapback with preview (the common path) ────────────────

    @Test fun `decodes Tapback with simple ASCII preview`() {
        val result = IncomingReactionDecoder.decode("Reacted ❤️ to «Hello»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with truncation marker (strips the ellipsis)`() {
        // SMS Tech appends `…` when the original body was truncated to fit one
        // UCS-2 segment. The decoder must strip it so the caller can match
        // `body LIKE 'preview%'` against the stored outgoing message.
        val result = IncomingReactionDecoder.decode("Reacted 👍 to «Lorem ipsum dolor sit…»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Lorem ipsum dolor sit")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with multi-codepoint emoji (ZWJ family)`() {
        // 👨‍👩‍👧 = man + ZWJ + woman + ZWJ + girl (4 code points, 7 UTF-16 units).
        val result = IncomingReactionDecoder.decode("Reacted 👨‍👩‍👧 to «Family pic»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👨‍👩‍👧")
        assertThat(result.previewPrefix).isEqualTo("Family pic")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with Unicode preview characters`() {
        val result = IncomingReactionDecoder.decode("Reacted 🎉 to «Joyeux anniversaire !»")
        assertThat(result).isNotNull()
        assertThat(result!!.previewPrefix).isEqualTo("Joyeux anniversaire !")
    }

    // ──────────────── Tapback without preview (voice/image MMS) ────────────────

    @Test fun `decodes Tapback without preview as previewPrefix null`() {
        val result = IncomingReactionDecoder.decode("Reacted ❤️")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isNull()
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    // ──────────────── v1.4.1 — emoji-only candidate (Kind.EmojiOnly) ────────────────

    @Test fun `decodes single emoji body as EmojiOnly candidate`() {
        // SMS Tech's `reactionEmojiOnly` mode sends just the emoji. The decoder now
        // tags it [Kind.EmojiOnly] so the caller knows to apply the 5 min time
        // window check before folding it onto an outgoing message.
        val result = IncomingReactionDecoder.decode("❤️")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isNull()
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `decodes ZWJ family emoji body as EmojiOnly candidate`() {
        val result = IncomingReactionDecoder.decode("👨‍👩‍👧")
        assertThat(result).isNotNull()
        assertThat(result!!.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `decodes double emoji body as EmojiOnly (some users send two emojis)`() {
        val result = IncomingReactionDecoder.decode("❤️❤️")
        assertThat(result).isNotNull()
        assertThat(result!!.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `emoji with trailing text is NOT decoded as EmojiOnly`() {
        // A real one-emoji-plus-text message ("❤️ je t'aime") must NOT slip through ;
        // it's a regular message that just happens to start with an emoji.
        assertThat(IncomingReactionDecoder.decode("❤️ je t'aime")).isNull()
        assertThat(IncomingReactionDecoder.decode("👍 great job!")).isNull()
        assertThat(IncomingReactionDecoder.decode("ok 👍")).isNull()
    }

    @Test fun `emoji-only body too long is NOT decoded (likely a long emoji-soup message)`() {
        // 20 hearts = 40 UTF-16 units, well past the [EMOJI_ONLY_MAX_UNITS] cap.
        // Likely a real expressive message, not a reaction.
        val longBody = "❤️".repeat(20)
        assertThat(IncomingReactionDecoder.decode(longBody)).isNull()
    }

    // ──────────────── Negative cases ────────────────

    @Test fun `plain text is NOT decoded`() {
        assertThat(IncomingReactionDecoder.decode("Hello, how are you?")).isNull()
        assertThat(IncomingReactionDecoder.decode("OK")).isNull()
        assertThat(IncomingReactionDecoder.decode("")).isNull()
        assertThat(IncomingReactionDecoder.decode("   ")).isNull()
    }

    @Test fun `text containing the word Reacted without the wire format is NOT decoded`() {
        // Defensive: a casual sentence starting with "Reacted" must not match.
        assertThat(IncomingReactionDecoder.decode("Reacted to your post earlier today.")).isNull()
        // Empty emoji slot is rejected.
        assertThat(IncomingReactionDecoder.decode("Reacted  to «Hello»")).isNull()
        // Empty preview slot is rejected.
        assertThat(IncomingReactionDecoder.decode("Reacted ❤️ to «»")).isNull()
    }

    @Test fun `decodes Tapback with leading and trailing whitespace`() {
        // Mobile carriers occasionally pad SMS bodies — the decoder trims first.
        val result = IncomingReactionDecoder.decode("  Reacted ❤️ to «Hello»  ")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
    }

    // ──────────────── v1.6.0 audit Q4 — ReDoS regression ────────────────

    @Test fun `decode rejects oversized body fast (ReDoS guard)`() {
        // A malicious sender could craft a multi-part SMS like `Reacted ❤️ to «aaa…`
        // with no closing guillemet to force the non-greedy regex into catastrophic
        // backtracking. The MAX_DECODE_INPUT_LENGTH guard short-circuits before the
        // regex sees the input.
        val malicious = "Reacted ❤️ to «" + "a".repeat(3000)
        val start = System.currentTimeMillis()
        val result = IncomingReactionDecoder.decode(malicious)
        val elapsed = System.currentTimeMillis() - start
        assertThat(result).isNull()
        // Allow generous CI slack ; on a real device the guard returns in microseconds.
        assertThat(elapsed).isLessThan(200L)
    }

    @Test fun `decode rejects body just above the cap`() {
        val justOver = "Reacted ❤️ to «" + "a".repeat(400) + "»"
        assertThat(IncomingReactionDecoder.decode(justOver)).isNull()
    }

    @Test fun `decode accepts a realistic Tapback close to the cap`() {
        // A real Tapback with the longest preview SMS Tech ever emits stays under
        // 100 chars — confirm the cap doesn't reject legitimate inputs.
        val realistic = "Reacted ❤️ to «" + "x".repeat(50) + "»"
        val result = IncomingReactionDecoder.decode(realistic)
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
    }
}
