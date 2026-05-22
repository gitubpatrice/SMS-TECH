package com.filestech.sms.ui.screens.thread

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.asEvents
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.AppSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sms.SmsSegmentCounter
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.data.voice.VoiceRecorder
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.domain.usecase.ExportConversationPdfUseCase
import com.filestech.sms.domain.usecase.MarkConversationReadUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.domain.usecase.SendSmsUseCase
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import com.filestech.sms.domain.usecase.SendVoiceMmsUseCase
import com.filestech.sms.system.notifications.ActiveConversationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ConversationRepository,
    private val sendSms: SendSmsUseCase,
    private val sendVoiceMms: SendVoiceMmsUseCase,
    private val sendMediaMms: SendMediaMmsUseCase,
    private val retrySend: RetrySendUseCase,
    private val markRead: MarkConversationReadUseCase,
    private val segCounter: SmsSegmentCounter,
    private val exportPdf: ExportConversationPdfUseCase,
    private val voiceRecorder: VoiceRecorder,
    private val playbackController: VoicePlaybackController,
    private val settings: SettingsRepository,
    private val blockNumber: com.filestech.sms.domain.usecase.BlockNumberUseCase,
    private val toggleConvState: com.filestech.sms.domain.usecase.ToggleConversationStateUseCase,
    private val contactRepo: com.filestech.sms.domain.repository.ContactRepository,
    private val sendReaction: SendReactionUseCase,
    private val incomingShare: com.filestech.sms.system.share.IncomingShareHolder,
    private val activeConversationTracker: ActiveConversationTracker,
    @ApplicationContext private val context: android.content.Context,
    @com.filestech.sms.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher,
    // v1.12.0 — overflow "Déplacer vers le coffre" : on a besoin du flag
    // PanicDecoy pour gater l'item (cohérence avec masquage liste +
    // Settings + filtre nav AppRoot). En decoy, l'item NE DOIT PAS
    // apparaître (l'agresseur ne doit pas savoir qu'un coffre existe).
    private val appLock: com.filestech.sms.security.AppLockManager,
) : ViewModel() {

    /** Live playback state, surfaced for the composer's preview UI. */
    val playbackState: StateFlow<VoicePlaybackController.PlaybackState> get() = playbackController.state

    private val conversationId: Long = checkNotNull(savedStateHandle["conversationId"])

    /**
     * Voice composer state machine:
     *   Idle      → no recording, normal text composer
     *   Recording → microphone is live, [elapsedMs]/[amplitude] update every 100 ms
     *   Reviewing → recording finished, user can play it back and Send or Discard
     */
    sealed interface VoiceState {
        data object Idle : VoiceState
        data class Recording(val elapsedMs: Long, val amplitude: Int) : VoiceState
        data class Reviewing(
            val file: File,
            val durationMs: Long,
            val sizeBytes: Long,
            val mimeType: String,
        ) : VoiceState
    }

    /**
     * v1.6.1 (audit QUAL-17) — `@Stable` indique au compilateur Compose que les écritures
     * sur les propriétés `val` de la data class sont observables via le moteur de runtime
     * (StateFlow chez nous). Sans cette annotation, Compose marque la classe `unstable` et
     * recompose tout sous-arbre qui la lit dès qu'un parent change. Avec 14 champs ici,
     * le gain est significatif sur l'animation/scroll des bulles.
     */
    @androidx.compose.runtime.Stable
    data class UiState(
        val isLoading: Boolean = true,
        val conversation: Conversation? = null,
        val messages: List<Message> = emptyList(),
        val draft: String = "",
        val segments: SmsSegmentCounter.Stats = SmsSegmentCounter.Stats(0, 0, 0, false),
        val isExporting: Boolean = false,
        val draftSeeded: Boolean = false,
        val hasContact: Boolean = false,
        val messageCount: Int = 0,
        val firstMessageAt: Long? = null,
        val lastMessageAt: Long? = null,
        val voice: VoiceState = VoiceState.Idle,
        val isSendingVoice: Boolean = false,
        /**
         * Non-null when the user tapped Send while [SendingSettings.confirmBeforeBroadcast] is on.
         * UI displays a confirm dialog with this body; [confirmPendingSend] / [cancelPendingSend]
         * resolves it.
         */
        val pendingSend: String? = null,
        /**
         * Active contextual-reply target (#8). Set by [startReply] when the user picks
         * "Répondre" from a bubble's overflow menu; the composer paints a cartouche with the
         * quoted excerpt and the next [send] call tags the outgoing row with this message's id.
         * Cleared by [cancelReply], by a successful send, or when the user switches threads.
         */
        val replyingTo: Message? = null,
        /**
         * v1.3.4 — pièces jointes stagées dans le composer (passé du dialog modal singleton
         * v1.2.1 à une liste affichée comme une bande horizontale au-dessus du champ texte).
         * L'utilisateur empile autant de PJ qu'il veut (cap par taille totale 280 KB,
         * pas par nombre), peut en retirer une via le X sur le chip, écrit éventuellement
         * du texte, puis tap Envoyer = un seul MMS multipart (texte + N PJ).
         *
         * État vide = `emptyList()`. Le composer reste fonctionnel pour les SMS texte purs.
         */
        val pendingAttachments: List<PendingAttachment> = emptyList(),
        /** Voice MMS staged for confirmation (toggle "Confirmer avant envoi" ON). */
        val pendingVoiceConfirm: Boolean = false,
        /**
         * v1.11.0 — Sujet 3 anti-smishing : toggle Settings exposé dans le state
         * pour gater l'application de [com.filestech.sms.domain.smishing.SmishingDetector]
         * sur les bulles entrantes côté [com.filestech.sms.ui.screens.thread.ThreadScreen].
         */
        val smishingDetectionEnabled: Boolean = true,
        /**
         * v1.11.0 audit P1 — verdicts smishing calculés côté ViewModel sur IO
         * dispatcher (ne plus bloquer le main thread dans `remember` côté
         * ThreadScreen). Clef = message.id, valeur = liste des raisons
         * détectées (vide si non-suspect ou sortant). Mis à jour quand
         * `messages` change ou que le toggle bascule.
         */
        val smishingVerdicts: Map<Long, List<com.filestech.sms.domain.smishing.SmishingReason>> = emptyMap(),
        /**
         * v1.12.0 — flag PanicDecoy pour gater l'overflow item "Déplacer
         * vers le coffre" / "Sortir du coffre". En decoy, l'option ne doit
         * pas apparaître (preserve l'illusion app SMS ordinaire).
         */
        val isPanicDecoy: Boolean = false,
    )

    /** Staged attachment awaiting user confirmation in the composer. */
    data class PendingAttachment(
        val file: java.io.File,
        val mimeType: String,
        val displayName: String,
        val sizeBytes: Long,
    )

    sealed interface Event {
        /**
         * v1.3.7 — `isError` distingue les confirmations positives (succès → snackbar slate-blue,
         * couleur de marque) des notifications d'échec (erreur → snackbar rouge `errorContainer`).
         * Default `false` pour ne casser aucun call site existant ; les sites _FAILED le passent
         * explicitement à `true`. Cf. [com.filestech.sms.ui.screens.thread.ThreadScreen] qui
         * traduit ce flag en `containerColor` via [androidx.compose.material3.SnackbarVisuals].
         */
        data class ShowSnackbar(val message: String, val isError: Boolean = false) : Event
        data class PdfReady(val uri: android.net.Uri, val pages: Int) : Event
        data class SendError(val error: AppError) : Event

        /** Launch the system contact editor pre-filled with the conversation's phone number. */
        data class OpenAddContact(val rawNumber: String) : Event

        /** Launch the system dialer pre-filled with the conversation's phone number. */
        data class OpenDialer(val rawNumber: String) : Event

        /**
         * v1.3.1 — premier envoi de réaction par SMS : la préférence est activée mais
         * l'utilisateur n'a pas encore validé le dialog de confirmation. [messageId] est
         * porté pour qu'au moment du confirm le caller puisse re-vérifier que la réaction
         * n'a pas été changée/retirée entre temps (anti-race F4 : sans messageId on
         * pourrait envoyer un emoji obsolète).
         */
        data class RequestReactionConfirm(val messageId: Long, val emoji: String) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = oneShotEvents<Event>()
    val events: SharedFlow<Event> = _events.asEvents()

    private var recorderJob: Job? = null

    /**
     * v1.6.1 PERF-01 / v1.6.2 BUGFIX — snapshot chaud des AppSettings.
     *
     * **Bug v1.6.1** : la version précédente utilisait `stateIn(viewModelScope,
     * WhileSubscribed(5_000))` mais aucun consommateur ne collectait jamais la
     * StateFlow (lecture uniquement via `.value`). Sans collecteur actif, le flux
     * sous-jacent n'était jamais souscrit et `.value` retournait toujours la valeur
     * initiale `AppSettings()` — donc TOUS les réglages utilisateur étaient ignorés :
     * `confirmBeforeBroadcast`, `reactionConfirmDismissed`, `reactionEmojiOnly`,
     * `sendReactionsToRecipient`, etc. resteraient figés sur leurs valeurs DEFAULT.
     *
     * **Fix v1.6.2** : on lit directement `settings.state` (StateFlow `Eagerly`
     * hydratée par `appScope` côté [SettingsRepository]) — garantie d'être à jour
     * tout au long de la vie du processus, zero-I/O, sans race.
     */
    private val cachedSettings: StateFlow<AppSettings> get() = settings.state

    /**
     * v1.11.0 audit P1 — recompute smishing verdicts sur IO dispatcher. Le
     * calcul des regex + Levenshtein peut prendre 3-15 ms par message sur
     * low-end ; sur thread 200 messages cela bloquerait le main thread
     * pendant 600 ms à 3 s en première composition. Ici on calcule sur IO,
     * on émet une seule fois le Map résultant — la recomposition Compose
     * sur changement de StateFlow ne fait que lire un Map (O(1) par bulle).
     *
     * Optimisation cache : on PRÉSERVE les verdicts déjà calculés pour les
     * mêmes message.id si le body n'a pas changé (rare en SMS — un message
     * ne change pas son contenu). En pratique on récalcule seulement les
     * nouveaux IDs apparus dans `msgs`, ce qui rend le coût O(delta).
     */
    // v1.11.0 audit SEC-V6 — job courant annulé avant re-lancement pour
    // éviter qu'un toggle rapide ON→OFF→ON ne produise deux compute IO
    // concurrents avec un snapshot stale → Map smishingVerdicts incohérent.
    private var smishingJob: Job? = null

    private fun recomputeSmishingVerdicts(
        msgs: List<com.filestech.sms.domain.model.Message>,
    ) {
        smishingJob?.cancel()
        smishingJob = viewModelScope.launch(io) {
            val previous = _state.value.smishingVerdicts
            val verdicts = msgs.asSequence()
                .filter { it.isIncoming && it.body.isNotBlank() }
                .associate { msg ->
                    val cached = previous[msg.id]
                    val reasons = cached ?: com.filestech.sms.domain.smishing.SmishingDetector
                        .analyze(msg.body)
                        .takeIf { it.shouldWarn }
                        ?.reasons
                        ?: emptyList()
                    msg.id to reasons
                }
                .filterValues { it.isNotEmpty() } // ne stocke que les positifs
            _state.update { it.copy(smishingVerdicts = verdicts) }
        }
    }

    init {
        // v1.3.9 — déclare la conversation comme "active foreground" pour que
        // `IncomingMessageNotifier` puisse poser un `setTimeoutAfter` court (~1500 ms)
        // sur toute notif arrivant sur CETTE conv pendant qu'elle est ouverte. Son /
        // heads-up jouent (signal sonore préservé), puis Android cancel la notif
        // automatiquement — l'utilisateur n'a pas à la dismiss à la main.
        activeConversationTracker.setActive(conversationId)

        // v1.12.0 — propage `isPanicDecoy` dans le state pour gater
        // l'overflow item "Déplacer vers le coffre" côté UI.
        viewModelScope.launch {
            appLock.state.collect { lockState ->
                val inDecoy = lockState is com.filestech.sms.security.AppLockManager.LockState.PanicDecoy
                if (_state.value.isPanicDecoy != inDecoy) {
                    _state.update { it.copy(isPanicDecoy = inDecoy) }
                }
            }
        }

        // v1.11.0 — Sujet 3 anti-smishing : propage le toggle Settings + RE-
        // CALCULE les verdicts pour les messages quand toggle bascule. Une
        // seule collect sur `settings.state` (hot StateFlow Eagerly), zéro
        // coût supplémentaire en lecture.
        viewModelScope.launch {
            settings.state.collect { snapshot ->
                val enabled = snapshot.security.smishingDetectionEnabled
                _state.update { it.copy(smishingDetectionEnabled = enabled) }
                // Audit P1 : recompute sur IO lorsque le toggle change.
                if (enabled) {
                    recomputeSmishingVerdicts(_state.value.messages)
                } else {
                    _state.update { it.copy(smishingVerdicts = emptyMap()) }
                }
            }
        }


        // Audit Q3: ONE observation of the conversation row (seeds the draft on first emission
        // only — subsequent emissions don't overwrite what the user is typing).
        // v1.6.1 (audit PERF-02) — la lookup contact est séparée et déclenchée UNIQUEMENT
        // quand le numéro du destinataire change. Avant : chaque frappe clavier mettait à
        // jour le champ `draft` de la conversation → l'`onEach` se redéclenchait → query
        // ContentProvider Contacts sur chaque frappe (jusqu'à 30 ms par lookup en cache miss).
        val convFlow = repo.observeOne(conversationId)
        convFlow.onEach { conv ->
            _state.update { st ->
                // v1.6.1 (audit QUAL-06) — `conv?.draft.orEmpty()` au lieu de
                // `conv!!.draft.orEmpty()`. Si `seededDraft = true` alors `conv.draft`
                // est non-vide donc `conv != null` ; mais le compilateur ne capture pas
                // l'invariante à travers deux variables séparées. `?.` produit le même
                // résultat ("" si null) sans risque NPE si un refactor casse l'invariante.
                val seededDraft = !st.draftSeeded && !conv?.draft.isNullOrEmpty()
                val draft = if (seededDraft) conv?.draft.orEmpty() else st.draft
                st.copy(
                    conversation = conv,
                    draft = draft,
                    segments = if (seededDraft) segCounter.count(draft) else st.segments,
                    draftSeeded = st.draftSeeded || conv != null,
                )
            }
        }.launchIn(viewModelScope)
        convFlow
            .map { conv -> conv?.addresses?.firstOrNull()?.raw }
            .distinctUntilChanged()
            .onEach { firstNumber ->
                val has = firstNumber?.let { contactRepo.lookupByPhone(it) != null } ?: false
                _state.update { it.copy(hasContact = has) }
            }
            .launchIn(viewModelScope)

        var markedReadAtLeastOnce = false
        repo.observeMessages(conversationId).onEach { msgs ->
            _state.update {
                it.copy(
                    isLoading = false,
                    messages = msgs,
                    messageCount = msgs.size,
                    firstMessageAt = msgs.minOfOrNull { m -> m.date },
                    lastMessageAt = msgs.maxOfOrNull { m -> m.date },
                )
            }
            // v1.11.0 audit P1 — recompute les verdicts smishing sur IO
            // dispatcher quand la liste de messages change (ajout, suppression,
            // edit). Le calcul reste limité aux INCOMING messages qui n'ont
            // pas encore de verdict cached → coût O(delta) en pratique.
            if (_state.value.smishingDetectionEnabled) {
                recomputeSmishingVerdicts(msgs)
            }
            if (msgs.isNotEmpty() && !markedReadAtLeastOnce) {
                markedReadAtLeastOnce = true
                markRead.invoke(conversationId)
            }
        }.launchIn(viewModelScope)

        // Best-effort: drop yesterday's orphan recordings from the cache on screen open.
        voiceRecorder.pruneOld()
    }

    fun updateDraft(text: String) {
        _state.update { it.copy(draft = text, segments = segCounter.count(text), draftSeeded = true) }
        viewModelScope.launch { repo.setDraft(conversationId, text.ifBlank { null }) }
    }

    fun send() {
        val body = _state.value.draft.trim()
        val hasAttachments = _state.value.pendingAttachments.isNotEmpty()
        // v1.3.4 — 3 chemins :
        //   (a) PJ stagées + texte optionnel → 1 MMS multipart via dispatchPendingAttachments
        //   (b) texte seul → flow SMS classique (avec confirm dialog si broadcast multi-dest)
        //   (c) rien → no-op
        if (!hasAttachments && body.isEmpty()) return
        viewModelScope.launch {
            if (hasAttachments) {
                dispatchPendingAttachments()
                return@launch
            }
            val confirm = cachedSettings.value.sending.confirmBeforeBroadcast
            if (confirm) {
                _state.update { it.copy(pendingSend = body) }
            } else {
                doSend(body)
            }
        }
    }

    /** Called from the UI when the user accepts the "Send this message?" confirmation. */
    fun confirmPendingSend() {
        val body = _state.value.pendingSend ?: return
        _state.update { it.copy(pendingSend = null) }
        viewModelScope.launch { doSend(body) }
    }

    /** Dismisses the pending-send confirmation without sending. */
    fun cancelPendingSend() {
        _state.update { it.copy(pendingSend = null) }
    }

    private suspend fun doSend(body: String) {
        val conv = _state.value.conversation ?: return
        val replyTargetId = _state.value.replyingTo?.id
        when (val res = sendSms.invoke(conv.addresses, body, replyToMessageId = replyTargetId)) {
            is Outcome.Success -> {
                _state.update {
                    it.copy(
                        draft = "",
                        segments = segCounter.count(""),
                        replyingTo = null,
                    )
                }
                repo.setDraft(conversationId, null)
            }
            is Outcome.Failure -> _events.tryEmit(Event.SendError(res.error))
        }
    }

    /**
     * Arms a contextual reply (#8). The picked message stays visible above the composer until
     * the user sends or cancels. Calling twice with different targets overwrites the previous
     * target — there is no "stack of replies", at most one is pending at any time.
     */
    fun startReply(message: Message) {
        _state.update { it.copy(replyingTo = message) }
    }

    /** Drops the pending reply target without sending. Idempotent. */
    fun cancelReply() {
        if (_state.value.replyingTo == null) return
        _state.update { it.copy(replyingTo = null) }
    }

    /**
     * v1.3.0 — pose / change / retire la réaction emoji locale d'un message. Le badge sur la
     * bulle se met à jour automatiquement via le Flow d'observation de la conversation.
     *
     * v1.3.1 — si la préférence [com.filestech.sms.data.local.datastore.SendingSettings
     * .sendReactionsToRecipient] est activée ET que la transition est une **première pose**
     * ([SetReactionResult.First] : null → emoji), on prévient le correspondant en envoyant
     * un SMS contenant uniquement l'emoji. Au tout premier envoi (jamais validé), un
     * dialog de confirmation s'affiche via [Event.RequestReactionConfirm] ; l'envoi réel
     * a lieu seulement après [confirmReactionSend]. Une fois la case "Ne plus demander"
     * cochée, les envois suivants sont silencieux.
     *
     * Les transitions [SetReactionResult.Changed] (A → B) et [SetReactionResult.Removed]
     * (emoji → null) sont **toujours silencieuses** côté réseau pour ne pas spammer le
     * correspondant en cas d'hésitation. Voir KDoc [ConversationRepository.setReaction]
     * pour la sémantique complète.
     */
    fun setReaction(messageId: Long, emoji: String?) {
        viewModelScope.launch {
            val result = runCatching { repo.setReaction(messageId, emoji) }
                .onFailure { timber.log.Timber.w(it, "setReaction(%d, %s) failed", messageId, emoji) }
                .getOrNull() ?: return@launch

            // v1.4.1 — dispatch SMS on BOTH First (null → emoji) AND Changed (A → B)
            // transitions. The v1.3.1 silence-on-Changed rule was too defensive : users
            // legitimately switch their mind ("oh wait, 👍 fits better than ❤️") and
            // expect the correspondent to see the update too. Removed (emoji → null)
            // stays local-only — Apple/Google Tapback has no "remove" wire format we
            // could encode in a one-segment UCS-2 SMS.
            //
            // [SetReactionResult.Changed] does not carry the messageId (the sealed type
            // is shared across the repo layer), so we close over the [messageId] /
            // [emoji] parameters from the enclosing call — both are still in scope and
            // describe the exact state the repo just committed.
            val targetMessageId: Long
            val targetEmoji: String
            // v1.6.0 (audit Q3) — `when` exhaustif sur sealed : pas de branche `else`
            // pour qu'une future variante du sealed interface fasse échouer la compilation
            // ici plutôt que d'être avalée silencieusement.
            when (result) {
                is SetReactionResult.First -> {
                    targetMessageId = result.messageId
                    targetEmoji = result.emoji
                }
                is SetReactionResult.Changed -> {
                    targetMessageId = messageId
                    targetEmoji = result.to
                }
                SetReactionResult.Removed -> return@launch
                SetReactionResult.Noop -> return@launch
            }

            // v1.3.1 audit P1 — tout le bloc post-dispatch est wrappé : un crash DataStore
            // (CorruptionException, IOException disque plein) ne doit pas remonter au
            // ViewModelScope (= crash process). Log + return safe.
            runCatching {
                // v1.3.1 audit F1/F2 — defense-in-depth : le UseCase refuse déjà les
                // messages outgoing et cible le sender unique, mais on garde un guard ici
                // pour ne MÊME PAS afficher le dialog confirm sur un cas invalide. Le repo
                // a déjà mis à jour la colonne reaction_emoji ; on ne touche pas au badge
                // local (cohérent avec la sémantique "réaction locale toujours possible").
                val targetMessage = repo.findMessageById(targetMessageId)
                if (targetMessage == null || targetMessage.isOutgoing) return@runCatching

                // v1.6.2 — lecture FRAÎCHE via `settings.flow.first()` au lieu de
                // `cachedSettings.value`. Le hot StateFlow Eagerly de v1.6.1 (PERF-11)
                // a un délai de propagation : si l'utilisateur réagit deux fois en
                // < 100 ms, la seconde lecture du flag `reactionConfirmDismissed` peut
                // encore retourner l'ancienne valeur `false` (le write async du
                // settings.update dans confirmReactionSend n'a pas encore atteint
                // le StateFlow), ce qui rouvre le dialog malgré la case "Ne plus
                // demander" cochée. Coût : ~5-10 ms par tap réaction (action humaine
                // lente, négligeable). Les 4 autres sites PERF-01 (envoi SMS/MMS hot
                // path) restent en `cachedSettings.value` car ils n'ont pas de write
                // précédent à attendre.
                val sending = settings.flow.first().sending
                if (!sending.sendReactionsToRecipient) return@runCatching

                // v1.4.1 — the confirmation dialog is only triggered on First (so the
                // user gets to opt-in to "send my reactions" the very first time). For
                // Changed, we assume the user has already validated the wire pattern
                // and dispatch silently.
                val isFirstEver = result is SetReactionResult.First
                if (sending.reactionConfirmDismissed || !isFirstEver) {
                    dispatchReactionSms(targetMessageId, targetEmoji, sending.reactionFormat)
                } else {
                    _events.tryEmit(Event.RequestReactionConfirm(targetMessageId, targetEmoji))
                }
            }.onFailure {
                timber.log.Timber.w(it, "setReaction post-emit failed for %d", targetMessageId)
            }
        }
    }

    /**
     * v1.3.1 — appelé par le dialog de confirmation du premier envoi. Re-vérifie que
     * l'état du message correspond toujours à l'emoji proposé (anti-race F4 : entre
     * l'ouverture du dialog et le confirm, l'utilisateur a pu changer/retirer la
     * réaction) avant de déclencher l'envoi SMS.
     *
     * Sémantique :
     *   - Si l'emoji actuel du message n'est plus [emoji] → abort silencieux (le badge
     *     local reflète déjà la nouvelle valeur, l'envoi n'aurait plus de sens).
     *   - Si [neverAskAgain] est `true` → on persiste la préférence AVANT l'envoi pour
     *     qu'elle prenne effet même si l'envoi échoue (retry ultérieur silencieux).
     *   - L'envoi est délégué à [dispatchReactionSms].
     */
    fun confirmReactionSend(messageId: Long, emoji: String, neverAskAgain: Boolean) {
        viewModelScope.launch {
            runCatching {
                // Anti-race F4 : si le user a changé/retiré la réaction depuis l'ouverture
                // du dialog, on n'envoie rien (et on ne persiste pas non plus la pref).
                val current = repo.findMessageById(messageId)
                if (current == null || current.reactionEmoji != emoji) {
                    timber.log.Timber.d("confirmReactionSend: state drifted, abort send")
                    return@runCatching
                }
                // X3 audit v1.3.1 — on persiste `reactionConfirmDismissed = true` UNIQUEMENT
                // après une dispatch SENT effective. Persister avant l'envoi (comportement
                // initial) avait un bug subtil : si `dispatchReactionSms` échouait toujours
                // (par exemple : NotDefaultSmsApp, blocklist, sender alphanumérique), la
                // préférence était persistée → tous les envois futurs étaient silencieusement
                // skip sans que l'utilisateur ait jamais validé qu'un SMS partait. Idem si
                // dédup-skippé : l'utilisateur n'a pas vu l'envoi réel, donc on garde le
                // dialog pour la prochaine fois. Seul un Sent confirme le contrat user.
                //
                // v1.3.6 audit P1 — lit le snapshot sending une seule fois et passe
                // au dispatcher (au lieu d'un second `cachedSettings.value` dans le
                // dispatcher lui-même → 2 lectures DataStore par envoi).
                // v1.6.1 audit PERF-01 — lecture synchrone zéro-I/O via cachedSettings.
                // v1.8.0 (bug 5 fix) — passe désormais le `ReactionFormat` (3 options)
                // au lieu du boolean `reactionEmojiOnly` (legacy 2 options).
                val reactionFormat = cachedSettings.value.sending.reactionFormat
                val outcome = dispatchReactionSms(messageId, emoji, reactionFormat)
                if (outcome == DispatchOutcome.Sent && neverAskAgain) {
                    settings.update {
                        it.copy(sending = it.sending.copy(reactionConfirmDismissed = true))
                    }
                }
            }.onFailure { timber.log.Timber.w(it, "confirmReactionSend failed for %d", messageId) }
        }
    }

    /**
     * v1.3.1 — chemin commun d'envoi SMS d'une réaction. Centralisé pour éviter la
     * duplication entre le chemin "silencieux" (case cochée) et le chemin "post-confirm".
     * Propage les erreurs via [Event.SendError] (snack utilisateur), comme tout autre
     * envoi. La cible (sender unique du message) est résolue dans [SendReactionUseCase].
     *
     * **Dedup X2 (audit v1.3.1)** : un utilisateur qui hésite peut générer la séquence
     * `null → ❤️ → null → ❤️ → …`, chaque pose étant un `SetReactionResult.First` (donc
     * éligible à l'envoi). Sans garde, c'est 1 SMS facturé par cycle. On stocke en RAM
     * (session, pas DataStore — c'est une protection contre les rafales, pas un cache
     * d'état long terme) la dernière dispatch par messageId et on skip si < 60 secondes.
     *
     * Retourne :
     *   - [DispatchOutcome.DedupSkipped] : dans la fenêtre, rien tenté
     *   - [DispatchOutcome.Sent] : le use case a renvoyé [Outcome.Success]
     *   - [DispatchOutcome.Failed] : tentative effectuée mais échouée (snack émis)
     *
     * @param format v1.8.0 (bug 5 fix) — passé par le caller qui a déjà lu le snapshot
     *   `sending` une fois ; évite une 2e lecture DataStore par envoi de réaction.
     *   Voir [com.filestech.sms.data.local.datastore.ReactionFormat] pour les 3 options
     *   (READABLE_FR par défaut, TAPBACK_EN pour compat iPhone, EMOJI_ONLY minimal).
     */
    private suspend fun dispatchReactionSms(
        messageId: Long,
        emoji: String,
        format: com.filestech.sms.data.local.datastore.ReactionFormat,
    ): DispatchOutcome {
        val now = System.currentTimeMillis()
        // v1.3.5 G7 audit fix — purge opportuniste des entries expirées AVANT lecture
        // (la map croîtrait sinon linéairement avec le nb de messages réagis pendant
        // la vie du ViewModel). Coût O(N) acceptable car N est borné par la fenêtre
        // de dedup et l'activité utilisateur (typiquement < 50 entries).
        recentlySentReactionFor.entries.removeAll { now - it.value.timestamp > REACTION_DEDUP_WINDOW_MS }
        // v1.4.1 — dedup ne s'applique QU'AU MÊME emoji. Si l'utilisateur change
        // (❤️ → 👍), la réaction part même si on est dans la fenêtre 60s — sinon le
        // destinataire reste bloqué sur la 1ʳᵉ réaction et ne voit jamais le changement.
        val last = recentlySentReactionFor[messageId]
        if (last != null && last.emoji == emoji && now - last.timestamp < REACTION_DEDUP_WINDOW_MS) {
            timber.log.Timber.d("dispatchReactionSms: dedup re-send on %d within %ds", messageId, REACTION_DEDUP_WINDOW_MS / 1000)
            return DispatchOutcome.DedupSkipped
        }
        recentlySentReactionFor[messageId] = ReactionDispatch(emoji = emoji, timestamp = now)
        return when (val res = sendReaction(messageId, emoji, format)) {
            is Outcome.Success -> DispatchOutcome.Sent
            is Outcome.Failure -> {
                _events.tryEmit(Event.SendError(res.error))
                DispatchOutcome.Failed
            }
        }
    }

    /** v1.3.1 — résultat triadique de [dispatchReactionSms] (audit X2 + X3). */
    private enum class DispatchOutcome { Sent, Failed, DedupSkipped }

    /**
     * v1.4.1 — `(emoji, timestamp)` tuple. Track emoji in addition to time so the dedup
     * only fires on **same emoji + within 60s** — a legitimate change of heart
     * (❤️ → 👍) bypasses the window and reaches the correspondent.
     */
    private data class ReactionDispatch(val emoji: String, val timestamp: Long)

    /**
     * X2 — dedup en mémoire des envois récents de réactions. Clé = messageId, valeur =
     * dernier `(emoji, epoch ms)` dispatché. RAM seulement (perdu au kill process) —
     * voulu : c'est une protection courte fenêtre contre les rafales de taps sur
     * **le même emoji**, pas un journal pérenne.
     */
    private val recentlySentReactionFor = mutableMapOf<Long, ReactionDispatch>()

    /**
     * Receives an attachment URI from [com.filestech.sms.ui.components.AttachmentPickerSheet]
     * (#2) and dispatches it as a multipart MMS via [SendMediaMmsUseCase].
     *
     * v1.2.1 — the URI is read through `ContentResolver`, copied into a private cache file
     * (`cache/media_outgoing/`) so the system content URI is no longer needed past the call,
     * then handed to the use case. The use case caps payload at 300 KB total (Free MMSC is
     * the tightest French carrier).
     *
     * Photo / Video / Contact card / arbitrary file are all routed through the same path; the
     * MIME type is detected from the content resolver, with a fallback per [kind] for cases
     * where the system doesn't expose it.
     */
    fun onAttachmentPicked(
        uri: android.net.Uri,
        kind: com.filestech.sms.ui.components.AttachmentKind,
    ) {
        if (_state.value.conversation == null) return
        viewModelScope.launch {
            val cr = context.contentResolver
            val mime = cr.getType(uri) ?: when (kind) {
                com.filestech.sms.ui.components.AttachmentKind.PHOTO -> "image/jpeg"
                com.filestech.sms.ui.components.AttachmentKind.VIDEO -> "video/mp4"
                com.filestech.sms.ui.components.AttachmentKind.CONTACT -> "text/x-vcard"
                else -> "application/octet-stream"
            }
            // Resolve the user-facing name via OpenableColumns when possible — Android's
            // PickVisualMedia / OpenDocument both expose it. Falls back to the cached filename
            // (auto-generated, so always visually ugly, hence the lookup).
            val displayName = resolveDisplayName(uri) ?: "Pièce jointe"
            val file = copyAttachmentToCache(uri, mime)
            if (file == null) {
                _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_COPY_FAILED, isError = true))
                return@launch
            }
            // Auto-compress images bigger than the carrier cap. Most photos pickers hand back
            // a 2-4 MB JPEG; we don't want to reject every photo, so we re-encode quality 75 →
            // 50 → 30 + downscale until it fits.
            val finalFile = if (mime.startsWith("image/") && file.length() > IMAGE_COMPRESS_THRESHOLD_BYTES) {
                compressImage(file) ?: file
            } else file

            // v1.3.4 — la PJ est AJOUTÉE à la liste `pendingAttachments` (vs setter unique
            // v1.2.1 derrière dialog modal). L'utilisateur visualise tout dans la bande
            // staging du composer et tap Envoyer quand il a fini d'empiler.
            //
            // Cap dynamique 280 KB (carrier MMSC FR le plus strict, cf. VoiceRecorder.kt).
            // Si l'ajout ferait dépasser le cap : on refuse + snack + delete fichier
            // temporaire pour ne pas leaker en cache.
            val currentTotal = _state.value.pendingAttachments.sumOf { it.sizeBytes }
            val draftLen = _state.value.draft.length.toLong()
            val projected = currentTotal + finalFile.length() + draftLen
            if (projected > CARRIER_PAYLOAD_CAP_BYTES) {
                runCatching { finalFile.delete() }
                _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_CAP_REACHED))
                return@launch
            }
            _state.update {
                it.copy(
                    pendingAttachments = it.pendingAttachments + PendingAttachment(
                        file = finalFile,
                        mimeType = mime,
                        displayName = displayName,
                        sizeBytes = finalFile.length(),
                    ),
                )
            }
        }
    }

    /**
     * v1.3.4 — envoie en UN SEUL MMS multipart le texte du draft + toutes les PJ
     * actuellement stagées. Vide le draft et la liste pendingAttachments en cas de
     * succès. En cas d'échec : on garde les PJ stagées pour permettre un retry sans
     * re-pick (les fichiers ne sont PAS supprimés).
     *
     * Si `pendingAttachments` est vide ET draft non vide : route vers le SMS texte
     * standard (pas via cette méthode — `doSend` est l'autre chemin pour texte pur).
     */
    private suspend fun dispatchPendingAttachments() {
        // v1.3.4 M2 — snapshot des PJ au moment du send. Si l'user ajoute une N+1ᵉ PJ
        // pendant que le dispatch est in-flight (1-5 s), elle ne sera PAS wipée par
        // le clear post-Success — on filtre uniquement les PJ effectivement envoyées.
        val pending = _state.value.pendingAttachments
        if (pending.isEmpty()) return
        val conv = _state.value.conversation ?: return
        val textBody = _state.value.draft

        // v1.3.4 M3 audit fix — re-check cap au moment du send (le check à
        // `onAttachmentPicked` ne couvre PAS le cas où l'user ajoute du texte APRÈS
        // les PJ et fait dépasser le total).
        val totalBytes = pending.sumOf { it.sizeBytes } + textBody.length.toLong()
        if (totalBytes > CARRIER_PAYLOAD_CAP_BYTES) {
            _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_CAP_REACHED))
            return
        }

        val payloads = pending.map { p ->
            SendMediaMmsUseCase.AttachmentPayload(file = p.file, mimeType = p.mimeType)
        }
        when (val res = sendMediaMms.invoke(
            recipients = conv.addresses,
            attachments = payloads,
            textBody = textBody,
        )) {
            is Outcome.Success -> {
                // M2 audit fix — filter, not wipe, pour préserver les PJ ajoutées
                // pendant le dispatch async.
                val dispatchedSet = pending.toSet()
                _state.update {
                    it.copy(
                        draft = "",
                        segments = segCounter.count(""),
                        pendingAttachments = it.pendingAttachments.filterNot { p -> p in dispatchedSet },
                    )
                }
                repo.setDraft(conversationId, null)
                _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_SENT))
            }
            is Outcome.Failure -> {
                // Garde les PJ stagées pour retry, ne supprime pas les fichiers cache.
                _events.tryEmit(Event.SendError(res.error))
            }
        }
    }

    /**
     * v1.3.4 — retire UNE PJ de la bande staging (clic sur le X du chip) + supprime
     * son fichier cache. M4 audit fix : matching par **path absolu du fichier**
     * (id stable) au lieu d'index numérique → résistant aux races add/remove qui
     * pourraient sinon viser la mauvaise PJ après un shift d'index.
     */
    fun removePendingAttachment(fileAbsolutePath: String) {
        val current = _state.value.pendingAttachments
        val removed = current.firstOrNull { it.file.absolutePath == fileAbsolutePath } ?: return
        _state.update { it.copy(pendingAttachments = current - removed) }
        runCatching { removed.file.delete() }
    }

    /**
     * v1.3.4 — vide la bande staging + supprime tous les fichiers cache. Appelé sur
     * quitter de la conversation, ou bouton "Tout supprimer" si exposé.
     */
    fun clearAllPendingAttachments() {
        val current = _state.value.pendingAttachments
        if (current.isEmpty()) return
        _state.update { it.copy(pendingAttachments = emptyList()) }
        current.forEach { runCatching { it.file.delete() } }
    }

    /** Resolves OpenableColumns.DISPLAY_NAME for a content URI. */
    private suspend fun resolveDisplayName(uri: android.net.Uri): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }

    /**
     * Re-encodes the picked image as JPEG until it fits under [CARRIER_PAYLOAD_CAP_BYTES],
     * trying decreasing quality and downscaling once if needed. Returns the re-encoded file
     * (new file in the same dir) or null if everything failed.
     */
    private suspend fun compressImage(src: java.io.File): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(src.absolutePath, opts) }
                .getOrNull() ?: return@withContext null
            val maxDim = 1600
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true,
                ).also { if (it != bitmap) bitmap.recycle() }
            } else bitmap

            val out = java.io.File(src.parentFile, src.nameWithoutExtension + "-compressed.jpg")
            for (quality in intArrayOf(75, 60, 45, 30)) {
                try {
                    out.outputStream().use { os ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, os)
                    }
                    if (out.length() in 1..CARRIER_PAYLOAD_CAP_BYTES) {
                        scaled.recycle()
                        // Drop the source — we only keep the compressed copy from here on.
                        runCatching { src.delete() }
                        return@withContext out
                    }
                } catch (t: Throwable) {
                    timber.log.Timber.w(t, "JPEG compress quality=%d failed", quality)
                }
            }
            scaled.recycle()
            // Last-resort: hand the original back, the use-case will fail the size check and
            // the user sees an explicit "trop volumineux" snackbar instead of a silent black hole.
            null
        }

    /**
     * Copies the content-URI handed back by the system picker into our private cache so the
     * MMS dispatch path can read it as a regular file (the system URI can revoke its grant
     * the moment the picker activity finishes). Returns `null` on IO error or empty content.
     */
    private suspend fun copyAttachmentToCache(uri: android.net.Uri, mime: String): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ext = mime.substringAfter('/', "bin").take(8).replace(Regex("[^A-Za-z0-9]"), "")
            val dir = java.io.File(context.cacheDir, "media_outgoing").apply { mkdirs() }
            val target = java.io.File(
                dir,
                "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.${ext.ifBlank { "bin" }}",
            )
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                target.takeIf { it.exists() && it.length() > 0L }
            }.getOrNull()
        }

    fun retry(messageId: Long) {
        viewModelScope.launch { retrySend.invoke(messageId) }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { repo.deleteMessage(messageId) }
    }

    /**
     * v1.12.0 — Déplace la conversation courante DANS le coffre ou L'EN
     * SORT depuis l'overflow ThreadScreen. Délègue à
     * [ToggleConversationStateUseCase.requestMoveToVault] qui check
     * `AppLockState` (refuse PanicDecoy + Locked, auto-`markUnlocked` sinon).
     * Émet un snackbar `ShowSnackbar` de confirmation succès / échec.
     *
     * En PanicDecoy, l'overflow item est masqué côté UI (cf. `state.isPanicDecoy`)
     * — defense in depth : le UseCase refuse aussi en domain.
     */
    fun moveCurrentConversationToVault(intoVault: Boolean) {
        viewModelScope.launch {
            val outcome = toggleConvState.requestMoveToVault(conversationId, intoVault)
            // v1.12.0 audit B3 — différencier l'échec "session verrouillée"
            // (AppError.Locked, retourné par requestMoveToVault si lockState
            // = Locked ou PanicDecoy) du failure générique : message UX clair
            // au lieu d'un "Déplacement échoué" ambigu.
            val msgRes = when (outcome) {
                is com.filestech.sms.core.result.Outcome.Success ->
                    if (intoVault) com.filestech.sms.R.string.vault_move_in_done
                    else com.filestech.sms.R.string.vault_move_out_done
                is com.filestech.sms.core.result.Outcome.Failure -> when (outcome.error) {
                    is com.filestech.sms.core.result.AppError.Locked ->
                        com.filestech.sms.R.string.error_session_locked
                    else ->
                        if (intoVault) com.filestech.sms.R.string.vault_move_in_failed
                        else com.filestech.sms.R.string.vault_move_out_failed
                }
            }
            _events.tryEmit(
                Event.ShowSnackbar(
                    message = context.getString(msgRes),
                    isError = outcome is com.filestech.sms.core.result.Outcome.Failure,
                ),
            )
        }
    }

    /**
     * v1.11.0 — Sujet 5 apparence : couleur bulle sortante + avatar custom
     * (URI `content://` persistée par l'appelant via
     * `takePersistableUriPermission`). `null` sur un argument = reset à la
     * valeur par défaut.
     */
    fun setAppearance(bubbleColorArgb: Int?, avatarUri: String?) {
        viewModelScope.launch {
            toggleConvState.setAppearance(conversationId, bubbleColorArgb, avatarUri)
            _events.tryEmit(
                Event.ShowSnackbar(context.getString(com.filestech.sms.R.string.appearance_saved)),
            )
        }
    }

    // ──────────────────────────── Voice MMS composer ────────────────────────────

    /**
     * Starts a new recording. Caller must have already obtained [android.Manifest.permission.RECORD_AUDIO]
     * — the underlying [MediaRecorder] would otherwise emit a SecurityException and the use case
     * surfaces it as [AppError.Permission].
     *
     * If the user happens to be Reviewing a previous recording, that recording is discarded
     * (file deleted, playback stopped) before the new one starts.
     */
    fun startVoiceRecording() {
        if (_state.value.voice is VoiceState.Recording) return
        discardVoiceDraft()
        _state.update { it.copy(voice = VoiceState.Recording(elapsedMs = 0, amplitude = 0)) }
        recorderJob = viewModelScope.launch {
            voiceRecorder.record().collect { event ->
                when (event) {
                    is VoiceRecorder.Event.Tick -> _state.update { st ->
                        if (st.voice is VoiceState.Recording) {
                            st.copy(voice = VoiceState.Recording(event.elapsedMs, event.amplitude))
                        } else st
                    }
                    is VoiceRecorder.Event.Completed -> {
                        _state.update {
                            it.copy(
                                voice = VoiceState.Reviewing(
                                    file = event.result.file,
                                    durationMs = event.result.durationMs,
                                    sizeBytes = event.result.sizeBytes,
                                    mimeType = event.result.mimeType,
                                ),
                            )
                        }
                        if (event.cappedByLimit) {
                            _events.tryEmit(Event.ShowSnackbar(SNACK_MAX_DURATION))
                        }
                    }
                    is VoiceRecorder.Event.Failed -> {
                        _state.update { it.copy(voice = VoiceState.Idle) }
                        _events.tryEmit(Event.SendError(event.error))
                    }
                }
            }
        }
    }

    /** Asks the recorder to stop. Result will appear as [VoiceState.Reviewing] via the Flow. */
    fun stopVoiceRecording() {
        if (_state.value.voice !is VoiceState.Recording) return
        voiceRecorder.stop()
    }

    /**
     * Discards the active recording (Recording or Reviewing). Deletes the audio file and stops
     * any in-progress playback. Idempotent.
     */
    fun discardVoiceDraft() {
        playbackController.stop()
        when (val v = _state.value.voice) {
            is VoiceState.Recording -> voiceRecorder.cancel()
            is VoiceState.Reviewing -> v.file.delete()
            VoiceState.Idle -> Unit
        }
        recorderJob?.cancel()
        recorderJob = null
        _state.update { it.copy(voice = VoiceState.Idle) }
    }

    /** Toggles playback of any audio source, keyed for the controller's single-clip-at-a-time guarantee. */
    fun togglePlayback(key: String, uri: Uri) {
        playbackController.toggle(key, uri)
    }

    /** Seeks within whichever clip is currently active in the playback controller. */
    fun seekPlaybackTo(positionMs: Int) {
        playbackController.seekTo(positionMs)
    }

    /** Convenience for the composer preview — keyed on the recording's absolute path. */
    fun togglePreviewPlayback() {
        val r = _state.value.voice as? VoiceState.Reviewing ?: return
        playbackController.toggle(r.file.absolutePath, Uri.fromFile(r.file))
    }

    fun seekPreviewTo(positionMs: Int) {
        if (_state.value.voice !is VoiceState.Reviewing) return
        playbackController.seekTo(positionMs)
    }

    /**
     * Sends the reviewed audio clip as an MMS to the conversation's recipients. On failure the
     * draft is preserved so the user can retry; on success the audio file's ownership transfers
     * to the AttachmentEntity and the composer returns to Idle.
     */
    fun sendVoiceMms() {
        if (_state.value.conversation == null) return
        if (_state.value.voice !is VoiceState.Reviewing) return
        if (_state.value.isSendingVoice) return
        viewModelScope.launch {
            val confirm = cachedSettings.value.sending.confirmBeforeBroadcast
            if (confirm) {
                _state.update { it.copy(pendingVoiceConfirm = true) }
            } else {
                doSendVoice()
            }
        }
    }

    /** Confirms and actually dispatches the staged voice recording. */
    fun confirmPendingVoice() {
        if (!_state.value.pendingVoiceConfirm) return
        _state.update { it.copy(pendingVoiceConfirm = false) }
        viewModelScope.launch { doSendVoice() }
    }

    /** Dismisses the voice confirm dialog without sending — the recording stays in Reviewing. */
    fun cancelPendingVoice() {
        _state.update { it.copy(pendingVoiceConfirm = false) }
    }

    private suspend fun doSendVoice() {
        val conv = _state.value.conversation ?: return
        val reviewing = _state.value.voice as? VoiceState.Reviewing ?: return
        _state.update { it.copy(isSendingVoice = true) }
        playbackController.stop()
        when (val res = sendVoiceMms.invoke(
            recipients = conv.addresses,
            audioFile = reviewing.file,
            mimeType = reviewing.mimeType,
            durationMs = reviewing.durationMs,
        )) {
            is Outcome.Success -> {
                _state.update { it.copy(voice = VoiceState.Idle, isSendingVoice = false) }
                _events.tryEmit(Event.ShowSnackbar(SNACK_VOICE_SENT))
            }
            is Outcome.Failure -> {
                _state.update { it.copy(isSendingVoice = false) }
                _events.tryEmit(Event.SendError(res.error))
            }
        }
    }

    // ──────────────────────────── Misc ────────────────────────────

    fun exportToPdf() {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            when (val r = exportPdf.invoke(conversationId)) {
                is Outcome.Success -> _events.tryEmit(Event.PdfReady(r.value.shareUri, r.value.pages))
                // v1.6.1 (audit QUAL-07) — string localisée (avant : "PDF export failed"
                // affiché en anglais même sur device FR — regression i18n).
                is Outcome.Failure -> _events.tryEmit(
                    Event.ShowSnackbar(
                        context.getString(com.filestech.sms.R.string.snack_pdf_export_failed),
                        isError = true,
                    ),
                )
            }
            _state.update { it.copy(isExporting = false) }
        }
    }

    fun requestAddContact() {
        val addr = _state.value.conversation?.addresses?.firstOrNull()?.raw ?: return
        _events.tryEmit(Event.OpenAddContact(addr))
    }

    fun requestCall() {
        val addr = _state.value.conversation?.addresses?.firstOrNull()?.raw ?: return
        _events.tryEmit(Event.OpenDialer(addr))
    }

    /**
     * Blocks every address of the current conversation AND deletes the conversation locally.
     * v1.2.5 fix: previously only the block call ran — the conversation stayed in the list
     * showing past history of the now-blocked number, which is not what the user expects when
     * they explicitly tap "Block" from inside a conversation. The list-level Block action
     * stays unchanged (block-only) so users keeping history have a path.
     */
    fun blockSenders(onDone: () -> Unit = {}) {
        val addresses = _state.value.conversation?.addresses ?: return
        viewModelScope.launch {
            for (addr in addresses) blockNumber.invoke(addr.raw)
            toggleConvState.delete(conversationId)
            _events.tryEmit(Event.ShowSnackbar("Numéro(s) bloqué(s)"))
            onDone()
        }
    }

    fun deleteThisConversation(onDeleted: () -> Unit) {
        viewModelScope.launch {
            toggleConvState.delete(conversationId)
            onDeleted()
        }
    }

    fun onConversationOpened() {
        viewModelScope.launch { markRead.invoke(conversationId) }
        // v1.3.3 bug #4 — auto-consume du partage entrant. Z3 audit fix : on ATTEND
        // que la conversation soit hydratée (state.conversation != null) avant
        // d'appeler onAttachmentPicked, qui sinon retourne early sans rien attacher
        // et le holder serait vidé pour rien.
        consumeIncomingShareIfAny()
    }

    /**
     * v1.4.0 (F5 forward feature) — stages the given [message] into [com.filestech.sms.system.share
     * .IncomingShareHolder] so the next [ThreadViewModel] that hydrates (i.e. the
     * destination thread the user picks in [com.filestech.sms.ui.components
     * .ForwardMessageSheet]) pulls it through [consumeIncomingShareIfAny] and pre-fills
     * its draft / staged attachment.
     *
     * The payload mirrors what the share-target flow produces for [android.content
     * .Intent.ACTION_SEND]:
     *   - `text` = the message body (trimmed, `null` if blank);
     *   - `uris` = the first attachment's URI if any (image / file / audio / video). The
     *     URI is built from the stored `localUri` — either a `content://` (system MMS
     *     parts) used as-is, or a `file://` for our own cache (intra-process consume,
     *     no FileUriExposedException because no [Intent] crosses).
     *   - `mimeType` = the attachment's MIME (drives the `AttachmentKind` selection in
     *     [onAttachmentPicked]).
     */
    fun stageForward(message: com.filestech.sms.domain.model.Message) {
        val text = message.body.trim().takeIf { it.isNotBlank() }
        val attachment = message.attachments.firstOrNull()
        val uris = buildList {
            if (attachment != null && attachment.localUri.isNotBlank()) {
                // v1.4.0 (S1) — local file paths are wrapped via FileProvider so the
                // resulting `content://` URI stays safe even if a future caller stuffs it
                // into an outgoing Intent. Using `Uri.fromFile` here was technically OK
                // (the consumer is intra-process via `ContentResolver.openInputStream`),
                // but the `file://` pattern is a known FileUriExposedException landmine
                // and we standardise on FileProvider throughout the app already (cf.
                // [MediaAttachmentBubble.toShareableUri]).
                val uri = if (attachment.localUri.startsWith("content://")) {
                    android.net.Uri.parse(attachment.localUri)
                } else {
                    val file = java.io.File(attachment.localUri)
                    if (file.exists()) {
                        runCatching {
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                        }.getOrNull()
                    } else null
                }
                if (uri != null) add(uri)
            }
        }
        val pending = com.filestech.sms.system.share.IncomingShareHolder.Pending(
            uris = uris,
            mimeType = attachment?.mimeType,
            text = text,
        )
        if (!pending.isEmpty) {
            incomingShare.set(pending)
        }
    }

    /**
     * v1.3.3 bug #4 — auto-attach d'un partage entrant. Pas de routing dédié pour
     * v1.3.3 ; l'user choisit lui-même la conversation cible parmi sa liste après
     * tap sur SMS Tech dans le chooser système. Le 1ᵉʳ URI du partage est attaché ;
     * les éventuels suivants (ACTION_SEND_MULTIPLE) sont ignorés cette release (v1.3.4
     * gérera le multi-attach). Le texte EXTRA_TEXT est préchargé dans le draft.
     *
     * Z3 audit fix : on suspend jusqu'à hydration de `state.conversation`. Sans ça
     * la course `onConversationOpened` ⇆ flow Room peut faire échouer silencieusement
     * `onAttachmentPicked` qui guarde `if (_state.value.conversation == null) return`.
     */
    private fun consumeIncomingShareIfAny() {
        viewModelScope.launch {
            // Attente bornée : si la conv n'arrive pas en 5 s (cas extrême purge
            // concurrente), on abandonne pour ne pas leaker le holder en RAM.
            val hydrated = withTimeoutOrNull(5_000L) {
                _state.first { it.conversation != null }
            }
            if (hydrated == null) {
                timber.log.Timber.w("consumeIncomingShareIfAny: conversation never hydrated, share ignored")
                return@launch
            }
            val pending = incomingShare.consume() ?: return@launch
            // 1) Texte → draft si encore vide.
            if (!pending.text.isNullOrBlank() && _state.value.draft.isBlank()) {
                updateDraft(pending.text)
            }
            // 2) Premier URI → attachement via le pipeline standard.
            val firstUri = pending.uris.firstOrNull() ?: return@launch
            val kind = when {
                pending.mimeType?.startsWith("image/") == true ->
                    com.filestech.sms.ui.components.AttachmentKind.PHOTO
                pending.mimeType?.startsWith("video/") == true ->
                    com.filestech.sms.ui.components.AttachmentKind.VIDEO
                pending.mimeType == "text/x-vcard" || pending.mimeType == "text/vcard" ->
                    com.filestech.sms.ui.components.AttachmentKind.CONTACT
                else -> com.filestech.sms.ui.components.AttachmentKind.FILE
            }
            onAttachmentPicked(firstUri, kind)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // v1.3.9 — libère le slot "conversation active" pour que les notifs futures
        // (l'app étant désormais en background ou sur une autre conv) repassent en
        // persistance normale. Le `compareAndSet` interne au tracker garantit qu'un
        // ViewModel d'une autre conv fraîchement init ne sera pas écrasé par ce clear
        // (race configChange : new init avant old onCleared).
        activeConversationTracker.clearActive(conversationId)
        recorderJob?.cancel()
        voiceRecorder.cancel()
        playbackController.stop()
        // v1.2.3 audit F13: an attachment staged in `media_outgoing/` but never confirmed or
        // cancelled (Activity recreated, user backed out) leaks until the TelephonySyncWorker's
        // 24 h prune sweeps it. Clean up the in-memory staged file synchronously here so the
        // common path (rotate, back-out) never leaves a trail.
        // v1.3.4 — cleanup TOUS les fichiers cache des PJ stagées non-envoyées.
        _state.value.pendingAttachments.forEach { p -> runCatching { p.file.delete() } }
    }

    private companion object {
        // v1.3.0 audit Q8 — calculé depuis `VoiceRecorder.MAX_DURATION_MS` pour qu'un futur
        // changement du cap soit reflété sans drift dans le snack utilisateur.
        val SNACK_MAX_DURATION: String = "Limite de ${VoiceRecorder.MAX_DURATION_MS / 1000} s atteinte"
        const val SNACK_ATTACH_SENT: String = "Pièce jointe envoyée"
        const val SNACK_ATTACH_COPY_FAILED: String = "Impossible de lire la pièce jointe"
        const val SNACK_ATTACH_CAP_REACHED: String =
            "Limite MMS atteinte (280 Ko). Retirez une pièce jointe ou réduisez le texte."

        // Image compression knobs. The first threshold says "do nothing for tiny images";
        // the second is the hard target used by the quality loop.
        const val IMAGE_COMPRESS_THRESHOLD_BYTES: Long = 250L * 1024L
        const val CARRIER_PAYLOAD_CAP_BYTES: Long = 280L * 1024L
        const val SNACK_VOICE_SENT: String = "Message vocal envoyé"

        /**
         * X2 audit v1.3.1 — fenêtre de dédup pour les envois SMS de réaction. Un user qui
         * tape/retire/re-tape rapidement le même emoji ne paie qu'un SMS dans cette fenêtre.
         * 60 secondes est large : couvre la rafale émotionnelle typique sans bloquer un
         * envoi légitime "j'ai re-réfléchi 2 min plus tard".
         */
        const val REACTION_DEDUP_WINDOW_MS: Long = 60_000L
    }
}
