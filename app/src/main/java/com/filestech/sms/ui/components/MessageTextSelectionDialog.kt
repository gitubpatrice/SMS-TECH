package com.filestech.sms.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R
import com.filestech.sms.ui.security.SensitiveClipboard

/**
 * v1.28.6 — sélection libre d'un extrait de message.
 *
 * Jusqu'ici, l'appui long sur une bulle copiait le message ENTIER, doublon du « Copier » du menu
 * ⋮, et rien ne permettait d'en copier une partie. La sélection se fait ici, hors de la liste, et
 * non dans la bulle : dans une `LazyColumn`, la sélection par appui long et glissement se bat avec
 * le défilement, avec le tap-pour-réessayer des messages en échec et avec les liens cliquables.
 * Signal, Google Messages font de même.
 *
 * Le corps est affiché tel quel, sans liens ni numéros cliquables : ici on sélectionne, on ne
 * navigue pas.
 *
 * ⚠️ Le menu système de sélection copie via [LocalClipboard], pas via
 * [com.filestech.sms.ui.security.copyToClipboardSensitive]. L'enveloppe [SensitiveClipboard] pose
 * la même marque « sensible » que la copie totale, pour qu'un extrait d'un message du coffre ne
 * ressorte pas en vignette d'aperçu (invariant N4). `MessageTextSelectionDialogTest` le mesure.
 */
@Composable
fun MessageTextSelectionDialog(
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_select_text)) },
        text = { MessageTextSelectionContent(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * Le corps sélectionnable, séparé de la fenêtre pour être testable : une `Dialog` re-fournit ses
 * propres locals de plateforme à sa racine, une barre d'outils factice posée à l'extérieur n'y
 * entrerait pas. L'enveloppe [SensitiveClipboard] est posée ICI, sous cette racine, donc elle
 * s'applique dans la fenêtre comme dans le test.
 */
@Composable
internal fun MessageTextSelectionContent(body: String) {
    val clipboard = LocalClipboard.current
    val sensitive = remember(clipboard) { SensitiveClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides sensitive) {
        SelectionContainer(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .testTag(MESSAGE_TEXT_SELECTION_TAG),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal const val MESSAGE_TEXT_SELECTION_TAG = "message_text_selection"
