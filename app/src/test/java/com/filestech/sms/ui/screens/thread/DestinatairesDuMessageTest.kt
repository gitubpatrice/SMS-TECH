package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (issue #76) — ce que la fenêtre des destinataires montre, et ce qu'elle conseille.
 *
 * L'utilisateur est MARQUÉ, jamais retiré : on ne sait pas toujours le reconnaître, et une liste tantôt
 * complète, tantôt amputée mentirait sans le dire. L'aide nomme le réglage qui manque, dans l'ordre où
 * `GroupMmsMembers` les exige : le mode groupe, puis « Mon numéro ».
 */
class DestinatairesDuMessageTest {

    @Test
    fun `l'utilisateur est marque, dans l'ordre de l'en-tete`() {
        val liste = destinatairesAffiches(listOf("+331", "+332", "+333")) { it == "+332" }

        assertThat(liste).containsExactly(
            DestinataireAffiche("+331", estMoi = false),
            DestinataireAffiche("+332", estMoi = true),
            DestinataireAffiche("+333", estMoi = false),
        ).inOrder()
    }

    /** Contrôle négatif : numéro inconnu, personne n'est marqué et personne n'est retiré. */
    @Test
    fun `sans mon numero, personne n'est marque ni retire`() {
        val liste = destinatairesAffiches(listOf("+331", "+332")) { false }

        assertThat(liste.map { it.numero }).containsExactly("+331", "+332").inOrder()
        assertThat(liste.none { it.estMoi }).isTrue()
    }

    /** La clé d'identité, pas l'écriture brute : « Mon numéro » saisi en national reconnaît le +33 de l'en-tête. */
    @Test
    fun `preparer - l'utilisateur reconnu a sa cle, pas a son ecriture`() {
        val cle: (String) -> String = { brut -> brut.filter { it.isDigit() }.takeLast(9) }

        val ui = checkNotNull(
            preparerDestinataires(
                destinataires = listOf("+33607231541", "+33698765432"),
                envoye = false,
                monNumero = "06 07 23 15 41",
                mmsDeGroupe = true,
                cle = cle,
            ),
        )

        assertThat(ui.destinataires.map { it.estMoi }).containsExactly(true, false).inOrder()
        assertThat(ui.aide).isEqualTo(AideGroupe.DEJA_REGLE)
    }

    /** Contrôle négatif : « Mon numéro » vide ou sans clé, personne n'est marqué, et l'aide le demande. */
    @Test
    fun `preparer - sans mon numero, personne n'est marque`() {
        val bruts = listOf("+33607231541", "+33698765432")

        for (monNumero in listOf(null, "", "   ")) {
            val ui = checkNotNull(preparer(bruts, monNumero = monNumero, mmsDeGroupe = true))
            assertThat(ui.destinataires.none { it.estMoi }).isTrue()
            assertThat(ui.aide).isEqualTo(AideGroupe.RENSEIGNER_MON_NUMERO)
        }
        val sansCle = checkNotNull(preparerDestinataires(bruts, false, "+33607231541", true, cle = null))
        assertThat(sansCle.destinataires.none { it.estMoi }).isTrue()
    }

    @Test
    fun `preparer - un message envoye n'a pas d'aide aux reglages`() {
        val ui = preparer(listOf("+331", "+332"), envoye = true)

        assertThat(ui?.aide).isEqualTo(AideGroupe.ENVOI_SORTANT)
    }

    @Test
    fun `preparer - un seul destinataire, pas de fenetre`() {
        assertThat(preparer(listOf("+331"))).isNull()
    }

    private fun preparer(
        bruts: List<String>,
        envoye: Boolean = false,
        monNumero: String? = null,
        mmsDeGroupe: Boolean = false,
    ) = preparerDestinataires(bruts, envoye, monNumero, mmsDeGroupe) { it }

    @Test
    fun `l'aide nomme le reglage qui manque`() {
        assertThat(aideGroupe(mmsDeGroupe = false, monNumeroConnu = false)).isEqualTo(AideGroupe.ACTIVER_MMS_DE_GROUPE)
        assertThat(aideGroupe(mmsDeGroupe = false, monNumeroConnu = true)).isEqualTo(AideGroupe.ACTIVER_MMS_DE_GROUPE)
        assertThat(aideGroupe(mmsDeGroupe = true, monNumeroConnu = false)).isEqualTo(AideGroupe.RENSEIGNER_MON_NUMERO)
        assertThat(aideGroupe(mmsDeGroupe = true, monNumeroConnu = true)).isEqualTo(AideGroupe.DEJA_REGLE)
    }
}
