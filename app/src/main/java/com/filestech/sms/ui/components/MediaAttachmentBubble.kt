package com.filestech.sms.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.theme.bubbleIncomingColor
import timber.log.Timber
import java.io.File

/**
 * v1.3.3 bug #2 — bulle dédiée aux pièces jointes **non-audio** (image / vidéo /
 * fichier). Le tap ouvre la PJ via [Intent.ACTION_VIEW] + FileProvider URI dans
 * l'app système associée au MIME type (Galerie pour image, lecteur vidéo, etc.).
 *
 * Architecture :
 *
 *   - **Image** : thumbnail rendu par Coil ([AsyncImage]) avec contentScale = Crop,
 *     max 240×320 dp pour cohérence avec [MessageBubble]. Tap → `ACTION_VIEW`.
 *   - **Vidéo** : icône play + nom de fichier (pas de preview frame extraite pour
 *     éviter la dépendance MediaMetadataRetriever — overkill pour v1.3.3). Tap →
 *     `ACTION_VIEW` ouvre le player système.
 *   - **Autre fichier** : icône doc + nom + taille. Tap → `ACTION_VIEW` (chooser).
 *
 * Sécurité : URI émise via [FileProvider.getUriForFile] avec
 * `FLAG_GRANT_READ_URI_PERMISSION` strict — l'app cible ne peut PAS lire d'autre
 * fichier que celui partagé. Cohérent avec l'audit F1 PDF Tech (allowed roots).
 *
 * Cohérence visuelle : reuse [outgoingBubbleColor] / [bubbleIncomingColor] et le
 * [BubbleMenuTrigger] (delete/reply/react) du pattern existant — l'utilisateur a
 * les mêmes actions que sur les bulles texte/audio.
 */
@Composable
fun MediaAttachmentBubble(
    message: Message,
    attachment: Attachment,
    showTimestamp: Boolean,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onReact: (() -> Unit)?,
    onRemoveReaction: () -> Unit,
    repliedToPreview: com.filestech.sms.ui.components.ReplyQuotePreview? = null,
    /** v1.3.3 #7 — étiquette d'expéditeur ; voir [MessageBubble] pour la sémantique. */
    senderLabel: String? = null,
) {
    val context = LocalContext.current
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme
    // Outgoing : primary (couleur de marque) ; Incoming : slate-blue d'app (cohérent
    // avec MessageBubble / AudioMessageBubble).
    val bgColor = if (isOut) cs.primary else bubbleIncomingColor(cs)
    val shape = RoundedCornerShape(18.dp)

    // v1.3.3 Z1 audit fix — `localUri` peut être :
    //   (1) un absolute file path (cache MMS, voice_mms, vault decrypt — fichiers app)
    //   (2) une `content://mms/part/N` URI (MMS importés du système Android via
    //       TelephonyReader → ConversationMirror.upsertIncomingMms)
    // L'ancien code wrappait aveuglément `File(localUri)` → FileProvider throw sur (2)
    // = aucun MMS image historique ne pouvait s'ouvrir. On détecte le scheme avant.
    val openAttachment: () -> Unit = remember(attachment.id, attachment.localUri) {
        {
            runCatching {
                val targetUri = attachment.toShareableUri(context)
                if (targetUri == null) {
                    Timber.w("MediaAttachmentBubble: cannot resolve URI for %s", attachment.localUri)
                    return@runCatching
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, attachment.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, attachment.fileName ?: ""))
            }.onFailure { Timber.w(it, "Failed to open attachment %d", attachment.id) }
        }
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .heightIn(min = 32.dp),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOut) {
            BubbleMenuTrigger(onReply = onReply, onReact = onReact, onDelete = onDelete)
        }
        Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (!senderLabel.isNullOrBlank()) {
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = if (isOut) 0.dp else 4.dp,
                        end = if (isOut) 4.dp else 0.dp,
                        bottom = 2.dp,
                    ),
                )
            }
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                onRemoveReaction = onRemoveReaction,
            ) {
                when {
                    attachment.isImage -> ImagePreview(
                        attachment = attachment,
                        shape = shape,
                        bgColor = bgColor,
                        onClick = openAttachment,
                    )
                    attachment.mimeType.startsWith("video/", ignoreCase = true) ->
                        IconAttachment(
                            attachment = attachment,
                            icon = Icons.Outlined.PlayCircleOutline,
                            iconLabelRes = R.string.attachment_video_label,
                            shape = shape,
                            bgColor = bgColor,
                            isOutgoing = isOut,
                            onClick = openAttachment,
                        )
                    else -> IconAttachment(
                        attachment = attachment,
                        icon = if (attachment.mimeType.startsWith("text/")) Icons.Outlined.Description else Icons.Outlined.AttachFile,
                        iconLabelRes = R.string.attachment_file_label,
                        shape = shape,
                        bgColor = bgColor,
                        isOutgoing = isOut,
                        onClick = openAttachment,
                    )
                }
            }
        }
        if (!isOut) {
            BubbleMenuTrigger(onReply = onReply, onReact = onReact, onDelete = onDelete)
        }
    }
}

/** Thumbnail image via Coil. Tap = ACTION_VIEW. */
@Composable
private fun ImagePreview(
    attachment: Attachment,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // v1.3.3 Z1 — Coil accepte File OU Uri ; on évite File("content://...") qui
    // crash silencieusement à l'affichage.
    val model: Any = if (attachment.localUri.startsWith("content://")) {
        Uri.parse(attachment.localUri)
    } else {
        File(attachment.localUri)
    }
    Box(
        modifier = Modifier
            .widthIn(min = 80.dp, max = 240.dp)
            .heightIn(min = 80.dp, max = 320.dp)
            .clip(shape)
            .border(width = 1.dp, color = bgColor, shape = shape)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .build(),
            contentDescription = attachment.fileName ?: stringResource(R.string.attachment_image_label),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 240.dp, height = 240.dp),
        )
    }
}

/**
 * v1.3.3 Z1 — résout `localUri` en une [Uri] partageable via Intent. Pour les paths
 * `content://...` (MMS importés du système), on retourne l'URI tel quel ; pour les
 * fichiers locaux app (cache / files), on encapsule dans FileProvider pour ne pas
 * exposer un `file://` direct (Android 7+ refuse).
 *
 * Retourne `null` si le fichier n'existe pas (cas path stale après nettoyage cache).
 */
private fun com.filestech.sms.domain.model.Attachment.toShareableUri(
    context: android.content.Context,
): Uri? {
    if (localUri.isBlank()) return null
    if (localUri.startsWith("content://")) {
        return Uri.parse(localUri)
    }
    val file = File(localUri)
    if (!file.exists()) {
        Timber.w("Attachment.toShareableUri: file not found %s", localUri)
        return null
    }
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/** Icône + nom + taille pour vidéo / fichier. Tap = ACTION_VIEW. */
@Composable
private fun IconAttachment(
    attachment: Attachment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconLabelRes: Int,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    isOutgoing: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val textColor = if (isOutgoing) cs.onPrimary else cs.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .clip(shape)
            .then(
                if (isOutgoing) Modifier.border(width = 1.5.dp, color = bgColor, shape = shape)
                else Modifier.background(bgColor),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(iconLabelRes), tint = textColor)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName ?: stringResource(iconLabelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.sizeBytes > 0) {
                Text(
                    text = formatBytes(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f Mo".format(b / (1024.0 * 1024.0))
    b >= 1024 -> "%d Ko".format(b / 1024)
    else -> "$b o"
}
