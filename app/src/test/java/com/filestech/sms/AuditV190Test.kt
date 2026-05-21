package com.filestech.sms

import com.filestech.sms.data.local.datastore.ReactionFormat
import com.filestech.sms.data.local.datastore.SafetyCallContactCodec
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.filestech.sms.domain.usecase.buildEmojiWithQuoteBody
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.9.0 — garde-régression pour les 2 features livrées :
 *  - Safety call : timer + templates + contacts + codec JSON
 *  - 4ᵉ format réaction EMOJI_WITH_QUOTE (encoder + decoder)
 *
 * **Vigilance** : ces tests garantissent que les valeurs par défaut (opt-in
 * strict, désactivé) ne régressent jamais. Toute modif qui passerait
 * `SafetyCallConfig.enabled = true` par défaut ferait casser les tests.
 */
class AuditV190Test {

    // ──────────────── Safety call defaults ────────────────

    @Test fun `SafetyCallConfig defaults are opt-in (disabled, 48h, empty contacts)`() {
        val c = SafetyCallConfig()
        assertThat(c.enabled).isFalse()
        assertThat(c.timeoutMs).isEqualTo(SafetyCallConfig.TIMEOUT_48H_MS)
        assertThat(c.contacts).isEmpty()
        assertThat(c.template).isEqualTo(SafetyCallTemplate.CHECK_IN)
        assertThat(c.customMessage).isEmpty()
        assertThat(c.lastActivityAt).isEqualTo(0L)
        // v1.10.0 SEC-11 — clock monotonic complémentaire (défaut 0L = filet).
        assertThat(c.monotonicLastActivityAt).isEqualTo(0L)
    }

    @Test fun `SafetyCallConfig isExpired returns false when disabled`() {
        val c = SafetyCallConfig(
            enabled = false,
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            lastActivityAt = 0L,
        )
        assertThat(c.isExpired()).isFalse()
    }

