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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
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
 * UI-side projection of an in-flight or completed translation. Decoupled from the data-layer
 * ML Kit state so this component stays a leaf in the dependency tree.
 */
sealed interface TranslationDisplayState {
    /** ML Kit is detecting the source language or downloading the model. */
    data object Pending : TranslationDisplayState

    /** Translation succeeded. */
    data class Ready(val translated: String) : TranslationDisplayState

    /** Detection / download / inference failed — show a discreet error indicator. */
    data object Failed : TranslationDisplayState
}

/**
 * Renders the on-device translation (#4) of a chat bubble's body, anchored just below it.
 *
 * Styled to look like a sibling of [ReplyQuoteCard] — same rounded corners, restrained palette
 * — so a bubble that carries both a reply quote (above) and a translation (below) reads as one
 * coherent stack. The mini header carries the "Traduction" label + a tiny dismiss button so
 * users can collapse it without burning the original.
 *
 * Three render branches matching [TranslationDisplayState] — without this, an ML Kit model
 * download (up to ~30 s) leaves the user staring at an empty bubble and a model-language
 * failure passes silently. Reported as v1.2.2 audit U1.
 */
@Composable
fun TranslationBlock(
    state: TranslationDisplayState,
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
                // 36 dp touch target (vs the legacy 22 dp that violated WCAG 2.5.5).
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_dismiss_translation),
                        tint = labelColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        when (state) {
            is TranslationDisplayState.Pending -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = labelColor,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.translation_in_progress),
                    color = bodyColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            is TranslationDisplayState.Ready -> Text(
                text = state.translated,
                color = bodyColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(top = 2.dp),
            )

            is TranslationDisplayState.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = labelColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.translation_failed),
                    color = bodyColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
