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

    // ════════════════ v1.28.12 — les trois pays livrés avec les trois langues ════════════════
    //
    // Avant cette version, les trois listes (mots d'urgence, numéros surtaxés, domaines
    // officiels) ne connaissaient que la France. La version allemande de l'application
    // ANNONÇAIT pourtant détecter « e1ster.de » — alors qu'elster.de n'était nulle part.
    // Les cas ci-dessous auraient tous été manqués en silence.

    @Test fun `arnaque ALLEMANDE - elster usurpe, mot d urgence allemand`() {
        val body = "Dringend: Ihre Steuererklärung. Konto gesperrt. " +
            "Jetzt bestätigen: https://e1ster.de/login"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    @Test fun `arnaque ALLEMANDE - numero surtaxe 0900 plus urgence`() {
        val body = "Ihr Paket konnte nicht zugestellt werden. Rufen Sie 0900 123456 an."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.PremiumNumber)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    @Test fun `arnaque ITALIENNE - agence des impots usurpee, urgence italienne`() {
        val body = "Conto bloccato. Verifichi i suoi dati su agenziaentrata.gov.it " +
            "tramite https://bit.ly/9aZ1"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
        assertThat(verdict.reasons).contains(SmishingReason.UrlShortener)
    }

    @Test fun `arnaque ITALIENNE - numero surtaxe 899 plus urgence`() {
        val body = "Pacco in attesa di spese doganali. Chiami subito 899 123 456."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.PremiumNumber)
    }

    @Test fun `arnaque ESPAGNOLE - correos usurpe, urgence espagnole`() {
        val body = "Paquete retenido. Tarjeta bloqueada. Confirme sus datos en correros.es"
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.TyposquattedDomain)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    @Test fun `arnaque ESPAGNOLE - numero surtaxe 806 plus urgence`() {
        val body = "Cuenta suspendida. Llame al 806 123 456 inmediatamente."
        val verdict = SmishingDetector.analyze(body)
        assertThat(verdict.shouldWarn).isTrue()
        assertThat(verdict.reasons).contains(SmishingReason.PremiumNumber)
        assertThat(verdict.reasons).contains(SmishingReason.UrgencyKeyword)
    }

    // ──────────────── Les contrôles négatifs : rien de légitime ne doit rougir ────────────────
    //
    // C'est ici que ce genre d'élargissement fait des dégâts. Trois listes de plus, c'est
    // trois fois plus d'occasions de coller un bandeau rouge sur un SMS parfaitement normal.

    @Test fun `les domaines officiels EXACTS des quatre pays ne sont jamais signales`() {
        val legitimes = listOf(
            "Votre avis est disponible sur impots.gouv.fr",
            "Ihre Rechnung finden Sie auf telekom.de",
            "La sua bolletta è su unicredit.it",
            "Su factura está en iberdrola.es",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `un SMS allemand parfaitement normal ne declenche rien`() {
        val body = "Hallo, wir sehen uns um 18 Uhr am Bahnhof. Bis später!"
        assertThat(SmishingDetector.analyze(body).shouldWarn).isFalse()
    }

    @Test fun `un SMS italien parfaitement normal ne declenche rien`() {
        val body = "Ciao, ci vediamo alle 18 in stazione. A dopo!"
        assertThat(SmishingDetector.analyze(body).shouldWarn).isFalse()
    }

    @Test fun `un SMS espagnol parfaitement normal ne declenche rien`() {
        val body = "Hola, nos vemos a las 18 en la estación. ¡Hasta luego!"
        assertThat(SmishingDetector.analyze(body).shouldWarn).isFalse()
    }

    @Test fun `un numero de telephone ordinaire n est pas pris pour un surtaxe`() {
        // Les nouvelles plages sont ancrees sur des longueurs precises. Un mobile
        // francais, allemand, italien ou espagnol ordinaire ne doit rien declencher.
        val ordinaires = listOf(
            "Rappelle-moi au 06 12 34 56 78",
            "Ruf mich an: 0151 23456789",
            "Chiamami al 333 1234567",
            "Llámame al 612 345 678",
        )
        for (body in ordinaires) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `un montant ou une reference ne devient pas un numero surtaxe`() {
        // Le 118xx allemand (renseignements) est VOLONTAIREMENT absent des motifs :
        // cinq chiffres se confondent avec une reference ou un montant. Ce test
        // verrouille cette abstention.
        val body = "Ihre Bestellung 11833 wurde versandt. Betrag: 806123 Cent."
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.PremiumNumber)
    }
}
