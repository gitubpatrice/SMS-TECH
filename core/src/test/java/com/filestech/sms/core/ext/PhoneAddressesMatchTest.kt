package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 (audit Codex du 2026-08-05, P-11 puis **C-07**) — [phoneAddressesMatch] est la **seule**
 * règle de rapprochement de conversation, des quatre côtés : composition, import SMS, import MMS,
 * réaction entrante. C'est aussi elle qui décide si un expéditeur est bloqué.
 *
 * # 🔴 Ce que ces tests empêchent
 *
 * [blockKey] retient neuf chiffres, qui ne portent **aucune information de pays** :
 *
 * ```
 * "+33 6 12 34 56 78"  →  612345678
 * "+1 561 234 5678"    →  612345678
 * "+1 212 345 6789"    →  123456789
 * "01 23 45 67 89"     →  123456789
 * ```
 *
 * Quand l'égalité stricte du CSV échouait, le repli prenait la première conversation portant cette
 * clé. Composer vers le numéro américain ouvrait donc la conversation française, **et le message
 * rédigé partait au mauvais destinataire** ; à l'import, les deux historiques fusionnaient ; côté
 * liste noire, bloquer l'un faisait rejeter les messages de l'autre — et **définitivement**, le
 * curseur d'import avançant sur la ligne écartée.
 *
 * ⚠️ La première correction ne traitait que le cas où **les deux** formes portaient un `+`. Les
 * deux dernières lignes du tableau ci-dessus lui échappaient : une forme nationale suffisait à
 * rouvrir la collision. C'est le motif du **jumeau asymétrique**, dans le correctif censé le
 * fermer. La règle est désormais l'égalité **E.164**, qui traite les quatre combinaisons d'un coup.
 */
class PhoneAddressesMatchTest {

    /**
     * Doublure de `PhoneNumberUtils.formatNumberToE164(number, "FR")` — région française, la seule
     * dont ces tests ont besoin. Elle n'émule que les issues réellement en jeu ; `core` n'a pas de
     * dépendance Android, et c'est tout l'intérêt d'injecter le convertisseur.
     */
    private fun frE164(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            // Déjà international : la forme canonique est elle-même.
            trimmed.startsWith('+') && digits.length in 8..15 -> "+$digits"
            // National français : `0X XX XX XX XX` → `+33XXXXXXXXX`.
            digits.length == 10 && digits.startsWith("0") -> "+33" + digits.drop(1)
            // Codes courts, libellés alphanumériques, numéros invalides pour la région.
            else -> null
        }
    }

    private fun match(a: String, b: String) = phoneAddressesMatch(a, b) { frE164(it) }

    /**
     * 🔴 **C-07** — le cas que la première correction laissait passer : une seule des deux formes
     * porte un `+`. Codex l'a établi en exécutant la fonction compilée.
     */
    @Test
    fun `un international etranger ne se rapproche pas d un national francais`() {
        // Non-vacuité : les deux partagent bien la même clé de neuf chiffres.
        assertThat("+12123456789".blockKey()).isEqualTo("0123456789".blockKey())
        // LE POINT : ils ne désignent pourtant pas la même personne.
        assertThat(match("+1 212 345 6789", "01 23 45 67 89")).isFalse()
        assertThat(match("01 23 45 67 89", "+1 212 345 6789")).isFalse()
    }

    @Test
    fun `deux indicatifs pays differents ne se rapprochent JAMAIS`() {
        assertThat("+33612345678".blockKey()).isEqualTo("+15612345678".blockKey())
        assertThat(match("+33 6 12 34 56 78", "+1 561 234 5678")).isFalse()
    }

    @Test
    fun `le meme numero international se rapproche de lui-meme, quelle que soit la mise en forme`() {
        assertThat(match("+33612345678", "+33 6 12 34 56 78")).isTrue()
        assertThat(match("+33612345678", "+33-6-12-34-56-78")).isTrue()
    }

    /**
     * Le cas pour lequel le repli existe : une seule des deux formes porte un indicatif, et elles
     * désignent bien la même personne. Sans ce rapprochement, un SMS reçu en `0612345678` créerait
     * une seconde conversation à côté de celle importée en `+33612345678`.
     */
    @Test
    fun `national et international du meme numero se rapprochent toujours`() {
        assertThat(match("0612345678", "+33612345678")).isTrue()
        assertThat(match("+33612345678", "0612345678")).isTrue()
    }

    /**
     * Non-régression de l'audit H13 (v1.26.1) : huit chiffres amputaient le chiffre qui sépare un
     * `06…` d'un `07…`. Deux personnes différentes partageaient leur clé.
     */
    @Test
    fun `deux mobiles nationaux distincts ne se rapprochent pas`() {
        assertThat(match("0612345678", "0712345678")).isFalse()
    }

    /**
     * ⚠️ **L'échec est FERMÉ quand la canonicalisation est impossible** — région indéterminable,
     * numéro invalide. Ne pas rapprocher crée au pire une conversation en double, visible et
     * réparable ; rapprocher à tort envoie un message privé à quelqu'un d'autre.
     */
    @Test
    fun `sans canonicalisation possible, on refuse le rapprochement`() {
        val sansRegion = { _: String -> null as String? }
        assertThat(phoneAddressesMatch("0612345678", "+33612345678", sansRegion)).isFalse()
        // Non-vacuité : avec la région, la même paire se rapproche.
        assertThat(match("0612345678", "+33612345678")).isTrue()
    }

    /**
     * Non-régression de l'audit H5 : l'en-tête `From:` d'un PDU porte `/TYPE=PLMN`, et [blockKey]
     * bascule en mode alphanumérique dès qu'il voit une lettre. L'oublier d'un seul côté rendait le
     * rapprochement **silencieusement inopérant** — le nettoyage est donc fait ici, aux deux bords.
     */
    @Test
    fun `le suffixe de passerelle MMS est retire des deux cotes`() {
        assertThat(match("+33612345678/TYPE=PLMN", "+33612345678")).isTrue()
        assertThat(match("+33612345678/TYPE=PLMN", "0612345678")).isTrue()
        // Et la désambiguïsation par indicatif survit au nettoyage.
        assertThat(match("+33612345678/TYPE=PLMN", "+15612345678")).isFalse()
    }

    /**
     * Libellés alphanumériques et codes courts : il n'y a **pas de pays à départager**, la clé est
     * l'identité. Sans cette voie, l'échec fermé sur `toE164 == null` aurait cassé le
     * rapprochement de « Free » avec lui-même.
     */
    @Test
    fun `expediteurs alphanumeriques et codes courts se comparent sur leur libelle`() {
        assertThat(match("Free", "FREE")).isTrue()
        assertThat(match("SFR", "SFR 123")).isFalse()
        assertThat(match("SFR 123", "123")).isFalse()
        assertThat(match("3646", "3646")).isTrue()
        assertThat(match("3646", "3945")).isFalse()
    }
}
