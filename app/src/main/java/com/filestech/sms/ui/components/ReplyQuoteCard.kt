package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Compact preview of the message a reply is targeting (#8). Captured at the moment the user
 * arms or sends the reply — passing the snapshot through the UI tree means we never have to
 * re-resolve the original row mid-recomposition.
 *
 * - [senderLabel] is the rendered author (e.g. "Marie" or "Vous").
 * - [body] is the **raw** quoted text. The composable caps the displayed length itself.
 * - [isFromSelf] toggles the accent color so the user can tell at a glance whether they are
 *   replying to themselves or to the other party.
 */
data class ReplyQuotePreview(
    val senderLabel: String,
    val body: String,
    val isFromSelf: Boolean,
)

/**
 * Mini-quote card rendered **above** a chat bubble whose row is a contextual reply. Visually
 * a thin colored stripe + 1–2 lines of the quoted body, restrained so it never overpowers the
 * reply itself. The card inherits the bubble's max width so it doesn't push the speaker side
 * outside the row.
 *
 * The card is intentionally non-interactive: tapping it does not jump to the quoted message
 * (that would be a v1.2 nicety — for now we keep recomposition surface to a minimum).
 */
@Composable
fun ReplyQuoteCard(
    preview: ReplyQuotePreview,
    isOutgoingHost: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // The card sits in the same color family as the host bubble so the eye reads them as one
    // structure: tinted variants on incoming (surfaceContainerHighest), primary-toned on
    // outgoing. The accent stripe uses the complementary tone — never error red — so the
    // information stays calm.
    // v1.2.3 audit U17: alpha 0.78f on primary + onPrimary body alpha 0.82f composited to
    // ~5.5:1, right at the WCAG AA edge. Bumping container alpha to 0.88f gets us cleanly above
    // the 4.5:1 threshold for body text on outgoing bubbles.
    val containerColor = if (isOutgoingHost) {
        cs.primary.copy(alpha = 0.88f)
    } else {
        cs.surfaceContainerHighest
    }
    val stripeColor = if (isOutgoingHost) cs.onPrimary else cs.primary
    val labelColor = if (isOutgoingHost) cs.onPrimary else cs.onSurface
    val bodyColor = if (isOutgoingHost) cs.onPrimary.copy(alpha = 0.9f) else cs.onSurfaceVariant

    Row(
        modifier = modifier
            .widthIn(min = 80.dp, max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .background(stripeColor),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                text = preview.senderLabel,
                color = labelColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.body,
                color = bodyColor,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Cartouche shown **above the composer** while a reply target is armed (#8). Mirrors the
 * structure of [ReplyQuoteCard] but stretches to the full composer width and exposes a Close
 * button so the user can drop the reply without sending.
 */
@Composable
fun ComposerReplyChip(
    preview: ReplyQuotePreview,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerHigh)
            .padding(start = 0.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(cs.primary),
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = preview.senderLabel,
                    color = cs.primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.body,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 40 dp touch target (WCAG 2.5.5) — visual icon stays at 18 dp.
        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_cancel_reply),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
