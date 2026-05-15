package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Renders the on-device translation (#4) of a chat bubble's body, anchored just below it.
 *
 * Styled to look like a sibling of [ReplyQuoteCard] — same rounded corners, restrained palette
 * — so a bubble that carries both a reply quote (above) and a translation (below) reads as one
 * coherent stack. The mini header carries the "Traduction" label + a tiny dismiss button so
 * users can collapse it without burning the original.
 *
 * The composable does **not** know how the translation was produced. It is fed a finalized
 * string by [com.filestech.sms.ui.screens.thread.ThreadViewModel]; the actual ML Kit pipeline
 * lives in the data layer.
 */
@Composable
fun TranslationBlock(
    translated: String,
    isOutgoingHost: Boolean,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val container = if (isOutgoingHost) cs.primary.copy(alpha = 0.72f) else cs.surfaceContainerHighest
    val labelColor = if (isOutgoingHost) cs.onPrimary else cs.primary
    val bodyColor = if (isOutgoingHost) cs.onPrimary else cs.onSurface

    Column(
        modifier = modifier
            .widthIn(min = 80.dp, max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.translation_label),
                    color = labelColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_dismiss_translation),
                        tint = labelColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = translated,
            color = bodyColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
