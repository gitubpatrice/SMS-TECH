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
import androidx.compose.ui.focus.focusRequester
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
import com.filestech.sms.ui.theme.BrandDanger
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

// v1.2.3 audit U21: BrandDanger is defined once in `com.filestech.sms.ui.theme.Color`.
// References below resolve via the file-level import.

/** Compact human-readable file size — "284 KB", "1.2 MB", etc. Used by the attachment dialog. */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

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
    // v1.3.0 — état des dialogs/sheets de réaction emoji.
    //   `pickingReactionFor` non-null = bottom-sheet quick-pick affiché pour ce message.
    //   `customReactionFor` non-null = dialog TextField "Plus" affiché pour ce message.
    var pickingReactionFor by remember { mutableStateOf<Long?>(null) }
    var customReactionFor by remember { mutableStateOf<Long?>(null) }
    // v1.3.1 — premier envoi de réaction par SMS : on stocke (messageId, emoji) tant que
    // l'utilisateur n'a pas validé le dialog de confirmation. `null` = pas de dialog ouvert.
    // Le messageId est porté pour permettre au ViewModel de re-vérifier l'état au moment du
    // confirm (anti-race F4 : l'user a pu changer/retirer la réaction entre temps).
    var reactionConfirmRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }

    // v1.2.4 audit U12: scroll-to-bottom on new messages only when the user is already at
    // the bottom — preserves their reading position higher up the thread. The first paint
    // is a special case: opening a conversation must land on the most recent message, not
    // the start. We track `initialScrollDone` to discriminate the two.
    var initialScrollDone by remember { mutableStateOf(false) }
    val isAtBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val li = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            li == null || li.index >= state.messages.lastIndex - 1
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            // Non-animated for first paint — animateScrollToItem from index 0 with hundreds
            // of items is visibly choppy.
            listState.scrollToItem(state.messages.lastIndex)
            initialScrollDone = true
        } else if (isAtBottom) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
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
                is ThreadViewModel.Event.RequestReactionConfirm -> {
                    // X5 audit v1.3.1 — si un dialog est déjà ouvert (l'user n'a pas
                    // encore validé/annulé), on ignore silencieusement le 2ᵉ event pour
                    // ne pas écraser silencieusement l'intention en cours (le user verrait
                    // un autre emoji/contact que celui qu'il vient de toucher). Le badge
                    // local de la 2ᵉ réaction est déjà posé (côté repo), donc l'état UI
                    // reste cohérent ; seule la dispatch SMS est ignorée pour cette pose,
                    // ce qui est cohérent avec la sémantique "1 confirm = 1 envoi".
                    if (reactionConfirmRequest == null) {
                        reactionConfirmRequest = e.messageId to e.emoji
                    }
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
                        // v1.3.1 — "Réagir" exposé uniquement sur les messages reçus : on
                        // ne réagit pas à son propre envoi (pas de sens UX + cohérent avec
                        // la garde côté UseCase qui refuse les sender = soi-même).
                        onReact = if (msg.isIncoming) {
                            { pickingReactionFor = msg.id }
                        } else null,
                        onRemoveReaction = { viewModel.setReaction(msg.id, null) },
                        repliedToPreview = previewFor(msg),
                        showTimestamp = showTimestamp,
                    )
                } else {
                    val translation = state.translations[msg.id]
                    val translationDisplay = when (translation) {
                        is ThreadViewModel.TranslationState.Pending -> com.filestech.sms.ui.components.TranslationDisplayState.Pending
                        is ThreadViewModel.TranslationState.Ready -> com.filestech.sms.ui.components.TranslationDisplayState.Ready(translation.translated)
                        is ThreadViewModel.TranslationState.Failed -> com.filestech.sms.ui.components.TranslationDisplayState.Failed
                        null -> null
                    }
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
                        // v1.3.1 — "Réagir" exposé uniquement sur les messages reçus
                        // (voir AudioMessageBubble ci-dessus pour la même justification).
                        onReact = if (msg.isIncoming) {
                            { pickingReactionFor = msg.id }
                        } else null,
                        onRemoveReaction = { viewModel.setReaction(msg.id, null) },
                        repliedToPreview = previewFor(msg),
                        translationState = translationDisplay,
                        // Dismiss is exposed for every non-null state so user can collapse a
                        // stuck Pending or a Failed state, not just a Ready translation.
                        onDismissTranslation = if (translation != null) {
                            { viewModel.dismissTranslation(msg.id) }
                        } else null,
                    )
                }
            }
        }
    }

    // v1.3.0 — Emoji reaction quick-pick sheet
    pickingReactionFor?.let { msgId ->
        com.filestech.sms.ui.components.EmojiReactionPickerSheet(
            onPicked = { emoji ->
                viewModel.setReaction(msgId, emoji)
                pickingReactionFor = null
            },
            onOpenCustom = {
                pickingReactionFor = null
                customReactionFor = msgId
            },
            onDismiss = { pickingReactionFor = null },
        )
    }
    // v1.3.0 — Emoji reaction custom dialog (bouton "Plus")
    customReactionFor?.let { msgId ->
        com.filestech.sms.ui.components.EmojiCustomDialog(
            onPicked = { emoji ->
                viewModel.setReaction(msgId, emoji)
                customReactionFor = null
            },
            onDismiss = { customReactionFor = null },
        )
    }
    // v1.3.1 — dialog de confirmation du PREMIER envoi de réaction par SMS. Une fois la
    // case "Ne plus demander" cochée et validée, l'envoi sera silencieux les fois suivantes.
    // Le label destinataire pioche dans la conversation chargée ; en garde-fou (audit P5),
    // si la conversation n'est pas (encore) résolue, on rend "ce contact" plutôt que vide
    // pour ne jamais afficher un dialog sans destinataire visible.
    reactionConfirmRequest?.let { (messageId, emoji) ->
        val recipientLabel = state.conversation?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: state.conversation?.addresses?.joinToString { it.raw }?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.reaction_send_confirm_fallback_recipient)
        com.filestech.sms.ui.components.ReactionSendConfirmDialog(
            emoji = emoji,
            recipientLabel = recipientLabel,
            onConfirm = { neverAskAgain ->
                viewModel.confirmReactionSend(messageId, emoji, neverAskAgain)
                reactionConfirmRequest = null
            },
            onDismiss = { reactionConfirmRequest = null },
        )
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

    // Attachment confirmation dialog — v1.2.1 UX fix. The picker drops the file into the
    // staging slot, and the user explicitly validates here before any MMS dispatch. Cancel
    // deletes the cached copy.
    state.pendingAttachment?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAttachment() },
            title = { Text(stringResource(R.string.attach_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.attach_confirm_size,
                        pending.displayName,
                        formatFileSize(pending.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.confirmPendingAttachment() },
                ) { Text(stringResource(R.string.attach_confirm_send)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingAttachment() }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
                // v1.2.5: block-from-detail now also removes the conversation so the user is
                // not left staring at the very thread they just blocked. List-level Block
                // (bottom sheet) keeps the block-only behaviour for users wanting to keep
                // the history.
                viewModel.blockSenders { onBack() }
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
    // v1.2.1: the "Confirm before broadcast" setting (Réglages → Envoi) drives every
    // outgoing path — SMS text, MMS voice, and MMS attachments. Toggle OFF → instant send
    // everywhere. Toggle ON → this dialog (for text), the voice confirm below, and the
    // attachment confirm at the bottom of the file. Three dialogs, one setting, consistent.
    state.pendingSend?.let { body ->
        // v1.2.3 audit U10: Send is the positive primary action — autofocus + Button (heavier
        // weight) instead of two ambiguous TextButtons. Material 3 confirm-flow guideline.
        val sendFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { sendFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingSend() },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = { Text(body) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.confirmPendingSend() },
                    modifier = Modifier.focusRequester(sendFocus),
                ) {
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

    if (state.pendingVoiceConfirm) {
        val sendFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { sendFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingVoice() },
            title = { Text(stringResource(R.string.voice_confirm_title)) },
            text = { Text(stringResource(R.string.voice_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.confirmPendingVoice() },
                    modifier = Modifier.focusRequester(sendFocus),
                ) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingVoice() }) {
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
    // v1.2.5: revert to the solid BrandDanger fill — the Material 3 `errorContainer` token
    // resolves to a pastel pink in the light scheme, which the user explicitly didn't want
    // for a destructive confirm. Strong red + white text matches the brand identity used by
    // the delete button on conversation rows and the snackbar background.
    val cancelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { cancelFocus.requestFocus() }
    }
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
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            ) { Text(stringResource(R.string.action_cancel)) }
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

    // v1.2.4 audit U15: animate the cancel-hint background so the swipe-towards-cancel
    // gesture has a continuous color cue instead of the previous abrupt flip.
    val cancelBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (cancelHinted) danger.copy(alpha = 0.12f) else cs.surfaceContainerHigh,
        label = "rec-cancel-bg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cancelBg)
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
