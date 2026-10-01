package com.filestech.sms.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.R
import com.filestech.sms.data.contacts.ContactsReader
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ContactRepositoryImpl
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.components.AttachmentKind
import com.filestech.sms.ui.screens.compose.ComposeViewModel
import com.filestech.sms.ui.screens.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * v1.28.13 — **l'application ne plante plus quand la permission Contacts est refusée.**
 *
 * Relevé par by-architect sur la MR F-Droid !38458 (1.28.12, Android 16) : le protocole de test de
 * F-Droid refuse exprès les permissions optionnelles, et ouvrir « Nouveau message » faisait planter
 * l'app — `SecurityException` du fournisseur de contacts, non rattrapée dans `viewModelScope`. Nos
 * tests, et le premier testeur, accordaient toutes les permissions : rien ne passait par ce chemin.
 *
 * Les deux écrans sont des JUMEAUX : le testeur n'a vu que le premier, le second a été trouvé en
 * relisant les appels au dépôt de contacts. Ils sont verrouillés ensemble pour que le prochain
 * correctif ne parte pas sur un seul des deux.
 *
 * Le faux dépôt lève exactement ce que lève Android, au même endroit : la lecture. Les constructions
 * ont lieu DANS `runTest`, qui recueille toute exception non rattrapée d'une coroutine pendant le test
 * et fait échouer celui-ci — c'est ce qui rend le plantage visible ici (contrôle négatif : retirer le
 * correctif fait tomber les deux premiers tests).
 */
class ContactsRefusesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Dépôt de contacts dont la permission est retirée tant que [refuse] vaut `true`. */
    private class ContactsSansPermission : ContactRepository {
        var refuse = true
        val alice = Contact(1L, "Alice", listOf(PhoneAddress.of("+33612345678")), null)

        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            if (refuse) throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            return alice
        }

        override suspend fun listAll(): List<Contact> {
            if (refuse) throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            return listOf(alice)
        }
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
    fun `nouveau message - la permission refusee vide la liste et le dit, sans planter`() = runTest(dispatcher) {
        val contacts = ContactsSansPermission()

        val vm = ComposeViewModel(SavedStateHandle(), contacts, mockk(relaxed = true), dispatcher)

        assertThat(vm.state.value.contactsRefused).isTrue()
        assertThat(vm.state.value.results).isEmpty()
        assertThat(vm.state.value.initialLoaded).isTrue()

        // Retour de la fiche Android où l'utilisateur vient d'accorder la permission.
        contacts.refuse = false
        vm.chargerContacts()

        assertThat(vm.state.value.contactsRefused).isFalse()
        assertThat(vm.state.value.results).containsExactly(contacts.alice)
    }

    @Test
    fun `nouveau message - une autre panne du fournisseur ne se dit pas refus de permission`() = runTest(dispatcher) {
        val enPanne = object : ContactRepository {
            override suspend fun lookupByPhone(rawPhone: String): Contact? = null
            override suspend fun listAll(): List<Contact> = error("fournisseur indisponible")
        }

        val vm = ComposeViewModel(SavedStateHandle(), enPanne, mockk(relaxed = true), dispatcher)

        assertThat(vm.state.value.contactsRefused).isFalse()
        assertThat(vm.state.value.results).isEmpty()
    }

    @Test
    fun `conversation - la permission refusee ne fait pas planter l'ouverture du fil`() = runTest(dispatcher) {
        val contacts = ContactsSansPermission()

        val vm = threadViewModel(contacts)

        // « Contact introuvable » : le menu propose de l'ajouter, ce que l'appli Contacts du système
        // fait sans notre permission.
        assertThat(vm.state.value.hasContact).isFalse()
    }

    /**
     * Témoin positif du test précédent : avec la permission, le même montage trouve le contact. Sans
     * lui, un fil dont l'adresse ne serait jamais lue rendrait aussi `hasContact = false`, et le test
     * ci-dessus passerait sans avoir rien exercé.
     */
    @Test
    fun `conversation - temoin, avec la permission le contact est trouve`() = runTest(dispatcher) {
        val contacts = ContactsSansPermission().apply { refuse = false }

        val vm = threadViewModel(contacts)

        assertThat(vm.state.value.hasContact).isTrue()
    }

    /**
     * L'invariant qui interdit de « simplifier » le correctif en rattrapant le refus dans le dépôt :
     * `IncomingBlockPolicy` a besoin de l'exception pour distinguer « je n'ai pas pu regarder » de
     * « expéditeur inconnu ». Un dépôt qui l'avalerait rendrait `null`, donc « inconnu », pour TOUS
     * les numéros — et l'option « bloquer les inconnus » couperait toute réception, sans un mot.
     */
    @Test
    fun `le depot de contacts laisse remonter le refus de permission`() = runTest(dispatcher) {
        val lecteur: ContactsReader = mockk {
            every { lookupByPhone(any()) } throws SecurityException("READ_CONTACTS")
            every { listAll() } throws SecurityException("READ_CONTACTS")
        }
        val depot = ContactRepositoryImpl(lecteur, dispatcher)

        assertThrows<SecurityException> { depot.lookupByPhone("+33612345678") }
        assertThrows<SecurityException> { depot.listAll() }
    }

    /**
     * v1.28.13 (D7) — joindre une fiche contact sans `READ_CONTACTS` échoue, et l'échec doit dire
     * pourquoi. Le contexte factice ne sait rien lire : la fiche est illisible, comme sans permission.
     */
    @Test
    fun `joindre un contact sans la permission - le message dit pourquoi`() = runTest(dispatcher) {
        val messages = messagesApresJointureDUnContact(permissionContacts = PackageManager.PERMISSION_DENIED)

        assertThat(messages).containsExactly("permission contacts")
    }

    /** Témoin : la même fiche illisible, permission accordée, garde le message générique. */
    @Test
    fun `joindre un contact illisible avec la permission - message generique`() = runTest(dispatcher) {
        val messages = messagesApresJointureDUnContact(permissionContacts = PackageManager.PERMISSION_GRANTED)

        assertThat(messages).containsExactly("lecture impossible")
    }

    private fun kotlinx.coroutines.test.TestScope.messagesApresJointureDUnContact(
        permissionContacts: Int,
    ): List<String> {
        val context: Context = mockk(relaxed = true) {
            every { checkPermission(Manifest.permission.READ_CONTACTS, any(), any()) } returns permissionContacts
            every { getString(R.string.attach_contact_needs_permission) } returns "permission contacts"
            every { getString(R.string.snack_thread_attach_copy_failed) } returns "lecture impossible"
        }
        val vm = threadViewModel(ContactsSansPermission().apply { refuse = false }, context)
        val recus = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(recus) }

        vm.onAttachmentPicked(mockk(relaxed = true), AttachmentKind.CONTACT)

        return recus.filterIsInstance<ThreadViewModel.Event.ShowSnackbar>().map { it.message }
    }

    private fun threadViewModel(
        contacts: ContactRepository,
        context: Context = mockk(relaxed = true),
    ): ThreadViewModel {
        val conversation = Conversation(
            id = 7L,
            threadId = null,
            addresses = listOf(PhoneAddress.of("+33612345678")),
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
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeOne(any()) } returns flowOf(conversation)
            every { observeVaultHidden(any()) } returns flowOf(false)
            every { observeMessagesWindow(any(), any()) } returns emptyFlow()
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppSettings())
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
        }
        return ThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
            repo = repo,
            envoyer = mockk(relaxed = true),
            sendVoiceMms = mockk(relaxed = true),
            retrySend = mockk(relaxed = true),
            markRead = mockk(relaxed = true),
            segCounter = mockk(relaxed = true),
            exportPdf = mockk(relaxed = true),
            voiceRecorder = mockk(relaxed = true),
            playbackController = mockk(relaxed = true),
            settings = settings,
            blockNumber = mockk(relaxed = true),
            toggleConvState = mockk(relaxed = true),
            contactRepo = contacts,
            sendReaction = mockk(relaxed = true),
            scheduleMessage = mockk(relaxed = true),
            incomingShare = mockk(relaxed = true),
            activeConversationTracker = mockk(relaxed = true),
            context = context,
            io = dispatcher,
            appLock = appLock,
        )
    }
}
