package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.1 (revue externe GitLab !38458, 3e passe, constat 2) — la table de decision qui precede
 * une suppression destructrice.
 *
 * # Pourquoi ce test existe alors qu'un test instrumente couvre deja le chemin
 *
 * `SystemRowIdentityTest` ecrit de vrais SMS et verifie ce qui reste. Il est indispensable — un
 * faux fournisseur affirmerait ce qu'on veut bien lui faire dire. Mais **mesure du 2026-09-08** :
 * en remettant l'ancienne politique (`UNKNOWN` traite comme `MATCH`), il restait VERT. La raison
 * est mecanique : sous une identite invérifiable, la suppression echouait de toute facon, si bien
 * que les deux politiques produisaient le meme effet observable.
 *
 * Un test qui ne peut pas echouer ne prouve rien. La regle se prouve donc ici, ou elle est une
 * simple table, et ou une inversion se voit immediatement.
 */
class SystemRowMatchPolicyTest {

    @Test
    fun `une ligne absente est deja partie, sans rien tenter`() {
        assertThat(SystemRowMatch.ABSENT.issueSansToucherAuFournisseur).isTrue()
    }

    @Test
    fun `une ligne prouvee identique est la seule qu'on tente de supprimer`() {
        // `null` = « aucune conclusion sans toucher au fournisseur », donc on tente.
        assertThat(SystemRowMatch.MATCH.issueSansToucherAuFournisseur).isNull()
    }

    @Test
    fun `une ligne qui decrit un autre message n'est pas touchee`() {
        assertThat(SystemRowMatch.MISMATCH.issueSansToucherAuFournisseur).isFalse()
    }

    @Test
    fun `une identite inverifiable echoue du cote sur`() {
        // LE point de la v1.28.1. Avant elle, `UNKNOWN` partageait la branche de `MATCH` et
        // declenchait une suppression sur une ligne dont rien ne disait qu'elle etait la notre.
        assertThat(SystemRowMatch.UNKNOWN.issueSansToucherAuFournisseur).isFalse()
    }

    @Test
    fun `une seule valeur sur quatre autorise a toucher au fournisseur`() {
        // Garde de completude : si une cinquieme valeur apparait un jour, elle devra choisir son
        // camp explicitement plutot que d'heriter du comportement du voisin — c'est ainsi que
        // `UNKNOWN` avait glisse du cote destructeur.
        val quiTentent = SystemRowMatch.entries.filter { it.issueSansToucherAuFournisseur == null }
        assertThat(quiTentent).containsExactly(SystemRowMatch.MATCH)
    }
}
