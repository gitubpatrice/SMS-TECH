package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.foldForSearch
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.SortMode
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.ToggleConversationStateUseCase
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val iAmOk: com.filestech.sms.domain.usecase.IAmOkUseCase,
    // v1.6.1 (audit QUAL-03) — privé : la screen passe désormais par
    // [buildChangeDefaultIntent] au lieu de manipuler le manager directement.
    private val defaultAppManager: DefaultSmsAppManager,
    // Audit H4 (v1.14.8) — `MessageDao`, `ConversationDao` et `TelephonyReader` retirés des
    // dépendances directes du VM. La logique "tout marquer comme lu" est désormais dans
    // [ConversationRepository.markAllRead]. Le VM respecte data → domain → presentation.
    @IoDispatcher private val io: CoroutineDispatcher,
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
        // v1.13.0 audit SEC-4 — purge la sélection multiple à chaque entrée
        // en PanicDecoy (séparé du combine pour éviter une mutation depuis un
        // flow transform — anti-pattern fragile face à un futur changement de
        // .stateIn → .shareIn). collect distinctUntilChanged-equivalent via
        // StateFlow sémantique : aucune cascade.
        viewModelScope.launch {
            appLock.state.collect { lockState ->
                if (lockState is AppLockManager.LockState.PanicDecoy) {
                    selectedIds.value = emptySet()
                }
            }
        }
    }

    // v1.6.1 (audit QUAL-17) — @Stable : Compose traite la classe comme stable et
    // skip les recompositions de sous-arbres dont les inputs n'ont pas changé.
    @androidx.compose.runtime.Stable
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
        /**
         * v1.14.0 — `true` si l'user a déclenché le mode urgence il y a moins de
         * [I_AM_OK_WINDOW_MS] (30 min) et la session n'est pas en PanicDecoy.
         * Quand `true`, ConversationsScreen affiche un chip flottant
         * "Je vais bien" qui ouvre un dialog de confirmation puis appelle
         * [com.filestech.sms.domain.usecase.IAmOkUseCase]. Le flag retombe
         * à `false` automatiquement à l'écoulement de la fenêtre OU dès le
         * reset (via `lastTriggeredAt = 0L`).
         */
        val showIAmOkChip: Boolean = false,
        /**
         * v1.13.0 — sélection multiple de conversations pour action en masse
         * "Déplacer vers le coffre". `selectedIds` non vide ⇒ TopAppBar bascule en
         * mode contextuel (titre = count, action principale = déplacer, navigation
         * icon = X annule). Toujours vide quand `isPanicDecoy=true` (toute entrée
         * en mode décoy vide la sélection par `clearSelection`).
         */
        val selectedIds: Set<Long> = emptySet(),
    ) {
        val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    }

    /**
     * v1.6.1 (audit PERF-06) — `debounce(200ms)` sur la query de recherche pour éviter
     * une recomposition LazyColumn complète à chaque frappe. Une saisie rapide "bonjour"
     * (7 frappes en < 200 ms) déclenchait 7 filterConversations + 7 émissions stateIn.
     * Désormais : un seul filter + une seule émission après stabilisation. La valeur
     * vide est émise immédiatement (cas "effacer la recherche") via `distinctUntilChanged`
     * appliqué sur l'OUTPUT du debounce — pas de perte de réactivité perceptible.
     */
    @OptIn(FlowPreview::class)
    private val debouncedQuery = query
        .debounce { q -> if (q.isEmpty()) 0L else 200L }
        .distinctUntilChanged()

    /**
     * v1.13.0 — IDs des conversations actuellement sélectionnées pour une action
     * en masse. Mutée par [toggleSelection] / [clearSelection] depuis l'UI.
     * Mergée dans le `combine` via le tuple `(defaultRefreshTick, appLock.state)`
     * pour conserver le 5-arg cap typé de kotlinx-coroutines.
     */
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<UiState> = combine(
        repo.observeAll(includeArchived = archivedFlag),
        settings.flow,
        debouncedQuery,
        // v1.13.0 — Triple : on packe defaultRefreshTick + appLock.state + selectedIds
        // pour rester dans la limite 5-args typée. Tous sont des inputs de
        // recomposition pure ; le packing est sound.
        combine(defaultRefreshTick, appLock.state, selectedIds) { _, lockState, sel ->
            lockState to sel
        },
        syncManager.state,
    ) { rows, s, q, lockAndSelection, syncState ->
        val (lockState, sel) = lockAndSelection
        val matched = filterConversations(rows, q)
        val sorted = sortConversations(matched, s.conversations.sortMode)
        // v1.13.0 — sélection effective filtrée :
        //  - PanicDecoy → purge via le collector dans `init {}` (audit SEC-4),
        //    ici on rend juste emptySet par sécurité défensive.
        //  - sinon → intersect avec les conv présentes (filtre les IDs orphelins
        //    si une conv est supprimée pendant la sélection multi).
        val effectiveSelection: Set<Long> = if (lockState is AppLockManager.LockState.PanicDecoy) {
            emptySet()
        } else {
            val present = sorted.mapTo(HashSet(sorted.size)) { it.id }
            sel.intersect(present)
        }
        // v1.14.0 — chip "Je vais bien" visible si l'user a déclenché urgence
        // < 30 min ET non-PanicDecoy. Anti-tampering : décoy masque le chip
        // pour ne pas révéler à l'agresseur qu'un déclenchement a eu lieu.
        val isPanic = lockState is AppLockManager.LockState.PanicDecoy
        val triggeredAt = s.security.emergency.lastTriggeredAt
        val showChip = !isPanic &&
            triggeredAt > 0L &&
            (System.currentTimeMillis() - triggeredAt) < I_AM_OK_WINDOW_MS
        UiState(
            isLoading = false,
            conversations = sorted,
            settings = s,
            isDefaultSmsApp = defaultAppManager.isDefault(),
            archived = archivedFlag,
            query = q,
            filtered = q.isNotBlank(),
            // Show the "Importing your SMS…" banner only during the very first sync — after that,
            // delta syncs touch a handful of rows and complete in milliseconds, no banner needed.
            isImporting = syncState is TelephonySyncManager.State.Running && syncState.isFirstRun,
            importedCount = (syncState as? TelephonySyncManager.State.Running)?.importedSoFar ?: 0,
            isPanicDecoy = isPanic,
            showIAmOkChip = showChip,
            selectedIds = effectiveSelection,
        )
    }
        // v1.6.1 (audit QUAL-02) — `defaultAppManager.isDefault()` est un IPC Binder
        // synchrone (RoleManager.isRoleHeld + ContentProvider query) qui peut bloquer
        // ~5-15 ms sur OEM lents. `flowOn(io)` déplace tout le `combine` sur le
        // dispatcher IO ; le dernier émetteur (stateIn) bascule sur Main pour la
        // souscription côté Compose, comme attendu.
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    /** Forces a re-evaluation of [DefaultSmsAppManager.isDefault]. Call from ON_RESUME / role result. */
    fun refreshDefaultStatus() {
        defaultRefreshTick.update { it + 1 }
    }

    /**
     * v1.6.1 (audit QUAL-03) — facade pour la screen, qui n'a plus à tenir une référence
     * directe à [DefaultSmsAppManager] (couche data). Retourne l'`Intent` à lancer pour
     * que l'utilisateur choisisse SMS Tech comme app SMS par défaut, ou `null` si l'OS
     * n'expose pas cette demande (Android < 7 — hors target ici puisque minSdk=26).
     */
    fun buildChangeDefaultIntent(): android.content.Intent? =
        defaultAppManager.buildChangeDefaultIntent()

    /**
     * Triggered by the host screen when SMS permissions are freshly granted, so the sync manager
     * picks up rows it couldn't read before. The manager itself debounces concurrent calls.
     */
    fun requestSyncNow() {
        syncManager.requestSync(reason = "permission granted")
    }

    /**
     * v1.8.0 — action utilisateur "Tout marquer comme lu" depuis le menu
     * overflow de la liste. Marque tous les messages INCOMING `read=0`
     * comme lus EN MASSE puis recalcule les `conversations.unread_count`
     * pour repasser tous les badges à 0. Très utile quand l'utilisateur a
     * lu ses messages dans une autre app SMS sans ouvrir SMS Tech : sans
     * cette action, les badges restent indéfiniment car SMS Tech ne re-lit
     * pas le flag `READ` système pour les messages déjà mirror-és.
     *
     * Le dialog de confirmation est géré côté UI ([ConversationsScreen]).
     */
    fun markAllAsRead() {
        // Audit H4 (v1.14.8) — Délégué au [ConversationRepository.markAllRead] qui encapsule
        // les 3 étapes (Room markAllIncomingAsRead + recomputeAllUnreadCounts + propagation
        // content://sms+mms). Le VM ne touche plus aux DAOs ni au TelephonyReader directement.
        viewModelScope.launch { repo.markAllRead() }
    }

    fun setQuery(q: String) { query.update { q } }
    fun clearQuery() { query.update { "" } }

    private fun filterConversations(rows: List<Conversation>, q: String): List<Conversation> {
        if (q.isBlank()) return rows
        // Repli insensible casse + accents (« maite » trouve « Maïté ») calculé une fois pour la
        // requête ; le repli par ligne se fait à la volée. `trim()` conservé (parité avec l'ancien
        // `q.trim().lowercase()`) pour qu'un espace en tête/fin ne casse pas le match.
        val needle = q.trim().foldForSearch()
        val needleDigits = q.normalizePhone()
        return rows.filter { c ->
            // `||` court-circuite : le repli (coûteux) du preview n'est effectué que si le nom
            // ne matche pas, et le match numéro que si l'utilisateur a tapé des chiffres.
            c.displayName?.foldForSearch()?.contains(needle) == true ||
                c.lastMessagePreview?.foldForSearch()?.contains(needle) == true ||
                (needleDigits.isNotBlank() && c.addresses.any { it.normalized.contains(needleDigits) })
        }
    }

    private fun sortConversations(rows: List<Conversation>, mode: SortMode): List<Conversation> = when (mode) {
        // v1.6.1 (audit QUAL-14) — DATE = tri par date pure SANS prioriser les
        // épinglés (avant : DATE et PINNED_FIRST produisaient le même tri à
        // l'identique, ce qui rendait DATE indistinguable de PINNED_FIRST pour
        // l'utilisateur qui sélectionnait l'un ou l'autre).
        SortMode.DATE -> rows.sortedByDescending { it.lastMessageAt }
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

    /**
     * v1.11.0 — déplace la conversation dans le coffre depuis la liste
     * principale. Délègue à [ToggleConversationStateUseCase.requestMoveToVault]
     * qui check `AppLockState` (refuse PanicDecoy + Locked, auto-arme
     * `sessionUnlocked` sinon). Émet un [Event] de résultat pour que l'UI
     * affiche le snackbar de confirmation ou d'erreur.
     */
    fun moveConversationToVault(conversationId: Long) = viewModelScope.launch {
        val outcome = toggle.requestMoveToVault(conversationId, intoVault = true)
        _events.trySend(
            when (outcome) {
                is Outcome.Success -> Event.MovedToVault(count = 1)
                is Outcome.Failure -> Event.MoveToVaultFailed(count = 1)
            },
        )
    }

    /**
     * v1.13.0 — entre en mode sélection multiple sur un long-press d'item, OU
     * toggle l'appartenance d'un item dans la sélection si on est déjà en mode
     * sélection. Pas d'effet si `id` n'existe plus (la sélection effective est
     * filtrée dans le `combine` à chaque émission).
     */
    fun toggleSelection(id: Long) {
        selectedIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    /**
     * v1.13.0 — sort du mode sélection (TopAppBar `navigationIcon = X`,
     * `onBack`, sortie vers `VaultScreen`, etc.). Idempotent.
     */
    fun clearSelection() {
        selectedIds.update { emptySet() }
    }

    /**
     * v1.13.0 — action "Déplacer les N conv sélectionnées vers le coffre".
     * Boucle séquentielle (pas batch transactionnel — Room WAL absorbe et la
     * défense PanicDecoy est ré-évaluée à chaque appel). Émet un seul
     * [Event.MovedToVault] avec le count effectif (`success`) ; si TOUTES les
     * conv échouent (e.g. décoy actif), émet `MoveToVaultFailed`. Mixed
     * outcome rare en pratique mais traité défensivement.
     */
    fun bulkMoveSelectedToVault() = viewModelScope.launch {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        // v1.14.8 R8 — Wrap atomique Room transaction. Avant : boucle itérative N appels →
        // process-kill au milieu = état partiel non-récupérable. Maintenant : succès complet
        // ou rollback complet. Cf. [ConversationRepository.bulkMoveToVault].
        val outcome = toggle.requestBulkMoveToVault(ids = ids, intoVault = true)
        // Vide la sélection avant d'émettre — l'UI revient au mode normal pendant
        // que le snackbar s'affiche, plus naturel qu'un retour en mode sélection
        // avec 0 sélectionné (les IDs ont été filtrés par `effectiveSelection`).
        clearSelection()
        _events.trySend(
            when (outcome) {
                is Outcome.Success<Int> -> {
                    if (outcome.value > 0) Event.MovedToVault(count = outcome.value)
                    else Event.MoveToVaultFailed(count = ids.size)
                }
                is Outcome.Failure -> Event.MoveToVaultFailed(count = ids.size)
            }
        )
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * v1.14.0 — Trigger "Je vais bien" : reset cooldown urgence + (optionnel)
     * SMS aux contacts safety. Délègue à [com.filestech.sms.domain.usecase.IAmOkUseCase].
     * Émet un [Event] selon outcome. Idempotent : si pas de trigger récent,
     * NothingToReset → pas d'event.
     */
    fun triggerIAmOk() = viewModelScope.launch {
        val result = iAmOk()
        val event = when (result) {
            is com.filestech.sms.domain.usecase.IAmOkUseCase.Result.ResetAndSmsSent ->
                Event.IAmOkDoneWithSms(sent = result.sent, failed = result.failed)
            is com.filestech.sms.domain.usecase.IAmOkUseCase.Result.ResetWithoutSms ->
                Event.IAmOkDoneNoSms
            is com.filestech.sms.domain.usecase.IAmOkUseCase.Result.NothingToReset -> null
            is com.filestech.sms.domain.usecase.IAmOkUseCase.Result.PanicSuppressed -> null
        }
        if (event != null) _events.trySend(event)
    }

    /**
     * v1.13.0 — le count permet à l'UI d'afficher le bon snackbar
     * ("N déplacées" au pluriel) sans dupliquer les events. count = 1 pour
     * un mouvement single (long-press → menu), count = N pour la sélection
     * multiple.
     */
    sealed interface Event {
        data class MovedToVault(val count: Int) : Event
        data class MoveToVaultFailed(val count: Int) : Event
        /** v1.14.0 — Reset cooldown + SMS "Je vais bien" envoyé. */
        data class IAmOkDoneWithSms(val sent: Int, val failed: Int) : Event
        /** v1.14.0 — Reset cooldown effectué, pas de SMS (opt-in OFF). */
        data object IAmOkDoneNoSms : Event
    }

    companion object {
        /**
         * v1.14.0 — Fenêtre d'affichage du chip "Je vais bien" sur
         * [com.filestech.sms.ui.screens.conversations.ConversationsScreen].
         * 30 minutes post-trigger : assez long pour que l'user constate
         * que c'était une fausse alerte et envoie une rassurance.
         */
        const val I_AM_OK_WINDOW_MS: Long = 30L * 60L * 1000L
    }
}
