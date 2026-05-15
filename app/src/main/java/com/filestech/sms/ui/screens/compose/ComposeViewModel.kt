package com.filestech.sms.ui.screens.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {

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

    fun createConversation() {
        viewModelScope.launch {
            val rec = _state.value.recipients
            if (rec.isEmpty()) return@launch
            val res = conversationRepo.findOrCreate(rec)
            if (res is Outcome.Success) events.tryEmit(Event.ConversationCreated(res.value.id))
        }
    }

    fun filteredContacts(): List<Contact> {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return _state.value.results
        val n = q.normalizePhone()
        return _state.value.results.filter { c ->
            (c.displayName?.contains(q, ignoreCase = true) == true) ||
                c.phones.any { p -> p.normalized.contains(n) }
        }
    }
}
