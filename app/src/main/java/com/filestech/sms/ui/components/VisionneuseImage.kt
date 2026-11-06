package com.filestech.sms.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment

/**
 * 2026-11-06 (issue #76) — **une photo s'ouvre dans l'application.**
 *
 * Toucher une photo la confiait à une app externe ([ouvrirPieceJointe]). Sur GrapheneOS, la Galerie restait
 * noire sur une photo reçue, « sauf si l'image est déjà sur le téléphone » ; la cause n'est pas établie (la
 * photo lui parvient par une URI du FileProvider, avec la permission de lecture). L'afficher nous-mêmes ne
 * dépend plus d'aucune autre app. La photo ne quitte plus l'application, et la fenêtre hérite de la
 * protection contre les captures de l'activité (`SecureFlagPolicy.Inherit`, le défaut de [Dialog]).
 * « Ouvrir avec une autre app » garde l'ancien chemin. Les vidéos et les fichiers vont toujours à l'app du
 * système.
 */
@Composable
fun VisionneuseImage(image: Attachment, onOuvrirAvec: () -> Unit, onFermer: () -> Unit) {
    Dialog(
        onDismissRequest = onFermer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var echec by remember(image.id) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ImageZoomable(image, onEchec = { echec = true })
            if (echec) {
                Text(
                    text = stringResource(R.string.image_viewer_error),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onFermer) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onOuvrirAvec) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.image_viewer_open_with),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** Pincer pour agrandir (jusqu'à [ZOOM_MAX]), glisser pour se déplacer, toucher deux fois pour basculer. */
@Composable
private fun ImageZoomable(image: Attachment, onEchec: () -> Unit) {
    var echelle by remember(image.id) { mutableFloatStateOf(1f) }
    var decalage by remember(image.id) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val largeur = constraints.maxWidth.toFloat()
        val hauteur = constraints.maxHeight.toFloat()

        // L'image agrandie ne quitte pas l'écran : le décalage reste dans ce que l'agrandissement a ajouté.
        fun borne(o: Offset, s: Float): Offset {
            val maxX = largeur * (s - 1f) / 2f
            val maxY = hauteur * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image.modeleImage())
                // Assez pour agrandir sans flou visible, sans décoder une photo de 48 Mpx en pleine taille.
                .size(TAILLE_MAX_PX)
                .build(),
            contentDescription = image.fileName ?: stringResource(R.string.attachment_image_label),
            contentScale = ContentScale.Fit,
            onError = { onEchec() },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(image.id) {
                    detectTapGestures(
                        onDoubleTap = { point ->
                            if (echelle > 1f) {
                                echelle = 1f
                                decalage = Offset.Zero
                            } else {
                                echelle = ZOOM_DOUBLE_APPUI
                                // Agrandir VERS le point touché : il reste sous le doigt.
                                decalage = borne((size.center.toOffset() - point) * (ZOOM_DOUBLE_APPUI - 1f), ZOOM_DOUBLE_APPUI)
                            }
                        },
                    )
                }
                .pointerInput(image.id) {
                    detectTransformGestures { _, deplacement, zoom, _ ->
                        val nouvelle = (echelle * zoom).coerceIn(1f, ZOOM_MAX)
                        echelle = nouvelle
                        decalage = if (nouvelle == 1f) Offset.Zero else borne(decalage + deplacement, nouvelle)
                    }
                }
                .graphicsLayer {
                    scaleX = echelle
                    scaleY = echelle
                    translationX = decalage.x
                    translationY = decalage.y
                },
        )
    }
}

/**
 * Comment un écran ouvre ses pièces jointes : une photo dans [VisionneuseImage], le reste dans l'app du
 * système ([ouvrirPieceJointe]). Une seule règle pour la bulle et pour la galerie de la conversation.
 */
@Stable
class OuvertureDesPiecesJointes internal constructor(private val context: Context) {

    /** La photo affichée, ou `null`. */
    var enVisionneuse: Attachment? by mutableStateOf(null)
        private set

    fun ouvrir(cible: Attachment) {
        if (cible.isImage) enVisionneuse = cible else ouvrirPieceJointe(context, cible)
    }

    fun ouvrirAvec(cible: Attachment) = ouvrirPieceJointe(context, cible)

    fun fermer() {
        enVisionneuse = null
    }
}

@Composable
fun rememberOuvertureDesPiecesJointes(): OuvertureDesPiecesJointes {
    val context = LocalContext.current
    return remember(context) { OuvertureDesPiecesJointes(context) }
}

/** La visionneuse de [etat], quand une photo y est ouverte. */
@Composable
fun VisionneuseDesPiecesJointes(etat: OuvertureDesPiecesJointes) {
    etat.enVisionneuse?.let { image ->
        VisionneuseImage(image = image, onOuvrirAvec = { etat.ouvrirAvec(image) }, onFermer = etat::fermer)
    }
}

private const val TAILLE_MAX_PX = 2048
private const val ZOOM_MAX = 5f
private const val ZOOM_DOUBLE_APPUI = 2.5f
