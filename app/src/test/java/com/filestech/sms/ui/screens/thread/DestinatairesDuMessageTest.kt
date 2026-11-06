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

    @Test
    fun `l'aide nomme le reglage qui manque`() {
        assertThat(aideGroupe(mmsDeGroupe = false, monNumeroConnu = false)).isEqualTo(AideGroupe.ACTIVER_MMS_DE_GROUPE)
        assertThat(aideGroupe(mmsDeGroupe = false, monNumeroConnu = true)).isEqualTo(AideGroupe.ACTIVER_MMS_DE_GROUPE)
        assertThat(aideGroupe(mmsDeGroupe = true, monNumeroConnu = false)).isEqualTo(AideGroupe.RENSEIGNER_MON_NUMERO)
        assertThat(aideGroupe(mmsDeGroupe = true, monNumeroConnu = true)).isEqualTo(AideGroupe.DEJA_REGLE)
    }
}
