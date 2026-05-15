package com.filestech.sms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.ui.util.relativeRowLabel
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * One row of the main conversations list. Designed to stand out from stock Android SMS apps:
 *
 *  - **Accent rail** on the leading edge whenever the conversation has unread messages — a thin
 *    vertical bar in the brand primary that turns the whole row into a single glance signal.
 *  - **Two-tone typography**: title in semibold + on-surface when unread, regular + variant when
 *    read; preview line shrinks from `bodyMedium` to a slightly lighter shade once read so the
 *    eye is drawn to fresh conversations first.
 *  - **Circular unread chip** in the brand primary, replaces the default Material `Badge` for a
 *    larger touch target and cleaner geometry. Counts are clamped to "99+" for over-99 unreads.
 *  - **Pinned / muted** show as tiny outline icons inline next to the title so they don't fight
 *    the avatar for attention.
 *
 * The component is stateless: tap opens the thread, long-press triggers [onLongClick] (used by
 * [SwipeableConversationRow] to surface the delete confirmation without forcing the user to do
 * a precise drag-to-dismiss gesture).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    showAvatars: Boolean = true,
    previewLines: Int = 1,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val title = conversation.displayName ?: conversation.addresses.joinToString { it.raw }
    val unread = conversation.unreadCount > 0

    val formatters = rememberChatFormatters()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading accent rail — only painted when the conversation has unread messages. Uses
        // the theme primary so it adapts to Light / Dark / Dark Tech palettes automatically
        // (Light = BrandBlue, Dark = BrandBlueDark, Dark Tech = electric blue).
        Box(
            modifier = Modifier
                .width(if (unread) 4.dp else 0.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (unread) cs.primary else cs.surface),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAvatars) {
                Avatar(label = title)
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.muted) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = formatters.relativeRowLabel(conversation.lastMessageAt),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (unread) cs.primary else cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val draftText = conversation.draft
                    Text(
                        text = if (!draftText.isNullOrBlank()) {
                            androidx.compose.ui.res.stringResource(
                                id = com.filestech.sms.R.string.conversations_draft_prefix,
                                draftText,
                            )
                        } else conversation.lastMessagePreview.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) {
                        Spacer(Modifier.width(8.dp))
                        UnreadChip(count = conversation.unreadCount)
                    }
                }
            }
        }
    }
}

/**
 * Circular pill showing the unread count. Larger than Material's stock `Badge` so the digits
 * are legible without zooming, and shaped so a single digit produces a perfect circle while
 * two-digit values expand into a pill. "99+" is the cap to avoid ridiculous widths.
 */
@Composable
private fun UnreadChip(count: Int) {
    val cs = MaterialTheme.colorScheme
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(cs.primary)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onPrimary,
            maxLines = 1,
        )
    }
}
