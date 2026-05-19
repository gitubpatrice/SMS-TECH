package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.ui.theme.bubbleIncomingColor

/**
 * v1.3.0 — Petit cercle 28 dp affichant l'emoji de réaction posé sur un message.
 *
 * **v1.4.1** : le fond du badge est désormais **identique à la couleur de la bulle**
 * qu'il décore (paramètre [bgColor] passé par [BubbleReactionOverlay] : `cs.primary`
 * pour outgoing, `bubbleIncomingColor` pour incoming). La bordure couleur `surface`
 * (fond du fil) conserve l'effet "le cercle découpe la bulle" quand le badge chevauche
 * son bord — l'anneau blanc ressort la silhouette du cercle, exactement comme avant,
 * mais le centre se fond maintenant dans la bulle plutôt que d'introduire une 3ᵉ
 * couleur (gris) qui cassait l'unité visuelle de la conversation.
 *
 * Tap = appelle [onClick] (typiquement : retire la réaction côté ViewModel/Room).
 */
@Composable
fun EmojiReactionBadge(
    emoji: String,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // v1.5.0 — pill shape that AUTO-GROWS when the reaction string carries multiple
    // emojis (multi-react feature). A single-emoji badge keeps the same 28 dp circle
    // appearance it had since v1.3.0 (min width = min height = 28 dp, rounded-rect
    // with corner radius half the height = visually a circle). Adding a 2nd, 3rd or
    // 4th emoji lets the Box widen naturally, so "❤️👍🎉" renders as a horizontal
    // pill instead of being cropped inside a 28 dp circle. The 1.5 dp border in
    // `cs.surface` is preserved so the badge still "cuts" cleanly into the bubble it
    // overlaps.
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .widthIn(min = 28.dp)
            .clip(shape)
            .background(bgColor)
            .border(width = 1.5.dp, color = cs.surface, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
 *
 * **v1.4.1** : la couleur de fond du badge est calculée ici à partir de [isOutgoing] et
 * passée à [EmojiReactionBadge]. Outgoing → `cs.primary` (brand-blue, identique au
 * `outgoingBrush` de [MessageBubble] et au fond brand-blue de [MediaAttachmentBubble]).
 * Incoming → [bubbleIncomingColor] (le même slate-blue que la bulle reçue dessine).
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
    val cs = MaterialTheme.colorScheme
    val badgeBg = if (isOutgoing) cs.primary else bubbleIncomingColor(cs)
    Box {
        content()
        EmojiReactionBadge(
            emoji = reactionEmoji,
            bgColor = badgeBg,
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
