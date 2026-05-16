package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R
import java.io.File

/**
 * v1.3.4 — bande horizontale de PJ stagées affichée AU-DESSUS du champ texte du
 * composer. Chaque PJ apparaît comme un chip 72 dp :
 *
 *   - **Image** : thumbnail Coil (crop carré) + petit X en haut-droite pour retirer.
 *   - **Vidéo / fichier** : icône + nom tronqué + X en haut-droite.
 *
 * L'utilisateur peut empiler autant qu'il veut (jusqu'au cap carrier 280 KB total
 * appliqué côté ViewModel). Scroll horizontal si trop large pour l'écran.
 *
 * Pas de bouton "Envoyer" intégré : c'est le bouton standard du composer (juste en
 * dessous) qui dispatche le tout en 1 MMS multipart via `viewModel.send()`.
 */
@Composable
fun PendingAttachmentsBar(
    pending: List<PendingAttachmentChipData>,
    /**
     * v1.3.4 M4 audit fix — callback indexé par **id stable String** (pas par index
     * `Int`) pour éviter qu'une race entre add/remove ne wipe la mauvaise PJ.
     */
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(pending, key = { _, c -> c.id }) { _, chip ->
                AttachmentChip(chip = chip, onRemove = { onRemove(chip.id) })
            }
        }
    }
}

/**
 * Data class plate (UI-only) pour découpler le composant du `ThreadViewModel
 * .PendingAttachment` interne. Le caller construit la liste depuis l'état.
 *
 * v1.3.4 M4 — `id: String` stable (typiquement le path absolu du fichier cache) au
 * lieu d'un hash Int : évite les collisions LazyRow `key` et permet à `onRemove`
 * de cibler la PJ avec précision même en cas de race add/remove.
 */
data class PendingAttachmentChipData(
    val id: String,
    val file: File,
    val mimeType: String,
    val displayName: String,
)

@Composable
private fun AttachmentChip(
    chip: PendingAttachmentChipData,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isImage = chip.mimeType.startsWith("image/", ignoreCase = true)
    val isVideo = chip.mimeType.startsWith("video/", ignoreCase = true)
    val shape = RoundedCornerShape(8.dp)

    Box(modifier = Modifier.size(width = 72.dp, height = 72.dp)) {
        // Vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(cs.surfaceContainerHighest)
                .border(width = 1.dp, color = cs.outlineVariant, shape = shape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isImage -> AsyncImage(
                    model = ImageRequest.Builder(context).data(chip.file).crossfade(true).build(),
                    contentDescription = chip.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
                isVideo -> Icon(
                    imageVector = Icons.Outlined.PlayCircleOutline,
                    contentDescription = stringResource(R.string.attachment_video_label),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.attachment_file_label),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = chip.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Bouton X de retrait (en haut-droite, sur-positionné).
        // **Box + clickable** (pas IconButton) : IconButton force
        // `minimumInteractiveComponentSize = 48 dp` qui ignore notre size 16 dp →
        // bouton invisible-mais-énorme. Le Box honnore strictement size(16dp).
        // Action destructive (retirer la PJ) → fond rouge + croix blanche
        // (règle "rouge = destructive uniquement" v1.3.3).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(cs.error)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.attachment_remove_label),
                tint = cs.onError,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
