package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R
import com.filestech.sms.domain.model.GroupName

/**
 * v1.28.3 — nommer ou renommer un groupe. Le même dialogue sert au menu du fil et à l'appui
 * long dans la liste : une seule surface, une seule règle ([GroupName.normalize]).
 *
 * Le texte d'aide dit ce que l'utilisateur ne peut pas deviner : le nom reste sur ce téléphone.
 * Sans lui, « Pat voit toujours mon numéro » passerait pour un défaut.
 *
 * @param current le nom choisi actuel, `null` s'il n'y en a pas.
 * @param onConfirm reçoit la saisie brute ; l'appelant la normalise. Vide = retirer le nom.
 */
@Composable
fun RenameGroupDialog(
    current: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var saisie by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_group_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = saisie,
                    onValueChange = { saisie = it.take(GroupName.MAX) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.rename_group_placeholder)) },
                    supportingText = { Text("${saisie.length} / ${GroupName.MAX}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.rename_group_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(saisie) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
