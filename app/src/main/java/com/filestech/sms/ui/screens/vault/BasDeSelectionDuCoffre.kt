package com.filestech.sms.ui.screens.vault

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.screens.conversations.ActionDeSelection
import com.filestech.sms.ui.screens.conversations.ConfirmationSuppression
import com.filestech.sms.ui.screens.conversations.ConfirmationSuppressionGroupee

/**
 * 2026-11-27 (issue #108, « I can't do anything » une fois les messages sélectionnés) — **le bas du Coffre pendant
 * une sélection** : « Sortir du coffre » et « Supprimer », chacune avec son libellé.
 *
 * La v1.31.0 a donné cette barre à la liste ; le Coffre, son jumeau, gardait un cadenas sans libellé en haut de
 * l'écran, et aucun moyen de supprimer une sélection. Mêmes boutons ([ActionDeSelection]) et même confirmation
 * ([ConfirmationSuppressionGroupee]) que la liste : la fenêtre FIGE les conversations à son ouverture et ce sont
 * elles que reçoit [onSupprimer]. Rien hors sélection, ni tant que le contenu du Coffre n'est pas dévoilé
 * ([visible]). La confirmation repart fermée à chaque nouvelle sélection : son état quitte la composition avec la barre.
 */
@Composable
internal fun BasDeSelectionDuCoffre(
    selectionnees: Set<Long>,
    visible: Boolean,
    onSortir: () -> Unit,
    onSupprimer: (List<Long>) -> Unit,
) {
    if (selectionnees.isEmpty() || !visible) return
    var aConfirmer by remember { mutableStateOf<List<Long>?>(null) }
    BottomAppBar {
        ActionDeSelection(
            icone = Icons.Outlined.LockOpen,
            libelle = stringResource(R.string.bulk_vault_move_out),
            onClick = onSortir,
            modifier = Modifier.weight(1f),
        )
        ActionDeSelection(
            icone = Icons.Outlined.Delete,
            libelle = stringResource(R.string.action_delete),
            onClick = { aConfirmer = selectionnees.toList() },
            modifier = Modifier.weight(1f),
            teinte = MaterialTheme.colorScheme.error,
        )
    }
    aConfirmer?.let { figees ->
        ConfirmationSuppressionGroupee(
            nombre = figees.size,
            onConfirmer = {
                aConfirmer = null
                onSupprimer(figees)
            },
            onAnnuler = { aConfirmer = null },
        )
    }
}

/**
 * 2026-11-27 (issue #108) — **l'appui long sur une conversation du Coffre** : « Sortir du coffre », « Sélectionner
 * plusieurs », et désormais « Supprimer », en rouge et confirmé, comme dans le menu de la liste. Le Coffre ne
 * proposait que d'en sortir : supprimer une conversation demandait de l'ouvrir.
 *
 * [cible] : la conversation de la feuille, `null` feuille fermée. La confirmation survit à la fermeture de la
 * feuille (son état vit ici, pas dans la feuille) et reçoit la conversation figée à l'appui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionsDUneConversationDuCoffre(
    cible: Long?,
    onFermer: () -> Unit,
    onSortir: (Long) -> Unit,
    onSelectionner: (Long) -> Unit,
    onSupprimer: (Long) -> Unit,
) {
    var aSupprimer by remember { mutableStateOf<Long?>(null) }
    if (cible != null) {
        ModalBottomSheet(onDismissRequest = onFermer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    text = stringResource(R.string.vault_actions_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                LigneDAction(Icons.Outlined.LockOpen, stringResource(R.string.vault_move_out)) {
                    onFermer()
                    onSortir(cible)
                }
                LigneDAction(Icons.Outlined.Check, stringResource(R.string.bulk_select_multiple)) {
                    onFermer()
                    onSelectionner(cible)
                }
                LigneDAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), MaterialTheme.colorScheme.error) {
                    onFermer()
                    aSupprimer = cible
                }
            }
        }
    }
    aSupprimer?.let { id ->
        ConfirmationSuppression(
            titre = stringResource(R.string.conversations_delete_confirm_title),
            corps = stringResource(R.string.conversations_delete_confirm_body),
            onConfirmer = {
                aSupprimer = null
                onSupprimer(id)
            },
            onAnnuler = { aSupprimer = null },
        )
    }
}

/** Une ligne de la feuille : icône et libellé, de la teinte donnée (le rouge pour « Supprimer », comme la liste). */
@Composable
private fun LigneDAction(icone: ImageVector, libelle: String, teinte: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(icone, contentDescription = null, tint = teinte.takeOrElse { LocalContentColor.current })
        },
        headlineContent = { Text(libelle, color = teinte) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 2026-11-27 (issue #108) — affiche le bilan d'une suppression groupée du Coffre, en rouge si elle n'a pas abouti. */
internal suspend fun SnackbarHostState.afficherBilanDuCoffre(res: Resources, bilan: VaultViewModel.Event.Suppression) {
    val (texte, erreur) = texteDuBilanDuCoffre(res, bilan)
    if (erreur) showError(texte) else showSnackbar(texte)
}

/**
 * 2026-11-27 (issue #108) — le texte du bilan, et s'il s'affiche en rouge. Testable sans l'écran.
 *
 * Une conversation du Coffre retenue par sa copie système l'est le plus souvent parce que SMS Tech n'est pas
 * l'application SMS par défaut : réessayer tel quel n'y change rien, et le texte dit quoi faire, comme celui du fil
 * (`conversations_delete_vault_kept_system`). Pour toute autre cause, ou des causes mêlées, le texte de la liste.
 */
internal fun texteDuBilanDuCoffre(res: Resources, bilan: VaultViewModel.Event.Suppression): Pair<String, Boolean> =
    when (bilan) {
        is VaultViewModel.Event.Suppression.Supprimees ->
            res.getQuantityString(R.plurals.conversations_bulk_deleted, bilan.nombre, bilan.nombre) to false
        is VaultViewModel.Event.Suppression.Conservees -> res.getQuantityString(
            if (bilan.copieSysteme) R.plurals.vault_bulk_delete_kept_system else R.plurals.conversations_bulk_delete_kept,
            bilan.nombre,
            bilan.nombre,
        ) to true
    }
