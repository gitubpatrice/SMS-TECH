package com.filestech.sms.ui.screens.vault

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase.BilanSuppression
import com.filestech.sms.security.VaultManager
import com.filestech.sms.ui.screens.vault.VaultViewModel.Event
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #108) — **les actions sur une sélection du Coffre**, jumelles de celles de la liste
 * (`ActionsGroupeesTest`).
 *
 * Une sélection du Coffre n'offrait que « Sortir du coffre », et rien pour la supprimer. La suppression groupée
 * n'agit que sur ce que la fenêtre a compté et que le Coffre montre encore, vide la sélection d'un seul geste, dit
 * son bilan — avec sa cause quand la copie du téléphone a retenu les conversations —, et ne fait rien, ni ne dit
 * rien, hors session du Coffre.
 */
class CoffreActionsGroupeesTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val toggle = mockk<ToggleConversationStateUseCase>()
    private var sessionOuverte = true
    private val coffre = mockk<VaultManager> {
        every { isVaultUnlockedInSession } answers { sessionOuverte }
        every { unlockedInSession } returns MutableStateFlow(true)
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun conv(id: Long) = Conversation(
        id = id,
        threadId = id,
        addresses = emptyList(),
        displayName = "c$id",
        lastMessageAt = id,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = true,
        draft = null,
    )

    private fun ecran(ids: List<Long> = listOf(1L, 2L, 3L)) = VaultViewModel(
        repo = mockk(relaxed = true) { every { observeVault() } returns flowOf(ids.map { conv(it) }) },
        vault = coffre,
        toggle = toggle,
        settings = mockk { every { flow } returns flowOf(AppSettings()) },
        vaultPin = mockk(relaxed = true),
        biometricGate = mockk(relaxed = true),
        io = dispatcher,
    )

    /** L'écran observe la liste du Coffre (sans quoi elle n'est pas lue) et les événements. */
    private fun TestScope.observer(vm: VaultViewModel): List<Event> {
        val evenements = mutableListOf<Event>()
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { evenements += it } }
        return evenements
    }

    @Test
    fun `supprimer la selection - chacune passe par la suppression, le bilan se dit`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 2, conservees = 0)
        val vm = ecran()
        val evenements = observer(vm)
        vm.toggleSelection(1L)
        vm.toggleSelection(3L)

        vm.bulkDeleteSelected(listOf(1L, 3L))

        coVerify(exactly = 1) { toggle.deleteAll(listOf(1L, 3L)) }
        assertThat(vm.selectedIds.value).isEmpty()
        assertThat(evenements).containsExactly(Event.Suppression.Supprimees(nombre = 2))
    }

    /** La fenêtre a compté deux conversations ; une troisième cochée depuis n'est pas emportée. */
    @Test
    fun `la suppression ne va jamais au-dela de ce que la fenetre a compte`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        val vm = ecran()
        observer(vm)
        vm.toggleSelection(1L)
        vm.toggleSelection(2L)

        vm.bulkDeleteSelected(listOf(2L))

        coVerify(exactly = 1) { toggle.deleteAll(listOf(2L)) }
    }

    /** Une conversation cochée puis sortie du Coffre ailleurs n'y est plus : elle n'est pas supprimée. */
    @Test
    fun `seules les conversations que le coffre montre encore sont prises`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        val vm = ecran(ids = listOf(1L))
        observer(vm)
        vm.toggleSelection(1L)
        vm.toggleSelection(9L)

        vm.bulkDeleteSelected(listOf(1L, 9L))

        coVerify(exactly = 1) { toggle.deleteAll(listOf(1L)) }
    }

    @Test
    fun `retenues par la copie du telephone - le bilan en donne la cause`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns
            BilanSuppression(supprimees = 1, conservees = 2, parCopieSysteme = 2)
        val vm = ecran()
        val evenements = observer(vm)
        listOf(1L, 2L, 3L).forEach(vm::toggleSelection)

        vm.bulkDeleteSelected(listOf(1L, 2L, 3L))

        assertThat(evenements).containsExactly(Event.Suppression.Conservees(nombre = 2, copieSysteme = true))
    }

    /** Causes mêlées : le texte général, pas une cause qui ne vaut que pour une partie. */
    @Test
    fun `causes melees - le bilan ne pretend pas a une seule cause`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns
            BilanSuppression(supprimees = 0, conservees = 2, parCopieSysteme = 1)
        val vm = ecran()
        val evenements = observer(vm)
        listOf(1L, 2L).forEach(vm::toggleSelection)

        vm.bulkDeleteSelected(listOf(1L, 2L))

        assertThat(evenements).containsExactly(Event.Suppression.Conservees(nombre = 2, copieSysteme = false))
    }

    @Test
    fun `session du coffre fermee - rien n'est supprime`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        val vm = ecran()
        val evenements = observer(vm)
        vm.toggleSelection(1L)
        sessionOuverte = false

        vm.bulkDeleteSelected(listOf(1L))

        coVerify(exactly = 0) { toggle.deleteAll(any()) }
        assertThat(evenements).isEmpty()
    }

    /** Verrouillage ou code de panique pendant une longue suppression : elle finit, mais ne dit rien. */
    @Test
    fun `session fermee pendant la suppression - pas de bilan`() = runTest(dispatcher) {
        val fin = CompletableDeferred<BilanSuppression>()
        coEvery { toggle.deleteAll(any()) } coAnswers { fin.await() }
        val vm = ecran()
        val evenements = observer(vm)
        vm.toggleSelection(1L)

        vm.bulkDeleteSelected(listOf(1L))
        sessionOuverte = false
        fin.complete(BilanSuppression(supprimees = 1, conservees = 0))

        coVerify(exactly = 1) { toggle.deleteAll(listOf(1L)) }
        assertThat(evenements).isEmpty()
    }

    /** L'appui long : une seule conversation, même chemin ; réussie, elle ne se dit pas, comme dans la liste. */
    @Test
    fun `supprimer une conversation depuis l'appui long`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        val vm = ecran()
        val evenements = observer(vm)

        vm.deleteOne(2L)

        coVerify(exactly = 1) { toggle.deleteAll(listOf(2L)) }
        assertThat(evenements).isEmpty()
    }

    @Test
    fun `une conversation retenue par la copie du telephone le dit`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns
            BilanSuppression(supprimees = 0, conservees = 1, parCopieSysteme = 1)
        val vm = ecran()
        val evenements = observer(vm)

        vm.deleteOne(2L)

        assertThat(evenements).containsExactly(Event.Suppression.Conservees(nombre = 1, copieSysteme = true))
    }

    @Test
    fun `appui long, session fermee - rien n'est supprime`() = runTest(dispatcher) {
        val vm = ecran()
        observer(vm)
        sessionOuverte = false

        vm.deleteOne(2L)

        coVerify(exactly = 0) { toggle.deleteAll(any()) }
    }

    /**
     * La sélection est prise AVANT l'appel : un second appui pendant le déplacement ne sort pas les mêmes
     * conversations une seconde fois — ce second déplacement, qui n'en trouvait plus aucune au Coffre, s'affichait
     * en échec rouge.
     */
    @Test
    fun `sortir du coffre - un second appui pendant le deplacement ne refait rien`() = runTest(dispatcher) {
        val fin = CompletableDeferred<Outcome<Int>>()
        coEvery { toggle.requestBulkMoveToVault(any(), intoVault = false) } coAnswers { fin.await() }
        val vm = ecran()
        val evenements = observer(vm)
        vm.toggleSelection(1L)
        vm.toggleSelection(2L)

        vm.bulkMoveSelectedOut()
        vm.bulkMoveSelectedOut()
        fin.complete(Outcome.Success(2))

        coVerify(exactly = 1) { toggle.requestBulkMoveToVault(listOf(1L, 2L), intoVault = false) }
        assertThat(vm.selectedIds.value).isEmpty()
        assertThat(evenements).containsExactly(Event.MovedOut(count = 2))
    }
}
