package com.filestech.sms.ui.screens.thread

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.components.AttachmentPickerSheet
import com.filestech.sms.ui.components.AudioMessageBubble
import com.filestech.sms.ui.components.BurstPosition
import com.filestech.sms.ui.components.ComposerReplyChip
import com.filestech.sms.ui.components.MessageBubble
import com.filestech.sms.ui.components.ReplyQuotePreview
import com.filestech.sms.ui.components.toPlaybackUri
import com.filestech.sms.ui.util.daySeparatorLabel
import com.filestech.sms.ui.util.rememberChatFormatters
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.max

/** Brand danger color used for swipe-to-delete background + destructive dialog confirm button. */
private val BrandDanger = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    conversationId: Long,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val formatters = rememberChatFormatters()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var askBlock by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    LaunchedEffect(Unit) {
        viewModel.onConversationOpened()
        viewModel.events.collect { e ->
            when (e) {
                is ThreadViewModel.Event.ShowSnackbar -> snackbarHost.showSnackbar(e.message)
                is ThreadViewModel.Event.PdfReady -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, "PDF")) }
                    snackbarHost.showSnackbar(context.getString(R.string.thread_export_success, e.pages))
                }
                is ThreadViewModel.Event.SendError -> snackbarHost.showSnackbar(
                    context.getString(R.string.error_send_failed),
                )
                is ThreadViewModel.Event.OpenAddContact -> {
                    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, e.rawNumber)
                    }
                    runCatching { context.startActivity(intent) }
                }
                is ThreadViewModel.Event.OpenDialer -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(e.rawNumber)}"))
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    val title = state.conversation?.displayName
        ?: state.conversation?.addresses?.joinToString { it.raw }.orEmpty()
    val canCall = state.conversation?.addresses?.size == 1

    // #8 contextual reply — index messages by id so each bubble can resolve its quoted target
    // in O(1) during recomposition. `remember(state.messages)` cap rebuilds to the actual list
    // identity changes (the Flow emits a fresh list when Room invalidates, but pure UI ticks
    // — playback ticker, draft typing — do not re-run this).
    val youLabel = stringResource(R.string.chat_you)
    val deletedLabel = stringResource(R.string.thread_reply_deleted)
    val contactDisplayName = state.conversation?.displayName
    val messageById = remember(state.messages) {
        state.messages.associateBy { it.id }
    }
    fun previewOf(tgt: Message): ReplyQuotePreview = ReplyQuotePreview(
        senderLabel = if (tgt.isOutgoing) youLabel else (contactDisplayName ?: tgt.address),
        body = tgt.body.take(140),
        isFromSelf = tgt.isOutgoing,
    )
    fun previewFor(msg: Message): ReplyQuotePreview? {
        val tgtId = msg.replyToMessageId ?: return null
        val tgt = messageById[tgtId]
            ?: return ReplyQuotePreview(senderLabel = "—", body = deletedLabel, isFromSelf = false)
        return previewOf(tgt)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (canCall) {
                        IconButton(onClick = { viewModel.requestCall() }) {
                            Icon(
                                Icons.Outlined.Phone,
                                contentDescription = stringResource(R.string.action_call),
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    ThreadActionsMenu(
                        expanded = menuOpen,
                        hasContact = state.hasContact,
                        isExporting = state.isExporting,
                        canExport = state.messages.isNotEmpty(),
                        onDismiss = { menuOpen = false },
                        onAddContact = { menuOpen = false; viewModel.requestAddContact() },
                        onDetails = { menuOpen = false; detailsOpen = true },
                        onExportPdf = { menuOpen = false; viewModel.exportToPdf() },
                        onBlock = { menuOpen = false; askBlock = true },
                        onDelete = { menuOpen = false; askDelete = true },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
        bottomBar = {
            Column {
                // #8 contextual reply chip — renders above the composer when a reply is armed.
                // Cancelling drops the target without sending; sending the message clears it
                // automatically through `ThreadViewModel.doSend`.
                state.replyingTo?.let { target ->
                    ComposerReplyChip(
                        preview = previewOf(target),
                        onCancel = viewModel::cancelReply,
                    )
                }
                ComposerBar(
                    voice = state.voice,
                    playback = playbackState,
                    draft = state.draft,
                    segments = state.segments,
                    isSendingVoice = state.isSendingVoice,
                    onDraftChanged = viewModel::updateDraft,
                    onSendText = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.send()
                    },
                    onStartRecording = viewModel::startVoiceRecording,
                    onStopRecording = viewModel::stopVoiceRecording,
                    onCancelRecording = viewModel::discardVoiceDraft,
                    onTogglePreview = viewModel::togglePreviewPlayback,
                    onSeekPreview = viewModel::seekPreviewTo,
                    onDiscardDraft = viewModel::discardVoiceDraft,
                    onSendVoice = viewModel::sendVoiceMms,
                    onAttachClick = { attachmentSheetOpen = true },
                    onMicPermissionDenied = {
                        scope.launch {
                            snackbarHost.showSnackbar(
                                context.getString(R.string.voice_permission_denied),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Audit P-Q8 (v1.2.0): `formatters` is already declared at function scope (above the
        // Scaffold). Re-declaring it here forced a fresh allocation on every Scaffold-body
        // recomposition — wasted work given the formatters are stateless cached singletons.
        val todayLabel = stringResource(R.string.date_today)
        val yesterdayLabel = stringResource(R.string.date_yesterday)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
        ) {
            itemsIndexed(state.messages, key = { _, m -> m.id }) { index, msg ->
                val prev = state.messages.getOrNull(index - 1)
                val next = state.messages.getOrNull(index + 1)
                val showTimestamp = prev?.date?.let { msg.date - it > 5 * 60_000L } ?: true

                // Insert a centered date divider whenever we cross a calendar-day boundary
                // (including the first message of the thread).
                if (prev == null || !sameDay(prev.date, msg.date)) {
                    DateSeparator(
                        label = formatters.daySeparatorLabel(
                            timestampMillis = msg.date,
                            todayLabel = todayLabel,
                            yesterdayLabel = yesterdayLabel,
                        ),
                    )
                }

                val burstPosition = computeBurstPosition(prev, msg, next)
                val audio = msg.audioAttachment
                if (audio != null) {
                    // Audit P-P1-2: pass the playback state as a **lambda**, not the value.
                    // The bubble re-reads it only inside its own `derivedStateOf`, so the 5 Hz
                    // ticker only recomposes the bubble that is actually playing — all the
                    // other bubbles in the thread stay frozen.
                    AudioMessageBubble(
                        message = msg,
                        audio = audio,
                        playbackProvider = { playbackState },
                        onTogglePlay = { viewModel.togglePlayback(audio.localUri, audio.toPlaybackUri()) },
                        onSeekTo = viewModel::seekPlaybackTo,
                        onDelete = { pendingDelete = msg },
                        onReply = { viewModel.startReply(msg) },
                        repliedToPreview = previewFor(msg),
                        showTimestamp = showTimestamp,
                    )
                } else {
                    val translation = state.translations[msg.id]
                    val translatedBody = (translation as? ThreadViewModel.TranslationState.Ready)?.translated
                    MessageBubble(
                        message = msg,
                        showTimestamp = showTimestamp,
                        burstPosition = burstPosition,
                        onTap = { if (msg.status == Message.Status.FAILED) viewModel.retry(msg.id) },
                        onDelete = { pendingDelete = msg },
                        onReply = { viewModel.startReply(msg) },
                        onTranslate = if (msg.body.isNotBlank() && translation !is ThreadViewModel.TranslationState.Ready) {
                            { viewModel.translateMessage(msg.id) }
                        } else null,
                        repliedToPreview = previewFor(msg),
                        translatedBody = translatedBody,
                        onDismissTranslation = if (translatedBody != null) {
                            { viewModel.dismissTranslation(msg.id) }
                        } else null,
                    )
                }
            }
        }
    }

    pendingDelete?.let { msg ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.thread_confirm_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deleteMessage(msg.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (attachmentSheetOpen) {
        AttachmentPickerSheet(
            onDismiss = { attachmentSheetOpen = false },
            onAttachmentPicked = { uri, kind ->
                viewModel.onAttachmentPicked(uri, kind)
            },
        )
    }

    if (detailsOpen) {
        ConversationDetailsDialog(
            title = title,
            participants = state.conversation?.addresses?.size ?: 0,
            messages = state.messageCount,
            firstAt = state.firstMessageAt,
            lastAt = state.lastMessageAt,
            formatters = formatters,
            onDismiss = { detailsOpen = false },
        )
    }
    if (askBlock) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.action_block),
            message = title,
            confirmLabel = stringResource(R.string.action_block),
            onConfirm = {
                askBlock = false
                viewModel.blockSenders()
                onBack()
            },
            onDismiss = { askBlock = false },
        )
    }
    if (askDelete) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = title,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                askDelete = false
                viewModel.deleteThisConversation { onBack() }
            },
            onDismiss = { askDelete = false },
        )
    }
    state.pendingSend?.let { body ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingSend() },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingSend() }) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingSend() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThreadActionsMenu(
    expanded: Boolean,
    hasContact: Boolean,
    isExporting: Boolean,
    canExport: Boolean,
    onDismiss: () -> Unit,
    onAddContact: () -> Unit,
    onDetails: () -> Unit,
    onExportPdf: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!hasContact) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_contact)) },
                onClick = onAddContact,
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            text = { Text(stringResource(R.string.action_details)) },
            onClick = onDetails,
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
            text = { Text(stringResource(R.string.action_export_pdf)) },
            enabled = canExport && !isExporting,
            onClick = onExportPdf,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    Icons.Outlined.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    stringResource(R.string.action_block),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onBlock,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

