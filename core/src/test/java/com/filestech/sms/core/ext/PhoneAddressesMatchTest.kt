package com.filestech.sms.core.ext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 (audit Codex du 2026-08-05, P-11) — [phoneAddressesMatch] est la **seule** règle de
 * rapprochement de conversation, des quatre côtés : composition, import SMS, import MMS, réaction
 * entrante.
 *
 * # 🔴 Ce que ces tests empêchent
 *
 * [blockKey] retient neuf chiffres — ce qu'il faut pour réunir `06 12 34 56 78` et `+33612345678`,
 * mais neuf chiffres ne portent **aucune information de pays** :
 *
 * ```
 * "+33 6 12 34 56 78"  →  612345678
 * "+1 561 234 5678"    →  612345678     ← même clé, DEUX PERSONNES
 * ```
 *
 * Quand l'égalité stricte du CSV échouait, le repli prenait la première conversation portant cette
 * clé. Composer vers le numéro américain ouvrait donc la conversation française, **et le message
 * rédigé partait au mauvais destinataire** ; à l'import, les deux historiques fusionnaient.
 *
 * Les deux bords comptent, et ils tirent en sens opposés : refuser trop rapproche mal (doublons de
 * conversations, historique éclaté), accepter trop envoie au mauvais correspondant. Le second est
 * sans commune mesure, mais aucun des deux n'est acceptable — d'où les tests de non-régression du
 * cas national ↔ international ci-dessous.
 */
class PhoneAddressesMatchTest {

    @Test
    fun `deux indicatifs pays differents ne se rapprochent JAMAIS`() {
        // Non-vacuité : les deux partagent bien la même clé de neuf chiffres.
        assertThat("+33612345678".blockKey()).isEqualTo("+15612345678".blockKey())
        // LE POINT : ils ne désignent pourtant pas la même personne.
        assertThat(phoneAddressesMatch("+33 6 12 34 56 78", "+1 561 234 5678")).isFalse()
    }

    @Test
    fun `le meme numero international se rapproche de lui-meme, quelle que soit la mise en forme`() {
        assertThat(phoneAddressesMatch("+33612345678", "+33 6 12 34 56 78")).isTrue()
        assertThat(phoneAddressesMatch("+33612345678", "+33-6-12-34-56-78")).isTrue()
    }

    /**
     * Le cas pour lequel le suffixe existe, et le seul où il est légitime : une seule des deux
     * formes porte un indicatif. Sans ce rapprochement, un SMS reçu en `0612345678` créerait une
     * seconde conversation à côté de celle importée en `+33612345678`.
     */
    @Test
    fun `national et international du meme numero se rapprochent toujours`() {
        assertThat(phoneAddressesMatch("0612345678", "+33612345678")).isTrue()
        assertThat(phoneAddressesMatch("+33612345678", "0612345678")).isTrue()
        assertThat(phoneAddressesMatch("612345678", "+33612345678")).isTrue()
    }

    /**
     * Non-régression de l'audit H13 (v1.26.1) : huit chiffres amputaient le chiffre qui sépare un
     * `06…` d'un `07…`. Deux personnes différentes partageaient leur clé.
     */
    @Test
    fun `deux mobiles nationaux distincts ne se rapprochent pas`() {
        assertThat(phoneAddressesMatch("0612345678", "0712345678")).isFalse()
    }

    /**
     * Non-régression de l'audit H5 : l'en-tête `From:` d'un PDU porte `/TYPE=PLMN`, et [blockKey]
     * bascule en mode alphanumérique dès qu'il voit une lettre. L'oublier d'un seul côté rendait le
     * rapprochement **silencieusement inopérant** — le nettoyage est donc fait ici, aux deux bords.
     */
    @Test
    fun `le suffixe de passerelle MMS est retire des deux cotes`() {
        assertThat(phoneAddressesMatch("+33612345678/TYPE=PLMN", "+33612345678")).isTrue()
        assertThat(phoneAddressesMatch("+33612345678/TYPE=PLMN", "0612345678")).isTrue()
        // Et la désambiguïsation par indicatif survit au nettoyage.
        assertThat(phoneAddressesMatch("+33612345678/TYPE=PLMN", "+15612345678")).isFalse()
    }

    @Test
    fun `deux expediteurs alphanumeriques se comparent sur leur libelle`() {
        assertThat(phoneAddressesMatch("Free", "FREE")).isTrue()
        assertThat(phoneAddressesMatch("SFR", "SFR 123")).isFalse()
        assertThat(phoneAddressesMatch("SFR 123", "123")).isFalse()
    }

    @Test
    fun `un code court se compare a l identique`() {
        assertThat(phoneAddressesMatch("3646", "3646")).isTrue()
        assertThat(phoneAddressesMatch("3646", "3945")).isFalse()
    }
}
