package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase.BilanSuppression
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.screens.conversations.ConversationsViewModel.Event
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
 * 2026-11-16 (v1.31.0, courriel du 2026-11-11) — **les actions sur une sélection de conversations.**
 *
 * Une action groupée n'agit que sur ce que l'écran montre coché ([ConversationsViewModel.selectionVisible]) :
 * la sélection brute peut garder une conversation sortie de la liste (supprimée ailleurs, masquée par la
 * recherche). Le Coffre, seule action groupée jusqu'ici, lisait la sélection brute : le test qui le couvre est
 * celui de ce correctif. Archiver depuis la liste, désarchiver depuis les Archives ; chaque bilan se dit ; rien en
 * session leurre.
 */
class ActionsGroupeesTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val toggle = mockk<ToggleConversationStateUseCase>()
    private val verrou = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Unlocked)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun conv(id: Long, archived: Boolean = false) = Conversation(
        id = id,
        threadId = id,
        addresses = emptyList(),
        displayName = "c$id",
        lastMessageAt = id,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = archived,
        muted = false,
        inVault = false,
        draft = null,
        blocked = false,
    )

    private fun liste(conversations: List<Conversation>, archives: Boolean = false) = ConversationsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("archived" to archives)),
        repo = mockk(relaxed = true) { every { observeAllNonVault() } returns flowOf(conversations) },
        settings = mockk { every { flow } returns flowOf(AppSettings()) },
        toggle = toggle,
        mirror = mockk(relaxed = true),
        syncManager = mockk { every { state } returns MutableStateFlow(TelephonySyncManager.State.Idle) },
        appLock = mockk { every { state } returns verrou },
        blockNumber = mockk(relaxed = true),
        unblockNumber = mockk(relaxed = true),
        iAmOk = mockk(relaxed = true),
        defaultAppManager = mockk { every { isDefault() } returns true },
        io = dispatcher,
    )

    /** L'écran observe l'état (sans quoi la sélection visible n'est pas calculée) et les événements. */
    private fun TestScope.observer(vm: ConversationsViewModel): List<Event> {
        val evenements = mutableListOf<Event>()
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { evenements += it } }
        return evenements
    }

    @Test
    fun `la suppression groupee n'efface que les conversations cochees encore affichees`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        val vm = liste(listOf(conv(1), conv(2)))
        val evenements = observer(vm)

        vm.toggleSelection(1)
        vm.toggleSelection(99) // cochée, puis sortie de la liste
        vm.bulkDeleteSelected()

        coVerify(exactly = 1) { toggle.deleteAll(listOf(1L)) }
        assertThat(evenements).containsExactly(Event.Groupe.Supprimees(nombre = 1))
        assertThat(vm.state.value.selectedIds).isEmpty()
    }

    @Test
    fun `le Coffre ne recoit plus une conversation cochee qui n'est plus affichee`() = runTest(dispatcher) {
        coEvery { toggle.requestBulkMoveToVault(any(), any()) } returns Outcome.Success(1)
        val vm = liste(listOf(conv(1), conv(2)))
        val evenements = observer(vm)

        vm.toggleSelection(1)
        vm.toggleSelection(99)
        vm.bulkMoveSelectedToVault()

        coVerify(exactly = 1) { toggle.requestBulkMoveToVault(listOf(1L), true) }
        assertThat(evenements).containsExactly(Event.MovedToVault(count = 1))
    }

    @Test
    fun `depuis la liste, la selection est archivee en une ecriture`() = runTest(dispatcher) {
        coEvery { toggle.setArchivedAll(any(), any()) } returns 2
        val vm = liste(listOf(conv(1), conv(2), conv(3)))
        val evenements = observer(vm)

        vm.toggleSelection(1)
        vm.toggleSelection(3)
        vm.bulkArchiveSelected()

        coVerify(exactly = 1) { toggle.setArchivedAll(match { it.toSet() == setOf(1L, 3L) }, true) }
        assertThat(evenements).containsExactly(Event.Groupe.Archivees(nombre = 2, archivees = true))
        assertThat(vm.state.value.selectedIds).isEmpty()
    }

    @Test
    fun `depuis les Archives, la selection est rendue a la liste`() = runTest(dispatcher) {
        coEvery { toggle.setArchivedAll(any(), any()) } returns 1
        val vm = liste(listOf(conv(4, archived = true)), archives = true)
        val evenements = observer(vm)

        vm.toggleSelection(4)
        vm.bulkArchiveSelected()

        coVerify(exactly = 1) { toggle.setArchivedAll(listOf(4L), false) }
        assertThat(evenements).containsExactly(Event.Groupe.Archivees(nombre = 1, archivees = false))
    }

    @Test
    fun `un archivage qui echoue le dit`() = runTest(dispatcher) {
        coEvery { toggle.setArchivedAll(any(), any()) } throws IllegalStateException("base indisponible")
        val vm = liste(listOf(conv(1)))
        val evenements = observer(vm)

        vm.toggleSelection(1)
        vm.bulkArchiveSelected()

        assertThat(evenements).containsExactly(Event.Groupe.ArchivageEchoue)
    }

    @Test
    fun `une suppression dont une partie reste annonce les conservees`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 2, conservees = 1)
        val vm = liste(listOf(conv(1), conv(2), conv(3)))
        val evenements = observer(vm)

        listOf(1L, 2L, 3L).forEach(vm::toggleSelection)
        vm.bulkDeleteSelected()

        assertThat(evenements).containsExactly(Event.Groupe.SuppressionsConservees(nombre = 1))
    }

    @Test
    fun `en session leurre, aucune action groupee n'agit`() = runTest(dispatcher) {
        coEvery { toggle.deleteAll(any()) } returns BilanSuppression(supprimees = 1, conservees = 0)
        coEvery { toggle.setArchivedAll(any(), any()) } returns 1
        val vm = liste(listOf(conv(1)))
        val evenements = observer(vm)

        vm.toggleSelection(1)
        verrou.value = AppLockManager.LockState.PanicDecoy
        vm.bulkDeleteSelected()
        vm.bulkArchiveSelected()

        coVerify(exactly = 0) { toggle.deleteAll(any()) }
        coVerify(exactly = 0) { toggle.setArchivedAll(any(), any()) }
        assertThat(evenements).isEmpty()
    }
}
