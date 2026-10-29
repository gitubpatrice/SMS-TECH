package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * 2026-10-29 (issue #53) — la ligne « heure • statut » sous une bulle, écrite UNE fois pour les
 * trois bulles (texte, vocale, photo). Le libellé vient de [libelleDeStatut].
 *
 * Avant : deux copies identiques (texte, vocale) et RIEN sous une photo — `MediaAttachmentBubble`
 * recevait `showTimestamp` sans jamais l'utiliser. Un MMS photo en échec était donc indiscernable
 * d'un MMS parti : c'est exactement ce que décrit l'issue #53.
 *
 * Affichée si [showTimestamp] (début d'une rafale) ou si l'envoi a échoué — un échec se dit
 * toujours. [modifier] porte les marges propres à chaque bulle.
 */
@Composable
internal fun MessageStatusLine(
    message: Message,
    isOut: Boolean,
    showTimestamp: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showTimestamp && message.status != Message.Status.FAILED) return
    val cs = MaterialTheme.colorScheme
    val label = libelleDeStatut(message)?.let { statut ->
        statut.codeOperateur?.let { stringResource(statut.res, it) } ?: stringResource(statut.res)
    }
    val formatters = rememberChatFormatters()
    // Audit P-Q7 (v1.2.0) : l'heure formatée est mise en cache, pas recalculée à chaque recomposition.
    val time = remember(message.date) { formatters.time.format(Date(message.date)) }
    Row(
        modifier = Modifier.fillMaxWidth().then(modifier),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (label != null) "$time • $label" else time,
            color = if (message.status == Message.Status.FAILED) cs.error else cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
