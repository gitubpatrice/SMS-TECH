package com.filestech.sms.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    // v1.6.1 (audit QUAL-17) — @Stable pour Compose recomposition skipping.
    @androidx.compose.runtime.Stable
    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val excludedConversationId: Long = -1L,
    )

    private val _state = MutableStateFlow(UiState())

    /**
     * v1.27.9 — **texte saisi, en état Compose (snapshot), plus un champ de [UiState].**
     * `OutlinedTextField` exige que la valeur remontée par `onValueChange` lui revienne dans
     * la MÊME recomposition ; via `StateFlow` + `collectAsStateWithLifecycle` elle revenait au
     * mieux une frame plus tard et le champ réappliquait une valeur — et un curseur — périmés.
     * Même patron que [com.filestech.sms.ui.screens.conversations.ConversationsViewModel]
     * et [com.filestech.sms.ui.screens.compose.ComposeViewModel] : les trois champs de
     * recherche de l'app suivent la même règle.
     */
    var searchInput by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            conversationRepo.observeAll(includeArchived = false).collect { list ->
                // v1.25.3 — `observeAll` ne filtre plus les conversations bloquées : elles
                // restent visibles dans la liste principale, signalées en rouge. Ici en
                // revanche elles n'ont rien à faire — on ne propose pas de transférer un
                // message vers un correspondant qu'on vient de bloquer.
                val selectable = list.filterNot { it.blocked }
                _state.update { it.copy(conversations = selectable) }
            }
        }
    }

    fun setQuery(q: String) {
        searchInput = q
    }

    /**
     * Sets the source conversation to exclude from the filtered list. Called from the
     * composable's `LaunchedEffect(currentConversationId)` — passing a fresh id while the
     * sheet is open (rare but possible on configuration changes) just re-filters.
     */
    fun setExcludedConversation(id: Long) {
        if (_state.value.excludedConversationId == id) return
        _state.update { it.copy(excludedConversationId = id) }
    }

    /**
     * v1.27.9 — résultats filtrés exposés en [StateFlow] **dérivé**, comme dans
     * [com.filestech.sms.ui.screens.compose.ComposeViewModel].
     *
     * Ils étaient auparavant recalculés **dans le lambda de `_state.update`**, donc sur le
     * thread appelant — celui de `onValueChange`, c'est-à-dire le Main — et ce lambda est
     * rejouable : la boucle CAS de [MutableStateFlow.update] le réexécute si une émission
     * Room arrive pendant la frappe, ce qui refaisait le filtre O(n·m) plusieurs fois par
     * touche. La mémoïsation voulue en v1.3.11 (« P1 ») est conservée — le filtre ne tourne
     * toujours qu'au changement de requête ou de liste, jamais dans une recomposition — mais
     * il tourne désormais sur [io], et `filtered` ne peut plus désynchroniser d'avec
     * [searchInput] puisqu'il en dérive au lieu d'être stocké à côté.
     */
    val filtered: StateFlow<List<Conversation>> = combine(
        snapshotFlow { searchInput.trim() }.distinctUntilChanged(),
        _state,
    ) { query, state -> computeFiltered(state, query) }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Pure filter: case-insensitive substring on display name OR substring on any stored
     * address raw (digit prefixes like "0612" work because `PhoneAddress.raw` keeps the
     * user-typed format alongside the normalized form).
     */
    private fun computeFiltered(state: UiState, query: String): List<Conversation> {
        val source = state.conversations.filter { it.id != state.excludedConversationId }
        if (query.isEmpty()) return source
        return source.filter { conv ->
            (conv.displayName?.contains(query, ignoreCase = true) == true) ||
                conv.addresses.any { it.raw.contains(query) }
        }
    }
}
