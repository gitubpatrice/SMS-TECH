package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * Position of a message inside a burst (consecutive messages from the same sender, < 60 s apart).
 * Used to draw distinctive bubble shapes — only [Solo] and [First] carry the classic "tail"
 * corner; middle / last bubbles flatten the joining edge so the burst reads as a single block.
 *
 * The caller (ThreadScreen) computes this; the bubble stays stateless.
 */
enum class BurstPosition { Solo, First, Middle, Last }

@Composable
fun MessageBubble(
    message: Message,
    showTimestamp: Boolean,
    burstPosition: BurstPosition = BurstPosition.Solo,
    onTap: () -> Unit = {},
    /** Emitted when the user picks "Supprimer" in the bubble's overflow menu. */
    onDelete: () -> Unit = {},
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    onRemoveReaction: () -> Unit = {},
    repliedToPreview: ReplyQuotePreview? = null,
    translationState: TranslationDisplayState? = null,
    onDismissTranslation: (() -> Unit)? = null,
) {
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme

    // Outgoing bubbles use a subtle vertical gradient on top of the primary color — gives the
    // bubble depth without committing to a hard shadow or a custom drawable. Incoming bubbles
    // stay flat surfaceContainerHigh so reading them remains restful.
    val outgoingBrush = Brush.linearGradient(
        colors = listOf(cs.primary, cs.primary.copy(alpha = 0.88f)),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )
    val textColor = if (isOut) cs.onPrimary else cs.onSurface

    val shape = bubbleShape(isOut, burstPosition)

    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = 12.dp,
            vertical = bubbleVerticalSpacing(burstPosition),
        ),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Outgoing: trigger sits to the LEFT of the bubble. Menu carries Reply / Translate /
        // Delete (in that order; Delete in red at the bottom of the list).
        if (isOut) {
            BubbleMenuTrigger(
                onReply = onReply,
                onTranslate = onTranslate,
                onReact = onReact,
                onDelete = onDelete,
            )
        }
        // Stack the optional reply quote ABOVE the bubble — same horizontal alignment so it
        // visually anchors to the same side as the bubble. Inline column to keep the row
        // layout intact.
        Column(horizontalAlignment = if (isOut) Alignment.End else Alignment.Start) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // v1.3.0 — wrap dans BubbleReactionOverlay : no-op si pas de réaction (early
            // return interne), sinon affiche le cercle emoji en chevauchement.
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                onRemoveReaction = onRemoveReaction,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 32.dp, max = 320.dp)
                        .clip(shape)
                        .then(
                            if (isOut) Modifier.drawBehind { drawRect(outgoingBrush) }
                            else Modifier.background(com.filestech.sms.ui.theme.bubbleIncomingColor(cs)),
                        )
                        // Tap → retry (handled by parent on FAILED rows). Delete is now driven by
                        // the bubble's overflow menu (see [BubbleMenuTrigger]) so the bubble area
                        // itself only handles taps.
                        .clickable(onClick = onTap)
                        .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
                ) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                    )
                }
            }
            if (translationState != null) {
                TranslationBlock(
                    state = translationState,
                    isOutgoingHost = isOut,
                    onDismiss = onDismissTranslation,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Incoming: trigger sits to the RIGHT of the bubble.
        if (!isOut) {
            BubbleMenuTrigger(
                onReply = onReply,
                onTranslate = onTranslate,
                onReact = onReact,
                onDelete = onDelete,
            )
        }
    }
    if (showTimestamp || message.status == Message.Status.FAILED) {
        val label = when (message.status) {
            Message.Status.FAILED -> stringResource(R.string.thread_status_failed)
            Message.Status.DELIVERED -> stringResource(R.string.thread_status_delivered)
            Message.Status.SENT -> stringResource(R.string.thread_status_sent)
            Message.Status.PENDING -> stringResource(R.string.thread_status_pending)
            else -> null
        }
        val formatters = rememberChatFormatters()
        // Audit P-Q7 (v1.2.0): cache the formatted time so we don't re-allocate a Date + run
        // the SimpleDateFormat at every bubble recomposition. Same pattern as AudioMessageBubble.
        val time = androidx.compose.runtime.remember(message.date) {
            formatters.time.format(Date(message.date))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (label != null) "$time • $label" else time,
                color = if (message.status == Message.Status.FAILED) cs.error else cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Builds the rounded-corner shape that matches the bubble's position in a burst. Solo and first
 * bubbles keep the "tail" corner (4 dp) on the speaker side; mid / last bubbles flatten the
 * joining edge so the burst reads as one connected stack.
 */
private fun bubbleShape(isOut: Boolean, position: BurstPosition): RoundedCornerShape {
    val tail = 4.dp
    val full = 20.dp
    return when (position) {
        BurstPosition.Solo -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.First -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Middle -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Last -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = full,
            bottomEnd = full,
        )
    }
}

/** 1 dp between burst neighbours, 4 dp around solo / boundaries — gives bursts visible cohesion. */
private fun bubbleVerticalSpacing(position: BurstPosition) = when (position) {
    BurstPosition.Solo, BurstPosition.First, BurstPosition.Last -> 3.dp
    BurstPosition.Middle -> 1.dp
}
