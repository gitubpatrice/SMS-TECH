package com.filestech.sms.data.sync

import com.filestech.sms.data.sms.MMS_MSG_BOX_DRAFT
import com.filestech.sms.data.sms.MMS_MSG_BOX_FAILED
import com.filestech.sms.data.sms.MMS_MSG_BOX_INBOX
import com.filestech.sms.data.sms.MMS_MSG_BOX_OUTBOX
import com.filestech.sms.data.sms.MMS_MSG_BOX_SENT
import com.filestech.sms.data.sms.TelephonyReader.EtatMms
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-19 (issue #98) — le curseur des nouveaux MMS et sa liste d'attente.
 *
 * Ce que ces cas fixent : le curseur avance toujours, mais ne fait rien PERDRE — une ligne non terminée qu'il passe
 * (brouillon, envoi en cours, copie en cours d'écriture) attend et s'importe une fois terminée, même après plus d'une
 * heure (le brouillon sauté des trois relectures) ; une ligne disparue ou trop vieille sort de l'attente ; la liste
 * reste bornée.
 */
class CurseurMmsTest {

    private val maintenant = 1_800_000_000_000L

    private fun etat(id: Long, boite: Int) = EtatMms(id, boite, maintenant / 1000L)

    private fun avancer(
        apres: Long,
        nouveaux: List<EtatMms>,
        attente: List<IdDate> = emptyList(),
        etatsDeLAttente: List<EtatMms> = emptyList(),
    ) = CurseurMms.avancer(apres, nouveaux, attente, etatsDeLAttente, maintenant)

    @Test
    fun `sans ligne nouvelle, rien ne bouge`() {
        val avance = avancer(10L, emptyList())
        assertThat(avance.jusqua).isEqualTo(10L)
        assertThat(avance.aImporter).isEmpty()
        assertThat(avance.attente).isEmpty()
    }

    @Test
    fun `des lignes terminees font avancer le curseur sans rien mettre en attente`() {
        val nouveaux = listOf(etat(11, MMS_MSG_BOX_INBOX), etat(12, MMS_MSG_BOX_SENT), etat(14, MMS_MSG_BOX_INBOX))
        val avance = avancer(10L, nouveaux)
        assertThat(avance.jusqua).isEqualTo(14L)
        assertThat(avance.attente).isEmpty()
    }

    /** Une autre application peut renvoyer un échec sous le même identifiant (relecture Claude, 2e vague). */
    @Test
    fun `un echec n'est pas termine, il attend`() {
        val avance = avancer(10L, listOf(etat(11, MMS_MSG_BOX_FAILED)))
        assertThat(avance.jusqua).isEqualTo(11L)
        assertThat(avance.attente.map { it.id }).containsExactly(11L)
    }

    /** Contrôle négatif : une règle qui passerait un brouillon sans le noter le perdrait une fois envoyé. */
    @Test
    fun `un brouillon et un envoi en cours sont passes mais mis en attente`() {
        val nouveaux = listOf(etat(11, MMS_MSG_BOX_DRAFT), etat(12, MMS_MSG_BOX_OUTBOX), etat(13, MMS_MSG_BOX_INBOX))
        val avance = avancer(10L, nouveaux)
        assertThat(avance.jusqua).isEqualTo(13L)
        assertThat(avance.attente.map { it.id }).containsExactly(11L, 12L)
        assertThat(avance.attente.map { it.ms }.toSet()).containsExactly(maintenant)
    }

    /** Le cas des trois relectures : le brouillon devenu « envoyé » sous le même identifiant, bien après. */
    @Test
    fun `une ligne en attente devenue terminee est a importer et sort de l'attente`() {
        val attente = listOf(IdDate(11L, maintenant - 5L * 24 * 3600 * 1000), IdDate(12L, maintenant))
        val etats = listOf(etat(11, MMS_MSG_BOX_SENT), etat(12, MMS_MSG_BOX_OUTBOX))
        val avance = avancer(13L, emptyList(), attente, etats)
        assertThat(avance.aImporter).containsExactly(11L)
        assertThat(avance.attente.map { it.id }).containsExactly(12L)
        assertThat(avance.jusqua).isEqualTo(13L)
    }

    @Test
    fun `une ligne en attente supprimee en sort`() {
        val avance = avancer(13L, emptyList(), listOf(IdDate(11L, maintenant)), emptyList())
        assertThat(avance.aImporter).isEmpty()
        assertThat(avance.attente).isEmpty()
    }

    @Test
    fun `une ligne qui ne se termine pas sort de l'attente au bout d'un mois`() {
        val vieille = IdDate(11L, maintenant - CurseurMms.ATTENTE_MAX_MS)
        val recente = IdDate(12L, maintenant - CurseurMms.ATTENTE_MAX_MS + 1)
        val avance = avancer(
            13L,
            emptyList(),
            listOf(vieille, recente),
            listOf(etat(11, MMS_MSG_BOX_DRAFT), etat(12, MMS_MSG_BOX_DRAFT)),
        )
        assertThat(avance.attente).containsExactly(recente)
    }

    @Test
    fun `une ligne deja en attente n'y entre pas deux fois, et l'attente garde sa premiere date`() {
        val dejaLa = IdDate(11L, maintenant - 1_000L)
        val enCours = listOf(etat(11, MMS_MSG_BOX_OUTBOX))
        val avance = avancer(10L, enCours, listOf(dejaLa), enCours)
        assertThat(avance.attente).containsExactly(dejaLa)
    }

    @Test
    fun `l'attente reste bornee aux plus recentes`() {
        val nouveaux = (1L..(CurseurMms.TAILLE_MAX + 50L)).map { etat(10_000L + it, MMS_MSG_BOX_DRAFT) }
        val anciens = listOf(IdDate(1L, maintenant - 10_000L))
        val avance = avancer(10_000L, nouveaux, anciens, listOf(etat(1L, MMS_MSG_BOX_DRAFT)))
        assertThat(avance.attente).hasSize(CurseurMms.TAILLE_MAX)
        assertThat(avance.attente.map { it.id }).doesNotContain(1L)
    }

    /**
     * Borner, c'est perdre ce qu'on retire (relecture GPT, 2e vague) : les brouillons partent d'abord — un envoi en
     * cours, même plus ancien, est gardé. Contrôle négatif : un tri par date seule ferait partir l'envoi.
     */
    @Test
    fun `au-dela de la borne, ce sont les brouillons qui partent, jamais un envoi en cours`() {
        val brouillons = (1L..CurseurMms.TAILLE_MAX.toLong()).map { etat(10_000L + it, MMS_MSG_BOX_DRAFT) }
        val envoiAncien = IdDate(5L, maintenant - 1_000L)
        val avance = avancer(10_000L, brouillons, listOf(envoiAncien), listOf(etat(5L, MMS_MSG_BOX_OUTBOX)))
        assertThat(avance.attente).hasSize(CurseurMms.TAILLE_MAX)
        assertThat(avance.attente).contains(envoiAncien)
    }

    @Test
    fun `ajouter a l'attente dedoublonne, garde la premiere date, et borne`() {
        val dejaLa = IdDate(7L, maintenant - 5_000L)
        val ajoutee = CurseurMms.ajouter(listOf(dejaLa), listOf(7L, 8L, 8L), maintenant)
        assertThat(ajoutee).containsExactly(IdDate(8L, maintenant), dejaLa)
        val beaucoup = CurseurMms.ajouter(emptyList(), (1L..(CurseurMms.TAILLE_MAX + 10L)).toList(), maintenant)
        assertThat(beaucoup).hasSize(CurseurMms.TAILLE_MAX)
    }

    @Test
    fun `le curseur ne recule jamais`() {
        assertThat(avancer(10L, listOf(etat(9, MMS_MSG_BOX_INBOX))).jusqua).isEqualTo(10L)
    }

    @Test
    fun `une valeur d'attente illisible est ignoree`() {
        assertThat(IdDate.decoder("12:34")).isEqualTo(IdDate(12L, 34L))
        assertThat(IdDate.decoder("12")).isNull()
        assertThat(IdDate.decoder("a:34")).isNull()
        assertThat(IdDate.decoder(IdDate(5L, 6L).encoder())).isEqualTo(IdDate(5L, 6L))
    }
}
