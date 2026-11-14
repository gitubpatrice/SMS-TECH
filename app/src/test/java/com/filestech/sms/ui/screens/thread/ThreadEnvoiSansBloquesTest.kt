package com.filestech.sms.ui.screens.thread

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-14 (groupe de 56 sur le S24 de Patrice) — **un MMS de groupe qui écarte un membre bloqué : prévenir, puis
 * suivre.**
 *
 * Vécu : un des 56 contacts figurait dans la liste de blocage importée d'Android. Le MMS est parti aux 55 autres, et
 * rangé dans une conversation de 55 que l'application venait de créer ; le groupe d'origine est resté vide, et le
 * bandeau disait « Envoyé à 1 destinataire(s) ». Ces tests tiennent la nouvelle suite : rien ne part avant l'accord,
 * l'accord envoie, l'écran ouvre la conversation où le message est parti — et rien ne change sans membre bloqué.
 */
class ThreadEnvoiSansBloquesTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val groupe = listOf("+33600000001", "+33600000002", "+33600000003")
    private val bloque = PhoneAddress.of("+33600000003")

    private val envoyer: EnvoyerMessageUseCase = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** L'envoi rend le message 42, rangé dans la conversation [conversationDuMessage]. */
    private fun fil(ecartes: List<PhoneAddress>, conversationDuMessage: Long): ThreadViewModel {
        coEvery { envoyer.membresEcartesDuGroupe(any()) } returns ecartes
        coEvery { envoyer.invoke(any(), any(), any(), any(), any()) } returns Outcome.Success(
            SendReport(dispatched = listOf(42L), failed = emptyList(), blocked = ecartes, servis = 3 - ecartes.size),
        )
        val envoye = mockk<Message> { io.mockk.every { conversationId } returns conversationDuMessage }
        return threadViewModelDeTest(dispatcher, envoyer, adresses = groupe) {
            coEvery { findMessageById(42L) } returns envoye
        }
    }

    @Test
    fun `membre bloque - rien ne part avant l'accord, et la fenetre dit qui et combien`() = runTest(dispatcher) {
        val vm = fil(ecartes = listOf(bloque), conversationDuMessage = 99L)

        vm.updateDraft("Bonjour")
        vm.send()

        val attente = vm.state.value.envoiSansBloques
        assertThat(attente).isNotNull()
        assertThat(attente?.bloques).containsExactly("+33600000003")
        assertThat(attente?.servis).isEqualTo(2)
        assertThat(attente?.suite).isEqualTo(ThreadViewModel.SuiteEnvoi.TEXTE)
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accord - le message part, et l'ecran ouvre la conversation ou il est parti`() = runTest(dispatcher) {
        val vm = fil(ecartes = listOf(bloque), conversationDuMessage = 99L)
        val evenements = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.collect { evenements += it } }

        vm.updateDraft("Bonjour")
        vm.send()
        vm.confirmerEnvoiSansBloques()

        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), any(), any()) }
        assertThat(vm.state.value.envoiSansBloques).isNull()
        assertThat(evenements).contains(ThreadViewModel.Event.OuvrirConversation(99L))
        assertThat(evenements.filterIsInstance<ThreadViewModel.Event.PartialSend>()).isEmpty()
    }

    @Test
    fun `annuler - rien ne part, le brouillon reste`() = runTest(dispatcher) {
        val vm = fil(ecartes = listOf(bloque), conversationDuMessage = 99L)

        vm.updateDraft("Bonjour")
        vm.send()
        vm.annulerEnvoiSansBloques()

        assertThat(vm.state.value.envoiSansBloques).isNull()
        assertThat(vm.state.value.draft).isEqualTo("Bonjour")
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any()) }
    }

    /** Contrôle négatif : aucun membre bloqué — ni fenêtre, ni changement de conversation. */
    @Test
    fun `sans membre bloque - l'envoi part tout de suite, comme avant`() = runTest(dispatcher) {
        val vm = fil(ecartes = emptyList(), conversationDuMessage = 7L)
        val evenements = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.collect { evenements += it } }

        vm.updateDraft("Bonjour")
        vm.send()

        assertThat(vm.state.value.envoiSansBloques).isNull()
        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), any(), any()) }
        assertThat(evenements.filterIsInstance<ThreadViewModel.Event.OuvrirConversation>()).isEmpty()
    }

    /**
     * Relecture Claude du 2026-11-14 : la vérification des membres bloqués SUSPEND avant que l'envoi ne pose
     * `isSending` ; un second appui pendant ce temps partait aussi — deux MMS envoyés et facturés à tout le groupe.
     */
    @Test
    fun `double appui pendant la verification - un seul envoi`() = runTest(dispatcher) {
        val porte = CompletableDeferred<Unit>()
        coEvery { envoyer.membresEcartesDuGroupe(any()) } coAnswers {
            porte.await()
            emptyList()
        }
        coEvery { envoyer.invoke(any(), any(), any(), any(), any()) } returns Outcome.Success(
            SendReport(dispatched = listOf(42L), failed = emptyList(), blocked = emptyList(), servis = 3),
        )
        val vm = threadViewModelDeTest(dispatcher, envoyer, adresses = groupe)

        vm.updateDraft("Bonjour")
        vm.send()
        vm.send()
        porte.complete(Unit)

        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), any(), any()) }
    }

    /**
     * Relecture Claude du 2026-11-14 : sans fenêtre acceptée, l'écran ne part pas ailleurs. En SMS par destinataire,
     * le message rendu est celui d'un destinataire, rangé dans SA conversation : le suivre aurait été une erreur, et
     * le bandeau d'envoi partiel se serait tu.
     */
    @Test
    fun `sans avertissement - pas de navigation, le bandeau parle`() = runTest(dispatcher) {
        coEvery { envoyer.membresEcartesDuGroupe(any()) } returns emptyList()
        coEvery { envoyer.invoke(any(), any(), any(), any(), any()) } returns Outcome.Success(
            SendReport(dispatched = listOf(42L), failed = emptyList(), blocked = listOf(bloque)),
        )
        val envoye = mockk<Message> { io.mockk.every { conversationId } returns 99L }
        val vm = threadViewModelDeTest(dispatcher, envoyer, adresses = groupe) {
            coEvery { findMessageById(42L) } returns envoye
        }
        val evenements = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.collect { evenements += it } }

        vm.updateDraft("Bonjour")
        vm.send()

        assertThat(evenements.filterIsInstance<ThreadViewModel.Event.OuvrirConversation>()).isEmpty()
        assertThat(evenements).contains(ThreadViewModel.Event.PartialSend(sent = 1, failed = 0, blocked = 1))
    }

    /** Relecture Claude du 2026-11-14 : un envoi PROGRAMMÉ vers ce groupe prévient aussi, à la programmation. */
    @Test
    fun `envoi programme - prevenu avant d'etre programme`() = runTest(dispatcher) {
        coEvery { envoyer.membresEcartesDuGroupe(any()) } returns listOf(bloque)
        val programmer = mockk<ScheduleMessageUseCase>()
        coEvery { programmer.invoke(any(), any(), any(), any(), any(), any()) } returns Outcome.Success(1L)
        val vm = threadViewModelDeTest(dispatcher, envoyer, adresses = groupe, scheduleMessage = programmer)
        val echeance = System.currentTimeMillis() + 3_600_000L

        vm.updateDraft("Bonjour")
        vm.scheduleSend(echeance)

        assertThat(vm.state.value.envoiSansBloques?.suite).isEqualTo(ThreadViewModel.SuiteEnvoi.PROGRAMMATION)
        coVerify(exactly = 0) { programmer.invoke(any(), any(), any(), any(), any(), any()) }

        vm.confirmerEnvoiSansBloques()

        coVerify(exactly = 1) { programmer.invoke(any(), any(), "Bonjour", echeance, any(), any()) }
    }

    /**
     * Le message est resté ici (cas d'un groupe déjà réduit) : pas de navigation, et le bandeau compte les PERSONNES
     * servies — « Envoyé à 1 destinataire(s) » pour un MMS parti à 55 était le défaut vécu.
     */
    @Test
    fun `reste ici - bandeau d'envoi partiel avec les personnes servies`() = runTest(dispatcher) {
        val vm = fil(ecartes = listOf(bloque), conversationDuMessage = 7L)
        val evenements = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.collect { evenements += it } }

        vm.updateDraft("Bonjour")
        vm.send()
        vm.confirmerEnvoiSansBloques()

        assertThat(evenements).contains(ThreadViewModel.Event.PartialSend(sent = 2, failed = 0, blocked = 1))
        assertThat(evenements.filterIsInstance<ThreadViewModel.Event.OuvrirConversation>()).isEmpty()
    }
}
