package com.filestech.sms.domain.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (issue #76) — les destinataires gardés avec un MMS adressé à plusieurs.
 *
 * Ce que ces cas fixent : l'ordre de l'en-tête (`To` puis `Cc`) ; ni doublon ni jeton réservé d'AOSP ; un
 * seul destinataire, ce n'est pas un envoi groupé (`null`, rien n'est écrit) ; et la colonne se relit telle
 * qu'elle a été écrite.
 */
class DestinatairesMmsTest {

    private companion object {
        const val MOI = "+33607231541"
        const val PAT = "+33617332729"
        const val MARIE = "+33698765432"
    }

    @Test
    fun `to puis cc, dans l'ordre de l'en-tete`() {
        assertThat(DestinatairesMms.of(to = listOf(MOI, PAT), cc = listOf(MARIE)))
            .containsExactly(MOI, PAT, MARIE).inOrder()
    }

    @Test
    fun `un seul destinataire n'est pas un envoi groupe`() {
        assertThat(DestinatairesMms.of(to = listOf(MOI), cc = emptyList())).isNull()
        assertThat(DestinatairesMms.of(to = emptyList(), cc = emptyList())).isNull()
    }

    /** Contrôle négatif du précédent : le même destinataire écrit deux fois n'en fait pas deux. */
    @Test
    fun `un doublon ne fait pas un groupe`() {
        assertThat(DestinatairesMms.of(to = listOf(MOI, " $MOI "), cc = listOf(MOI))).isNull()
        assertThat(DestinatairesMms.of(to = listOf(MOI, PAT), cc = listOf(PAT))).containsExactly(MOI, PAT).inOrder()
    }

    @Test
    fun `jeton reserve, adresse vide ou portant le separateur sont ecartes`() {
        val r = DestinatairesMms.of(
            to = listOf("insert-address-token", "", "  ", MOI, "a;b"),
            cc = listOf(PAT),
        )

        assertThat(r).containsExactly(MOI, PAT).inOrder()
    }

    /** Un opérateur recopie parfois l'expéditeur dans `To` : il n'en devient pas un destinataire. */
    @Test
    fun `l'expediteur recopie dans to n'est pas un destinataire`() {
        assertThat(DestinatairesMms.of(to = listOf(MOI, PAT), cc = emptyList(), expediteur = PAT)).isNull()
        assertThat(DestinatairesMms.of(to = listOf(MOI, PAT, MARIE), cc = emptyList(), expediteur = PAT))
            .containsExactly(MOI, MARIE).inOrder()
    }

    /** Deux écritures du même numéro ne font qu'un destinataire : la première forme est gardée. */
    @Test
    fun `rapprochement par la cle d'identite`() {
        val cle: (String) -> String = { brut -> brut.filter { it.isDigit() }.takeLast(9) }

        val r = DestinatairesMms.of(to = listOf(MOI, "06 07 23 15 41", PAT), cc = emptyList(), cle = cle)

        assertThat(r).containsExactly(MOI, PAT).inOrder()
        // La même clé retire aussi l'expéditeur écrit autrement.
        assertThat(DestinatairesMms.of(listOf(MOI, PAT, MARIE), emptyList(), expediteur = "06 17 33 27 29", cle = cle))
            .containsExactly(MOI, MARIE).inOrder()
    }

    @Test
    fun `la colonne se relit telle qu'elle a ete ecrite`() {
        val ecrite = DestinatairesMms.encoder(listOf(MOI, PAT, MARIE))

        assertThat(ecrite).isEqualTo("$MOI;$PAT;$MARIE")
        assertThat(DestinatairesMms.decoder(ecrite)).containsExactly(MOI, PAT, MARIE).inOrder()
    }

    @Test
    fun `hors envoi groupe, rien n'est ecrit et rien n'est relu`() {
        assertThat(DestinatairesMms.encoder(null)).isNull()
        assertThat(DestinatairesMms.encoder(listOf(MOI))).isNull()
        assertThat(DestinatairesMms.decoder(null)).isEmpty()
        assertThat(DestinatairesMms.decoder(" ; ")).isEmpty()
        assertThat(DestinatairesMms.decoder(DestinatairesMms.VERIFIE_SANS_GROUPE)).isEmpty()
    }

    // 2026-11-06 — membres d'un fil de groupe du fournisseur (import en groupe).

    @Test
    fun `fil de groupe - ses membres, dans l'ordre du fournisseur`() {
        assertThat(DestinatairesMms.membresDuFil(listOf(PAT, MARIE), soi = null)).containsExactly(PAT, MARIE).inOrder()
    }

    /** Contrôle négatif : un fil à un destinataire est une 1-à-1, pas un groupe. */
    @Test
    fun `fil a un seul destinataire - pas un groupe`() {
        assertThat(DestinatairesMms.membresDuFil(listOf(PAT), soi = null)).isNull()
    }

    @Test
    fun `mon numero laisse dans le fil est retire, et le fil a deux redevient une 1-a-1`() {
        val aTrois = DestinatairesMms.membresDuFil(listOf(MOI, PAT, MARIE), soi = MOI)

        assertThat(aTrois).containsExactly(PAT, MARIE).inOrder()
        assertThat(DestinatairesMms.membresDuFil(listOf(MOI, PAT), soi = MOI)).isNull()
    }

    @Test
    fun `une meme personne ecrite deux fois ne fait pas un groupe`() {
        val cle: (String) -> String = { it.filter(Char::isDigit).takeLast(9) }

        assertThat(DestinatairesMms.membresDuFil(listOf(PAT, "06 17 33 27 29"), soi = null, cle = cle)).isNull()
    }

    /** `addresses_csv` se coupe aussi sur la virgule : une telle adresse ferait un membre de trop. */
    @Test
    fun `une adresse qui porte un separateur est ecartee`() {
        assertThat(DestinatairesMms.membresDuFil(listOf(PAT, "a,b", "c;d", MARIE), soi = null))
            .containsExactly(PAT, MARIE).inOrder()
    }
}
