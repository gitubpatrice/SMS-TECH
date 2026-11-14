package com.filestech.sms.ui.screens.thread

import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-10 (v1.30.1) — **le fil envoie par la SIM qu'il affiche.**
 *
 * Avant la 1.30.1, le fil ne transmettait aucune SIM : tout partait par la SIM SMS d'Android, et l'on répondait
 * par la SIM 1 à un message reçu sur la SIM 2. Ces tests tiennent le câblage du ViewModel : la SIM retenue
 * ([ThreadViewModel.UiState.simEnvoi]) est celle du dernier message, le choix au composeur la remplace, et
 * c'est elle que reçoit l'envoi.
 */
class ThreadSimEnvoiTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val sim1 = SimActive(subId = 11, emplacement = 0, nom = "Orange")
    private val sim2 = SimActive(subId = 22, emplacement = 1, nom = "Free")

    private val envoyer: EnvoyerMessageUseCase = mockk<EnvoyerMessageUseCase>().also { m ->
        coEvery { m.invoke(any(), any(), any(), any(), any()) } returns
            Outcome.Success(SendReport(dispatched = listOf(1L), failed = emptyList(), blocked = emptyList()))
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `la SIM du dernier message est retenue, et c'est elle qui part`() = runTest(dispatcher) {
        // Le plus récent a été reçu sur la SIM 2 ; Android, lui, envoie par la SIM 1.
        // Dans l'ordre chronologique, exprès : c'est à la règle de trouver le plus récent (relecture Claude).
        val historique = listOf(message(1_000L, 11), message(2_000L, 22))
        val vm = threadViewModel(sims = listOf(sim1, sim2), historique = historique)

        assertThat(vm.state.value.sims).containsExactly(sim1, sim2).inOrder()
        assertThat(vm.state.value.simEnvoi).isEqualTo(22)

        vm.updateDraft("Bonjour")
        vm.send()

        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), 22, any()) }
    }

    @Test
    fun `le choix au composeur l'emporte et part avec l'envoi`() = runTest(dispatcher) {
        val vm = threadViewModel(sims = listOf(sim1, sim2), historique = listOf(message(2_000L, 22)))

        vm.choisirSim(11)
        assertThat(vm.state.value.simEnvoi).isEqualTo(11)

        vm.updateDraft("Bonjour")
        vm.send()

        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), 11, any()) }
    }

    @Test
    fun `le choix survit a la mort du processus, par l'etat sauvegarde`() = runTest(dispatcher) {
        // Un premier ViewModel enregistre le choix ; Android tue l'appli et recrée l'écran avec l'état sauvegardé.
        val etat = SavedStateHandle(mapOf("conversationId" to 7L))
        threadViewModel(sims = listOf(sim1, sim2), historique = listOf(message(2_000L, 22)), etat = etat)
            .choisirSim(11)

        val recree = threadViewModel(sims = listOf(sim1, sim2), historique = listOf(message(2_000L, 22)), etat = etat)

        assertThat(recree.state.value.simEnvoi).isEqualTo(11)
    }

    @Test
    fun `une SIM qui n'est pas active ne peut pas etre choisie`() = runTest(dispatcher) {
        val vm = threadViewModel(sims = listOf(sim1, sim2), historique = listOf(message(2_000L, 22)))

        vm.choisirSim(99)

        assertThat(vm.state.value.simEnvoi).isEqualTo(22)
    }

    @Test
    fun `une seule SIM laisse Android choisir, comme avant`() = runTest(dispatcher) {
        val vm = threadViewModel(sims = listOf(sim1), historique = listOf(message(2_000L, 11)))

        assertThat(vm.state.value.simEnvoi).isNull()
        vm.updateDraft("Bonjour")
        vm.send()

        coVerify(exactly = 1) { envoyer.invoke(any(), "Bonjour", any(), null, any()) }
    }

    private fun message(date: Long, subId: Int?) = Message(
        id = date,
        conversationId = 7L,
        address = "+33612345678",
        body = "reçu",
        type = Message.Type.SMS,
        direction = Message.Direction.INCOMING,
        date = date,
        dateSent = date,
        read = true,
        starred = false,
        status = Message.Status.RECEIVED,
        errorCode = null,
        attachmentsCount = 0,
        subId = subId,
        scheduledAt = null,
    )

    private fun threadViewModel(
        sims: List<SimActive>,
        historique: List<Message>,
        etat: SavedStateHandle = SavedStateHandle(mapOf("conversationId" to 7L)),
    ): ThreadViewModel = threadViewModelDeTest(
        dispatcher = dispatcher,
        envoyer = envoyer,
        historique = historique,
        sims = sims,
        etat = etat,
    )
}
