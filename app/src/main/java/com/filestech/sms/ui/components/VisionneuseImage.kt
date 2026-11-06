package com.filestech.sms.ui.components

import android.content.Context
import android.os.Build
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.imageLoader
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment

/**
 * 2026-11-06 (issue #76) — **une photo s'ouvre dans l'application.**
 *
 * Toucher une photo la confiait à une app externe ([ouvrirPieceJointe]). Sur GrapheneOS, la Galerie restait
 * noire sur une photo reçue, « sauf si l'image est déjà sur le téléphone » ; la cause n'est pas établie (la
 * photo lui parvient par une URI du FileProvider, avec la permission de lecture). L'afficher nous-mêmes ne
 * dépend plus d'aucune autre app. La photo ne quitte l'application que par « Ouvrir avec une autre app »
 * (l'ancien chemin), et la fenêtre hérite de la protection contre les captures de l'activité
 * (`SecureFlagPolicy.Inherit`, le défaut de [Dialog]). Les vidéos et les fichiers vont toujours à l'app du
 * système.
 *
 * Un GIF s'anime ([chargeurAnime], décodeur `coil-gif`, ajouté pour cela à la demande de Patrice) ; les vignettes
 * du fil restent fixes, pour ne pas animer toute une liste.
 */
@Composable
fun VisionneuseImage(image: Attachment, onOuvrirAvec: () -> Unit, onFermer: () -> Unit) {
    Dialog(
        onDismissRequest = onFermer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var etat by remember(image.id) { mutableStateOf(EtatImage.CHARGEMENT) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ImageZoomable(image, onEtat = { etat = it })
            when (etat) {
                EtatImage.CHARGEMENT -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TAG_CHARGEMENT),
                )
                EtatImage.ECHEC -> Text(
                    text = stringResource(R.string.image_viewer_error),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
                EtatImage.AFFICHEE -> Unit
            }
            // Un voile sous les boutons : blancs sur une photo claire, ils resteraient lisibles.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
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

internal enum class EtatImage { CHARGEMENT, AFFICHEE, ECHEC }

/** Repère du chargement en cours, pour les tests. */
internal const val TAG_CHARGEMENT = "visionneuse-chargement"

/**
 * Pincer pour agrandir (jusqu'à [ZOOM_MAX]) AUTOUR des doigts, glisser pour se déplacer, toucher deux fois
 * pour basculer. Le déplacement est borné à ce que l'agrandissement de l'image AFFICHÉE a ajouté : une photo
 * en hauteur sur un écran large ne part pas dans les bandes noires.
 */
@Composable
private fun ImageZoomable(image: Attachment, onEtat: (EtatImage) -> Unit) {
    var echelle by remember(image.id) { mutableFloatStateOf(1f) }
    var decalage by remember(image.id) { mutableStateOf(Offset.Zero) }
    var tailleImage by remember(image.id) { mutableStateOf<Size?>(null) }
    val contexte = LocalContext.current
    val chargeur = remember(contexte) { chargeurAnime(contexte) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ecran = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val affichee = tailleAffichee(tailleImage, ecran)
        AsyncImage(
            model = ImageRequest.Builder(contexte)
                .data(image.modeleImage())
                // Assez pour agrandir sans flou visible, sans décoder une photo de 48 Mpx en pleine taille.
                .size(TAILLE_MAX_PX)
                .build(),
            // Le nom d'un fichier de MMS (« image000000.jpg ») ne dit rien : « Image » le dit mieux.
            contentDescription = stringResource(R.string.attachment_image_label),
            imageLoader = chargeur,
            contentScale = ContentScale.Fit,
            onSuccess = { succes ->
                tailleImage = succes.painter.intrinsicSize.takeIf { it.isSpecified() }
                onEtat(EtatImage.AFFICHEE)
            },
            onError = { onEtat(EtatImage.ECHEC) },
            modifier = Modifier
                .fillMaxSize()
                // Clés : l'écran (rotation, fenêtre redimensionnée) et l'image décodée changent les bornes.
                .pointerInput(image.id, ecran, affichee) {
                    detectTapGestures(
                        onDoubleTap = { point ->
                            if (echelle > 1f) {
                                echelle = 1f
                                decalage = Offset.Zero
                            } else {
                                echelle = ZOOM_DOUBLE_APPUI
                                val versLePoint = zoomAutour(point, ecran, Offset.Zero, ZOOM_DOUBLE_APPUI)
                                decalage = borne(versLePoint, ZOOM_DOUBLE_APPUI, affichee, ecran)
                            }
                        },
                    )
                }
                .pointerInput(image.id, ecran, affichee) {
                    detectTransformGestures { centre, deplacement, zoom, _ ->
                        val nouvelle = (echelle * zoom).coerceIn(1f, ZOOM_MAX)
                        decalage = if (nouvelle == 1f) {
                            Offset.Zero
                        } else {
                            val autour = zoomAutour(centre, ecran, decalage, nouvelle / echelle)
                            borne(autour + deplacement, nouvelle, affichee, ecran)
                        }
                        echelle = nouvelle
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
 * Le chargeur de la visionneuse : celui de l'application (mêmes caches, `newBuilder`), plus le décodage des
 * images animées — `ImageDecoderDecoder` à partir d'Android 9 (GIF, WebP et HEIF animés), `GifDecoder`
 * avant.
 */
internal fun chargeurAnime(context: Context): ImageLoader =
    context.imageLoader.newBuilder()
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

private fun Size.isSpecified(): Boolean = width.isFinite() && height.isFinite() && width > 0f && height > 0f

/** La taille de l'image à l'échelle 1 dans [ecran], en `ContentScale.Fit` ; l'écran entier tant qu'elle est inconnue. */
internal fun tailleAffichee(image: Size?, ecran: Size): Size {
    if (image == null || !image.isSpecified() || !ecran.isSpecified()) return ecran
    val k = minOf(ecran.width / image.width, ecran.height / image.height)
    return Size(image.width * k, image.height * k)
}

/**
 * Le décalage qui garde sous [point] ce qui s'y trouvait quand l'échelle est multipliée par [facteur]. La couche
 * s'agrandit autour du centre de l'écran : sans ce terme, l'image glisse hors des doigts.
 */
internal fun zoomAutour(point: Offset, ecran: Size, decalage: Offset, facteur: Float): Offset {
    val depuisLeCentre = point - Offset(ecran.width / 2f, ecran.height / 2f)
    return depuisLeCentre * (1f - facteur) + decalage * facteur
}

/** Le décalage ramené dans ce que l'agrandissement à [echelle] de l'image [affichee] dépasse de l'[ecran]. */
internal fun borne(decalage: Offset, echelle: Float, affichee: Size, ecran: Size): Offset {
    val maxX = ((affichee.width * echelle - ecran.width) / 2f).coerceAtLeast(0f)
    val maxY = ((affichee.height * echelle - ecran.height) / 2f).coerceAtLeast(0f)
    return Offset(decalage.x.coerceIn(-maxX, maxX), decalage.y.coerceIn(-maxY, maxY))
}

/**
 * Comment un écran ouvre ses pièces jointes : une photo dans [VisionneuseImage], le reste dans l'app du
 * système ([ouvrirPieceJointe]). Une seule règle pour le fil et pour la galerie de la conversation.
 *
 * Tenue par l'ÉCRAN, pas par une bulle : une bulle sort de la composition dès que la liste défile, et la
 * visionneuse se serait fermée avec elle. `remember` et non `rememberSaveable`, volontairement : rouverte
 * après la mort du processus, elle afficherait une photo du coffre avant que le coffre soit rouvert.
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