@Composable
private fun ConversationDetailsDialog(
    title: String,
    participants: Int,
    messages: Int,
    firstAt: Long?,
    lastAt: Long?,
    formatters: com.filestech.sms.ui.util.ChatFormatters,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_details)) },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.thread_details_participants, participants),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.thread_details_messages, messages),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (firstAt != null) {
                    Text(
                        stringResource(
                            R.string.thread_details_first,
                            formatters.fullDay.format(Date(firstAt)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (lastAt != null) {
                    Text(
                        stringResource(
                            R.string.thread_details_last,
                            formatters.fullDay.format(Date(lastAt)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BrandDanger,
                    contentColor = Color.White,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Composer bar — three modes
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
private fun ComposerBar(
    voice: ThreadViewModel.VoiceState,
    playback: VoicePlaybackController.PlaybackState,
    draft: String,
    segments: com.filestech.sms.data.sms.SmsSegmentCounter.Stats,
    isSendingVoice: Boolean,
    onDraftChanged: (String) -> Unit,
    onSendText: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onTogglePreview: () -> Unit,
    onSeekPreview: (Int) -> Unit,
    onDiscardDraft: () -> Unit,
    onSendVoice: () -> Unit,
    onAttachClick: () -> Unit,
    onMicPermissionDenied: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (!granted) onMicPermissionDenied()
        // Note: we do NOT auto-start recording here. The user must press-and-hold again — this
        // avoids surprising them with a recording that begins the moment they tap "Allow".
    }
    val permissionGranted = micPermission.status == PermissionStatus.Granted

    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))

        when (voice) {
            // Idle and Recording share the same row layout — switching between them must NOT
            // remove the mic button from the composition tree, otherwise its pointer-input
            // would be cancelled mid-gesture and the push-to-talk release event would be lost.
            ThreadViewModel.VoiceState.Idle,
            is ThreadViewModel.VoiceState.Recording -> ComposingRow(
                isRecording = voice is ThreadViewModel.VoiceState.Recording,
                elapsedMs = (voice as? ThreadViewModel.VoiceState.Recording)?.elapsedMs ?: 0L,
                amplitude = (voice as? ThreadViewModel.VoiceState.Recording)?.amplitude ?: 0,
                draft = draft,
                segments = segments,
                permissionGranted = permissionGranted,
                onDraftChanged = onDraftChanged,
                onSendText = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendText()
                },
                onRequestPermission = { micPermission.launchPermissionRequest() },
                onPressStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartRecording()
                },
                onReleaseStop = onStopRecording,
                onReleaseCancel = onCancelRecording,
                onCancelHintCrossed = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onAttachClick = onAttachClick,
            )

            is ThreadViewModel.VoiceState.Reviewing -> ReviewingMode(
                durationMs = voice.durationMs,
                playback = playback,
                isSending = isSendingVoice,
                onTogglePlay = onTogglePreview,
                onSeek = onSeekPreview,
                onDiscard = onDiscardDraft,
                onSend = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendVoice()
                },
            )
        }
    }
}

/**
 * Idle/Recording row. Push-to-talk gesture lives on the mic button: press-down starts the
 * recording, release stops it, dragging left past [CANCEL_THRESHOLD_DP] discards instead. The
 * mic stays in the composition tree across the Idle↔Recording transition so the in-flight
 * pointer gesture is never interrupted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposingRow(
    isRecording: Boolean,
    elapsedMs: Long,
    amplitude: Int,
    draft: String,
    segments: com.filestech.sms.data.sms.SmsSegmentCounter.Stats,
    permissionGranted: Boolean,
    onDraftChanged: (String) -> Unit,
    onSendText: () -> Unit,
    onRequestPermission: () -> Unit,
    onPressStart: () -> Unit,
    onReleaseStop: () -> Unit,
    onReleaseCancel: () -> Unit,
    onCancelHintCrossed: () -> Unit,
    onAttachClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val showMic = isRecording || draft.isBlank()

    // Live cancel hint: true once the finger has dragged past the cancel threshold while pressing.
    var cancelHinted by remember { mutableStateOf(false) }
    // Reset the hint when leaving Recording (release/cancel finished).
    LaunchedEffect(isRecording) { if (!isRecording) cancelHinted = false }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Attachment trigger (#2). Hidden while recording so the gesture surface stays minimal —
        // the user can attach a file before recording, or after dropping the recording, but not
        // mid-recording (a file pick would steal focus from the in-flight gesture).
        if (!isRecording) {
            IconButton(onClick = onAttachClick) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = stringResource(R.string.action_attach),
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (isRecording) {
                RecordingStrip(
                    elapsedMs = elapsedMs,
                    amplitude = amplitude,
                    cancelHinted = cancelHinted,
                )
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.thread_compose_placeholder)) },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    supportingText = if (segments.segmentCount > 0) {
                        {
                            Text(
                                text = stringResource(
                                    R.string.thread_segments_indicator,
                                    segments.length,
                                    segments.length + segments.charsRemainingInCurrentSegment,
                                    segments.segmentCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    } else null,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        if (showMic) {
            MicButton(
                isRecording = isRecording,
                cancelHinted = cancelHinted,
                permissionGranted = permissionGranted,
                onRequestPermission = onRequestPermission,
                onPressStart = onPressStart,
                onReleaseStop = onReleaseStop,
                onReleaseCancel = onReleaseCancel,
                onCancelHintChanged = { hinted ->
                    if (hinted && !cancelHinted) onCancelHintCrossed()
                    cancelHinted = hinted
                },
            )
        } else {
            FilledIconButton(onClick = onSendText) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.action_send),
                )
            }
        }
    }
}

/** Live recording strip: pulse, timer, waveform, and a slide-to-cancel hint. */
@Composable
private fun RecordingStrip(
    elapsedMs: Long,
    amplitude: Int,
    cancelHinted: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val danger = BrandDanger

    val infinite = rememberInfiniteTransition(label = "rec-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-pulse-alpha",
    )

    val history = remember { mutableStateListOf<Int>() }
    LaunchedEffect(elapsedMs, amplitude) {
        history.add(amplitude)
        if (history.size > WAVE_BARS) history.removeAt(0)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (cancelHinted) danger.copy(alpha = 0.12f) else cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(danger.copy(alpha = pulse)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            if (cancelHinted) {
                Text(
                    text = stringResource(R.string.voice_action_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = danger,
                    modifier = Modifier.weight(1f),
                )
            } else {
                WaveformLive(
                    history = history,
                    color = cs.primary,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
            }
        }
    }
}

/**
 * Push-to-talk mic button. Driven by a single pointer gesture:
 *
 *  - DOWN  → [onPressStart] (or [onRequestPermission] if RECORD_AUDIO isn't granted yet)
 *  - DRAG  → emits [onCancelHintChanged] when the finger crosses the cancel threshold
 *  - UP    → [onReleaseCancel] if the user was in the cancel zone, [onReleaseStop] otherwise
 *
 * The button visually grows + reddens while recording so the user can see the active state.
 */
@Composable
private fun MicButton(
    isRecording: Boolean,
    cancelHinted: Boolean,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onPressStart: () -> Unit,
    onReleaseStop: () -> Unit,
    onReleaseCancel: () -> Unit,
    onCancelHintChanged: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { CANCEL_THRESHOLD_DP.toPx() }
    val danger = BrandDanger
    val cs = MaterialTheme.colorScheme

    val containerColor = when {
        cancelHinted -> danger
        isRecording -> danger
        else -> cs.primary
    }
    val contentColor = Color.White

    val pushToTalk = Modifier.pointerInput(permissionGranted) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!permissionGranted) {
                onRequestPermission()
                down.consume()
                return@awaitEachGesture
            }
            onPressStart()
            down.consume()
            var inCancelZone = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (!change.pressed) {
                    if (inCancelZone) onReleaseCancel() else onReleaseStop()
                    return@awaitEachGesture
                }
                val dragX = change.position.x - down.position.x
                val newInCancelZone = dragX < -cancelThresholdPx
                if (newInCancelZone != inCancelZone) {
                    inCancelZone = newInCancelZone
                    onCancelHintChanged(inCancelZone)
                }
                change.consume()
            }
            @Suppress("UNREACHABLE_CODE") Unit
        }
    }

    FilledIconButton(
        onClick = { /* push-to-talk gesture handled by pointerInput above */ },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = Modifier
            .size(if (isRecording) 56.dp else 40.dp)
            .then(pushToTalk),
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = stringResource(
                if (isRecording) R.string.voice_action_stop else R.string.voice_action_record,
            ),
            tint = contentColor,
        )
    }
}

