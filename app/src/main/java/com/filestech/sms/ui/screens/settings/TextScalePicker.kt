package com.filestech.sms.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.settings.TextScale
import com.filestech.sms.ui.theme.factor
import com.filestech.sms.ui.theme.scaledBy

/**
 * 2026-10-29 — sélecteur de la taille du texte des messages (Réglages → Apparence), cf.
 * [com.filestech.sms.ui.theme.MessageTextSize]. Chaque cran est écrit À SA TAILLE : on choisit en
 * voyant, sans aller-retour avec une conversation. Défilable : au cran le plus haut, sur une police
 * Android déjà grande, les six lignes dépassent la hauteur de l'écran.
 */
@Composable
internal fun TextScalePickerDialog(
    current: TextScale,
    onPick: (TextScale) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_text_scale)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_text_scale_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                // Un seul groupe pour TalkBack : il annonce la position (« 3 sur 6 »).
                Column(modifier = Modifier.selectableGroup()) {
                    TextScale.entries.forEach { scale ->
                        TextScaleOption(
                            label = textScaleLabel(scale),
                            style = MaterialTheme.typography.bodyLarge.scaledBy(scale.factor()),
                            selected = scale == current,
                            onClick = { onPick(scale) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Une ligne = un seul élément pour TalkBack (`selectable` + bouton radio sans action propre). */
@Composable
private fun TextScaleOption(label: String, style: TextStyle, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label, style = style)
    }
}

/** `when` exhaustif : un cran ajouté à [TextScale] sans libellé ne compile pas. */
@Composable
internal fun textScaleLabel(scale: TextScale): String = stringResource(
    when (scale) {
        TextScale.XS -> R.string.text_scale_xs
        TextScale.S -> R.string.text_scale_s
        TextScale.MEDIUM -> R.string.text_scale_medium
        TextScale.L -> R.string.text_scale_l
        TextScale.XL -> R.string.text_scale_xl
        TextScale.XXL -> R.string.text_scale_xxl
    },
)
