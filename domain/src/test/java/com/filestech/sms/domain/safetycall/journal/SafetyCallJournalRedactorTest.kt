package com.filestech.sms.domain.safetycall.journal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.0 — verrouille la réduction des destinataires dans le journal technique.
 *
 * # Ce qui compte ici
 *
 * Le jeton doit tenir **deux promesses opposées** en même temps, et un test qui n'en vérifierait
 * qu'une laisserait passer la moitié des accidents.
 *
 * 1. **Identité.** Deux lignes visant le même proche doivent porter le même jeton, quelle que soit
 *    la forme sous laquelle le numéro a été saisi. C'est la seule façon de prouver qu'aucun contact
 *    n'a reçu deux fois la même relance — et c'est précisément le doute qu'un test sur appareil a
 *    exigé de lever à la main, en relevant deux boîtes SMS, le 2026-08-06.
 * 2. **Non-lisibilité.** Le numéro ne doit pas se lire dans le jeton, sel mis à part.
 *
 * # 🔴 Le défaut que cette classe verrouille désormais
 *
 * Une première version normalisait par `blockKey()` — la clé de la **liste noire**, qui ne retient
 * que les neuf derniers chiffres et ne porte donc aucune information de pays. Deux personnes
 * différentes obtenaient le même jeton, et le journal aurait lu un doublon là où il n'y en avait pas.
 *
 * Et les tests d'origine ne le voyaient pas : ils n'utilisaient que des numéros **français**, dont
 * le numéro national significatif fait justement neuf chiffres. Le défaut ne pouvait pas apparaître.
 * C'est la définition du test vert sur un chemin mort — il rassurait sans rien exercer. La paire
 * [FR_MOBILE] / [US_MOBILE] est là pour ça : sous `blockKey()`, elle produit **exactement le même
 * jeton**, chiffres de queue compris.
 *
 * # Et surtout : le repli échoue du bon côté
 *
 * Sans sel, la tentation serait de retomber sur le numéro en clair « puisque c'est du diagnostic ».
 * Ce serait le motif de défaut le plus fréquent du dépôt — *le repli qui échoue du mauvais côté* —
 * appliqué à une fonction de contrainte. Le test le verrouille dans le sens sûr.
 */
class SafetyCallJournalRedactorTest {

    private companion object {
        /** 32 caractères hexadécimaux, la forme attendue d'un sel de 16 octets. */
        const val SALT = "0123456789abcdef0123456789abcdef"
        const val OTHER_SALT = "fedcba9876543210fedcba9876543210"
        const val NATIONAL = "0607231541"
        const val INTERNATIONAL = "+33607231541"
        const val SPACED = "06 07 23 15 41"
        const val OTHER_NUMBER = "0660146767"

        /**
         * Paire de collision sous `blockKey()` : les neuf derniers chiffres **et** les deux derniers
         * sont identiques, seul l'indicatif pays les sépare. Deux personnes distinctes.
         */
        const val FR_MOBILE = "+33612345678"
        const val US_MOBILE = "+15612345678"
    }

