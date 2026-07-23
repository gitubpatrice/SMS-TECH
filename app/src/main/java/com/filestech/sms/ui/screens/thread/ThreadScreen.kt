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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.splitGraphemeClusters
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.ui.components.AttachmentPickerSheet
import com.filestech.sms.ui.components.AudioMessageBubble
import com.filestech.sms.ui.components.BurstPosition
import com.filestech.sms.ui.components.ComposerReplyChip
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.MAX_REACTION_EMOJIS
import com.filestech.sms.ui.components.MessageBubble
import com.filestech.sms.ui.components.ReplyQuotePreview
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.SmsTechSnackbarVisuals
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.components.toPlaybackUri
import com.filestech.sms.ui.theme.BrandDanger
import com.filestech.sms.ui.util.daySeparatorLabel
import com.filestech.sms.ui.util.rememberChatFormatters
import com.filestech.sms.ui.util.rememberSimLabeler
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
    /**
     * v1.3.11 (F5) — navigation hook fired when the user picks an existing target
     * conversation in the forward sheet. The caller (`AppRoot`) typically does
     * `popBackStack(); navigate(Thread(id))` so the user returns to the conversation list
     * after the forwarded message is sent rather than stacking thread on top of thread.
     */
    onForwardToConversation: (Long) -> Unit = {},
    /**
     * v1.3.11 (F5) — navigation hook fired when the user picks "Nouveau destinataire"
     * in the forward sheet. The caller routes to [com.filestech.sms.ui.screens.compose
     * .ComposeScreen] which then funnels back into a thread once a contact is picked.
     */
    onForwardToNewContact: () -> Unit = {},
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
    // v1.3.11 (F1) — retract the soft keyboard + drop IME focus after every send so the
    // sender can read the just-sent message at the bottom of the thread without manually
    // collapsing the keyboard (Apple Messages / Google Messages convention).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // v1.3.11 (F3) — single Clipboard channel for all bubble copy actions. We use the
    // Compose-provided manager (backed by the system `ClipboardManager`) so the same path
    // applies for paste targets across apps.
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyMessageBody: (Message) -> Unit = { m ->
        val body = m.body.trim()
        if (body.isNotEmpty()) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(body))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val label = context.getString(R.string.toast_copied)
            scope.launch { snackbarHost.showSnackbar(label) }
        }
    }

    // v1.7.1 — Translation feature delegated to the system. After ML Kit was
    // removed in v1.7.0 (FLOSS compliance for F-Droid — MR !38458), we don't
    // ship an in-app translator anymore : the user picks their preferred
    // translation app via ACTION_PROCESS_TEXT (Google Translate, DeepL,
    // Aves Translate, LibreTranslate…). The system shows its standard chooser,
    // so the user stays in control of which app gets the message body.
    //
    // Security : we pass `EXTRA_PROCESS_TEXT_READONLY = true` so the receiving
    // app cannot modify the original text. Empty / blank bodies are filtered
    // out — no point opening a chooser with nothing to translate. If no app
    // declares the intent, we show a snackbar instead of letting the chooser
    // display its empty state (cleaner UX).
    val translateMessageExternal: (Message) -> Unit = { m ->
        val body = m.body.trim()
        if (body.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, body)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            if (intent.resolveActivity(context.packageManager) != null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                context.startActivity(Intent.createChooser(intent, null))
            } else {
                val label = context.getString(R.string.snack_no_translate_app)
                scope.launch { snackbarHost.showSnackbar(label) }
            }
        }
    }

    // v1.3.11 (F4) — phone number tapped inside any bubble body / caption opens the
    // [PhoneActionsDialog] hosted below. Single state owned by ThreadScreen so we don't
    // multiply dialog instances across hundreds of bubbles in a busy thread.
    var pickingPhoneNumber by remember { mutableStateOf<String?>(null) }
    val onPhoneClick: (String) -> Unit = { pickingPhoneNumber = it }
    // v1.3.11 (F5) — message being forwarded. Non-null = open the picker sheet. The
    // selected destination conversation (or new-recipient flow) consumes the staged
    // payload via [IncomingShareHolder] which the dest [ThreadViewModel] picks up at
    // open through `consumeIncomingShareIfAny`.
    var forwardingMessage by remember { mutableStateOf<Message?>(null) }

    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    // v1.11.0 — Sujet 5 apparence : dialog ouvert depuis l'overflow menu.
    var appearanceOpen by remember { mutableStateOf(false) }
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
    // Audit R3 (v1.14.8) — Retry d'un message FAILED avec errorCode WATCHDOG_TIMEOUT
    // peut produire un doublon (le radio a peut-être livré, on n'a juste pas reçu le
    // broadcast de confirmation). On confirme via dialog avant retry. SYNCHRONOUS reste
    // un retry direct sans dialog (le radio a rejeté l'envoi, pas de risque doublon).
    var pendingRetryWatchdog by remember { mutableStateOf<Message?>(null) }
    // v1.15.1 — Flow Programmer un SMS : 2 étapes (DatePicker puis TimePicker).
    // `scheduleStep` null = aucun picker ouvert ; "date" = DatePicker actif ;
    // "time" = TimePicker actif (date déjà choisie, stockée dans scheduledEpochMidnight).
    var scheduleStep by remember { mutableStateOf<String?>(null) }
    var scheduledEpochMidnight by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    // v1.2.4 audit U12: scroll-to-bottom on new messages only when the user is already at
    // the bottom — preserves their reading position higher up the thread. The first paint
    // is a special case: opening a conversation must land on the most recent message, not
    // the start. We track `initialScrollDone` to discriminate the two.
    var initialScrollDone by remember { mutableStateOf(false) }
    // v1.24.0 — `LazyListItemInfo.index` et l'argument de `scrollToItem` vivent dans l'espace
    // d'index GLOBAL du LazyColumn, où l'item « charger plus ancien » occupe la position 0 quand
    // il est affiché. `state.messages[i]` est donc à l'index global `i + headOffset`. Confondre
    // les deux ciblait l'avant-dernier message au lieu du dernier.
    val headOffset = if (state.hasMoreMessages) 1 else 0
    val bottomItemIndex = state.messages.lastIndex + headOffset
    val isAtBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val li = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            li == null || li.index >= bottomItemIndex - 1
        }
    }
    // Audit R4 (v1.14.8) — Compteur de messages non-vus apparus pendant que l'user était
    // scrollé vers le haut. Affiche un chip "↓ N nouveau(x)" cliquable en bas-droite.
    // Sans ça, un message arrivait silencieusement et restait invisible.
    //
    // v1.24.0 (finding A) — le suivi se fait par **id**, plus par index. Avec la fenêtre
    // glissante, charger des messages plus anciens les insère EN TÊTE et décale tous les index :
    // une soustraction d'index compterait alors comme "non vus" des messages que l'utilisateur a
    // déjà lus, voire afficherait un nombre absurde. L'id du dernier message vu est stable quoi
    // qu'il arrive à la fenêtre.
    var lastSeenMessageId by remember { androidx.compose.runtime.mutableLongStateOf(-1L) }
    val unseenCount by remember {
        androidx.compose.runtime.derivedStateOf {
            if (lastSeenMessageId < 0L) {
                0
            } else {
                val seenIndex = state.messages.indexOfLast { it.id == lastSeenMessageId }
                // -1 = le message vu est sorti de la fenêtre par le haut (purge, suppression) :
                // tout ce qui reste est postérieur, donc rien à signaler comme non vu.
                if (seenIndex < 0) 0 else state.messages.lastIndex - seenIndex
            }
        }
    }
    val newestMessageId = state.messages.lastOrNull()?.id
    LaunchedEffect(isAtBottom, newestMessageId) {
        if (isAtBottom && newestMessageId != null) lastSeenMessageId = newestMessageId
    }
    // v1.24.0 (finding A) — déclenché par l'id du dernier message, PAS par `size`. Charger des
    // messages plus anciens augmente `size` : avec l'ancienne clé, chaque « charger plus ancien »
    // aurait rejeté l'utilisateur en bas du fil, exactement au moment où il remonte l'historique.
    LaunchedEffect(newestMessageId) {
        if (newestMessageId == null) return@LaunchedEffect
        if (!initialScrollDone) {
            // Non-animated for first paint — animateScrollToItem from index 0 with hundreds
            // of items is visibly choppy.
            listState.scrollToItem(bottomItemIndex)
            initialScrollDone = true
            lastSeenMessageId = newestMessageId
        } else if (isAtBottom) {
            listState.animateScrollToItem(bottomItemIndex)
            lastSeenMessageId = newestMessageId
        }
    }

    // v1.24.0 — ré-ancrage explicite au « charger plus ancien ».
    //
    // Compose ne ré-ancre par clé que dans une fenêtre d'environ 130 items autour du premier
    // visible (`NearestRangeKeyIndexMap`). Un prepend de PAGE_SIZE = 200 dépasse cette portée :
    // `findIndexByKey` ne retrouve pas la clé, garde l'index inchangé, et l'utilisateur est
    // téléporté 200 messages en arrière — exactement quand il remonte son historique. On mémorise
    // donc le message en tête d'écran et son décalage, et on restaure la position nous-mêmes.
    var pendingAnchor by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val oldestMessageId = state.messages.firstOrNull()?.id
    LaunchedEffect(oldestMessageId) {
        val (anchorId, anchorOffset) = pendingAnchor ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.id == anchorId }
        if (index >= 0) {
            listState.scrollToItem(index + headOffset, -anchorOffset)
            pendingAnchor = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onConversationOpened()
        viewModel.events.collect { e ->
            when (e) {
                // v1.3.7 — branche le `isError` du Event vers le `SnackbarVisuals` custom
                // pour que le `SnackbarHost` ci-dessous puisse colorer rouge vs slate-blue.
                is ThreadViewModel.Event.ShowSnackbar -> snackbarHost.showSnackbar(
                    SmsTechSnackbarVisuals(message = e.message, isError = e.isError),
                )
                is ThreadViewModel.Event.PdfReady -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, "PDF")) }
                    snackbarHost.showSnackbar(context.getString(R.string.thread_export_success, e.pages))
                }
                // v1.3.7 — un échec d'envoi est par définition une erreur → rouge.
                is ThreadViewModel.Event.SendError -> snackbarHost.showError(
                    context.getString(R.string.error_send_failed),
                )
                is ThreadViewModel.Event.OpenAddContact -> {
                    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, e.rawNumber)
                    }
                    runCatching { context.startActivity(intent) }
                }
                is ThreadViewModel.Event.OpenCreateContact -> {
                    // Fiche vierge pré-remplie (ACTION_INSERT) — évite le sélecteur système
                    // et le défilement jusqu'à "créer un nouveau contact".
                    ContactIntents.createContact(context, e.rawNumber)
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
    // v1.23.x — numéro en sous-titre de l'en-tête pour une conversation 1-to-1 QUI A un nom de
    // contact (sinon le titre EST déjà le numéro, inutile de le répéter). Permet de voir tout de
    // suite quel numéro on a ouvert (ex. distinguer un numéro FR d'un numéro étranger).
    val headerSubtitleNumber = state.conversation
        ?.takeIf { it.displayName != null && it.addresses.size == 1 }
        ?.addresses?.firstOrNull()?.raw
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
    // v1.24.0 (finding A) — avec la fenêtre glissante, la cible d'une citation peut être hors
    // de la tranche chargée. Sans ce repli, la bulle afficherait « message supprimé » alors que
    // le message existe toujours. Les cibles manquantes sont résolues une fois, à la demande.
    val missingQuoteIds = remember(state.messages, state.quotedOutsideWindow) {
        state.messages.mapNotNull { it.replyToMessageId }
            .filterTo(mutableSetOf()) { it !in messageById && it !in state.quotedOutsideWindow }
    }
    LaunchedEffect(missingQuoteIds) {
        if (missingQuoteIds.isNotEmpty()) viewModel.ensureQuotedResolved(missingQuoteIds)
    }
    fun previewFor(msg: Message): ReplyQuotePreview? {
        val tgtId = msg.replyToMessageId ?: return null
        val tgt = messageById[tgtId] ?: state.quotedOutsideWindow[tgtId]
            ?: return ReplyQuotePreview(senderLabel = "—", body = deletedLabel, isFromSelf = false)
        return previewOf(tgt)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (headerSubtitleNumber != null) {
                        Column {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                headerSubtitleNumber,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(title)
                    }
                },
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
                        // v1.12.0 — vault overflow item, masqué en PanicDecoy.
                        inVault = state.conversation?.inVault == true,
                        onMoveVault = if (state.isPanicDecoy) null else {
                            {
                                menuOpen = false
                                viewModel.moveCurrentConversationToVault(
                                    intoVault = state.conversation?.inVault != true,
                                )
                            }
                        },
                        onDismiss = { menuOpen = false },
                        onCreateContact = { menuOpen = false; viewModel.requestCreateContact() },
                        onAddContact = { menuOpen = false; viewModel.requestAddContact() },
                        onDetails = { menuOpen = false; detailsOpen = true },
                        onExportPdf = { menuOpen = false; viewModel.exportToPdf() },
                        onAppearance = { menuOpen = false; appearanceOpen = true },
                        onBlock = { menuOpen = false; askBlock = true },
                        onDelete = { menuOpen = false; askDelete = true },
                        // v1.15.1 — Programmer le draft pour plus tard. Grisé tant qu'il
                        // n'y a rien à programmer (draft vide) — évite le tunnel
                        // date/heure → snackbar "Tape un message avant" frustrant.
                        canSchedule = state.draft.isNotBlank(),
                        onScheduleSend = {
                            menuOpen = false
                            scheduleStep = "date"
                        },
                    )
                },
            )
        },
        snackbarHost = {
            // v1.3.7 — branchement bi-couleur cohérent avec le système de marque :
            //   - confirmations positives (`isError = false`, défaut) → fond slate-blue
            //     `inverseSurface` (override de marque, cf. `Color.kt:SnackbarBg = BrandBlue`).
            //   - notifications d'échec (`isError = true`, posé par les call sites
            //     d'erreur via `showError` ou `Event.ShowSnackbar(isError = true)`) →
            //     fond [BrandDanger] (`#C62828`), le rouge fort utilisé partout dans
            //     l'app pour les destructives. Volontairement PAS `errorContainer`
            //     Material 3 qui est pâle en light mode. v1.9.0 — extrait dans
            //     `ui.components.SmsTechSnackbarHost` pour partage cross-écrans.
            SmsTechSnackbarHost(snackbarHost)
        },
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
                // v1.3.4 — bande staging des pièces jointes empilées (image / vidéo /
                // fichier). Affichée si non vide. Chaque chip a un X pour retirer la
                // PJ + supprimer son fichier cache. Tap sur Envoyer dans le composer
                // ci-dessous = 1 MMS multipart (texte + toutes les PJ).
                if (state.pendingAttachments.isNotEmpty()) {
                    com.filestech.sms.ui.components.PendingAttachmentsBar(
                        pending = state.pendingAttachments.map { p ->
                            com.filestech.sms.ui.components.PendingAttachmentChipData(
                                id = p.file.absolutePath, // String stable (M4)
                                file = p.file,
                                mimeType = p.mimeType,
                                displayName = p.displayName,
                            )
                        },
                        onRemove = { id -> viewModel.removePendingAttachment(id) },
                    )
                }
                ComposerBar(
                    voice = state.voice,
                    playback = playbackState,
                    draft = state.draft,
                    segments = state.segments,
                    isSendingVoice = state.isSendingVoice,
                    hasPendingAttachments = state.pendingAttachments.isNotEmpty(),
                    onDraftChanged = viewModel::updateDraft,
                    onSendText = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.send()
                        // v1.3.11 (F1) — drop focus + hide IME so the freshly-sent
                        // message becomes visible at the bottom of the thread.
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
                    onStartRecording = viewModel::startVoiceRecording,
                    onStopRecording = viewModel::stopVoiceRecording,
                    onCancelRecording = viewModel::discardVoiceDraft,
                    onTogglePreview = viewModel::togglePreviewPlayback,
                    onSeekPreview = viewModel::seekPreviewTo,
                    onDiscardDraft = viewModel::discardVoiceDraft,
                    onSendVoice = {
                        viewModel.sendVoiceMms()
                        // v1.3.11 (F1) — symmetrical retract for voice MMS dispatch.
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
                    onAttachClick = { attachmentSheetOpen = true },
                    onMicPermissionDenied = {
                        // v1.3.7 — refus de permission = erreur utilisateur visible → rouge.
                        scope.launch {
                            snackbarHost.showError(
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
        // v1.6.1 (audit PERF-04) — pré-calcul des séparateurs de jour HORS du lambda
        // `itemsIndexed`. Avant : `sameDay` + `daySeparatorLabel` allouaient 3 Calendar
        // par message visible (≈ 600 allocations sur thread de 200 msgs au premier rendu)
        // ET re-couraient à chaque recomposition partielle (mise à jour `read` d'un
        // message). Désormais : un seul `remember(state.messages, todayLabel,
        // yesterdayLabel)` recalcule la liste de labels (`null` = pas de séparateur, sinon
        // le label localisé prêt à afficher). Le bloc se reconstruit uniquement quand la
        // liste change OU si la langue change (todayLabel/yesterdayLabel sont keys).
        val daySeparatorLabels: List<String?> = remember(
            state.messages, todayLabel, yesterdayLabel,
        ) {
            state.messages.mapIndexed { index, msg ->
                val prev = state.messages.getOrNull(index - 1)
                if (prev == null || !sameDay(prev.date, msg.date)) {
                    formatters.daySeparatorLabel(
                        timestampMillis = msg.date,
                        todayLabel = todayLabel,
                        yesterdayLabel = yesterdayLabel,
                    )
                } else null
            }
        }
        // v1.18.0 — Bundle de callbacks stable réutilisé par chaque item. Capture les setters
        // `var by remember` (pendingDelete, pickingReactionFor, forwardingMessage,
        // pendingRetryWatchdog) + les références ViewModel + les lambdas locales. Recréé
        // uniquement si viewModel change (singleton scope ViewModel — donc 1 fois par
        // composition de ThreadScreen). Référence stable → Compose peut skipper la
        // recomposition des items dont le `msg` n'a pas changé.
        val itemActions = remember(viewModel) {
            ThreadMessageItemActions(
                onTogglePlayback = { audio -> viewModel.togglePlayback(audio.localUri, audio.toPlaybackUri()) },
                onSeekPlayback = viewModel::seekPlaybackTo,
                onTapFailed = { msg ->
                    // Audit R3 (v1.14.8) — WATCHDOG_TIMEOUT → confirmation dialog (risque doublon).
                    // SYNCHRONOUS = rejeté radio, retry direct sans risque.
                    if (msg.errorCode == SendErrorCode.WATCHDOG_TIMEOUT) {
                        pendingRetryWatchdog = msg
                    } else {
                        viewModel.retry(msg.id)
                    }
                },
                onDelete = { msg -> pendingDelete = msg },
                onReply = { msg -> viewModel.startReply(msg) },
                onReact = { msgId -> pickingReactionFor = msgId },
                // v1.4.1 — Tap-pour-retirer le badge autorisé uniquement sur incoming (la
                // réaction a été posée localement par l'user). Pour outgoing (Tapback fold du
                // destinataire), ignore (sinon désync : badge local disparaît mais reste chez
                // l'autre — convention iMessage).
                onRemoveReaction = { msg -> if (msg.isIncoming) viewModel.setReaction(msg.id, null) },
                onForward = { msg -> forwardingMessage = msg },
                onTranslate = { msg -> translateMessageExternal(msg) },
                onCopy = { msg -> copyMessageBody(msg) },
                onPhoneClick = onPhoneClick,
            )
        }
        // v1.22.0 (double SIM) — résolveur SIM (subId → libellé) calculé une fois pour tout le
        // fil. Retourne `null` en mono-SIM / permission absente / subId inconnu, donc aucun tag
        // n'apparaît dans ces cas. Lambda stable (mémoïsée) : passable aux items sans casser le
        // scoping de recomposition de [ThreadMessageItem].
        val simLabeler = rememberSimLabeler()
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            // v1.24.0 (finding A) — tête de fenêtre. `key` stable pour que l'insertion des
            // messages plus anciens ne détruise pas la position de scroll.
            if (state.hasMoreMessages) {
                item(key = "load-older", contentType = "load-older") {
                    LoadOlderRow(
                        isLoading = state.isLoadingOlder,
                        remaining = (state.messageCount - state.messages.size).coerceAtLeast(0),
                        onClick = {
                            // Le premier item visible portant une clé Long est un message : c'est
                            // lui que l'utilisateur regarde, et sur lui qu'on se ré-ancrera.
                            pendingAnchor = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key is Long }
                                ?.let { (it.key as Long) to it.offset }
                            viewModel.loadOlder()
                        },
                    )
                }
            }
            itemsIndexed(
                state.messages,
                key = { _, m -> m.id },
                // Audit PERF-M1 (v1.14.8) — `contentType` indique au recycleur Compose que
                // les bulles audio / media / texte sont 3 layouts distincts. Sans ce hint
                // Compose tente de réutiliser des compositions incompatibles → allocations
                // forcées + jank sur scroll. 3 buckets stables = recyclage propre.
                contentType = { _, m ->
                    when {
                        m.audioAttachment != null -> "audio"
                        m.attachments.any { !it.isAudio } -> "media"
                        else -> "text"
                    }
                },
            ) { index, msg ->
                // v1.18.0 — Dispatcher extrait vers [ThreadMessageItem]. Avant : ~140 lignes
                // inline (audio / media / text bubble). Maintenant : 1 appel. Les valeurs
                // dérivées (showTimestamp, burstPosition, senderLabel) sont calculées DANS
                // l'item Composable, plus dans le scope LazyColumn. Recompositions
                // mieux scopées : un item ne re-compose que sur changement de SON msg / prev /
                // next, pas sur changement du state global du parent.
                ThreadMessageItem(
                    msg = msg,
                    prev = state.messages.getOrNull(index - 1),
                    next = state.messages.getOrNull(index + 1),
                    daySeparatorLabel = daySeparatorLabels.getOrNull(index),
                    conversationDisplayName = state.conversation?.displayName,
                    bubbleColorArgb = state.conversation?.bubbleColorArgb,
                    smishingReasons = state.smishingVerdicts[msg.id] ?: emptyList(),
                    playbackProvider = { playbackState },
                    repliedToPreview = previewFor(msg),
                    // v1.22.0 (double SIM) — tag SIM sur les messages REÇUS uniquement (la bulle
                    // du contact, cf. demande). Les sortants portent aussi un `sub_id` (SIM
                    // d'envoi par défaut) mais afficher "Vous · SIM 2" sur chaque burst sortant
                    // serait du bruit non sollicité.
                    simLabel = if (msg.isIncoming) simLabeler(msg.subId) else null,
                    actions = itemActions,
                )
            }
        }
        // Audit R4 (v1.14.8) — Chip flottant "↓ N nouveau(x) message(s)" affiché en
        // bas-droite quand l'user est scrollé vers le haut ET que de nouveaux messages
        // sont arrivés. Tap → scroll animé + reset du compteur via le LaunchedEffect.
        if (!isAtBottom && unseenCount > 0 && state.messages.isNotEmpty()) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(bottomItemIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.pluralStringResource(
                        id = R.plurals.thread_unseen_messages,
                        count = unseenCount,
                        unseenCount,
                    ),
                )
            }
        }
        }
    }

    // Audit R3 (v1.14.8) — Dialog confirmation retry WATCHDOG_TIMEOUT (risque doublon).
    pendingRetryWatchdog?.let { msg ->
        AlertDialog(
            onDismissRequest = { pendingRetryWatchdog = null },
            title = { Text(stringResource(R.string.thread_retry_watchdog_title)) },
            text = { Text(stringResource(R.string.thread_retry_watchdog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = msg.id
                    pendingRetryWatchdog = null
                    viewModel.retry(id)
                }) { Text(stringResource(R.string.thread_retry_watchdog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetryWatchdog = null }) {
                    Text(stringResource(R.string.thread_retry_watchdog_cancel))
                }
            },
        )
    }

    // v1.15.1 — DatePicker pour programmer un message. Date d'aujourd'hui min, pas de cap max.
    if (scheduleStep == "date") {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { scheduleStep = null },
            confirmButton = {
                TextButton(onClick = {
                    val selected = datePickerState.selectedDateMillis
                    if (selected != null) {
                        scheduledEpochMidnight = selected
                        scheduleStep = "time"
                    } else {
                        scheduleStep = null
                    }
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { scheduleStep = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            androidx.compose.material3.DatePicker(
                state = datePickerState,
                title = { Text(stringResource(R.string.thread_schedule_date_title), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            )
        }
    }
    // v1.15.1 — TimePicker (étape 2) : compose la date choisie + l'heure pour obtenir l'epoch.
    if (scheduleStep == "time") {
        val timePickerState = androidx.compose.material3.rememberTimePickerState(
            initialHour = 9,
            initialMinute = 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { scheduleStep = null },
            title = { Text(stringResource(R.string.thread_schedule_time_title)) },
            text = {
                androidx.compose.material3.TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    // Combine date (millis at midnight UTC selon DatePicker) + heure/minute locales.
                    // DatePicker retourne l'epoch UTC à 00:00 — on rajoute le décalage local pour
                    // que l'heure choisie soit interprétée dans le fuseau de l'user.
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = scheduledEpochMidnight
                    cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH)
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val localCal = java.util.Calendar.getInstance().apply {
                        clear()
                        set(year, month, day, timePickerState.hour, timePickerState.minute, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val epochMs = localCal.timeInMillis
                    scheduleStep = null
                    viewModel.scheduleSend(epochMs)
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { scheduleStep = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // v1.3.11 (F4) — phone number actions dialog. Triggered from any bubble's
    // [MessageTextWithLinks] (text body or attachment caption) through the
    // [onPhoneClick] lambda built above.
    pickingPhoneNumber?.let { number ->
        com.filestech.sms.ui.components.PhoneActionsDialog(
            number = number,
            onDismiss = { pickingPhoneNumber = null },
            onSnack = { msg -> scope.launch { snackbarHost.showSnackbar(msg) } },
        )
    }

    // v1.3.11 (F5) — forward bottom-sheet. The order of operations matters:
    //   1. stageForward() posts the payload to IncomingShareHolder FIRST so it is in
    //      place by the time the destination ThreadViewModel hydrates.
    //   2. forwardingMessage is cleared BEFORE navigation so the sheet dismisses
    //      cleanly even if the user backs out of the dest screen.
    //   3. The caller's onForwardToConversation does pop+navigate so we don't stack a
    //      new thread on top of the source thread (cleaner back behaviour).
    forwardingMessage?.let { msg ->
        com.filestech.sms.ui.components.ForwardMessageSheet(
            currentConversationId = conversationId,
            onDismiss = { forwardingMessage = null },
            onPickConversation = { destId ->
                viewModel.stageForward(msg)
                forwardingMessage = null
                onForwardToConversation(destId)
            },
            onPickNewContact = {
                viewModel.stageForward(msg)
                forwardingMessage = null
                onForwardToNewContact()
            },
        )
    }

    // v1.3.0 / v1.5.0 — Emoji reaction quick-pick sheet (multi-select)
    pickingReactionFor?.let { msgId ->
        // v1.5.0 — pre-load the message's existing reaction so the sheet opens with
        // it already selected. Lets the user add / remove an emoji from a multi
        // reaction without having to re-tap everything from scratch.
        val existingReaction = state.messages.firstOrNull { it.id == msgId }?.reactionEmoji
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
            currentReaction = existingReaction,
        )
    }
    // v1.3.0 — Emoji reaction custom dialog (bouton "Plus")
    customReactionFor?.let { msgId ->
        com.filestech.sms.ui.components.EmojiCustomDialog(
            onPicked = { emoji ->
                // v1.5.1 (audit U1) — le clavier système peut produire une chaîne
                // arbitrairement longue (ex. l'utilisateur tape 5+ emojis). On applique
                // ici le même cap que le picker quick-pick (MAX_REACTION_EMOJIS) pour
                // éviter qu'une réaction "custom" déforme le badge ou contourne le
                // contrat UX. Le découpage en clusters de graphèmes garantit qu'on ne
                // coupe pas une ZWJ family / drapeau / skin-tone au milieu.
                val capped = emoji
                    .splitGraphemeClusters()
                    .take(MAX_REACTION_EMOJIS)
                    .joinToString(separator = "")
                if (capped.isNotEmpty()) viewModel.setReaction(msgId, capped)
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
    // v1.3.4 — le dialog modal `pendingAttachment` (v1.2.1) est SUPPRIMÉ au profit
    // d'une bande staging dans le composer (cf. `PendingAttachmentsBar` ci-dessous,
    // affichée dans `bottomBar` au-dessus du champ texte). L'utilisateur empile autant
    // de PJ qu'il veut, écrit éventuellement du texte, et tape Envoyer une seule fois
    // pour tout dispatcher en 1 MMS multipart.

    if (detailsOpen) {
        ConversationDetailsDialog(
            title = title,
            // v1.22.x — numéro affiché uniquement pour une conversation 1-to-1 (une seule
            // adresse). `singleOrNull` renvoie null pour un groupe → pas de ligne numéro.
            phoneNumber = state.conversation?.addresses?.singleOrNull()?.raw,
            participants = state.conversation?.addresses?.size ?: 0,
            messages = state.messageCount,
            firstAt = state.firstMessageAt,
            lastAt = state.lastMessageAt,
            formatters = formatters,
            onDismiss = { detailsOpen = false },
        )
    }
    // v1.11.0 — Sujet 5 apparence : dialog couleur bulle + avatar.
    if (appearanceOpen) {
        com.filestech.sms.ui.components.AppearanceDialog(
            currentBubbleColorArgb = state.conversation?.bubbleColorArgb,
            currentAvatarUri = state.conversation?.avatarUri,
            onDismiss = { appearanceOpen = false },
            onConfirm = { color, avatar ->
                viewModel.setAppearance(color, avatar)
                appearanceOpen = false
            },
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
    // v1.12.0 — vault move-in/out via overflow ; `null` = item absent (PanicDecoy).
    inVault: Boolean,
    onMoveVault: (() -> Unit)?,
    onDismiss: () -> Unit,
    onCreateContact: () -> Unit,
    onAddContact: () -> Unit,
    onDetails: () -> Unit,
    onExportPdf: () -> Unit,
    onAppearance: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    // v1.15.1 — Programme l'envoi du draft pour plus tard. Grisé si pas de draft.
    canSchedule: Boolean,
    onScheduleSend: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!hasContact) {
            // Choix clair, sans passer par le sélecteur système + défilement :
            //  - "Créer un contact"       → fiche vierge pré-remplie (ACTION_INSERT)
            //  - "Mettre à jour un contact" → sélecteur pour rattacher à un existant (INSERT_OR_EDIT)
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.thread_create_contact)) },
                onClick = onCreateContact,
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null) },
                text = { Text(stringResource(R.string.thread_update_contact)) },
                onClick = onAddContact,
            )
        }
        // v1.15.1 — Programme l'envoi pour plus tard. Place en haut pour visibilité
        // (action constructive, contraste avec les actions destructives en bas).
        // Grisé tant qu'il n'y a rien à programmer (draft vide).
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            text = { Text(stringResource(R.string.thread_schedule_action)) },
            enabled = canSchedule,
            onClick = onScheduleSend,
        )
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
        // v1.11.0 — Sujet 5 apparence par contact.
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
            text = { Text(stringResource(R.string.thread_overflow_appearance)) },
            onClick = onAppearance,
        )
        // v1.12.0 — Déplacer vers le coffre / sortir du coffre.
        // Absent en PanicDecoy (onMoveVault == null).
        if (onMoveVault != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        if (inVault) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (inVault) R.string.vault_move_out
                            else R.string.vault_move_in,
                        ),
                    )
                },
                onClick = onMoveVault,
            )
        }
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
    /**
     * v1.22.x — numéro du correspondant pour une conversation 1-to-1 (`null` pour un groupe :
     * le compteur de participants suffit alors). Permet de distinguer deux conversations d'un
     * même contact portant des numéros différents (ex. FR vs LU).
     */
    phoneNumber: String?,
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
                if (!phoneNumber.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.thread_details_number, phoneNumber),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
    /** v1.3.4 M1 audit fix — flag pour basculer mic → send button quand des PJ sont stagées. */
    hasPendingAttachments: Boolean,
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
                hasPendingAttachments = hasPendingAttachments,
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
    /** v1.3.4 M1 — quand `true`, force l'affichage du bouton Send au lieu du mic même
     *  si `draft.isBlank()` (l'user a juste empilé des PJ et veut les envoyer sans texte). */
    hasPendingAttachments: Boolean,
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
    // v1.3.4 M1 — afficher le Mic seulement si pas de PJ stagées ; sinon l'utilisateur
    // doit pouvoir envoyer les PJ via le bouton Send même sans texte.
    val showMic = isRecording || (draft.isBlank() && !hasPendingAttachments)

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

// ─────────────────────────────────────────────────────────────────────────────
//  Snackbar variant — extracted to shared `ui.components.SmsTechSnackbar` in
//  v1.9.0 for re-use by all screens (Settings, Backup, SafetyCallSetup…).
//  Local copies removed ; this file now imports from there.
// ─────────────────────────────────────────────────────────────────────────────

// ============================================================================
// v1.18.0 — Refactor ThreadScreen : extraction du dispatcher LazyColumn item en
// `ThreadMessageItem` private @Composable. Avant : 140 lignes inline avec ~15
// captures locales (state setters + lambdas + VM refs), fonction principale
// `ThreadScreen` atteignait ~850 lignes. Bénéfice : lisibilité + recompositions
// Compose mieux scopées (chaque item ne re-compose que sur changement de SON
// state, pas sur recomposition globale du parent).
// ============================================================================

/**
 * Bundle des callbacks transmis à [ThreadMessageItem]. Annoté `@Stable` : Compose peut
 * skipper la recomposition de l'item quand la référence d'instance ne change pas. Le parent
 * (`ThreadScreen`) crée l'instance UNE SEULE FOIS via `remember(viewModel, snackbarHost, ...)`
 * — donc référence stable sur toute la vie de la composition. Les setters de state Compose
 * (`pendingDelete = msg`, `pickingReactionFor = msg.id`, …) sont capturés au moment de la
 * création des lambdas et restent fonctionnels via le delegate `var by remember`.
 *
 * Pas de `data class` (ByteArray-equivalent : les lambdas n'ont pas d'`equals` content-based,
 * data class générerait un `equals` par référence — fragile). Class simple suffisante.
 */
@androidx.compose.runtime.Stable
private class ThreadMessageItemActions(
    val onTogglePlayback: (com.filestech.sms.domain.model.Attachment) -> Unit,
    // Position en millisecondes ; aligné sur la signature [ThreadViewModel.seekPlaybackTo].
    val onSeekPlayback: (Int) -> Unit,
    /** Tap sur bulle FAILED — l'action interne distingue WATCHDOG_TIMEOUT vs SYNCHRONOUS. */
    val onTapFailed: (Message) -> Unit,
    val onDelete: (Message) -> Unit,
    val onReply: (Message) -> Unit,
    val onReact: (Long) -> Unit,
    val onRemoveReaction: (Message) -> Unit,
    val onForward: (Message) -> Unit,
    val onTranslate: (Message) -> Unit,
    val onCopy: (Message) -> Unit,
    val onPhoneClick: (String) -> Unit,
)

/**
 * Item renderer de la LazyColumn thread. Dispatch sur 3 sous-Composables selon le type :
 * audio → [AudioMessageBubble], image/video/file → [MediaAttachmentBubble], sinon
 * [MessageBubble] texte. Calcule les valeurs dérivées (showTimestamp, burstPosition,
 * senderLabel) localement pour minimiser la surface d'API.
 *
 * **Compose recomposition** : grâce à `@Stable ThreadMessageItemActions`, l'item ne recompose
 * que si :
 *   - le `msg` change (nouvelle instance Message via Room Flow)
 *   - les voisins `prev`/`next` changent (insert/delete devant)
 *   - `playbackProvider` re-évalué (mais c'est une `() -> PlaybackState` lambda stable,
 *     délibérément lambda et pas value pour qu'AudioMessageBubble re-lise via derivedStateOf
 *     uniquement quand SA propre bulle joue — pas toutes les bulles à chaque tick 5 Hz)
 *   - le `daySeparatorLabel` change (rare, dépend du jour de la date du message)
 *
 * Reads localisés à l'instance : `conversationDisplayName`, `bubbleColorArgb`,
 * `smishingReasons`, `simLabel` sont passés comme valeurs ; pas de capture du `state` global au
 * parent. `simLabel` (String `@Stable`) est dérivé de `msg.subId` côté parent — déjà couvert par
 * le trigger `msg`.
 */
@Composable
private fun ThreadMessageItem(
    msg: Message,
    prev: Message?,
    next: Message?,
    daySeparatorLabel: String?,
    conversationDisplayName: String?,
    bubbleColorArgb: Int?,
    smishingReasons: List<com.filestech.sms.domain.smishing.SmishingReason>,
    playbackProvider: () -> com.filestech.sms.data.voice.VoicePlaybackController.PlaybackState,
    repliedToPreview: ReplyQuotePreview?,
    /**
     * v1.22.0 (double SIM) — libellé court de la SIM d'arrivée de ce message (ex. "SIM 2",
     * "POST", nom donné par l'user). `null` sur mono-SIM / permission absente / message sans
     * `sub_id` : aucun tag n'est alors ajouté. Résolu par le parent via [rememberSimLabeler].
     */
    simLabel: String? = null,
    actions: ThreadMessageItemActions,
) {
    // Date separator (calendar day boundary) — affiché au-dessus du 1er msg du jour.
    daySeparatorLabel?.let { label ->
        DateSeparator(label = label)
    }

    val showTimestamp = prev?.date?.let { msg.date - it > 5 * 60_000L } ?: true
    val burstPosition = computeBurstPosition(prev, msg, next)
    val audio = msg.audioAttachment
    // v1.3.3 bug #2 — pour les pièces jointes NON-audio (image/video/file), on délègue à
    // MediaAttachmentBubble qui ajoute le tap-to-view (ACTION_VIEW via FileProvider).
    val mediaAttachment = msg.attachments.firstOrNull { !it.isAudio }
    // v1.3.3 #7 — étiquette d'expéditeur uniquement sur la 1ʳᵉ bulle d'un burst (Solo ou First)
    // pour ne pas surcharger visuellement les suites consécutives du même expéditeur.
    val baseSenderLabel: String? = if (
        burstPosition == BurstPosition.Solo ||
        burstPosition == BurstPosition.First
    ) {
        if (msg.isOutgoing) {
            stringResource(R.string.bubble_sender_self)
        } else {
            conversationDisplayName?.takeIf { it.isNotBlank() } ?: msg.address
        }
    } else null
    // v1.22.0 (double SIM) — suffixe le libellé SIM au nom de l'expéditeur sur la 1ʳᵉ bulle du
    // burst ("Marie · SIM 2"), ce qui distingue d'un coup d'œil les conversations reçues sur la
    // puce locale vs étrangère. `simLabel` vaut déjà `null` hors multi-SIM (ou message sans
    // `sub_id`), donc les cas mono-SIM et les messages historiques restent strictement inchangés.
    val senderLabel: String? = when {
        baseSenderLabel == null -> null
        simLabel.isNullOrBlank() -> baseSenderLabel
        else -> "$baseSenderLabel · $simLabel"
    }

    when {
        audio != null -> {
            AudioMessageBubble(
                message = msg,
                audio = audio,
                playbackProvider = playbackProvider,
                onTogglePlay = { actions.onTogglePlayback(audio) },
                onSeekTo = actions.onSeekPlayback,
                onDelete = { actions.onDelete(msg) },
                onReply = { actions.onReply(msg) },
                // v1.3.1 — "Réagir" exposé uniquement sur les messages reçus.
                onReact = if (msg.isIncoming) { { actions.onReact(msg.id) } } else null,
                onForward = { actions.onForward(msg) },
                onRemoveReaction = { actions.onRemoveReaction(msg) },
                repliedToPreview = repliedToPreview,
                showTimestamp = showTimestamp,
                senderLabel = senderLabel,
            )
        }
        mediaAttachment != null -> {
            com.filestech.sms.ui.components.MediaAttachmentBubble(
                message = msg,
                attachment = mediaAttachment,
                showTimestamp = showTimestamp,
                onDelete = { actions.onDelete(msg) },
                onReply = { actions.onReply(msg) },
                onReact = if (msg.isIncoming) { { actions.onReact(msg.id) } } else null,
                // v1.3.11 (F3) — copy exposed only when the bubble carries a user-typed caption.
                onCopy = if (msg.body.isNotBlank()) { { actions.onCopy(msg) } } else null,
                onForward = { actions.onForward(msg) },
                onPhoneClick = actions.onPhoneClick,
                onRemoveReaction = { actions.onRemoveReaction(msg) },
                repliedToPreview = repliedToPreview,
                senderLabel = senderLabel,
            )
        }
        else -> {
            MessageBubble(
                message = msg,
                showTimestamp = showTimestamp,
                burstPosition = burstPosition,
                onTap = { if (msg.status == Message.Status.FAILED) actions.onTapFailed(msg) },
                onDelete = { actions.onDelete(msg) },
                onReply = { actions.onReply(msg) },
                // v1.7.1 — Translate via ACTION_PROCESS_TEXT system intent. Only wired when
                // the body is non-blank (nothing to translate on attachment-only messages).
                onTranslate = if (msg.body.isNotBlank()) { { actions.onTranslate(msg) } } else null,
                onReact = if (msg.isIncoming) { { actions.onReact(msg.id) } } else null,
                onCopy = if (msg.body.isNotBlank()) { { actions.onCopy(msg) } } else null,
                onForward = { actions.onForward(msg) },
                onPhoneClick = actions.onPhoneClick,
                onRemoveReaction = { actions.onRemoveReaction(msg) },
                repliedToPreview = repliedToPreview,
                senderLabel = senderLabel,
                customBubbleColorArgb = bubbleColorArgb,
                smishingReasons = smishingReasons,
            )
        }
    }
}

/**
 * Head-of-thread control that widens the loaded window (v1.24.0, finding A).
 *
 * Sits above the oldest loaded message and states how many older ones remain, so the boundary of
 * the window is explicit rather than looking like the start of the conversation.
 */
@Composable
private fun LoadOlderRow(
    isLoading: Boolean,
    remaining: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            TextButton(onClick = onClick) {
                Text(
                    text = if (remaining > 0) {
                        stringResource(R.string.thread_load_older_remaining, remaining)
                    } else {
                        stringResource(R.string.thread_load_older)
                    },
                )
            }
        }
    }
}
