package com.filestech.sms.ui.screens.conversations

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.theme.BrandDanger

/**
 * 2026-11-16 (v1.31.0) — le bas de la liste pendant une sélection : la barre des actions, et la confirmation d'une
 * suppression, qui appelle [onSupprimer] seulement une fois confirmée. Rien hors sélection, rien en session leurre.
 * Les conditions vivent ici et non dans [ConversationsScreen], au seuil de complexité de detekt. La confirmation
 * repart fermée à chaque nouvelle sélection : son état quitte la composition avec la barre.
 */
@Composable
internal fun BasDeSelection(
    selection: Boolean,
    leurre: Boolean,
    archives: Boolean,
    nombre: Int,
    onArchiver: () -> Unit,
    onCoffre: () -> Unit,
    onSupprimer: () -> Unit,
) {
    if (!selection || leurre) return
    var confirmer by remember { mutableStateOf(false) }
    BarreActionsSelection(
        archives = archives,
        onArchiver = onArchiver,
        onCoffre = onCoffre,
        onSupprimer = { confirmer = true },
    )
    if (confirmer) {
        ConfirmationSuppressionGroupee(
            nombre = nombre,
            onConfirmer = {
                confirmer = false
                onSupprimer()
            },
            onAnnuler = { confirmer = false },
        )
    }
}

/** 2026-11-16 (v1.31.0) — affiche le bilan d'une action groupée, en rouge s'il n'a pas abouti. */
internal suspend fun SnackbarHostState.afficherBilan(res: Resources, bilan: ConversationsViewModel.Event.Groupe) {
    val (texte, erreur) = texteDuBilan(res, bilan)
    if (erreur) showError(texte) else showSnackbar(texte)
}

/**
 * 2026-11-16 (v1.31.0, courriel du 2026-11-11) — **les actions sur une sélection de conversations**, en bas de
 * l'écran, chacune avec son libellé.
 *
 * Jusqu'ici, la seule action d'une sélection était une icône de cadenas sans libellé en haut de l'écran, à la place
 * et sous la forme de l'icône « ouvrir le Coffre » : l'utilisateur qui demandait de pouvoir mettre une sélection au
 * Coffre ne l'avait pas trouvée. Les trois actions ont donc un texte, lu aussi par TalkBack (le clic fusionne
 * l'icône et le libellé en un seul bouton).
 *
 * [archives] : la vue est celle des Archives, où l'action rend les conversations à la liste.
 */
@Composable
internal fun BarreActionsSelection(
    archives: Boolean,
    onArchiver: () -> Unit,
    onCoffre: () -> Unit,
    onSupprimer: () -> Unit,
) {
    BottomAppBar {
        ActionDeSelection(
            icone = if (archives) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2,
            libelle = stringResource(if (archives) R.string.action_unarchive else R.string.action_archive),
            onClick = onArchiver,
            modifier = Modifier.weight(1f),
        )
        ActionDeSelection(
            icone = Icons.Outlined.Lock,
            libelle = stringResource(R.string.bulk_vault_move_in),
            onClick = onCoffre,
            modifier = Modifier.weight(1f),
        )
        ActionDeSelection(
            icone = Icons.Outlined.Delete,
            libelle = stringResource(R.string.action_delete),
            onClick = onSupprimer,
            modifier = Modifier.weight(1f),
            teinte = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ActionDeSelection(
    icone: ImageVector,
    libelle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    teinte: Color = LocalContentColor.current,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icone, contentDescription = null, tint = teinte)
        Text(
            text = libelle,
            color = teinte,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * 2026-11-16 (v1.31.0) — la confirmation d'une suppression groupée : le NOMBRE de conversations est dans le titre,
 * pour qu'une sélection plus large que prévu se voie avant d'être effacée. Même bouton que la suppression d'une
 * seule (rouge plein, texte blanc).
 */
@Composable
internal fun ConfirmationSuppressionGroupee(
    nombre: Int,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(pluralStringResource(R.plurals.conversations_bulk_delete_title, nombre, nombre)) },
        text = { Text(stringResource(R.string.conversations_bulk_delete_body)) },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirmer,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BrandDanger,
                    contentColor = Color.White,
                ),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 2026-11-16 (v1.31.0) — le texte du bilan d'une action groupée, et s'il s'affiche en rouge. Hors de l'écran, dont
 * la complexité est au seuil de detekt, et testable sans lui.
 */
internal fun texteDuBilan(res: Resources, bilan: ConversationsViewModel.Event.Groupe): Pair<String, Boolean> =
    when (bilan) {
        is ConversationsViewModel.Event.Groupe.Archivees -> res.getQuantityString(
            if (bilan.archivees) R.plurals.conversations_bulk_archived else R.plurals.conversations_bulk_unarchived,
            bilan.nombre,
            bilan.nombre,
        ) to false
        ConversationsViewModel.Event.Groupe.ArchivageEchoue ->
            res.getString(R.string.conversations_bulk_archive_failed) to true
        is ConversationsViewModel.Event.Groupe.Supprimees ->
            res.getQuantityString(R.plurals.conversations_bulk_deleted, bilan.nombre, bilan.nombre) to false
        is ConversationsViewModel.Event.Groupe.SuppressionsConservees ->
            res.getQuantityString(R.plurals.conversations_bulk_delete_kept, bilan.nombre, bilan.nombre) to true
    }