private val CANCEL_THRESHOLD_DP = 96.dp

/** Preview strip: play/pause, scrubber + duration, discard + send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewingMode(
    durationMs: Long,
    playback: VoicePlaybackController.PlaybackState,
    isSending: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Trust the controller's duration when available (the encoded clip's actual duration may
    // differ slightly from the wall-clock recording time we captured in the ViewModel).
    val totalMs = playback.durationMs.takeIf { it > 0 } ?: durationMs.toInt()
    val positionMs = playback.positionMs.coerceAtMost(totalMs)
    val sliderValue by remember(positionMs, totalMs) {
        derivedStateOf { if (totalMs <= 0) 0f else positionMs.toFloat() / totalMs }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDiscard, enabled = !isSending) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.voice_action_discard),
                tint = cs.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTogglePlay, enabled = !isSending) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (playback.isPlaying) R.string.voice_action_pause
                        else R.string.voice_action_play,
                    ),
                    tint = cs.primary,
                )
            }
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { f ->
                    if (totalMs > 0) onSeek((f * totalMs).toInt())
                },
                modifier = Modifier.weight(1f),
                enabled = !isSending && totalMs > 0,
            )
            Text(
                text = formatDuration(if (positionMs > 0) positionMs.toLong() else totalMs.toLong()),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isSending) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            FilledIconButton(onClick = onSend) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.voice_action_send),
                )
            }
        }
    }
}

/**
 * Live waveform: draws [WAVE_BARS] vertical bars whose heights are derived from the rolling
 * amplitude history. Older samples scroll out on the left, newest on the right.
 */
