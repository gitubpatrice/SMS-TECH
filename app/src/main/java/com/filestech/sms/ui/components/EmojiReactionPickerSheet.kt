package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R

/**
 * v1.3.0 — Bottom sheet pour réagir à un message. Grille de 24 emojis populaires
 * (4 lignes × 6 colonnes) + bouton "Autre emoji" en bas qui délègue au composant
 * [EmojiCustomDialog] hébergé par `ThreadScreen` (pour éviter de stacker un dialog
 * dans un sheet).
 *
 * Tap sur un emoji → [onPicked] + close. Tap sur "Autre emoji" → [onOpenCustom] + close.
 * Touche `back` / drag down → [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiReactionPickerSheet(
    onPicked: (String) -> Unit,
    onOpenCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    // v1.3.0 audit Q7 — `skipPartiallyExpanded = true` aligne ce sheet sur le pattern projet
    // (`AttachmentPickerSheet`, `ConversationsScreen`). Sinon le sheet s'ouvre half-expanded
    // sur écran haut, incohérent UX et masquage partiel des emojis.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Grille 4 lignes × 6 colonnes = 24 emojis. `chunked(6)` garantit la cohérence
            // si on ajoute/retire des emojis : le rendu reste rectangulaire automatiquement.
            QUICK_PICK_EMOJIS.chunked(EMOJIS_PER_ROW).forEach { rowEmojis ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowEmojis.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onPicked(emoji)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = emoji, fontSize = 28.sp)
                        }
                    }
                    // Si la dernière ligne n'est pas pleine (n < EMOJIS_PER_ROW), on insère
                    // des spacers invisibles de même taille pour préserver l'alignement avec
                    // `SpaceBetween` au lieu de centrer la ligne courte.
                    repeat(EMOJIS_PER_ROW - rowEmojis.size) {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Bouton "+ Autre emoji" — large bandeau bien visible (vs ancien rond 44 dp gris
            // discret en bout de ligne qu'on ne remarquait pas).
            FilledTonalButton(
                onClick = {
                    onOpenCustom()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddReaction,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = cs.onSecondaryContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.reaction_picker_other))
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private const val EMOJIS_PER_ROW = 6

/**
 * v1.3.0 — palette de 24 emojis quick-pick (4 lignes × 6 colonnes). Choisis pour couvrir
 * un éventail large de réactions courantes en messagerie : affection, accord/désaccord,
 * rire, surprise, tristesse, colère, gratitude, célébration. Pour un emoji hors liste,
 * l'utilisateur clique sur "Autre emoji" et ouvre son clavier emoji système.
 *
 * Ordre voulu : du plus utilisé au moins utilisé, regroupé par thématique pour scan rapide.
 */
private val QUICK_PICK_EMOJIS = listOf(
    "❤️", "👍", "👎", "😂", "🤣", "😍",
    "🥰", "😘", "😊", "😉", "😎", "🤔",
    "😮", "😱", "😢", "😭", "😡", "🤯",
    "🔥", "💯", "🎉", "👏", "🙏", "⏰",
)