    @Test fun `SafetyCallConfig isExpired triggers after timeout when both clocks agree`() {
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            lastActivityAt = now - SafetyCallConfig.TIMEOUT_24H_MS - 1L,
            monotonicLastActivityAt = nowMono - SafetyCallConfig.TIMEOUT_24H_MS - 1L,
        )
        assertThat(c.isExpired(nowMs = now, nowMonoMs = nowMono)).isTrue()
    }

    @Test fun `SafetyCallConfig isInWarningWindow during last 6h when both clocks agree`() {
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val withinWindow = SafetyCallConfig.TIMEOUT_24H_MS - (5 * 60 * 60 * 1000L)
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            // 5h before expiry (within 6h warning window) on both clocks
            lastActivityAt = now - withinWindow,
            monotonicLastActivityAt = nowMono - withinWindow,
        )
        assertThat(c.isInWarningWindow(nowMs = now, nowMonoMs = nowMono)).isTrue()
    }

    // ──────────────── Safety call contacts ────────────────

    @Test fun `SafetyCallContact validates dialable phone numbers`() {
        assertThat(SafetyCallContact(phoneNumber = "+33612345678").isValid()).isTrue()
        assertThat(SafetyCallContact(phoneNumber = "0612345678").isValid()).isTrue()
        assertThat(SafetyCallContact(phoneNumber = "06 12 34 56 78").isValid()).isTrue()
    }

    @Test fun `SafetyCallContact rejects alphanumeric senders`() {
        assertThat(SafetyCallContact(phoneNumber = "AMAZON").isValid()).isFalse()
        assertThat(SafetyCallContact(phoneNumber = "INFO").isValid()).isFalse()
        assertThat(SafetyCallContact(phoneNumber = "32665").isValid()).isTrue() // 5 digits OK
    }

    @Test fun `SafetyCallContactCodec roundtrip preserves data`() {
        val list = listOf(
            SafetyCallContact(displayName = "Marie", phoneNumber = "+33612345678"),
            SafetyCallContact(displayName = null, phoneNumber = "0698765432"),
        )
        val decoded = SafetyCallContactCodec.decode(SafetyCallContactCodec.encode(list))
        assertThat(decoded).hasSize(2)
        assertThat(decoded[0].phoneNumber).isEqualTo("+33612345678")
        assertThat(decoded[0].sanitizedDisplayName()).isEqualTo("Marie")
        assertThat(decoded[1].phoneNumber).isEqualTo("0698765432")
        assertThat(decoded[1].displayName).isNull()
    }

    @Test fun `SafetyCallContactCodec returns empty list on corrupt input`() {
        assertThat(SafetyCallContactCodec.decode("not json")).isEmpty()
        assertThat(SafetyCallContactCodec.decode(null)).isEmpty()
        assertThat(SafetyCallContactCodec.decode("")).isEmpty()
    }

    // ──────────────── Safety call templates ────────────────

    @Test fun `SafetyCallTemplate enum has 4 values`() {
        val names = SafetyCallTemplate.values().map { it.name }.toSet()
        assertThat(names).containsExactly("CHECK_IN", "URGENT", "FOLLOW_UP", "CUSTOM")
    }

    @Test fun `SafetyCallTemplate formatDuration renders correctly`() {
        assertThat(SafetyCallTemplate.formatDuration(SafetyCallConfig.TIMEOUT_24H_MS))
            .isEqualTo("1 jour")
        assertThat(SafetyCallTemplate.formatDuration(SafetyCallConfig.TIMEOUT_48H_MS))
            .isEqualTo("2 jours")
        assertThat(SafetyCallTemplate.formatDuration(SafetyCallConfig.TIMEOUT_72H_MS))
            .isEqualTo("3 jours")
        assertThat(SafetyCallTemplate.formatDuration(5 * 60 * 60 * 1000L))
            .isEqualTo("5 heures")
    }

    @Test fun `SafetyCallTemplate render substitutes DURÉE placeholder`() {
        val body = SafetyCallTemplate.CHECK_IN.render(SafetyCallConfig.TIMEOUT_48H_MS)
        assertThat(body).contains("2 jours")
        assertThat(body).doesNotContain("[DURÉE]")
    }

    @Test fun `SafetyCallTemplate URGENT wording matches user request v1_9_0`() {
        // Verbatim match — l'user a explicitement validé ce wording.
        val body = SafetyCallTemplate.URGENT.render(SafetyCallConfig.TIMEOUT_24H_MS)
        assertThat(body).isEqualTo(
            "Si tu reçois ce SMS, c'est que je n'ai pas pu utiliser mon téléphone " +
                "depuis 1 jour. Afin d'être certain que tout va bien, appelle-moi STP.",
        )
    }

    @Test fun `SafetyCallTemplate CUSTOM returns custom message with placeholder replaced`() {
        val body = SafetyCallTemplate.CUSTOM.render(
            SafetyCallConfig.TIMEOUT_24H_MS,
            customMessage = "Inactif depuis [DURÉE], please call.",
        )
        assertThat(body).isEqualTo("Inactif depuis 1 jour, please call.")
    }

    // ──────────────── ReactionFormat EMOJI_WITH_QUOTE (v1.9.0) ────────────────

    @Test fun `ReactionFormat includes EMOJI_WITH_QUOTE since v1_9_0`() {
        val names = ReactionFormat.values().map { it.name }.toSet()
        assertThat(names).contains("EMOJI_WITH_QUOTE")
    }

    @Test fun `buildEmojiWithQuoteBody produces compact format`() {
        val body = buildEmojiWithQuoteBody("❤️", "Salut ça va")
        assertThat(body).isEqualTo("❤️ «Salut ça va»")
    }

    @Test fun `buildEmojiWithQuoteBody falls back to emoji only when body is empty`() {
        val body = buildEmojiWithQuoteBody("👍", "")
        assertThat(body).isEqualTo("👍")
    }

    @Test fun `decoder recognises EMOJI_WITH_QUOTE format`() {
        val decoded = IncomingReactionDecoder.decode("❤️ «Hello»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
        assertThat(decoded.previewPrefix).isEqualTo("Hello")
        assertThat(decoded.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decoder rejects emoji-with-quote when emoji is ASCII (real text)`() {
        // "Hi «citation»" is a real human message, not a reaction — must NOT be
        // misdetected. The decoder requires the first token to have a non-ASCII
        // codepoint (real emojis always do).
        val decoded = IncomingReactionDecoder.decode("Hi «citation»")
        assertThat(decoded).isNull()
    }

    // ──────────────── Audit SEC-7 regression — FR accented words ────────────────

    @Test fun `decoder rejects emoji-with-quote when first token is FR accented word (SEC-7)`() {
        // SEC-7 — "Ça «fait plaisir de te voir»" is a real FR message starting with
        // an accented word. Pre-fix, `Ça` passed `code < 128` rejection because `Ç`
        // is U+00C7 ≥ 128 → it was decoded as `emoji=Ça preview=fait plaisir...`
        // and silently swallowed as a reaction. Now `isLikelyEmojiChar` requires
        // a real emoji codepoint (high-surrogate or U+2300..U+27BF or ZWJ/VS-16).
        assertThat(IncomingReactionDecoder.decode("Ça «fait plaisir de te voir»")).isNull()
        assertThat(IncomingReactionDecoder.decode("été «la belle saison»")).isNull()
        assertThat(IncomingReactionDecoder.decode("à «demain»")).isNull()
    }

    @Test fun `decoder accepts BMP heart emoji with quote (regression for SEC-7 fix)`() {
        // Verifies the SEC-7 fix didn't over-restrict : ❤️ = U+2764 (BMP, in
        // 0x2300..0x27BF range) + U+FE0F VS-16 must still be accepted as emoji.
        val decoded = IncomingReactionDecoder.decode("❤️ «message»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("❤️")
    }

    @Test fun `decoder accepts supplementary-plane emoji with quote (regression for SEC-7 fix)`() {
        // 👍 = U+1F44D in supplementary plane → high surrogate, must pass guard.
        val decoded = IncomingReactionDecoder.decode("👍 «good»")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("👍")
    }

    // ──────────────── Audit SEC-6 regression — ReDoS guard ────────────────

    @Test fun `decoder fast-fails on pathological input without closing guillemet (SEC-6)`() {
        // SEC-6 — input with opening `«` but no closing `»` could trigger
        // catastrophic backtracking on the non-greedy `.+?` quantifier (pre-fix).
        // Now : (1) early-exit check on `»`/`"` presence, (2) regex uses `[^»"]+`
        // negative class which cannot backtrack. Sanity : must return null fast.
        val pathological = "❤️ «" + "a".repeat(380)
        assertThat(IncomingReactionDecoder.decode(pathological)).isNull()
    }

    @Test fun `decoder rejects input exceeding 400 chars regardless of format`() {
        // Defense in depth — the hard cap MAX_DECODE_INPUT_LENGTH=400 must be
        // honored even when input "looks like" a valid reaction.
        val tooLong = "❤️ «" + "a".repeat(500) + "»"
        assertThat(IncomingReactionDecoder.decode(tooLong)).isNull()
    }

    // ──────────────── Audit SEC-3 regression — preemptive disable ────────────────

    @Test fun `TriggerSafetyCallUseCase Result sealed interface has PanicSuppressed variant`() {
        // SEC-8 fix introduced a new Result variant for panic-decoy short-circuit.
        // Test compile fails if the variant is renamed / removed (compile-time
        // garde-régression without needing kotlin-reflect).
        // v1.10.0 refacto C1 — ex-`SafetyCallTriggerService.Result`.
        val variant: com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase.Result =
            com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase.Result.PanicSuppressed
        assertThat(variant).isEqualTo(
            com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase.Result.PanicSuppressed,
        )
    }

    // ──────────────── Audit SEC-4 regression — codec defense in depth ────────────────

    @Test fun `SafetyCallContactCodec rejects invalid phone numbers at decode (SEC-4)`() {
        // A tampered DataStore restore could contain `|ALPHANUMERIC_SCAM` for the
        // phone slot. Pre-fix, decode would happily produce the contact and trust
        // the sender to fail validation later. Post-fix, decode filters via
        // isValid() so invalid contacts never enter the trigger pipeline.
        val raw = "Marie|+33612345678\n|FAKE_SCAM_SENDER"
        val decoded = SafetyCallContactCodec.decode(raw)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].phoneNumber).isEqualTo("+33612345678")
    }

    @Test fun `SafetyCallContactCodec strips C0-C1 chars including CR (SEC-4)`() {
        // SEC-4 — pre-fix only \n + | were stripped ; \r in displayName could
        // corrupt the line-based parser. Now full C0/C1 range is stripped both
        // by sanitizedDisplayName (→ replaced by space) AND by the codec's
        // FORBIDDEN_FIELD_CHARS (defense in depth). Critical assertion : the
        // encoded blob contains no raw \r.
        val contact = SafetyCallContact(
            displayName = "Marie\rDanger",
            phoneNumber = "+33612345678\r",
        )
        val encoded = SafetyCallContactCodec.encode(listOf(contact))
        assertThat(encoded).doesNotContain("\r")
        val decoded = SafetyCallContactCodec.decode(encoded)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].phoneNumber).isEqualTo("+33612345678")
    }

    // ──────────────── Audit SEC-5 regression — template render cap ────────────────

    @Test fun `SafetyCallTemplate CUSTOM caps custom message at MAX_CUSTOM_MESSAGE_LENGTH (SEC-5)`() {
        // SEC-5 — tampered DataStore could persist a CUSTOM message > 140c.
        // The UI ViewModel cap is the first line of defense ; render() now
        // re-caps as defense in depth so a multi-segment SMS surprise is avoided.
        val oversized = "x".repeat(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH + 50)
        val rendered = SafetyCallTemplate.CUSTOM.render(
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            customMessage = oversized,
        )
        assertThat(rendered.length).isAtMost(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH)
    }
}
