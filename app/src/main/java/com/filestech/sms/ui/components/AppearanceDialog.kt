package com.filestech.sms.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.filestech.sms.R
import com.filestech.sms.ui.theme.BrandBlue
import com.filestech.sms.ui.theme.BubbleColorPalette
import timber.log.Timber

/**
 * v1.11.0 — Sujet 5 apparence : dialog "Personnaliser l'apparence" pour
 * une conversation. Permet à l'utilisateur de :
 *  - choisir une couleur de bulle sortante parmi [BubbleColorPalette.OPTIONS]
 *    (palette WCAG-safe, pas de free picker pour garantir contraste blanc)
 *  - choisir un avatar custom via le `PickVisualMedia` système (galerie ou
 *    fichier image), persisté en `content://` URI via
 *    `takePersistableUriPermission`
 *  - réinitialiser (couleur = null + avatar = null) pour revenir au défaut
 *
 * **Politique d'URI persistante** : on demande `FLAG_GRANT_READ_URI_PERMISSION`
 * + `takePersistableUriPermission` lors du pick pour que l'URI reste lisible
 * après reboot device. Sinon Android révoque l'accès au prochain process
 * restart et l'avatar devient un `Icons.Outlined.BrokenImage` silencieux.
 *
 * **Limites acceptées** :
 *  - Si l'user déplace/supprime l'image source dans sa galerie, le rendu
 *    Coil échoue silencieusement → fallback au contact natif. Pas de copie
 *    en interne pour rester minimal et FLOSS-friendly (pas de FileProvider).
 *  - Pas de crop. L'image est rendue dans un cercle `CircleShape` avec
 *    `ContentScale.Crop` automatique par Coil.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceDialog(
    currentBubbleColorArgb: Int?,
    currentAvatarUri: String?,
    onDismiss: () -> Unit,
    onConfirm: (bubbleColorArgb: Int?, avatarUri: String?) -> Unit,
) {
    val ctx = LocalContext.current
    val palette = BubbleColorPalette.OPTIONS
    // BRAND_BLUE en première position = sélection équivalente à reset (null
    // → bleu marque). On normalise le state local au défaut si current=null.
    var pickedColor by remember(currentBubbleColorArgb) {
        mutableStateOf(currentBubbleColorArgb?.let { Color(it) } ?: BubbleColorPalette.BRAND_BLUE)
    }
    var pickedAvatarUri by remember(currentAvatarUri) {
        mutableStateOf(currentAvatarUri)
    }

    // Picker avatar : retourne Uri? (null si l'user annule).
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val newUriStr = uri.toString()
            // v1.11.0 audit SEC-V3 — persister la permission READ avant TOUT
            // changement d'état. Si le take échoue (provider révoqué entre
            // pick et take, PhotoPicker scope-restricted Android 14+), on
            // NE stocke PAS l'URI : sinon Room contient une URI invalide,
            // Coil échoue silencieusement, l'user croit avoir un avatar
            // configuré mais ne voit que le fallback. Mieux vaut signaler
            // l'échec en gardant l'ancien (ou null) que polluer la DB.
            val takeResult = runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (takeResult.isFailure) {
                Timber.w(
                    takeResult.exceptionOrNull(),
                    "AppearanceDialog: takePersistableUriPermission failed for %s — keeping previous avatar",
                    uri,
                )
                return@rememberLauncherForActivityResult
            }
            // v1.11.0 audit S6 — release l'ANCIENNE URI persisted APRÈS la
            // prise réussie de la nouvelle (anti-accumulation grants). Ordre
            // important : si le take réussit mais le release échoue, on a
            // un grant en plus mais l'avatar fonctionne. L'inverse (release
            // d'abord) laisserait potentiellement l'user sans avatar si le
            // take échouait après.
            pickedAvatarUri?.let { oldStr ->
                if (oldStr != newUriStr) {
                    runCatching {
                        ctx.contentResolver.releasePersistableUriPermission(
                            Uri.parse(oldStr),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
            pickedAvatarUri = newUriStr
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ─── Section couleur bulle ───
                Text(
                    text = stringResource(R.string.appearance_section_bubble_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                // v1.11.0 audit U3 — FlowRow pour s'adapter aux petits écrans
                // (320dp Galaxy A03s, Redmi 9A) où 8 × 36dp chips dépassaient
                // la largeur disponible dans un AlertDialog (margins 48dp).
                val pickedArgb = pickedColor.toArgb()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    palette.forEach { option ->
                        val name = stringResource(option.nameRes)
                        val isSelected = option.color.toArgb() == pickedArgb
                        ColorChip(
                            color = option.color,
                            selected = isSelected,
                            contentName = name,
                            onClick = { pickedColor = option.color },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))

                // ─── Section avatar ───
                Text(
                    text = stringResource(R.string.appearance_section_avatar),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarPreview(uri = pickedAvatarUri)
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.appearance_pick_avatar))
                        }
                        if (pickedAvatarUri != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    // Release la persistable permission pour ne pas
                                    // accumuler les permissions inutiles côté provider.
                                    pickedAvatarUri?.let { uriStr ->
                                        runCatching {
                                            ctx.contentResolver.releasePersistableUriPermission(
                                                Uri.parse(uriStr),
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                            )
                                        }
                                    }
                                    pickedAvatarUri = null
                                },
                            ) {
                                Text(stringResource(R.string.appearance_reset_avatar))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Sélection BRAND_BLUE = équivalent à reset couleur côté persistance.
                    val argbToSave = if (pickedColor.toArgb() == BubbleColorPalette.BRAND_BLUE.toArgb()) {
                        null
                    } else {
                        pickedColor.toArgb()
                    }
                    onConfirm(argbToSave, pickedAvatarUri)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue,
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ColorChip(
    color: Color,
    selected: Boolean,
    contentName: String,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.onSurface
    // v1.11.0 audit U1 — a11y TalkBack : un Box cliquable sans nom n'est pas
    // accessible aux malvoyants. On pose un contentDescription explicite +
    // role + selected pour que TalkBack annonce "Bleu marque, sélectionnée"
    // au lieu de "non étiqueté".
    val selectedSuffix = if (selected) {
        " " + stringResource(com.filestech.sms.R.string.appearance_color_selected)
    } else {
        ""
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics {
                contentDescription = contentName + selectedSuffix
                this.selected = selected
                role = Role.RadioButton
            }
            .background(color, CircleShape)
            .then(
                if (selected) Modifier.border(
                    width = 2.dp,
                    color = borderColor,
                    shape = CircleShape,
                ) else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AvatarPreview(uri: String?) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(cs.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .background(cs.surfaceVariant, CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

