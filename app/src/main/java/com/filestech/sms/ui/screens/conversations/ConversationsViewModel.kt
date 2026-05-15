package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.data.local.datastore.AppSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.datastore.SortMode
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the [ConversationsScreen]. Exposes a single [UiState] driven by four flows
 * (Room repository, persisted settings, local search query, telephony sync state) and a small
 * set of mutation methods.
 *
 * The search filters by display name, raw / normalized phone numbers, and last message preview.
 * Case-insensitive. Done client-side because the dataset stays modest (a few thousand rows max).
 *
 * The SMS import is no longer driven from this ViewModel — it is owned by [TelephonySyncManager],
 * a singleton registered in [com.filestech.sms.MainApplication.onCreate] that listens on the
 * system `content://sms` URI. We just surface its [TelephonySyncManager.state] so the screen
 * can show a banner during the very first sync.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ConversationRepository,
    private val settings: SettingsRepository,
    private val toggle: ToggleConversationStateUseCase,
    private val mirror: ConversationMirror,
    private val syncManager: TelephonySyncManager,
    private val appLock: AppLockManager,
    private val blockNumber: BlockNumberUseCase,
    val defaultAppManager: DefaultSmsAppManager,
) : ViewModel() {

    private val archivedFlag: Boolean = savedStateHandle.get<Boolean>("archived") ?: false

    private val query = MutableStateFlow("")

    /**
     * Bumped whenever the host screen suspects the OS-side default-SMS state may have changed
     * (e.g. user returns from the role-request dialog, or the screen comes back to foreground).
     * The combine() chain re-evaluates [DefaultSmsAppManager.isDefault] on every emission, so a
     * single bump is enough to refresh the banner.
     */
    private val defaultRefreshTick = MutableStateFlow(0L)

    init {
        // Backfill contact names for conversations that existed before READ_CONTACTS was granted
        // or that imported with displayName=null. Cheap when there's nothing to do.
        viewModelScope.launch { runCatching { mirror.refreshContactNames() } }
    }

    data class UiState(
        val isLoading: Boolean = true,
        val conversations: List<Conversation> = emptyList(),
        val settings: AppSettings = AppSettings(),
        val isDefaultSmsApp: Boolean = true,
        val archived: Boolean = false,
        val query: String = "",
        val filtered: Boolean = false,
        val isImporting: Boolean = false,
        val importedCount: Int = 0,
        /**
         * `true` when the session was unlocked via the panic code. The host screen uses this to
         * hide all vault entry points (top-bar icon, deep-link buttons). Combined with the
         * navigation gate in [com.filestech.sms.ui.AppRoot] and the data-layer filter in
         * [com.filestech.sms.data.repository.ConversationRepositoryImpl.observeVault], it makes
         * the panic decoy a real privacy backstop (audit S-P0-1).
         */
        val isPanicDecoy: Boolean = false,
    )

    val state: StateFlow<UiState> = combine(
        repo.observeAll(includeArchived = archivedFlag),
        settings.flow,
        query,
        // Merge the default-SMS-refresh tick with the lock state stream so we stay at 5 typed
        // arguments to `combine` (kotlinx coroutines's typed overloads cap at 5; beyond that we
        // would have to fall back to the `Array<*>` variadic form, which loses type safety).
        // Both inputs are pure triggers for a re-evaluation, so collapsing them is sound.
        combine(defaultRefreshTick, appLock.state) { _, lockState -> lockState },
        syncManager.state,
    ) { rows, s, q, lockState, syncState ->
        val matched = filterConversations(rows, q)
        UiState(
            isLoading = false,
            conversations = sortConversations(matched, s.conversations.sortMode),
            settings = s,
            isDefaultSmsApp = defaultAppManager.isDefault(),
            archived = archivedFlag,
            query = q,
            filtered = q.isNotBlank(),
            // Show the "Importing your SMS…" banner only during the very first sync — after that,
            // delta syncs touch a handful of rows and complete in milliseconds, no banner needed.
            isImporting = syncState is TelephonySyncManager.State.Running && syncState.isFirstRun,
            importedCount = (syncState as? TelephonySyncManager.State.Running)?.importedSoFar ?: 0,
            isPanicDecoy = lockState is AppLockManager.LockState.PanicDecoy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    /** Forces a re-evaluation of [DefaultSmsAppManager.isDefault]. Call from ON_RESUME / role result. */
    fun refreshDefaultStatus() {
        defaultRefreshTick.update { it + 1 }
    }

    /**
     * Triggered by the host screen when SMS permissions are freshly granted, so the sync manager
     * picks up rows it couldn't read before. The manager itself debounces concurrent calls.
     */
    fun requestSyncNow() {
        syncManager.requestSync(reason = "permission granted")
    }

    fun setQuery(q: String) { query.update { q } }
    fun clearQuery() { query.update { "" } }

    private fun filterConversations(rows: List<Conversation>, q: String): List<Conversation> {
        if (q.isBlank()) return rows
        val needle = q.trim().lowercase()
        val needleDigits = q.normalizePhone()
        return rows.filter { c ->
            val nameMatch = c.displayName?.lowercase()?.contains(needle) == true
            val previewMatch = c.lastMessagePreview?.lowercase()?.contains(needle) == true
            val phoneMatch = needleDigits.isNotBlank() &&
                c.addresses.any { it.normalized.contains(needleDigits) }
            nameMatch || previewMatch || phoneMatch
        }
    }

    private fun sortConversations(rows: List<Conversation>, mode: SortMode): List<Conversation> = when (mode) {
        SortMode.DATE -> rows.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageAt },
        )
        SortMode.UNREAD_FIRST -> rows.sortedWith(
            compareByDescending<Conversation> { it.unreadCount > 0 }.thenByDescending { it.lastMessageAt },
        )
        SortMode.PINNED_FIRST -> rows.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageAt },
        )
    }

    /** Persists the picked sort mode — observed by the conversations Flow which re-sorts. */
    fun setSortMode(mode: SortMode) = viewModelScope.launch {
        settings.update { it.copy(conversations = it.conversations.copy(sortMode = mode)) }
    }

    fun delete(id: Long) = viewModelScope.launch { toggle.delete(id) }

    /**
     * Blocks **every recipient** of a conversation, then deletes the conversation locally and
     * from the system content provider. The user-intent is "I never want to hear from this
     * person again" — keeping the history around half-deletes the gesture (the filter in
     * `observeAll` hides the row but the SMS still sit in Room + system provider). One-shot
     * cleanup removes both surfaces.
     *
     * Each address is sent individually to [BlockNumberUseCase] so a group-MMS with one already-
     * blocked party still adds the others without partial-failure noise. Idempotent.
     */
    fun block(conversation: Conversation) = viewModelScope.launch {
        for (addr in conversation.addresses) {
            runCatching { blockNumber.invoke(addr.raw, conversation.displayName) }
        }
        runCatching { toggle.delete(conversation.id) }
    }
}