    /**
     * Canonicalisation E.164 d'un appareil en région française, réduite à ce que les tests exercent.
     * Rend `null` quand elle est impossible — c'est le contrat de `phoneIdentityKey`.
     */
    private val toE164: (String) -> String? = { raw ->
        val digits = raw.filter { it.isDigit() }
        when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.length == 10 && digits.startsWith("0") -> "+33" + digits.drop(1)
            else -> null
        }
    }

    private fun redact(address: String, salt: String = SALT) =
        SafetyCallJournalRedactor.redact(address, salt, toE164)

    @Test
    fun `le meme proche donne le meme jeton quelle que soit la forme du numero`() {
        val national = redact(NATIONAL)

        assertThat(redact(INTERNATIONAL)).isEqualTo(national)
        assertThat(redact(SPACED)).isEqualTo(national)
    }

    @Test
    fun `deux proches distincts donnent deux jetons distincts`() {
        assertThat(redact(OTHER_NUMBER)).isNotEqualTo(redact(NATIONAL))
    }

    @Test
    fun `deux pays differents ne se confondent pas malgre des chiffres de fin identiques`() {
        // ⚠️ Non-régression. Sous `blockKey()`, ces deux numéros rendaient le MÊME jeton : neuf
        // derniers chiffres identiques (612345678) et mêmes deux chiffres de queue (78). Le journal
        // aurait affirmé qu'un seul proche avait été contacté deux fois, alors que deux personnes
        // distinctes avaient été alertées une fois chacune.
        assertThat(FR_MOBILE.filter { it.isDigit() }.takeLast(9))
            .isEqualTo(US_MOBILE.filter { it.isDigit() }.takeLast(9))

        assertThat(redact(US_MOBILE)).isNotEqualTo(redact(FR_MOBILE))
    }

    @Test
    fun `un numero non canonicalisable ne se rabat pas sur neuf chiffres`() {
        // Résolveur en échec : `phoneIdentityKey` garde alors les chiffres d'une forme explicitement
        // internationale, préfixés — surtout pas le suffixe de neuf chiffres, qui rouvrirait la
        // collision entre pays.
        val failing: (String) -> String? = { null }

        val fr = SafetyCallJournalRedactor.redact(FR_MOBILE, SALT, failing)
        val us = SafetyCallJournalRedactor.redact(US_MOBILE, SALT, failing)

        assertThat(us).isNotEqualTo(fr)
    }

    @Test
    fun `le numero n'est pas lisible dans le jeton`() {
        val token = redact(NATIONAL)

        // Seuls les deux derniers chiffres sont conservés, délibérément, pour que le journal reste
        // lisible à l'œil. Tout le reste du numéro doit être absent.
        assertThat(token).doesNotContain("0607231")
        assertThat(token).doesNotContain("607231")
        assertThat(token).endsWith("41")
    }

    @Test
    fun `changer de sel change le jeton du meme numero`() {
        // C'est ce qui rend un journal exporté non recoupable avec un autre appareil.
        assertThat(redact(NATIONAL, OTHER_SALT)).isNotEqualTo(redact(NATIONAL, SALT))
    }

    @Test
    fun `sans sel le repli est opaque et ne laisse fuir aucun chiffre`() {
        assertThat(redact(NATIONAL, "")).isEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
        assertThat(redact(NATIONAL, "trop court")).isEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
        assertThat(SafetyCallJournalRedactor.OPAQUE_TOKEN).doesNotContain("41")
        assertThat(SafetyCallJournalRedactor.OPAQUE_TOKEN.any { it.isDigit() }).isFalse()
    }

    @Test
    fun `un sel a la longueur minimale exacte est accepte`() {
        val exact = "a".repeat(SafetyCallJournalRedactor.SALT_MIN_LENGTH)

        assertThat(redact(NATIONAL, exact)).isNotEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
    }

    @Test
    fun `une adresse vide donne le jeton opaque`() {
        assertThat(redact("")).isEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
        assertThat(redact("   ")).isEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
    }

    @Test
    fun `un expediteur alphanumerique est reduit sans lever`() {
        val token = redact("InfoRED")

        assertThat(token).isNotEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN)
        // `phoneIdentityKey` passe un libellé alphanumérique en minuscules : la queue est donc « ed ».
        // Ce que ce test verrouille surtout, c'est qu'aucun chemin ne lève.
        assertThat(token).endsWith("ed")
    }

    @Test
    fun `le jeton est de longueur stable`() {
        // Un journal aligné se lit en diagonale ; une longueur variable le rendrait illisible.
        val tokens = listOf(NATIONAL, OTHER_NUMBER, INTERNATIONAL, US_MOBILE, "InfoRED")
            .map { redact(it) }

        assertThat(tokens.map { it.length }.distinct()).hasSize(1)
        assertThat(tokens.first().length).isEqualTo(SafetyCallJournalRedactor.OPAQUE_TOKEN.length)
    }

    @Test
    fun `le jeton ne contient aucun caractere interdit par le format de ligne`() {
        // Sinon l'assainissement de [SafetyCallJournalEntry] le mutilerait, et deux destinataires
        // différents pourraient se retrouver avec le même jeton tronqué.
        val token = redact(NATIONAL)
        val line = SafetyCallJournalEntry(
            wallMs = 1L,
            elapsedMs = 1L,
            generation = 1L,
            claimId = 1L,
            event = SafetyCallJournalEvent.SEND,
            subject = "1/1",
            details = "to=$token",
        ).format()

        assertThat(SafetyCallJournalEntry.parse(line)?.details).isEqualTo("to=$token")
    }
}
