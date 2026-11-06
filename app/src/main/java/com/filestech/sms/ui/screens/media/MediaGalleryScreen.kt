package com.filestech.sms.ui.screens.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.ui.components.VisionneuseDesPiecesJointes
import com.filestech.sms.ui.components.modeleImage
import com.filestech.sms.ui.components.rememberOuvertureDesPiecesJointes

/** Issue #59 — la grille des photos et vidéos. Toucher une vignette ouvre le fichier comme depuis la bulle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    onBack: () -> Unit,
    viewModel: MediaGalleryViewModel = hiltViewModel(),
) {
    val media by viewModel.media.collectAsStateWithLifecycle()
    val vaultHidden by viewModel.vaultHidden.collectAsStateWithLifecycle()
    val ouverture = rememberOuvertureDesPiecesJointes()
    VisionneuseDesPiecesJointes(ouverture)
    // `withResumed`, comme `ThreadScreen` : dépiler pendant qu'un autre écran est devant viserait le voisin.
    val navOwner = LocalLifecycleOwner.current
    LaunchedEffect(vaultHidden) {
        if (vaultHidden) navOwner.lifecycle.withResumed { onBack() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.media_gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // 2026-11-06 (issue #76) — une photo dans la visionneuse de l'app, une vidéo dans l'app du système.
        MediaGalleryContent(media, padding, ouverture::ouvrir)
    }
}

/** Sans état, pour que le test d'interface n'ait besoin ni de base ni de Hilt. */
@Composable
internal fun MediaGalleryContent(
    media: List<Attachment>?,
    padding: PaddingValues,
    onOpen: (Attachment) -> Unit,
) {
    when {
        media == null -> Unit
        media.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.media_gallery_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TILE_MIN_SIZE),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            items(media, key = { it.id }) { attachment ->
                MediaTile(attachment) { onOpen(attachment) }
            }
        }
    }
}

@Composable
private fun MediaTile(attachment: Attachment, onClick: () -> Unit) {
    val fond = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(fond)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (attachment.isImage) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(attachment.modeleImage())
                    // Plafond au décodage : une longue conversation ne charge pas ses photos en pleine taille.
                    .size(THUMBNAIL_PX)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.fileName ?: stringResource(R.string.attachment_image_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // Un fichier disparu laisse une case neutre, pas un trou.
                error = ColorPainter(fond),
                fallback = ColorPainter(fond),
            )
        } else {
            // Comme la bulle : pas d'image extraite de la vidéo.
            Icon(
                Icons.Outlined.PlayCircleOutline,
                contentDescription = stringResource(R.string.attachment_video_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(VIDEO_ICON_SIZE),
            )
        }
    }
}

private val TILE_MIN_SIZE = 110.dp
private val TILE_GAP = 2.dp
private val VIDEO_ICON_SIZE = 40.dp
private const val THUMBNAIL_PX = 360
