package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Accessible fallback for the long-press "delete" gesture on chat bubbles. Some users do not
 * discover the long-press (or the gesture is captured by other interactions on the bubble —
 * tap-to-retry on FAILED messages, slider on audio bubbles), so every bubble exposes a small
 * three-dot trigger that opens a one-item menu. The menu reuses the same delete pipeline as
 * the long-press (`onDelete()` → `pendingDelete = msg` in `ThreadScreen`), so the destructive
 * confirmation dialog still runs.
 *
 * Visually low-key by design: 32 dp icon button + 0.55 alpha tint so it does not compete with
 * the bubble content. Sits in the row gutter opposite the speaker side, where the layout was
 * already reserving 40 dp of breathing room.
 */
/**
 * Overflow menu attached to a chat bubble — surfaces Reply / Translate / **Delete** (in that
 * order, with Delete rendered in red at the bottom of the list as the destructive action).
 *
 * Reply and Translate are optional (a row with neither, e.g. a system status message, still
 * renders the menu just for the Delete action).
 */
@Composable
fun BubbleMenuTrigger(
    onDelete: () -> Unit,
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        // v1.2.3 audit U5: 40 dp touch target (WCAG 2.5.5) and 0.75 alpha tint
        // (previously 0.55 ≈ 2.3:1 — failed WCAG 3:1 for icons). The trigger sits in the row
        // gutter beside the bubble, so it renders against `surface`, not the bubble fill.
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_message_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (onReply != null) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Reply,
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(R.string.action_reply)) },
                    onClick = {
                        expanded = false
                        onReply()
                    },
                )
            }
            if (onTranslate != null) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Translate,
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(R.string.action_translate)) },
                    onClick = {
                        expanded = false
                        onTranslate()
                    },
                )
            }
            // Destructive action always at the bottom, painted in error red.
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
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
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
