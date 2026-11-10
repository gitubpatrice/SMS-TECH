package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * 2026-11-10 (v1.30.1) — le choix de la SIM d'envoi ([ChoixDeSim]) et sa validation ([SimDEnvoi]).
 *
 * Avant la 1.30.1, tout partait par la SIM SMS d'Android : un utilisateur à deux SIM répondait par la SIM 1 à
 * un message reçu sur la SIM 2, sans moyen de choisir.
 */
class ChoixDeSimTest {

    private val sim1 = SimActive(subId = 11, emplacement = 0, nom = "Orange")
    private val sim2 = SimActive(subId = 22, emplacement = 1, nom = "Free")
    private val deux = listOf(sim1, sim2)

    // ── ChoixDeSim.pourConversation ──

    @Test
    fun `une seule SIM ne choisit rien, deux SIM donnent toujours une SIM precise`() {
        assertThat(ChoixDeSim.pourConversation(listOf(sim1), historique = listOf(11), reglage = 11, simAndroid = 11))
            .isNull()
        assertThat(ChoixDeSim.pourConversation(emptyList(), listOf(11), 11, 11)).isNull()
        // Contrôle positif : avec deux SIM et rien d'autre, une SIM est rendue.
        assertThat(ChoixDeSim.pourConversation(deux, emptyList(), reglage = null, simAndroid = null)).isEqualTo(11)
    }

    @Test
    fun `on repond par la SIM du dernier message qui en porte une encore active`() {
        // Du plus récent au plus ancien : sans SIM, puis une SIM retirée (99), puis la SIM 2.
        val historique = listOf(null, 99, 22, 11)
        assertThat(ChoixDeSim.pourConversation(deux, historique, reglage = 11, simAndroid = 11)).isEqualTo(22)
    }

    @Test
    fun `sans historique, le reglage s'il est actif, sinon la SIM d'Android`() {
        assertThat(ChoixDeSim.pourConversation(deux, emptyList(), reglage = 22, simAndroid = 11)).isEqualTo(22)
        // Réglage sur une SIM retirée : on retombe sur Android.
        assertThat(ChoixDeSim.pourConversation(deux, listOf(null), reglage = 99, simAndroid = 22)).isEqualTo(22)
    }

    @Test
    fun `Android sur toujours demander, la premiere SIM par emplacement`() {
        // Liste volontairement dans le désordre : c'est l'emplacement qui compte, pas l'ordre rendu.
        assertThat(ChoixDeSim.pourConversation(listOf(sim2, sim1), emptyList(), reglage = null, simAndroid = null))
            .isEqualTo(11)
    }

    // ── ChoixDeSim.valider ──

    @Test
    fun `une SIM active passe, une SIM retiree laisse Android choisir`() {
        assertThat(ChoixDeSim.valider(22, deux)).isEqualTo(22)
        assertThat(ChoixDeSim.valider(99, deux)).isNull()
        assertThat(ChoixDeSim.valider(null, deux)).isNull()
    }

    @Test
    fun `liste inconnue, la demande passe telle quelle`() {
        // Permission Téléphone refusée : on ne sait pas contredire la demande.
        assertThat(ChoixDeSim.valider(99, emptyList())).isEqualTo(99)
    }

    // ── SimDEnvoi, à travers le vrai SendSmsUseCase ──

    private class Reglages(defaultSubId: Int?) : AppSettingsSource {
        private val valeur = AppSettings(sending = SendingSettings(defaultSubId = defaultSubId))
        override val flow: Flow<AppSettings> = flowOf(valeur)
        override val state: StateFlow<AppSettings> = MutableStateFlow(valeur)
        override suspend fun hydratedOrNull(): AppSettings = valeur
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun envoyer(reglage: Int?, sims: List<SimActive>, demande: Int? = null): RecordingSender {
        val sender = RecordingSender()
        val issue = runBlocking {
            sendSmsUseCase(Reglages(reglage), sender, sims = SimsConnues(sims))
                .invoke(recipients = listOf(PhoneAddress.of("+33611111111")), body = "Bonjour", subId = demande)
        }
        assertThat(issue).isInstanceOf(Outcome.Success::class.java)
        assertThat(sender.callCount).isEqualTo(1)
        return sender
    }

    @Test
    fun `le reglage sur une SIM retiree n'empeche plus l'envoi`() {
        // Réglé sur la SIM 2, puis la SIM 2 a été retirée : le SMS part par la SIM d'Android.
        assertThat(envoyer(reglage = 22, sims = listOf(sim1)).lastSubId).isNull()
    }

    @Test
    fun `le reglage sur une SIM presente est applique, et la SIM demandee l'emporte`() {
        assertThat(envoyer(reglage = 22, sims = deux).lastSubId).isEqualTo(22)
        assertThat(envoyer(reglage = 22, sims = deux, demande = 11).lastSubId).isEqualTo(11)
    }

    @Test
    fun `sans liste de SIM, rien ne change par rapport a avant`() {
        assertThat(envoyer(reglage = 22, sims = emptyList()).lastSubId).isEqualTo(22)
    }

    @Test
    fun `aucune lecture des SIM quand rien n'est demande`() {
        var lectures = 0
        val compteur = object : com.filestech.sms.domain.sender.SimsActives {
            override fun lister(): List<SimActive> {
                lectures++
                return deux
            }
            override fun simSmsDAndroid(): Int? = null
        }
        val resultat = runBlocking { SimDEnvoi(compteur, Dispatchers.Unconfined).pourEnvoi(null, AppSettings()) }
        assertThat(resultat).isNull()
        assertThat(lectures).isEqualTo(0)
        // Contrôle positif : une demande déclenche bien la lecture.
        runBlocking { SimDEnvoi(compteur, Dispatchers.Unconfined).pourEnvoi(22, AppSettings()) }
        assertThat(lectures).isEqualTo(1)
    }
}
