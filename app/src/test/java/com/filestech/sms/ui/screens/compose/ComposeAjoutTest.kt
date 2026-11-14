package com.filestech.sms.ui.screens.compose

import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
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
 * 2026-11-14 (Patrice, en créant un groupe de 56) — « chaque fois que j'en ajoute un, aucun message ne le confirme ».
 * Et l'un des 56 figurait dans la liste de blocage importée d'Android, sans que rien ne le montre.
 *
 * Ces tests tiennent l'annonce : nom et total à chaque ajout, « bloqué » quand il l'est (et sa puce marquée),
 * « déjà dans la liste » au lieu du silence, rien d'annoncé pour un contact retiré entre-temps.
 */
class ComposeAjoutTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun composeur(bloques: Set<String> = emptySet()): ComposeViewModel {
        val contacts = mockk<ContactRepository> { coEvery { listAll() } returns emptyList() }
        val liste = mockk<BlockedNumberRepository> {
            coEvery { isBlocked(any()) } answers { firstArg<String>() in bloques }
        }
        return ComposeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("groupe" to true)),
            contactRepo = contacts,
            conversationRepo = mockk(relaxed = true),
            io = dispatcher,
            blockedRepo = liste,
        )
    }

    @Test
    fun `chaque ajout est annonce, avec le nom et le total`() = runTest(dispatcher) {
        val vm = composeur()
        val annonces = mutableListOf<ComposeViewModel.Event>()
        backgroundScope.launch { vm.events.collect { annonces += it } }

        vm.addRecipient("+33600000001", "Hélène")
        vm.pickRecipient("+33600000002", "Lucie")
        vm.addRecipient("+33600000003")

        assertThat(annonces).containsExactly(
            ComposeViewModel.Event.DestinataireAjoute("Hélène", 1, bloque = false),
            ComposeViewModel.Event.DestinataireAjoute("Lucie", 2, bloque = false),
            // Sans nom de contact : le numéro.
            ComposeViewModel.Event.DestinataireAjoute("+33600000003", 3, bloque = false),
        ).inOrder()
    }

    @Test
    fun `un numero bloque est annonce comme tel, et sa puce marquee`() = runTest(dispatcher) {
        val vm = composeur(bloques = setOf("+33600000009"))
        val annonces = mutableListOf<ComposeViewModel.Event>()
        backgroundScope.launch { vm.events.collect { annonces += it } }

        vm.addRecipient("+33600000001", "Hélène")
        vm.addRecipient("+33600000009", "Roch")

        assertThat(annonces.last()).isEqualTo(ComposeViewModel.Event.DestinataireAjoute("Roch", 2, bloque = true))
        val roch = vm.state.value.recipients.single { it.raw == "+33600000009" }
        assertThat(vm.state.value.bloques).containsExactly(roch.normalized)
    }

    /** Avant : un second appui sur un contact déjà choisi ne faisait RIEN, sans un mot. */
    @Test
    fun `deja choisi - rien n'est ajoute, et on le dit`() = runTest(dispatcher) {
        val vm = composeur()
        val annonces = mutableListOf<ComposeViewModel.Event>()
        backgroundScope.launch { vm.events.collect { annonces += it } }

        vm.addRecipient("+33600000001", "Hélène")
        vm.addRecipient("+33600000001", "Hélène")

        assertThat(vm.state.value.recipients).hasSize(1)
        assertThat(annonces.last()).isEqualTo(ComposeViewModel.Event.DejaChoisi("Hélène"))
    }

    /**
     * Relecture Claude du 2026-11-14 : chaque lecture de la liste de blocage suspend ; deux ajouts rapides pouvaient
     * être annoncés dans le désordre, et tous deux « 2 au total ». Ici la réponse pour le second est prête AVANT celle
     * du premier : la file des annonces le fait attendre (il ne lit qu'après), et chacune garde son total.
     */
    @Test
    fun `deux ajouts rapides - annonces dans l'ordre, chacune avec son total`() = runTest(dispatcher) {
        val lectureA = CompletableDeferred<Boolean>()
        val lectureB = CompletableDeferred<Boolean>()
        val contacts = mockk<ContactRepository> { coEvery { listAll() } returns emptyList() }
        val liste = mockk<BlockedNumberRepository> {
            coEvery { isBlocked("+33600000001") } coAnswers { lectureA.await() }
            coEvery { isBlocked("+33600000002") } coAnswers { lectureB.await() }
        }
        val vm = ComposeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("groupe" to true)),
            contactRepo = contacts,
            conversationRepo = mockk(relaxed = true),
            io = dispatcher,
            blockedRepo = liste,
        )
        val annonces = mutableListOf<ComposeViewModel.Event>()
        backgroundScope.launch { vm.events.collect { annonces += it } }

        vm.addRecipient("+33600000001", "Hélène")
        vm.addRecipient("+33600000002", "Lucie")
        lectureB.complete(false)
        lectureA.complete(false)

        assertThat(annonces).containsExactly(
            ComposeViewModel.Event.DestinataireAjoute("Hélène", 1, bloque = false),
            ComposeViewModel.Event.DestinataireAjoute("Lucie", 2, bloque = false),
        ).inOrder()
    }

    /** Retirer une puce retire aussi sa marque « bloqué ». */
    @Test
    fun `retirer un bloque efface sa marque`() = runTest(dispatcher) {
        val vm = composeur(bloques = setOf("+33600000009"))
        vm.addRecipient("+33600000009", "Roch")
        val puce = vm.state.value.recipients.single()

        vm.removeRecipient(puce)

        assertThat(vm.state.value.recipients).isEmpty()
        assertThat(vm.state.value.bloques).isEmpty()
    }
}
