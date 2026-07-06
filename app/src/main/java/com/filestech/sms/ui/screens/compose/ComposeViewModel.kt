package com.filestech.sms.ui.screens.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.foldForSearch
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    // v1.6.1 (audit QUAL-17) — @Stable pour Compose recomposition skipping.
    @androidx.compose.runtime.Stable
    data class UiState(
        val query: String = "",
        val results: List<Contact> = emptyList(),
        val recipients: List<PhoneAddress> = emptyList(),
        val initialLoaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val events = oneShotEvents<Event>()

    sealed interface Event {
        data class ConversationCreated(val id: Long) : Event
    }

    init {
        savedStateHandle.get<String>("initialAddress")?.let { addr ->
            _state.update { it.copy(recipients = listOf(PhoneAddress.of(addr))) }
        }
        viewModelScope.launch {
            _state.update { it.copy(results = contactRepo.listAll(), initialLoaded = true) }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    fun addRecipient(rawNumber: String) {
        val addr = PhoneAddress.of(rawNumber)
        if (addr.normalized.isEmpty()) return
        if (_state.value.recipients.any { it.normalized == addr.normalized }) return
        _state.update { it.copy(recipients = it.recipients + addr) }
    }

    fun removeRecipient(addr: PhoneAddress) {
        _state.update { it.copy(recipients = it.recipients - addr) }
    }

    /**
     * v1.3.11 (F2) — "tap-to-pick" UX: when the picker is empty (the common single-recipient
     * case), tapping a contact / free-entry row should immediately open the thread instead of
     * requiring an extra "Continuer" tap. If the user has already started building a group
     * (chips already present), the tap only appends a new chip and the explicit "Continuer"
     * button stays as the validation step — preserving the multi-recipient flow.
     *
     * Returns `true` when the tap triggered the navigation (caller can use this to dismiss
     * the keyboard / clear the query field), `false` when it just appended a chip.
     */
    fun pickRecipient(rawNumber: String): Boolean {
        val addr = PhoneAddress.of(rawNumber)
        if (addr.normalized.isEmpty()) return false
        val wasEmpty = _state.value.recipients.isEmpty()
        if (_state.value.recipients.none { it.normalized == addr.normalized }) {
            _state.update { it.copy(recipients = it.recipients + addr) }
        }
        if (wasEmpty) {
            createConversation()
            return true
        }
        return false
    }

    fun createConversation() {
        viewModelScope.launch {
            val rec = _state.value.recipients
            if (rec.isEmpty()) return@launch
            val res = conversationRepo.findOrCreate(rec)
            if (res is Outcome.Success) events.tryEmit(Event.ConversationCreated(res.value.id))
        }
    }

    /**
     * Contact « pré-indexé » pour la recherche : le nom est **replié une seule fois**
     * (casse + accents retirés via [foldForSearch]) et les numéros normalisés une seule
     * fois, au moment où la liste de contacts change — et non à chaque frappe. Le filtrage
     * par requête ne fait alors que des `contains` sur ces chaînes déjà calculées.
     */
    private data class IndexedContact(
        val contact: Contact,
        val foldedName: String,
        val phoneDigits: List<String>,
    )

    /**
     * Résultats filtrés exposés en [StateFlow] dérivé (optimal) plutôt qu'en fonction
     * rappelée à chaque recomposition : le repli des contacts n'a lieu qu'au changement
     * de la liste source ([distinctUntilChanged]), et le filtrage qu'au changement de
     * requête. Recherche insensible à la **casse ET aux accents** (« maite » trouve
     * « Maïté »), et par numéro si l'utilisateur tape des chiffres.
     */
    val filtered: StateFlow<List<Contact>> = combine(
        _state.map { it.query.trim() }.distinctUntilChanged(),
        _state.map { it.results }.distinctUntilChanged().map { list ->
            list.map { c ->
                IndexedContact(
                    contact = c,
                    foldedName = c.displayName?.foldForSearch().orEmpty(),
                    phoneDigits = c.phones.map { it.normalized },
                )
            }
        },
    ) { q, indexed ->
        if (q.isEmpty()) return@combine indexed.map { it.contact }
        val needle = q.foldForSearch()
        // v1.2.10 : `normalizePhone()` d'un texte pur (ex. "Patrice") rend "" et
        // `contains("")` matcherait TOUT → on ne tente le match numéro que si la requête
        // contient réellement des chiffres (`n.isNotBlank()`).
        val n = q.normalizePhone()
        indexed.mapNotNull { idx ->
            val match = idx.foldedName.contains(needle) ||
                (n.isNotBlank() && idx.phoneDigits.any { it.contains(n) })
            if (match) idx.contact else null
        }
    }
        // Le repli NFD (`foldForSearch`) de tous les contacts au chargement de la liste ne doit
        // pas s'exécuter sur le Main : `combine{}.stateIn` tourne sur Main.immediate par défaut.
        // `flowOn(io)` déplace le fold + le filtrage sur IO — parité avec ConversationsViewModel.
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
}