@Composable
private fun WaveformLive(
    history: List<Int>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        drawBars(history, color)
    }
}

private fun DrawScope.drawBars(history: List<Int>, color: Color) {
    val n = WAVE_BARS
    val gap = 2f
    val barWidth = (size.width - gap * (n - 1)) / n
    val maxAmp = 32_767f
    // Pad the front so brand-new recordings start aligned to the right.
    val padding = (n - history.size).coerceAtLeast(0)
    for (i in 0 until n) {
        val sample = if (i < padding) 0 else history[i - padding]
        val norm = (sample.toFloat() / maxAmp).coerceIn(0f, 1f)
        val h = max(4f, norm * size.height)
        val x = i * (barWidth + gap)
        val top = (size.height - h) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(x, top),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

/** Formats a duration in ms as `m:ss`. */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private const val WAVE_BARS: Int = 28

// ============================================================================================
//  Thread layout helpers — burst grouping + date dividers
// ============================================================================================

/** Max gap (ms) between two messages of the same sender to count as one burst. */
private const val BURST_GAP_MS: Long = 60_000L

/** True when [a] and [b] fall on the same calendar day. */
private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * Decides where a message sits inside its burst (a run of consecutive messages from the same
 * sender, within [BURST_GAP_MS] of one another, not crossing a calendar day).
 *
 *  - Solo: stands alone
 *  - First: joined by the next message
 *  - Middle: joined on both sides
 *  - Last: joined by the previous message only
 *
 * The bubble shape function in [MessageBubble] keys off this so a series of bubbles flattens
 * into a single stack visually.
 */
private fun computeBurstPosition(
    prev: com.filestech.sms.domain.model.Message?,
    current: com.filestech.sms.domain.model.Message,
    next: com.filestech.sms.domain.model.Message?,
): BurstPosition {
    val joinsBefore = prev != null &&
        prev.isOutgoing == current.isOutgoing &&
        sameDay(prev.date, current.date) &&
        current.date - prev.date <= BURST_GAP_MS
    val joinsAfter = next != null &&
        next.isOutgoing == current.isOutgoing &&
        sameDay(current.date, next.date) &&
        next.date - current.date <= BURST_GAP_MS
    return when {
        !joinsBefore && !joinsAfter -> BurstPosition.Solo
        !joinsBefore && joinsAfter -> BurstPosition.First
        joinsBefore && joinsAfter -> BurstPosition.Middle
        else -> BurstPosition.Last
    }
}

/**
 * Centered day pill rendered between bursts on the very first message and on every day boundary.
 * Visual signature for the thread — a soft surfaceVariant tint with a subtle hairline, no
 * dividers above or below (the cleaner separation Google Messages does not bother with).
 */
@Composable
private fun DateSeparator(label: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = cs.onSurfaceVariant,
            )
        }
    }
}
