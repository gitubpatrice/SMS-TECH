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
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.ui.components.ConversationRow

/**
 * 2026-11-06 — le dossier des conversations bloquées, cf. [VueConversations] : une ligne qui dit ce qu'il
 * contient, puis les conversations.
 *
 * Jusqu'ici, ces lignes vivaient en bas de la liste principale sous un en-tête « Bloquées » (v1.25.3). Elles
 * gardent leur rendu et leurs deux actions : la corbeille, et « Débloquer » dans la feuille d'appui long.
 */
internal fun LazyListScope.blockedConversationsSection(
    conversations: List<Conversation>,
    showAvatars: Boolean,
    previewLines: Int,
    dividerColor: Color,
    onOpenThread: (Long) -> Unit,
    onLongClick: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    if (conversations.isEmpty()) return
    item(key = "blocked-info", contentType = "header") {
        Text(
            text = stringResource(R.string.conversations_blocked_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }
    items(conversations, key = { it.id }, contentType = { "blocked-row" }) { conv ->
        // Ligne simple, sans balayage : le geste de balayage sert à répondre et à supprimer sur une
        // conversation active. Sur une bloquée, les deux actions utiles sont explicites — corbeille rouge
        // ici, « Débloquer » dans la feuille d'appui long.
        //
        // v1.25.3 — les lignes bloquées ne participent PAS au mode sélection : elles utilisent
        // `ConversationRow` nu, sans l'état visuel de sélection de `SwipeableConversationRow`. Les y inclure
        // permettrait de sélectionner sans le voir, et une action groupée porterait sur des fils
        // invisiblement cochés.
        ConversationRow(
            conversation = conv,
            showAvatars = showAvatars,
            previewLines = previewLines,
            onClick = { onOpenThread(conv.id) },
            onLongClick = { onLongClick(conv) },
            onDelete = { onDelete(conv) },
        )
        HorizontalDivider(color = dividerColor)
    }
}
