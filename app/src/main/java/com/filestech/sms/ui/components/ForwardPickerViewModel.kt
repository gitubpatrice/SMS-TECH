package com.filestech.sms.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.3.11 (F5) — backs the [ForwardMessageSheet] with the recent conversations list.
 *
 * Observes [ConversationRepository.observeAll] so the picker stays in sync if a new
 * conversation is created (e.g. by a SMS arriving while the sheet is open).
 *
 * The "current" conversation (where the user is about to forward FROM) is intentionally
 * NOT excluded here — the source `ThreadScreen` passes its own [conversationId] when it
 * calls [filtered] so we can hide that single row at the picker level without coupling
 * this ViewModel to the navigation back-stack.
 */
@HiltViewModel
class ForwardPickerViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
) : ViewModel() {

    // v1.6.1 (audit QUAL-17) — @Stable pour Compose recomposition skipping.
    @androidx.compose.runtime.Stable
    data class UiState(
        val query: String = "",
        val conversations: List<Conversation> = emptyList(),
        val excludedConversationId: Long = -1L,
        /**
         * v1.3.11 (P1) — pre-filtered list cached at each `setQuery` / `observeAll`
         * emission so the composable never re-runs the O(n·m) filter inside a
         * recomposition (used to fire on every keystroke in the search field on Android
         * Go appliances, ~150-conv lists janked perceptibly).
         */
        val filtered: List<Conversation> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            conversationRepo.observeAll(includeArchived = false).collect { list ->
                _state.update { current ->
                    val next = current.copy(conversations = list)
                    next.copy(filtered = computeFiltered(next))
                }
            }
        }
    }

    fun setQuery(q: String) {
        _state.update { current ->
            val next = current.copy(query = q)
            next.copy(filtered = computeFiltered(next))
        }
    }

    /**
     * Sets the source conversation to exclude from the filtered list. Called from the
     * composable's `LaunchedEffect(currentConversationId)` — passing a fresh id while the
     * sheet is open (rare but possible on configuration changes) just re-filters.
     */
    fun setExcludedConversation(id: Long) {
        if (_state.value.excludedConversationId == id) return
        _state.update { current ->
            val next = current.copy(excludedConversationId = id)
            next.copy(filtered = computeFiltered(next))
        }
    }

    /**
     * Pure filter: case-insensitive substring on display name OR substring on any stored
     * address raw (digit prefixes like "0612" work because `PhoneAddress.raw` keeps the
     * user-typed format alongside the normalized form).
     */
    private fun computeFiltered(state: UiState): List<Conversation> {
        val q = state.query.trim()
        val source = state.conversations.filter { it.id != state.excludedConversationId }
        if (q.isEmpty()) return source
        return source.filter { conv ->
            (conv.displayName?.contains(q, ignoreCase = true) == true) ||
                conv.addresses.any { it.raw.contains(q) }
        }
    }
}
