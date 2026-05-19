package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R

/**
 * v1.3.0 / v1.5.0 — Bottom sheet pour réagir à un message. Grille de 24 emojis populaires
 * (4 lignes × 6 colonnes) + bouton "Autre emoji" qui délègue à [EmojiCustomDialog] +
 * bouton "Valider" en pied de sheet.
 *
 * **v1.5.0 — multi-emoji** : tap sur un emoji de la grille le **toggle** dans la sélection
 * courante (mis en évidence par un contour primary + fond). On peut sélectionner jusqu'à
 * [MAX_REACTION_EMOJIS] emojis dans une même réaction. "Valider" pose la chaîne combinée
 * (ex. "❤️👍🎉") via [onPicked] et ferme le sheet. Tap sur un emoji DÉJÀ posé sur le
 * message d'origine le pré-sélectionne automatiquement (déduit du paramètre
 * [currentReaction]) pour permettre un ajout incrémental sans perdre le contexte.
 *
 * Le bouton "Autre emoji" reste un raccourci direct vers le clavier système (cas d'usage
 * "je veux un emoji rare") — il commit immédiatement la sélection multi en plus de
 * l'emoji custom au passage.
 *
 * Touche `back` / drag down → [onDismiss] sans poser de réaction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiReactionPickerSheet(
    onPicked: (String) -> Unit,
    onOpenCustom: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * v1.5.0 — réaction actuellement posée sur le message (ou `null` si aucune). Lorsque
     * non-null, ses emojis (un ou plusieurs) sont pré-sélectionnés dans la grille pour
     * que l'utilisateur puisse ajouter / retirer un emoji depuis l'existant sans
     * repartir de zéro.
     */
    currentReaction: String? = null,
) {
    // v1.3.0 audit Q7 — `skipPartiallyExpanded = true` aligne ce sheet sur le pattern projet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme

    // v1.5.0 — état local de la sélection multi. Initialisée avec la réaction courante
    // (un emoji ou une chaîne d'emojis déjà posée) découpée en clusters de graphèmes pour
    // que les sélections multi-codepoint (ZWJ family, drapeau) restent atomiques.
    var selected by remember {
        mutableStateOf(currentReaction?.splitGraphemeClusters().orEmpty().toList())
    }
    val atCapacity = selected.size >= MAX_REACTION_EMOJIS

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            QUICK_PICK_EMOJIS.chunked(EMOJIS_PER_ROW).forEach { rowEmojis ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowEmojis.forEach { emoji ->
                        val isSelected = emoji in selected
                        // Disabled visual : when at capacity, non-selected emojis become
                        // dimmer + non-clickable so the user cannot exceed the cap.
                        val canTap = isSelected || !atCapacity
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .then(
                                    if (isSelected) {
                                        Modifier
                                            .background(cs.primaryContainer)
                                            .border(2.dp, cs.primary, CircleShape)
                                    } else Modifier
                                )
                                .clickable(enabled = canTap) {
                                    selected = if (isSelected) {
                                        selected - emoji
                                    } else {
                                        selected + emoji
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 28.sp,
                                modifier = if (canTap || isSelected) Modifier
                                else Modifier.then(Modifier),
                            )
                        }
                    }
                    repeat(EMOJIS_PER_ROW - rowEmojis.size) {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // "+ Autre emoji" — délègue au clavier emoji système. Tap commit immédiatement
            // la sélection courante via [onOpenCustom] qui posera l'emoji choisi via le
            // dialog custom (ThreadScreen merge la sélection multi + l'emoji custom dans
            // un onglet futur — pour v1.5.0 MVP, "Autre" abandonne la sélection multi
            // courante et part sur l'emoji custom seul).
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

            Spacer(Modifier.height(8.dp))

            // v1.5.0 — bouton "Valider" : commit la sélection multi. Désactivé tant
            // qu'aucun emoji n'est sélectionné (sinon click no-op étrange).
            Button(
                onClick = {
                    onPicked(selected.joinToString(separator = ""))
                    onDismiss()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (selected.isEmpty()) {
                        stringResource(R.string.reaction_picker_validate_empty)
                    } else {
                        stringResource(
                            R.string.reaction_picker_validate_count,
                            selected.joinToString(separator = ""),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private const val EMOJIS_PER_ROW = 6

/**
 * v1.5.0 — cap max d'emojis dans une seule réaction. 3 reste très lisible sur le
 * badge pilule de [EmojiReactionBadge] sans déformer la bulle, et couvre les cas
 * d'usage habituels (❤️ 👍 🎉 typique d'iMessage). Au-delà, le picker grise les
 * emojis non-sélectionnés pour bloquer les nouveaux taps.
 */
const val MAX_REACTION_EMOJIS: Int = 3

/**
 * v1.5.0 — découpe une chaîne en clusters de graphèmes Unicode (ZWJ family, drapeau,
 * emoji + variation selector restent ATOMIQUES). Implémentation maison naïve mais
 * suffisante pour les emojis courants : on regroupe surrogate pairs + ZWJ + VS-16
 * (U+FE0F). Pas de dépendance ICU.
 */
internal fun String.splitGraphemeClusters(): MutableList<String> {
    val out = mutableListOf<String>()
    if (isEmpty()) return out
    var i = 0
    while (i < length) {
        val sb = StringBuilder()
        // Base char : surrogate pair (BMP supplementary) ou simple BMP char.
        if (i < length - 1 && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) {
            sb.append(this[i]); sb.append(this[i + 1]); i += 2
        } else {
            sb.append(this[i]); i++
        }
        // Glob ZWJ continuations + variation selectors + further surrogate pairs.
        while (i < length) {
            val c = this[i]
            val code = c.code
            val isZwj = code == 0x200D
            val isVs = code in 0xFE00..0xFE0F
            val isSkinTone = i < length - 1 && c.isHighSurrogate() &&
                this[i + 1].isLowSurrogate() &&
                ((code - 0xD800) * 0x400 + (this[i + 1].code - 0xDC00) + 0x10000) in 0x1F3FB..0x1F3FF
            if (isZwj || isVs) {
                sb.append(c); i++
                // ZWJ is followed by another base char — fold it into the same cluster.
                if (isZwj && i < length) {
                    if (i < length - 1 && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) {
                        sb.append(this[i]); sb.append(this[i + 1]); i += 2
                    } else {
                        sb.append(this[i]); i++
                    }
                }
            } else if (isSkinTone) {
                sb.append(this[i]); sb.append(this[i + 1]); i += 2
            } else {
                break
            }
        }
        out += sb.toString()
    }
    return out
}

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
