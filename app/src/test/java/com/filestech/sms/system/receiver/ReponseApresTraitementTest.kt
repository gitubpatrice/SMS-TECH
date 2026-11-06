package com.filestech.sms.system.receiver

import com.filestech.sms.pdu.PduHeaders
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — la réponse au MMSC une fois un MMS téléchargé traité.
 *
 * Le retrait a eu lieu : `retrieved`, quel que soit le sort du message — écrit, déjà là, écrit sans un média
 * que la reprise rattrapera, écarté faute d'adresse. Une exception : l'expéditeur bloqué, à qui rien n'est
 * répondu, comme à l'annonce (relecture Claude du 2026-11-06 : « retrieved » pourrait valoir accusé de
 * remise auprès de celui qu'on a bloqué).
 */
class ReponseApresTraitementTest {

    @Test
    fun `message ecrit, deja la, ou ecarte sans adresse - retrieved`() {
        listOf(
            TraitementMmsRecu.Issue(garderLePdu = false, consigner = true),
            TraitementMmsRecu.Issue(garderLePdu = true, consigner = true),
            TraitementMmsRecu.Issue(garderLePdu = false, consigner = false),
        ).forEach { issue ->
            assertThat(reponseApresTraitement(issue)).isEqualTo(PduHeaders.STATUS_RETRIEVED)
        }
    }

    @Test
    fun `expediteur bloque - aucune reponse`() {
        val issue = TraitementMmsRecu.Issue(garderLePdu = false, consigner = false, expediteurBloque = true)

        assertThat(reponseApresTraitement(issue)).isNull()
    }

    /** Jamais `rejected` après un retrait : ce statut est réservé à un message que l'app ne peut pas recevoir. */
    @Test
    fun `jamais rejected apres un retrait`() {
        listOf(true, false).forEach { bloque ->
            val issue = TraitementMmsRecu.Issue(garderLePdu = false, consigner = !bloque, expediteurBloque = bloque)
            assertThat(reponseApresTraitement(issue)).isNotEqualTo(PduHeaders.STATUS_REJECTED)
        }
    }
}
