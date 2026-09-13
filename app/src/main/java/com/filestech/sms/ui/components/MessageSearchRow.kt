package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.ui.util.relativeRowLabel
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * v1.28.8 (issue #17) — un message trouvé par la recherche : sa conversation, sa date, et l'extrait
 * où les passages trouvés sont mis en évidence. Un appui ouvre la conversation.
 */
@Composable
fun MessageSearchRow(
    hit: MessageSearchHit,
    /**
     * `null` = ligne non cliquable (mode sélection) : ni action, ni effet d'appui. Sa sémantique reste
     * FUSIONNÉE (audit 3 axes, U1) : sans `clickable`, TalkBack annoncerait sinon l'avatar, le titre,
     * la date et l'extrait comme quatre éléments séparés.
     */
    onClick: (() -> Unit)?,
    showAvatars: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val conversation = hit.conversation
    val formatters = rememberChatFormatters()
    val dateLabel = remember(hit.date) { formatters.relativeRowLabel(hit.date) }
    val surligne = SpanStyle(fontWeight = FontWeight.SemiBold, color = cs.primary)
    val extrait = remember(hit.excerpt, surligne) { extraitSurligne(hit.excerpt, surligne) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAvatars) {
            Avatar(label = conversation.title, customUri = conversation.avatarUri, isGroup = conversation.isGroup)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = extrait,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Transforme l'extrait marqué par l'index en texte mis en évidence. Les marqueurs ne sont JAMAIS
 * rendus. Un marqueur orphelin — l'extrait peut couper un passage — ouvre ou ferme simplement la
 * mise en évidence, sans rien casser : une fin sans début est ignorée, un début sans fin court
 * jusqu'au bout de l'extrait.
 */
internal fun extraitSurligne(extrait: String, surligne: SpanStyle): AnnotatedString = buildAnnotatedString {
    val debut = MessageSearchHit.MARK_START.single()
    val fin = MessageSearchHit.MARK_END.single()
    var ouvert = false
    for (c in extrait) {
        when {
            c == debut -> if (!ouvert) {
                pushStyle(surligne)
                ouvert = true
            }
            c == fin -> if (ouvert) {
                pop()
                ouvert = false
            }
            else -> append(c)
        }
    }
    if (ouvert) pop()
}
