package com.filestech.sms.ui.screens.thread

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.asEvents
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.ml.TranslationService
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
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
    private val translator: TranslationService,
    private val sendReaction: SendReactionUseCase,
    private val incomingShare: com.filestech.sms.system.share.IncomingShareHolder,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
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
         * Per-message on-device translation cache (#4). Maps the message's local id to its
         * current translation state — Pending while ML Kit detects + downloads the model,
         * Ready once the target string is available, Failed when the language pair is not
         * supported or the model download was refused.
         *
         * The map is **session-scoped** by design: translations are not persisted in Room.
         * Re-opening the thread starts with an empty cache and the user re-arms translation
         * on whichever bubbles they care about, which avoids exporting ML output to backups.
         */
        val translations: Map<Long, TranslationState> = emptyMap(),
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
    )

    /** Staged attachment awaiting user confirmation in the composer. */
    data class PendingAttachment(
        val file: java.io.File,
        val mimeType: String,
        val displayName: String,
        val sizeBytes: Long,
    )

    /** UI projection of an in-flight or completed translation for a single bubble. */
    sealed interface TranslationState {
        data object Pending : TranslationState
        data class Ready(val translated: String, val sourceLanguage: String) : TranslationState
        data object Failed : TranslationState
    }

    sealed interface Event {
        data class ShowSnackbar(val message: String) : Event
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

    init {
        // Audit Q3: ONE observation of the conversation row (seeds the draft on first emission
        // only — subsequent emissions don't overwrite what the user is typing).
        repo.observeOne(conversationId).onEach { conv ->
            _state.update { st ->
                val seededDraft = !st.draftSeeded && !conv?.draft.isNullOrEmpty()
                val draft = if (seededDraft) conv!!.draft.orEmpty() else st.draft
                st.copy(
                    conversation = conv,
                    draft = draft,
                    segments = if (seededDraft) segCounter.count(draft) else st.segments,
                    draftSeeded = st.draftSeeded || conv != null,
                )
            }
            val firstNumber = conv?.addresses?.firstOrNull()?.raw
            val has = firstNumber?.let { contactRepo.lookupByPhone(it) != null } ?: false
            _state.update { it.copy(hasContact = has) }
        }.launchIn(viewModelScope)

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
            val confirm = settings.flow.first().sending.confirmBeforeBroadcast
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
     * Kicks off an on-device translation of [messageId]'s body (#4). The target language is
     * taken from the user's locale preference (`SettingsRepository.locale.languageTag`); when
     * absent we fall back to the device's primary locale. The UI observes [UiState.translations]
     * to render the [TranslationBlock] under the bubble.
     *
     * Calling twice on the same message id while a translation is already in flight is a
     * no-op (idempotent), so accidental double-taps don't double the model-download work.
     */
    fun translateMessage(messageId: Long) {
        val existing = _state.value.translations[messageId]
        if (existing is TranslationState.Pending || existing is TranslationState.Ready) return
        val msg = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (msg.body.isBlank()) return
        _state.update { it.copy(translations = it.translations + (messageId to TranslationState.Pending)) }
        viewModelScope.launch {
            val targetTag = settings.flow.first().locale.languageTag
                ?: java.util.Locale.getDefault().language
            when (val res = translator.translate(msg.body, targetTag)) {
                is Outcome.Success -> _state.update {
                    it.copy(
                        translations = it.translations + (messageId to TranslationState.Ready(
                            translated = res.value.translated,
                            sourceLanguage = res.value.sourceLanguage,
                        )),
                    )
                }
                is Outcome.Failure -> {
                    _state.update { it.copy(translations = it.translations + (messageId to TranslationState.Failed)) }
                    _events.tryEmit(Event.ShowSnackbar(SNACK_TRANSLATE_FAILED))
                }
            }
        }
    }

    /** Collapses any visible translation for [messageId]. Idempotent. */
    fun dismissTranslation(messageId: Long) {
        if (messageId !in _state.value.translations) return
        _state.update { it.copy(translations = it.translations - messageId) }
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

            if (result !is SetReactionResult.First) return@launch

            // v1.3.1 audit P1 — tout le bloc post-First est wrappé : un crash DataStore
            // (CorruptionException, IOException disque plein) ne doit pas remonter au
            // ViewModelScope (= crash process). Log + return safe.
            runCatching {
                // v1.3.1 audit F1/F2 — defense-in-depth : le UseCase refuse déjà les
                // messages outgoing et cible le sender unique, mais on garde un guard ici
                // pour ne MÊME PAS afficher le dialog confirm sur un cas invalide. Le repo
                // a déjà mis à jour la colonne reaction_emoji ; on ne touche pas au badge
                // local (cohérent avec la sémantique "réaction locale toujours possible").
                val targetMessage = repo.findMessageById(result.messageId)
                if (targetMessage == null || targetMessage.isOutgoing) return@runCatching

                val sending = settings.flow.first().sending
                if (!sending.sendReactionsToRecipient) return@runCatching

                if (sending.reactionConfirmDismissed) {
                    dispatchReactionSms(result.messageId, result.emoji)
                } else {
                    _events.tryEmit(Event.RequestReactionConfirm(result.messageId, result.emoji))
                }
            }.onFailure {
                timber.log.Timber.w(it, "setReaction post-emit failed for %d", result.messageId)
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
                val outcome = dispatchReactionSms(messageId, emoji)
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
     */
    private suspend fun dispatchReactionSms(messageId: Long, emoji: String): DispatchOutcome {
        val now = System.currentTimeMillis()
        val last = recentlySentReactionFor[messageId]
        if (last != null && now - last < REACTION_DEDUP_WINDOW_MS) {
            timber.log.Timber.d("dispatchReactionSms: dedup re-send on %d within %ds", messageId, REACTION_DEDUP_WINDOW_MS / 1000)
            return DispatchOutcome.DedupSkipped
        }
        recentlySentReactionFor[messageId] = now
        return when (val res = sendReaction(messageId, emoji)) {
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
     * X2 — dedup en mémoire des envois récents de réactions. Clé = messageId, valeur =
     * epoch ms de la dernière dispatch. RAM seulement (perdu au kill process) — voulu :
     * c'est une protection courte fenêtre contre les rafales de taps, pas un journal
     * pérenne. Suffit largement pour le cas d'usage "hésitation utilisateur".
     */
    private val recentlySentReactionFor = mutableMapOf<Long, Long>()

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
                _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_COPY_FAILED))
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
            val confirm = settings.flow.first().sending.confirmBeforeBroadcast
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
                is Outcome.Failure -> _events.tryEmit(Event.ShowSnackbar("PDF export failed"))
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
        const val SNACK_TRANSLATE_FAILED: String = "Échec de la traduction"
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
