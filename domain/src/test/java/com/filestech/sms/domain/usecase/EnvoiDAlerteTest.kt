package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.notification.NotificationDEchecDEnvoi
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

    /** Les notifications d'échec retirées, par identifiant de message. */
    private val retirees = mutableListOf<Long>()
    private val notifications = object : NotificationDEchecDEnvoi {
        override fun retirer(messageId: Long) {
            retirees += messageId
        }
    }

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
        /** SIM dont le message se lit d'abord « parti », puis « en échec » (accusés de parties dans le désordre). */
        private val echecTardif: Set<Int?> = emptySet(),
        /** Ce qui se passe pendant l'attente du verdict, donc entre deux passages. */
        private val pendantLAttente: () -> Unit = {},
    ) : OutgoingMessageMirror by base {
        private val simDeLaLigne = mutableMapOf<Long, Int?>()
        private val lectures = mutableMapOf<Long, Int>()

        /** Les lignes écrites : un envoi refusé à un destinataire bloqué en laisse une, sans rien envoyer. */
        val lignes get() = base.lignes

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
            pendantLAttente()
            val sim = simDeLaLigne[localId]
            val lecture = lectures.merge(localId, 1, Int::plus)!!
            if (sim in echecTardif) return if (lecture == 1) MessageStatus.SENT else MessageStatus.FAILED
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
            notificationsDEchec = notifications,
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
        val radio = Radio()
        val bilan = envoi(
            11,
            SimsConnues(listOf(sim1, sim2)),
            expediteur,
            radio,
            bloques = BlockedList(setOf(alice.raw)),
        ).envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(bilan.parAutreSim).isEqualTo(0)
        assertThat(expediteur.sims).isEmpty()
        // Une seule tentative, refusée : pas de second essai par l'autre SIM (il laisserait une seconde trace).
        assertThat(radio.lignes).hasSize(1)
    }

    @Test
    fun `une interruption arrete tout, renvoi compris`() = runTest {
        val expediteur = Expediteur()
        var autorises = 2
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(echec = setOf(11)))
            .envoyer(listOf(alice, bob), "ALERTE", avantChaqueEnvoi = { autorises-- > 0 })

        assertThat(bilan.interrompu).isTrue()
        // Interrompu pendant le renvoi : les deux échecs du premier passage restent comptés.
        assertThat(bilan.echouees).isEqualTo(2)
        // Les deux premiers envois, puis l'arrêt avant le premier renvoi.
        assertThat(expediteur.sims).containsExactly(11, 11)
    }

    /** Relecture Claude du 2026-11-16 : la notification « échec » restait, avec son bouton Renvoyer. */
    @Test
    fun `la notification d'echec du premier envoi est retiree quand l'autre SIM a servi`() = runTest {
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), Expediteur(), Radio(echec = setOf(11)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.parAutreSim).isEqualTo(1)
        // La ligne 1 est l'envoi en échec par la SIM 11 ; la 2, le renvoi parti par la 22.
        assertThat(retirees).containsExactly(1L)
    }

    @Test
    fun `aucune notification retiree quand l'autre SIM echoue aussi`() = runTest {
        envoi(11, SimsConnues(listOf(sim1, sim2)), Expediteur(), Radio(echec = setOf(11, 22)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(retirees).isEmpty()
    }

    /** Relectures GPT, Gemini et Claude du 2026-11-16 : il sortait du bilan. */
    @Test
    fun `un contact bloque pendant le renvoi compte parmi les echecs`() = runTest {
        // Bloqué pendant l'attente du verdict du premier passage, donc avant le renvoi.
        var bloque = false
        val bloqueApresLePremierPassage = object : BlockedNumberRepository by BlockedList(emptySet()) {
            override suspend fun isBlocked(rawNumber: String): Boolean = bloque
            override suspend fun blockedRawSnapshot(): List<String> = if (bloque) listOf(alice.raw) else emptyList()
        }
        val expediteur = Expediteur()
        val bilan = envoi(
            reglage = 11,
            sims = SimsConnues(listOf(sim1, sim2)),
            expediteur = expediteur,
            radio = Radio(echec = setOf(11), pendantLAttente = { bloque = true }),
            bloques = bloqueApresLePremierPassage,
        )
            .envoyer(listOf(alice), "ALERTE")

        // Parti une fois par la 11 (en échec), refusé au renvoi : un échec, et aucun second envoi.
        assertThat(expediteur.sims).containsExactly(11)
        assertThat(bilan.echouees).isEqualTo(1)
        assertThat(bilan.parties + bilan.sansVerdict).isEqualTo(0)
    }

    /** Relecture Claude du 2026-11-16 : sans première SIM connue, le renvoi a pu repartir par elle. */
    @Test
    fun `Android qui demande a chaque fois, le renvoi n'est pas annonce comme passe par l'autre SIM`() = runTest {
        val expediteur = Expediteur()
        val bilan = envoi(null, SimsConnues(listOf(sim1, sim2), android = null), expediteur, Radio(echec = setOf(null)))
            .envoyer(listOf(alice), "ALERTE")

        assertThat(bilan.parties).isEqualTo(1)
        assertThat(bilan.parAutreSim).isEqualTo(0)
        assertThat(expediteur.sims).containsExactly(null, 11).inOrder()
    }

    /**
     * Revue de sécurité du 2026-11-16 : l'accusé de la dernière partie d'un SMS long peut précéder celui d'une partie en
     * échec. Le « parti » lu d'abord est relu : l'échec arrivé entre-temps fait repartir le contact par l'autre SIM.
     */
    @Test
    fun `un parti d'un message long qui passe en echec a la relecture repart par l'autre SIM`() = runTest {
        val expediteur = Expediteur()
        val long = "ALERTE ".repeat(20)
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio(echecTardif = setOf(11)))
            .envoyer(listOf(alice), long)

        assertThat(bilan.parAutreSim).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(11, 22).inOrder()
    }

    @Test
    fun `une interruption au premier passage garde ce qui est deja parti`() = runTest {
        val expediteur = Expediteur()
        var autorises = 1
        val bilan = envoi(11, SimsConnues(listOf(sim1, sim2)), expediteur, Radio())
            .envoyer(listOf(alice, bob), "ALERTE", avantChaqueEnvoi = { autorises-- > 0 })

        assertThat(bilan.interrompu).isTrue()
        assertThat(bilan.sansVerdict).isEqualTo(1)
        assertThat(expediteur.sims).containsExactly(11)
    }
}
