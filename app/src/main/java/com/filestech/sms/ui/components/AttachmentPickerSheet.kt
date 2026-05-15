package com.filestech.sms.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Compose-side picker for "compose with an attachment" (#2). Hosted by the thread's composer:
 * tapping the paperclip opens this modal sheet, picking an option launches the appropriate
 * system contract (photo, video, file, contact), the contract emits a [Uri] that is forwarded
 * to the parent via [onAttachmentPicked].
 *
 * Each tile is wired to its **own** [rememberLauncherForActivityResult] so the contracts are
 * registered at composition time — required by the new ActivityResultRegistry API. Cancelling
 * a system picker dismisses gracefully (null uri ignored).
 *
 * UX choices:
 *  - 4 round tiles in a single row, evenly spaced. Each tile is large enough (56 dp icon target)
 *    to meet WCAG 2.5.5 (44×44 dp minimum).
 *  - The sheet auto-dismisses after a successful pick to avoid double-taps that would launch
 *    two contracts back-to-back.
 *  - Subtitle "Envoyé comme MMS" sets honest expectations — the user knows their carrier will
 *    charge MMS rates if they don't have an unlimited bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onAttachmentPicked: (uri: Uri, kind: AttachmentKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAttachmentPicked(uri, AttachmentKind.PHOTO)
        onDismiss()
    }
    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAttachmentPicked(uri, AttachmentKind.VIDEO)
        onDismiss()
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAttachmentPicked(uri, AttachmentKind.FILE)
        onDismiss()
    }
    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri ->
        if (uri != null) onAttachmentPicked(uri, AttachmentKind.CONTACT)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.attach_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.attach_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                AttachmentTile(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.attach_photo),
                    onClick = {
                        photoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                AttachmentTile(
                    icon = Icons.Outlined.Videocam,
                    label = stringResource(R.string.attach_video),
                    onClick = {
                        videoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly,
                            ),
                        )
                    },
                )
                AttachmentTile(
                    icon = Icons.Outlined.Description,
                    label = stringResource(R.string.attach_file),
                    onClick = {
                        // Common safe MIME types — narrow enough to stay routable through MMS
                        // gateways, broad enough to cover everyday "send me your invoice" needs.
                        fileLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "image/*",
                                "audio/*",
                                "video/*",
                                "text/*",
                            ),
                        )
                    },
                )
                AttachmentTile(
                    icon = Icons.Outlined.PersonOutline,
                    label = stringResource(R.string.attach_contact),
                    onClick = { contactLauncher.launch(null) },
                )
            }
        }
    }
}

/**
 * High-level taxonomy reported back to the parent. The parent maps this to the concrete
 * [com.filestech.sms.data.mms.MmsBuilder.MmsAttachment.Kind] at send time — keeping the UI
 * layer independent of the MMS PDU types.
 */
enum class AttachmentKind { PHOTO, VIDEO, FILE, CONTACT }

@Composable
private fun AttachmentTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // v1.2.3 audit U8: hit area widened to ≥64 dp so the touch target meets WCAG 2.5.5 even
    // for the "Contact" tile (~60 dp wide label). Semantics expose the role + the label so
    // TalkBack announces "Photo, bouton" instead of just "Photo".
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(
                onClickLabel = label,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface,
        )
    }
}
