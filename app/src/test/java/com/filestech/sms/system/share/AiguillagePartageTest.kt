package com.filestech.sms.system.share

import com.filestech.sms.system.share.AiguillagePartage.Decision
import com.filestech.sms.system.share.IncomingShareHolder.Cible
import com.filestech.sms.system.share.IncomingShareHolder.Pending
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-20 (courriel : « Transférer » n'écrit rien dans la conversation choisie) — **ce qu'AppRoot fait d'un dépôt.**
 *
 * La garde SEC-03 (v1.4.1) efface un partage arrivé pendant qu'un fil est ouvert. Elle effaçait aussi les transferts,
 * lancés depuis un fil : c'est le défaut. Ce que ces cas fixent : un transfert et le texte d'un lien `sms:` ne sont
 * jamais aiguillés ici ; un vrai partage garde SEC-03 et l'ouverture de « Nouveau message ».
 */
class AiguillagePartageTest {

    private val maintenant = 1_800_000_000_000L
    private val fil = "com.filestech.sms.ui.navigation.Thread/{conversationId}"
    private val liste = "com.filestech.sms.ui.navigation.Conversations"

    private fun depot(cible: Cible, ilYaMs: Long = 0L) =
        Pending(uris = emptyList(), mimeType = null, text = "Bonjour", postedAt = maintenant - ilYaMs, cible = cible)

    private fun decider(pending: Pending?, route: String?, verrouille: Boolean = false, leurre: Boolean = false) =
        AiguillagePartage.decider(pending, route, verrouille, leurre, maintenant)

    /** Le défaut : contrôle négatif, l'ancienne garde rend EFFACER ici. */
    @Test
    fun `un transfert lance depuis un fil n'est pas efface`() {
        assertThat(decider(depot(Cible.Conversation(7L)), fil)).isEqualTo(Decision.RIEN)
        assertThat(decider(depot(Cible.EnAttente), fil)).isEqualTo(Decision.RIEN)
        assertThat(decider(depot(Cible.EnAttente), "com.filestech.sms.ui.navigation.Compose")).isEqualTo(Decision.RIEN)
    }

    @Test
    fun `le texte d'un lien sms arrive depuis la liste n'ouvre pas Nouveau message`() {
        assertThat(decider(depot(Cible.EnAttente), liste)).isEqualTo(Decision.RIEN)
    }

    @Test
    fun `un partage d'une autre application garde SEC-03 et l'ouverture de Nouveau message`() {
        assertThat(decider(depot(Cible.AChoisir), fil)).isEqualTo(Decision.EFFACER)
        assertThat(decider(depot(Cible.AChoisir), liste)).isEqualTo(Decision.NOUVEAU_MESSAGE)
    }

    @Test
    fun `verrou, leurre ou depot absent ne declenchent rien`() {
        assertThat(decider(null, liste)).isEqualTo(Decision.RIEN)
        assertThat(decider(depot(Cible.AChoisir), liste, verrouille = true)).isEqualTo(Decision.RIEN)
        assertThat(decider(depot(Cible.AChoisir), liste, leurre = true)).isEqualTo(Decision.RIEN)
    }

    /** Relecture Claude : un dépôt expiré n'irait plus nulle part ; il ne reste pas dans le relais. */
    @Test
    fun `un depot expire est efface, quelle que soit sa cible`() {
        val partage = depot(Cible.AChoisir, ilYaMs = IncomingShareHolder.PENDING_TTL_MS + 1)
        val transfert = depot(Cible.Conversation(7L), ilYaMs = IncomingShareHolder.PENDING_TTL_MS + 1)
        assertThat(decider(partage, liste, verrouille = true)).isEqualTo(Decision.EFFACER)
        assertThat(decider(transfert, fil)).isEqualTo(Decision.EFFACER)
    }
}
