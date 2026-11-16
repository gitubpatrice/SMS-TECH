package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0) — **une alerte qui échoue sur la SIM choisie repart par l'autre.**
 *
 * L'échec d'une SIM sans réseau n'est pas une exception : il arrive par le verdict de la radio. Ces tests font
 * échouer une SIM précise — refus immédiat, verdict d'échec, ou silence — et regardent par quelles SIM chaque contact
 * a été servi. Ce qui ne doit PAS repartir est aussi mesuré : un message sans verdict (il peut encore partir), un
 * contact bloqué par l'utilisateur, et toute bascule quand on ne connaît qu'une SIM.
 */
class EnvoiDAlerteTest {

    private val sim1 = SimActive(subId = 11, emplacement = 0, nom = "Orange")
    private val sim2 = SimActive(subId = 22, emplacement = 1, nom = "Free")
    private val alice = PhoneAddress.of("+33611111111")
    private val bob = PhoneAddress.of("+33622222222")

    private class Reglages(defaultSubId: Int?) : AppSettingsSource {
        private val valeur = AppSettings(sending = SendingSettings(defaultSubId = defaultSubId))
        override val flow: Flow<AppSettings> = flowOf(valeur)
        override val state: StateFlow<AppSettings> = MutableStateFlow(valeur)
        override suspend fun hydratedOrNull(): AppSettings = valeur
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    /** Envoie, et note la SIM de chaque envoi. Une SIM de [refusImmediat] est refusée par `SmsManager`. */
    private class Expediteur(private val refusImmediat: Set<Int?> = emptySet()) : SmsSender {
        val sims = mutableListOf<Int?>()
        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
        ): Outcome<Unit> {
            sims += subId
            return if (subId in refusImmediat) radioFailure() else Outcome.Success(Unit)
        }
    }

    /** Le verdict de la radio selon la SIM de la ligne : échec pour [echec], rien pour [muette], sinon parti. */
    private class Radio(
        private val echec: Set<Int?> = emptySet(),
        private val muette: Set<Int?> = emptySet(),
        private val base: NoopMirror = NoopMirror(),
    ) : OutgoingMessageMirror by base {
        private val simDeLaLigne = mutableMapOf<Long, Int?>()

        override suspend fun upsertOutgoingSms(
            address: String,
            body: String,
            date: Long,
            telephonyUri: String?,
            subId: Int?,
            initialStatus: MessageStatus,
            replyToMessageId: Long?,
            localMirrorBody: String?,
        ): Long = base.upsertOutgoingSms(
            address,
            body,
            date,
            telephonyUri,
            subId,
            initialStatus,
            replyToMessageId,
            localMirrorBody,
        ).also { simDeLaLigne[it] = subId }

        override suspend fun outgoingStatus(localId: Long): MessageStatus {
            val sim = simDeLaLigne[localId]
            return when (sim) {
                in muette -> MessageStatus.PENDING
                in echec -> MessageStatus.FAILED
                else -> MessageStatus.SENT
            }
        }
    }

    private fun envoi(
        reglage: Int?,
        sims: SimsActives,
        expediteur: Expediteur,
        radio: Radio,
        bloques: BlockedNumberRepository = NeverBlocked(),
    ): EnvoiDAlerte {
        val reglages = Reglages(reglage)
        return EnvoiDAlerte(
            sendSms = sendSmsUseCase(reglages, expediteur, mirror = radio, blocked = bloques, sims = sims),
            mirror = radio,
            simDEnvoi = SimDEnvoi(sims, Dispatchers.Unconfined),
            sims = sims,
            settings = reglages,
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `la SIM choisie a du reseau, personne ne repart`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio())
            .envoyer(listOf(alice, bob), "ALERTE")

        assertThat(bilan).isEqualTo(
            EnvoiDAlerte.Bilan(parties = 2, echouees = 0, sansVerdict = 0, parAutreSim = 0, interrompu = false),
        )
        assertThat(expediteur.sims).containsExactly(11, 11)
    }

    @Test
    fun `sans reseau sur la SIM choisie, l'alerte repart par l'autre`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(echec = setOf(11)))
            .envoyer(listOf(alice, bob), "ALERTE")

        assertThat(bilan).isEqualTo(
            EnvoiDAlerte.Bilan(parties = 2, echouees = 0, sansVerdict = 0, parAutreSim = 2, interrompu = false),
        )
        assertThat(expediteur.sims).containsExactly(11, 11, 22, 22).inOrder()
    }

    @Test
    fun `un refus immediat de la SIM choisie repart aussi par l'autre`() = runTest {
        val expediteur = Expediteur(refusImmediat = setOf(11))
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio())
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.parAutreSim).isEqualTo(1)
        assertThat(bilan.echouees).isEqualTo(0)
        assertThat(expediteur.sims).containsExactly(11, 22).inOrder()
    }

    @Test
    fun `l'autre SIM echoue aussi, le contact compte une fois parmi les echecs`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(echec = setOf(11, 22)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan).isEqualTo(
            EnvoiDAlerte.Bilan(parties = 0, echouees = 1, sansVerdict = 0, parAutreSim = 0, interrompu = false),
        )
        assertThat(expediteur.sims).containsExactly(11, 22).inOrder()
    }

    @Test
    fun `sans verdict de la radio, rien ne repart, l'alerte peut encore partir`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(muette = setOf(11)))
            .envoyer(listOf(alice, bob), "ALERTE")

        assertThat(bilan.sansVerdict).isEqualTo(2)
        assertThat(bilan.parAutreSim).isEqualTo(0)
        assertThat(expediteur.sims).containsExactly(11, 11)
    }

    @Test
    fun `une seule SIM connue, pas de bascule`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsConnues(listOf(sim1)), expediteur, Radio(echec = setOf(11)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(11)
    }

    @Test
    fun `une seule SIM et Android qui demande a chaque fois, pas de second envoi sur la meme SIM`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(null, SimsConnues(listOf(sim1), android = null), expediteur, Radio(echec = setOf(null)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(null)
    }

    @Test
    fun `liste des SIM illisible, pas de bascule`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(11, SimsInconnues, expediteur, Radio(echec = setOf(11)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(11)
    }

    @Test
    fun `reglage « Comme Android », l'autre SIM est celle qu'Android n'utilise pas`() = runTest {
        val expediteur = Expediteur()
        // Sans SIM demandée, l'envoi part sur la SIM SMS d'Android (subId nul côté application).
        val bilan = envoi(null, SimsConnues(listOf(sim1, sim2), android = 22), expediteur, Radio(echec = setOf(null)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.parAutreSim).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(null, 11).inOrder()
    }

    @Test
    fun `un contact bloque par l'utilisateur ne repart pas par l'autre SIM`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(
            11,
            SimsConnues(listOf(sim1, sim2)),
            expediteur,
            Radio(),
            bloques = BlockedList(setOf(alice.raw)),
        ).envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(bilan.parAutreSim).isEqualTo(0)
        assertThat(expediteur.sims).isEmpty()
    }

    @Test
    fun `une interruption arrete tout, renvoi compris`() = runTest {
        val expediteur = Expediteur()
        var autorises = 2
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(echec = setOf(11)))
            .envoyer(listOf(alice, bob), "ALERTE", avantChaqueEnvoi = { autorises-- > 0 })

        assertThat(bilan.interrompu).isTrue()
        // Les deux premiers envois, puis l'arrêt avant le premier renvoi.
        assertThat(expediteur.sims).containsExactly(11, 11)
    }
}
