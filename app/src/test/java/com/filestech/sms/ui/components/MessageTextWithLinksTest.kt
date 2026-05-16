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
}
