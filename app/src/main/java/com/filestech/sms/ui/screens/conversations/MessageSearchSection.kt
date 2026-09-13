package com.filestech.sms.ui.screens.conversations

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.ui.components.MessageSearchRow

/**
 * v1.28.8 (issue #17) — section « Messages » de la liste : les messages dont le texte correspond à
 * la recherche, sous les conversations. Sortie de [ConversationsScreen], avec ses deux décisions,
 * pour garder l'écran sous le seuil de complexité du projet. Sans résultat, rien n'est émis — pas
 * même l'en-tête. En mode sélection, un appui n'ouvre rien : il ne doit pas quitter la sélection
 * en cours.
 */
internal fun LazyListScope.messageHitsSection(
    hits: List<MessageSearchHit>,
    showAvatars: Boolean,
    selectionMode: Boolean,
    dividerColor: Color,
    onOpenThread: (Long) -> Unit,
) {
    if (hits.isEmpty()) return
    item(key = "header-messages", contentType = "header") {
        Text(
            text = stringResource(R.string.conversations_section_messages),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        )
    }
    items(hits, key = { "message-${it.messageId}" }, contentType = { "message-hit" }) { hit ->
        MessageSearchRow(
            hit = hit,
            onClick = if (selectionMode) null else { { onOpenThread(hit.conversation.id) } },
            showAvatars = showAvatars,
        )
        HorizontalDivider(color = dividerColor)
    }
}

/**
 * v1.28.8 — l'état vide ne s'affiche que si RIEN ne correspond : ni conversation, ni message. Une
 * recherche qui ne trouve qu'un vieux message doit montrer ce message, pas « aucune conversation ».
 */
internal fun ConversationsViewModel.UiState.sansAucunResultat(hits: List<MessageSearchHit>): Boolean =
    conversations.isEmpty() && hits.isEmpty()
