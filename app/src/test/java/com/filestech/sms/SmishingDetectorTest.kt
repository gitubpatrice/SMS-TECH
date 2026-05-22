package com.filestech.sms

import com.filestech.sms.domain.smishing.SmishingDetector
import com.filestech.sms.domain.smishing.SmishingReason
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.11.0 — Sujet 3 : tests garde-régression du détecteur anti-smishing.
 *
 * **Priorité doctrine** : on minimise les FAUX POSITIFS (un SMS légitime
 * de la banque ou des impôts NE DOIT PAS afficher de bandeau rouge). Le
 * seuil par défaut est calibré à 2 heuristiques positives — un seul
 * indicateur suspect ne suffit jamais.
 */
class SmishingDetectorTest {

    // ──────────────── Cas véritables : DOIVENT trigger ────────────────

    @Test fun `colissimo phishing typique (typosquatting + urgence + URL shortener)`() {
        val body = "Votre colis est bloqué ! Frais de livraison non payés. " +
            "Régulariser sous 24h : https://bit.ly/3xY9z2 colissimo-track.fr"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
        assertThat(verdict.reasons).contains(SmishingReason.UrlShortener)
    }

    @Test fun `fake impots avec typosquatting et urgence`() {
        val body = "Vous avez des impôts impayés. Régularisez sur https://1mpots.gouv.fr/payer " +
            "avant minuit pour éviter la majoration."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    @Test fun `numero premium plus mot urgence trigger`() {
        val body = "URGENT : votre compte est suspendu. Rappelez le 0899 12 34 56 immédiatement."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.PremiumNumber)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    @Test fun `URL shortener plus typosquatting trigger`() {
        val body = "Mettre à jour vos coordonnées paypa1.fr/update via http://t.co/abc1"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.UrlShortener)
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
    }

    @Test fun `scam Amazon EN avec urgence et shortener`() {
        val body = "Your account has been locked. Verify your identity now: https://bit.ly/3xyZ"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.UrlShortener)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    // ──────────────── Faux positifs FR : NE DOIVENT PAS trigger ────────────────

    @Test fun `SMS de la banque officiel sans typosquatting`() {
        val body = "Crédit Agricole : votre virement de 500€ vers Marie a bien été enregistré."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isFalse()
    }

    @Test fun `SMS impots officiel avec domaine exact`() {
        val body = "Service des impôts : votre avis 2024 est disponible sur https://impots.gouv.fr"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isFalse()
    }

    @Test fun `SMS quotidien sans indicateurs`() {
        val body = "Salut, on se voit ce soir au cinéma à 20h ?"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isFalse()
        assertThat(verdict.score).isEqualTo(0)
    }

    @Test fun `un seul mot urgent isole ne trigger pas (1 heuristique sous seuil)`() {
        // Seuil = 2, donc une seule heuristique positive ne suffit pas.
        val body = "Urgent : tu peux me rappeler quand tu peux ?"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isFalse()
        assertThat(verdict.score).isEqualTo(1)
        assertThat(verdict.reasons).containsExactly(SmishingReason.UrgencyKeyword)
    }

    @Test fun `un seul shortener isole ne trigger pas`() {
        val body = "Regarde cette vidéo trop drôle : https://bit.ly/abcXY"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isFalse()
        assertThat(verdict.score).isEqualTo(1)
    }

    @Test fun `numero mobile FR 06 ne trigger pas premium`() {
        val body = "Rappelle-moi au 06 12 34 56 78 dès que tu peux."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.reasons).doesNotContain(SmishingReason.PremiumNumber)
    }

    @Test fun `numero vert 0800 ne trigger pas premium`() {
        val body = "Pour assistance gratuite, appelez le 0800 123 456 (numéro vert)."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.reasons).doesNotContain(SmishingReason.PremiumNumber)
    }

    @Test fun `domaine ameli officiel ne trigger pas typosquatting`() {
        val body = "Votre attestation est disponible sur https://ameli.fr/connexion"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.reasons).doesNotContain(SmishingReason.TyposquattedDomain)
    }

    @Test fun `colissimo officiel ne trigger pas typosquatting`() {
        val body = "Votre colis sera livré demain. Suivez-le sur https://colissimo.fr"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.reasons).doesNotContain(SmishingReason.TyposquattedDomain)
    }

    @Test fun `body vide retourne score zero`() {
        val verdict = SmishingDetector.analyze("")
        assertThat(verdict.score).isEqualTo(0)
        assertThat(verdict.shouldWarn).isFalse()
    }

    @Test fun `body blanc retourne score zero`() {
        val verdict = SmishingDetector.analyze("    \n\n   ")
        assertThat(verdict.score).isEqualTo(0)
        assertThat(verdict.shouldWarn).isFalse()
    }

    // ──────────────── Levenshtein helper ────────────────

    @Test fun `levenshteinAtMost detects 1-char substitution`() {
        // impots vs 1mpots → distance 1 (substitution).
        assertThat(SmishingDetector.levenshteinAtMost("1mpots", "impots", 2)).isTrue()
    }

    @Test fun `levenshteinAtMost detects 2-char substitution`() {
        // paypal vs paypa1 → distance 1. paypa11 → distance 2.
        assertThat(SmishingDetector.levenshteinAtMost("paypa1", "paypal", 2)).isTrue()
        assertThat(SmishingDetector.levenshteinAtMost("paypa11", "paypal", 2)).isTrue()
    }

    @Test fun `levenshteinAtMost rejects distance over 2`() {
        // ameli vs amazone → distance 4 (a→a, m→m, e→a, l→z, i→o, _→n, _→e).
        assertThat(SmishingDetector.levenshteinAtMost("amazone", "ameli", 2)).isFalse()
    }

    @Test fun `levenshteinAtMost handles identical strings`() {
        assertThat(SmishingDetector.levenshteinAtMost("impots", "impots", 2)).isTrue()
    }

    @Test fun `levenshteinAtMost handles empty strings`() {
        assertThat(SmishingDetector.levenshteinAtMost("", "abc", 2)).isFalse()
        assertThat(SmishingDetector.levenshteinAtMost("", "ab", 2)).isTrue()
        assertThat(SmishingDetector.levenshteinAtMost("", "", 0)).isTrue()
        // v1.11.0 audit Q2 — cas symétrique (n=3, m=0) couvre la branche
        // `if (m == 0) return n <= maxDistance` non testée auparavant.
        assertThat(SmishingDetector.levenshteinAtMost("abc", "", 2)).isFalse()
        assertThat(SmishingDetector.levenshteinAtMost("ab", "", 2)).isTrue()
    }

    // ──────────────── Sécurité : cap de longueur ────────────────

    @Test fun `body extremement long ne fait pas crasher (cap 1000c)`() {
        val huge = "urgent paypal " + "a".repeat(10_000)
        val verdict = SmishingDetector.analyze(huge)
        // Le corps est cappé en interne mais l'analyse reste valide.
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }
}
