package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.3.0 — Petit cercle 28 dp affichant l'emoji de réaction posé sur un message. Fond
 * `surfaceContainerHigh` + bordure couleur `surface` qui donne l'illusion d'un cercle qui
 * "découpe" la bulle quand il chevauche son bord (cf. [BubbleReactionOverlay]).
 *
 * Tap = appelle [onClick] (typiquement : retire la réaction côté ViewModel/Room).
 */
@Composable
fun EmojiReactionBadge(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .border(width = 1.5.dp, color = cs.surface, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 14.sp)
    }
}

/**
 * v1.3.0 — Wrap arbitraire d'une bulle pour ajouter un [EmojiReactionBadge] en surplomb
 * du coin bas, **côté opposé au speaker**. Réutilisé identiquement par [MessageBubble] et
 * [AudioMessageBubble] — pas de duplication de la logique d'alignement.
 *
 * Le early-return quand `reactionEmoji == null` garantit zéro overhead de composition pour
 * les bulles sans réaction (= 99 % des cas).
 */
@Composable
fun BubbleReactionOverlay(
    reactionEmoji: String?,
    isOutgoing: Boolean,
    onRemoveReaction: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (reactionEmoji == null) {
        content()
        return
    }
    Box {
        content()
        EmojiReactionBadge(
            emoji = reactionEmoji,
            onClick = onRemoveReaction,
            modifier = Modifier
                .align(if (isOutgoing) Alignment.BottomStart else Alignment.BottomEnd)
                .offset(
                    x = if (isOutgoing) (-8).dp else 8.dp,
                    y = 8.dp,
                ),
        )
    }
}
