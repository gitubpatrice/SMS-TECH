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
import com.filestech.sms.domain.usecase.ExportConversationPdfUseCase
import com.filestech.sms.domain.usecase.MarkConversationReadUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
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
         * Attachment staged for the user to confirm before dispatch (v1.2.1 UX fix). The picker
         * lands here first; the user sees a preview dialog and only the explicit "Envoyer" tap
         * triggers the actual MMS dispatch. Cancelling deletes the cached file.
         */
        val pendingAttachment: PendingAttachment? = null,
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
        if (body.isEmpty()) return
        viewModelScope.launch {
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

            // v1.2.1: the `confirmBeforeBroadcast` setting now drives the **attachment** dialog
            // exclusively (SMS / MMS text always sends directly). When OFF, the user wants the
            // Google-Messages flow: pick a photo, off it goes. When ON, the staging dialog
            // with filename + size is shown so they can sanity-check before dispatch.
            val confirmFirst = settings.flow.first().sending.confirmBeforeBroadcast
            if (confirmFirst) {
                _state.update {
                    it.copy(
                        pendingAttachment = PendingAttachment(
                            file = finalFile,
                            mimeType = mime,
                            displayName = displayName,
                            sizeBytes = finalFile.length(),
                        ),
                    )
                }
            } else {
                dispatchAttachment(finalFile, mime)
            }
        }
    }

    /** Internal: actually dispatches a (compressed-if-needed) attachment file. */
    private suspend fun dispatchAttachment(file: java.io.File, mime: String) {
        val conv = _state.value.conversation ?: return
        val payload = SendMediaMmsUseCase.AttachmentPayload(file = file, mimeType = mime)
        when (val res = sendMediaMms.invoke(
            recipients = conv.addresses,
            attachments = listOf(payload),
            textBody = _state.value.draft,
        )) {
            is Outcome.Success -> {
                _state.update { it.copy(draft = "", segments = segCounter.count("")) }
                repo.setDraft(conversationId, null)
                _events.tryEmit(Event.ShowSnackbar(SNACK_ATTACH_SENT))
            }
            is Outcome.Failure -> {
                runCatching { file.delete() }
                _events.tryEmit(Event.SendError(res.error))
            }
        }
    }

    /** Confirms and dispatches the staged attachment. Cleans up on failure. */
    fun confirmPendingAttachment() {
        val pending = _state.value.pendingAttachment ?: return
        _state.update { it.copy(pendingAttachment = null) }
        viewModelScope.launch { dispatchAttachment(pending.file, pending.mimeType) }
    }

    /** Discards the staged attachment without sending. Deletes the cached file. */
    fun cancelPendingAttachment() {
        val pending = _state.value.pendingAttachment ?: return
        _state.update { it.copy(pendingAttachment = null) }
        runCatching { pending.file.delete() }
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

    fun blockSenders() {
        val addresses = _state.value.conversation?.addresses ?: return
        viewModelScope.launch {
            for (addr in addresses) blockNumber.invoke(addr.raw)
            _events.tryEmit(Event.ShowSnackbar("Numéro(s) bloqué(s)"))
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
    }

    override fun onCleared() {
        super.onCleared()
        recorderJob?.cancel()
        voiceRecorder.cancel()
        playbackController.stop()
    }

    private companion object {
        const val SNACK_MAX_DURATION: String = "Limite de 60 s atteinte"
        const val SNACK_TRANSLATE_FAILED: String = "Échec de la traduction"
        const val SNACK_ATTACH_SENT: String = "Pièce jointe envoyée"
        const val SNACK_ATTACH_COPY_FAILED: String = "Impossible de lire la pièce jointe"

        // Image compression knobs. The first threshold says "do nothing for tiny images";
        // the second is the hard target used by the quality loop.
        const val IMAGE_COMPRESS_THRESHOLD_BYTES: Long = 250L * 1024L
        const val CARRIER_PAYLOAD_CAP_BYTES: Long = 280L * 1024L
        const val SNACK_VOICE_SENT: String = "Message vocal envoyé"
    }
}
