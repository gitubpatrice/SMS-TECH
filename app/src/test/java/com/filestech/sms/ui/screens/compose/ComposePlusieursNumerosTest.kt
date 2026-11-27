package com.filestech.sms.ui.screens.compose

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.ContactPhone
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #109) — « If a contact has multiple phone numbers, only the last one can be selected. »
 *
 * « Nouveau message » montrait une ligne par contact, avec un seul de ses numéros : les autres ne pouvaient pas
 * être choisis, et chercher l'un d'eux affichait la ligne avec un AUTRE numéro, celui qui recevait le message. Ces
 * tests tiennent la liste à une ligne par numéro, la recherche par chiffres au numéro cherché, et le choix d'un
 * second numéro jusqu'à la conversation créée.
 */
class ComposePlusieursNumerosTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val alice = Contact(
        id = 1L,
        displayName = "Alice",
        phones = listOf(
            ContactPhone(PhoneAddress.of("06 11 11 11 11"), type = 2),
            ContactPhone(PhoneAddress.of("01 22 22 22 22"), type = 3),
            ContactPhone(PhoneAddress.of("+44 7700 900123"), label = "Londres"),
        ),
    )
    private val bruno = Contact(id = 2L, displayName = "Bruno", phones = listOf(ContactPhone(PhoneAddress.of("0633333333"))))

    /** Les adresses de chaque conversation créée, dans l'ordre. */
    private val creees = mutableListOf<List<String>>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun composeur(groupe: Boolean = false): ComposeViewModel {
        val contacts = mockk<ContactRepository> { coEvery { listAll() } returns listOf(alice, bruno) }
        val conversations = mockk<ConversationRepository> {
            coEvery { findOrCreate(any()) } answers {
                val adresses = firstArg<List<PhoneAddress>>()
                creees += adresses.map { it.raw }
                Outcome.Success(conversation(adresses))
            }
        }
        return ComposeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("groupe" to groupe)),
            contactRepo = contacts,
            conversationRepo = conversations,
            io = dispatcher,
            blockedRepo = mockk(relaxed = true),
            incomingShare = com.filestech.sms.system.share.IncomingShareHolder(),
        )
    }

    /** Abonne la liste filtrée (elle ne calcule que suivie) et rend sa dernière valeur après [requete]. */
    private fun TestScope.lignesPour(vm: ComposeViewModel, requete: String): List<ComposeViewModel.LigneContact> {
        backgroundScope.launch { vm.filtered.collect {} }
        vm.setQuery(requete)
        // Hors composition, un état Compose modifié n'est signalé à `snapshotFlow` que sur demande.
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        return vm.filtered.value
    }

    @Test
    fun `chaque numero a sa ligne, avec le nom du contact`() = runTest(dispatcher) {
        val lignes = lignesPour(composeur(), "")

        assertThat(lignes.map { it.contact.displayName to it.numero.address.raw }).containsExactly(
            "Alice" to "06 11 11 11 11",
            "Alice" to "01 22 22 22 22",
            "Alice" to "+44 7700 900123",
            "Bruno" to "0633333333",
        ).inOrder()
        // Les clés de la liste paresseuse sont uniques : une clé en double fait planter l'écran.
        assertThat(lignes.map { it.cle }.toSet()).hasSize(lignes.size)
    }

    @Test
    fun `un nom cherche donne tous les numeros du contact`() = runTest(dispatcher) {
        val lignes = lignesPour(composeur(), "alice")

        assertThat(lignes.map { it.numero.address.raw })
            .containsExactly("06 11 11 11 11", "01 22 22 22 22", "+44 7700 900123").inOrder()
    }

    /** Le défaut caché derrière #109 : taper le second numéro montrait la ligne du premier. */
    @Test
    fun `des chiffres cherches ne donnent que le numero qui les contient`() = runTest(dispatcher) {
        val lignes = lignesPour(composeur(), "0122")

        assertThat(lignes.map { it.contact.displayName to it.numero.address.raw })
            .containsExactly("Alice" to "01 22 22 22 22")
    }

    @Test
    fun `choisir le second numero ouvre la conversation avec lui`() = runTest(dispatcher) {
        val vm = composeur()
        val second = lignesPour(vm, "")[1]

        vm.pickRecipient(second.numero.address.raw, second.contact.displayName)
        advanceUntilIdle()

        assertThat(creees).containsExactly(listOf("01 22 22 22 22"))
    }

    @Test
    fun `en groupe, deux numeros du meme contact font deux destinataires`() = runTest(dispatcher) {
        val vm = composeur(groupe = true)
        val lignes = lignesPour(vm, "alice")

        lignes.take(2).forEach { vm.pickRecipient(it.numero.address.raw, it.contact.displayName) }

        assertThat(vm.state.value.recipients.map { it.raw })
            .containsExactly("06 11 11 11 11", "01 22 22 22 22").inOrder()
        assertThat(creees).isEmpty()
    }

    private fun conversation(adresses: List<PhoneAddress>) = Conversation(
        id = 7L,
        threadId = null,
        addresses = adresses,
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )
}
