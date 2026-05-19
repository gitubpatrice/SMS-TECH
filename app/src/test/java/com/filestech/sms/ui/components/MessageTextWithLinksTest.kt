package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.3.2 — garde de sécurité sur le whitelist scheme de [toSafeHttpsTargetOrNull].
 * Sans cette validation, une URL avec `javascript:` ou `intent:` détectée par
 * `Patterns.WEB_URL` dans un message reçu malicieux pourrait être rendue cliquable
 * et ouvrir une intent système hostile via [androidx.compose.ui.platform.UriHandler].
 *
 * Les cas dépendant de `android.util.Patterns` (regex Android non disponible en JVM
 * pure sans Robolectric) sont reportés v1.3.3 — la garde scheme reste vérifiée ici
 * de manière isolée, c'est la défense critique.
 */
class MessageTextWithLinksTest {

    @Test fun `https URL is preserved as-is`() {
        assertThat("https://google.com".toSafeHttpsTargetOrNull()).isEqualTo("https://google.com")
        assertThat("https://example.org/path?q=1".toSafeHttpsTargetOrNull())
            .isEqualTo("https://example.org/path?q=1")
    }

    @Test fun `http URL is preserved (legacy sites)`() {
        assertThat("http://legacy.com".toSafeHttpsTargetOrNull()).isEqualTo("http://legacy.com")
    }

    @Test fun `https and http detection is case-insensitive`() {
        // Le scheme peut être en majuscules dans certains SMS (recopiés d'un mail).
        assertThat("HTTPS://X.COM".toSafeHttpsTargetOrNull()).isEqualTo("HTTPS://X.COM")
        assertThat("Http://Y.com".toSafeHttpsTargetOrNull()).isEqualTo("Http://Y.com")
    }

    @Test fun `bare domain gets https prepended`() {
        assertThat("google.com".toSafeHttpsTargetOrNull()).isEqualTo("https://google.com")
        assertThat("sub.example.org/path".toSafeHttpsTargetOrNull())
            .isEqualTo("https://sub.example.org/path")
    }

    @Test fun `javascript scheme is refused`() {
        // Le critique : un message recu malicieux contenant "javascript:alert(1)"
        // ne doit JAMAIS produire un LinkAnnotation.Url cliquable.
        assertThat("javascript:alert(1)".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `data scheme is refused`() {
        assertThat("data:text/html,<script>alert(1)</script>".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `intent scheme is refused`() {
        // Android intent: scheme peut router vers une activité hostile.
        assertThat("intent://x.y#Intent;scheme=http;end".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `file scheme is refused`() {
        assertThat("file:///etc/passwd".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `content scheme is refused`() {
        assertThat("content://com.evil.app/data".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `tel scheme is refused`() {
        // Réservé aux gestes téléphone explicites, pas aux URLs dans le body.
        assertThat("tel:+33612345678".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `domain with path containing colon does not trigger exotic-scheme detection`() {
        // Edge case : un path peut contenir un `:` après le premier `/` (ex. UUID
        // typographique). Le test "scheme = avant le premier /" évite ce faux positif.
        val out = "example.com/path:foo/bar".toSafeHttpsTargetOrNull()
        assertThat(out).isEqualTo("https://example.com/path:foo/bar")
    }

    // ──────────────── v1.3.11 (F4) — phone link helper unit tests ────────────────
    //
    // The full phone-detection path (`collectLinkHits` → `Patterns.PHONE.matcher`) requires
    // `android.util.Patterns` and would need Robolectric to run in pure JVM. We still
    // pin the **two purely-Kotlin helpers** that gate the phone-link promotion decision
    // so a future refactor cannot silently widen the band filter:
    //
    //   - [countDigits] : counts the digits inside a substring range — the value compared
    //     against [PHONE_DIGITS_MIN] / [PHONE_DIGITS_MAX].
    //   - [PHONE_DIGITS_MIN] / [PHONE_DIGITS_MAX] : the digit-count band itself.
    //
    // Together these ensure that even if `Patterns.PHONE` matches a 4-digit promo code or
    // a 20-digit IBAN, the link is filtered out before [LinkAnnotation.Clickable] is
    // emitted.

    @Test fun `countDigits counts only ASCII digits within the substring range`() {
        assertThat(countDigits("+33 612 34 56 78", 0, 16)).isEqualTo(11)
        assertThat(countDigits("Order #12345", 0, 12)).isEqualTo(5)
        assertThat(countDigits("no digits here", 0, 14)).isEqualTo(0)
        assertThat(countDigits("abc 123 def 456", 4, 7)).isEqualTo(3)
    }

    @Test fun `countDigits handles empty range without throwing`() {
        assertThat(countDigits("anything", 3, 3)).isEqualTo(0)
    }

    @Test fun `phone digit band rejects too-short and too-long sequences`() {
        // The band MUST stay at [7, 15] — anything lower lets order IDs / promo codes
        // through, anything higher accepts IBANs / credit-card numbers.
        assertThat(PHONE_DIGITS_MIN).isEqualTo(7)
        assertThat(PHONE_DIGITS_MAX).isEqualTo(15)
        // Sanity: a 6-digit count is below the floor, 16 is above the ceiling.
        assertThat(6 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isFalse()
        assertThat(16 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isFalse()
        // Common French national + international formats fall inside the band.
        assertThat(10 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isTrue() // 06 12 34 56 78
        assertThat(11 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isTrue() // +33 6 12 34 56 78
    }
}
