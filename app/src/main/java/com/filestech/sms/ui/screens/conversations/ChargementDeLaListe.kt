package com.filestech.sms.ui.screens.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import kotlinx.coroutines.delay

/**
 * 2026-11-11 (issue #90) — **une liste qui n'a pas encore répondu le dit.**
 *
 * Tant que la première lecture de la base n'était pas arrivée, l'écran ne dessinait RIEN à la place de la
 * liste : ni conversations, ni texte d'état vide, ni indicateur. Une lecture retardée (cf.
 * `ConversationMirror.refreshContactNames`) se voyait donc comme une liste vide, et l'utilisateur fermait
 * et rouvrait l'application jusqu'à la voir paraître.
 *
 * L'indicateur n'apparaît qu'après [DELAI_AVANT_INDICATEUR_MS] : une lecture ordinaire dure quelques
 * millisecondes, et un indicateur montré le temps d'une image clignoterait à chaque ouverture.
 */
@Composable
internal fun ChargementDeLaListe() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DELAI_AVANT_INDICATEUR_MS)
        visible = true
    }
    if (!visible) return
    val description = stringResource(R.string.conversations_loading)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

private const val DELAI_AVANT_INDICATEUR_MS = 400L
