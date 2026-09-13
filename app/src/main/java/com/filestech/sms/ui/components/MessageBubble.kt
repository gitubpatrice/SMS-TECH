package com.filestech.sms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.theme.BrandBlue
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * Position of a message inside a burst (consecutive messages from the same sender, < 60 s apart).
 * Used to draw distinctive bubble shapes — only [Solo] and [First] carry the classic "tail"
 * corner; middle / last bubbles flatten the joining edge so the burst reads as a single block.
 *
 * The caller (ThreadScreen) computes this; the bubble stays stateless.
 */
enum class BurstPosition { Solo, First, Middle, Last }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    showTimestamp: Boolean,
    burstPosition: BurstPosition = BurstPosition.Solo,
    onTap: () -> Unit = {},
    /** Emitted when the user picks "Supprimer" in the bubble's overflow menu. */
    onDelete: () -> Unit = {},
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F3) — copy the bubble's text body to the clipboard, from the overflow menu.
     * v1.28.6 — l'appui long ne copie PLUS le message entier (doublon de cette entrée) : il
     * ouvre la sélection libre, cf. [onSelectText]. `null` for bubbles without a text payload.
     */
    onCopy: (() -> Unit)? = null,
    /**
     * v1.28.6 — sélection libre d'un extrait du corps ([MessageTextSelectionDialog]). Déclenchée
     * par l'appui long sur la bulle ET par l'entrée « Sélectionner le texte » du menu ⋮. Même
     * disponibilité que [onCopy].
     */
    onSelectText: (() -> Unit)? = null,
    /** v1.3.11 (F5) — forward the bubble's text body to another conversation. */
    onForward: (() -> Unit)? = null,
    /** v1.26.1 (audit F2) — bascule « favori » ; l'état est lu sur [message]. */
    onToggleStar: (() -> Unit)? = null,
    /**
     * v1.3.11 (F4) — invoked when the user taps a phone number rendered inside the
     * bubble body. Forwarded to [MessageTextWithLinks]; the host (`ThreadScreen`)
     * surfaces a [PhoneActionsDialog] in response.
     */
    onPhoneClick: (String) -> Unit = {},
    onRemoveReaction: () -> Unit = {},
    repliedToPreview: ReplyQuotePreview? = null,
    /**
     * v1.3.3 #7 — étiquette d'expéditeur intégrée dans la bulle ("Vous" pour les
     * messages sortants, nom du contact pour les messages reçus). `null` = pas
     * d'étiquette (cas des bulles "Middle" et "Last" dans un burst). Le caller
     * ([com.filestech.sms.ui.screens.thread.ThreadScreen]) ne passe l'étiquette
     * que sur la 1ʳᵉ bulle d'un burst pour ne pas surcharger visuellement.
     */
    senderLabel: String? = null,
    /**
     * v1.11.0 — Sujet 5 apparence : couleur ARGB personnalisée pour la bulle
     * SORTANTE de cette conversation. `null` = utilise [com.filestech.sms.ui
     * .theme.BrandBlue] fixe (bleu de marque foncé). Sélectionnée par l'user dans `AppearanceDialog`
     * parmi [com.filestech.sms.ui.theme.BubbleColorPalette.OPTIONS]
     * (palette WCAG-safe). Aucun effet sur les bulles entrantes.
     */
    customBubbleColorArgb: Int? = null,
    /**
     * v1.11.0 — Sujet 3 anti-smishing : raisons détectées sur le corps de ce
     * message. Si non-vide ET message entrant, affiche un
     * [com.filestech.sms.ui.components.SmishingBanner] rouge sous la bulle.
     * Calculé en amont (ThreadScreen via [com.filestech.sms.domain.smishing
     * .SmishingDetector]) — MessageBubble ne fait que le rendu.
     */
    smishingReasons: List<com.filestech.sms.domain.smishing.SmishingReason> = emptyList(),
) {
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme

    // Outgoing bubbles use a subtle vertical gradient on top of the primary color — gives the
    // bubble depth without committing to a hard shadow or a custom drawable. Incoming bubbles
    // stay flat surfaceContainerHigh so reading them remains restful.
    // v1.11.0 — Sujet 5 apparence : couleur de bulle sortante personnalisée par
    // conversation. v1.19.0 — le défaut (`null`) rend désormais [BrandBlue] FIXE
    // au lieu de `cs.primary` : sous Material You / One UI, `cs.primary` suit le
    // fond d'écran et virait au bleu clair. La bulle de marque doit rester le
    // bleu foncé stable (cohérent avec la bulle entrante, elle aussi fixe).
    val outgoingBaseColor = customBubbleColorArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: BrandBlue
    val outgoingBrush = Brush.linearGradient(
        colors = listOf(outgoingBaseColor, outgoingBaseColor.copy(alpha = 0.88f)),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )
    // Texte blanc forcé sur toute bulle sortante : BrandBlue et chaque couleur de
    // [BubbleColorPalette] sont calibrées WCAG AA ≥ 4.5:1 contre le blanc. On ne
    // dépend plus de `cs.onPrimary` (qui pouvait dériver avec le scheme dynamique).
    val textColor = if (isOut) androidx.compose.ui.graphics.Color.White else cs.onSurface

    val shape = bubbleShape(isOut, burstPosition)

    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = 12.dp,
            vertical = bubbleVerticalSpacing(burstPosition),
        ),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Outgoing: trigger sits to the LEFT of the bubble. Menu carries Reply / Translate /
        // Delete (in that order; Delete in red at the bottom of the list).
        if (isOut) {
            BubbleMenuTrigger(
                onCopy = onCopy,
                onSelectText = onSelectText,
                onForward = onForward,
                onReply = onReply,
                onTranslate = onTranslate,
                onReact = onReact,
                starred = message.starred,
                onToggleStar = onToggleStar,
                onDelete = onDelete,
            )
        }
        // Stack the optional reply quote ABOVE the bubble — same horizontal alignment so it
        // visually anchors to the same side as the bubble. Inline column to keep the row
        // layout intact.
        // v1.28.7 — LE BOUTON ⋮ EST MESURÉ AVANT LA BULLE. Un `Row` mesure d'abord ses enfants
        // SANS poids, dans l'ordre : la bulle, plafonnée à une largeur FIXE de 320 dp, prenait
        // tout ce qu'elle voulait, et le bouton de 40 dp recevait le reste. Une bulle pleine
        // exige 384 dp avec les marges ; mesuré sur émulateur, le bouton tombait à 16 dp sur un
        // écran de 360 dp avec un long message, et à 0 dp sur 320 dp — le menu (copier,
        // transférer, répondre, favori, supprimer) devenait inatteignable. Trouvé par un test
        // de la v1.28.6 sur l'émulateur étroit de la CI.
        //
        // `weight(1f, fill = false)` fait mesurer la colonne APRÈS le bouton, dans l'espace qui
        // reste, sans l'étirer : une bulle courte garde sa largeur naturelle, et l'alignement
        // d'entrée ou de sortie est inchangé.
        //
        // Seules les bulles ENTRANTES étaient écrasées : pour une sortante, le bouton précède la
        // bulle dans le `Row`, il était donc déjà mesuré en premier. Contrôle négatif mesuré : sans
        // ce poids, le test entrant tombe et le test sortant reste vert.
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // v1.3.0 — wrap dans BubbleReactionOverlay : no-op si pas de réaction (early
            // return interne), sinon affiche le cercle emoji en chevauchement.
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                onRemoveReaction = onRemoveReaction,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 32.dp, max = 320.dp)
                        .clip(shape)
                        .then(
                            if (isOut) Modifier.drawBehind { drawRect(outgoingBrush) }
                            else Modifier.background(com.filestech.sms.ui.theme.bubbleIncomingColor(cs)),
                        )
                        // v1.3.11 (F3) — long-press on the bubble. v1.28.6 — it no longer copies
                        // the whole body (a duplicate of the ⋮ « Copier » entry) but opens the
                        // free text selection ([onSelectText]). Falls back to a plain `clickable`
                        // when unavailable so tap-to-retry on FAILED rows stays untouched.
                        .then(
                            if (onSelectText != null) {
                                Modifier.combinedClickable(
                                    onClick = onTap,
                                    onLongClick = onSelectText,
                                )
                            } else {
                                Modifier.clickable(onClick = onTap)
                            },
                        )
                        .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
                ) {
                    // v1.3.2 — URLs détectées et rendues cliquables (ouvre le navigateur
                    // système via UriHandler). Fallback texte brut si pas d'URL.
                    //
                    // Y6 audit : sur message FAILED, le tap sur la bulle entière déclenche
                    // un retry ([onTap] ligne ci-dessus). Linkifier les URLs créerait un
                    // conflit de gestes : un tap sur une URL au milieu du body pourrait
                    // soit ouvrir le browser, soit déclencher le retry, voire les deux
                    // selon le device. On désactive la linkification dans ce cas — l'UX
                    // "tap pour réessayer" reste sans ambiguïté. L'URL reste visible en
                    // texte brut ; l'utilisateur peut copier-coller ou retry puis cliquer.
                    Column {
                        if (!senderLabel.isNullOrBlank()) {
                            // v1.3.3 #7 — étiquette en gras + couleur de contraste ; padding-bottom
                            // 2dp pour aérer sans agrandir trop la bulle.
                            Text(
                                text = senderLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (message.status == com.filestech.sms.domain.model.Message.Status.FAILED) {
                            Text(
                                text = message.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                            )
                        } else if (message.estUnMmsSansContenu()) {
                            // v1.28.4 — un MMS restauré d'une sauvegarde n'a plus ses pièces
                            // jointes : la bulle le DIT, plutôt que de rester vide.
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    com.filestech.sms.R.string.bubble_mms_parts_missing,
                                ),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                ),
                                color = textColor,
                            )
                        } else {
                            MessageTextWithLinks(
                                text = message.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                onPhoneClick = onPhoneClick,
                            )
                        }
                    }
                }
            }
            // v1.7.1 — inline TranslationBlock removed (was rendering an in-app
            // translation result from ML Kit). Translation now happens out-of-process
            // via ACTION_PROCESS_TEXT — the user's chosen translation app shows the
            // result in its own UI, so no in-bubble overlay is needed anymore.
        }
        // Incoming: trigger sits to the RIGHT of the bubble.
        if (!isOut) {
            BubbleMenuTrigger(
                onCopy = onCopy,
                onSelectText = onSelectText,
                onForward = onForward,
                onReply = onReply,
                onTranslate = onTranslate,
                onReact = onReact,
                starred = message.starred,
                onToggleStar = onToggleStar,
                onDelete = onDelete,
            )
        }
    }
    // v1.11.0 — Sujet 3 anti-smishing : bandeau rouge sous la bulle entrante
    // si le SmishingDetector a flaggé le contenu. Tap → dialog "Pourquoi".
    // N'affecte JAMAIS les bulles sortantes (l'user fait confiance à ce
    // qu'il écrit). Full-width pour maximiser la visibilité.
    if (!isOut && smishingReasons.isNotEmpty()) {
        SmishingBanner(reasons = smishingReasons)
    }
    if (showTimestamp || message.status == Message.Status.FAILED) {
        val label = when (message.status) {
            Message.Status.FAILED -> stringResource(R.string.thread_status_failed)
            Message.Status.DELIVERED -> stringResource(R.string.thread_status_delivered)
            Message.Status.SENT -> stringResource(R.string.thread_status_sent)
            Message.Status.PENDING -> stringResource(R.string.thread_status_pending)
            else -> null
        }
        val formatters = rememberChatFormatters()
        // Audit P-Q7 (v1.2.0): cache the formatted time so we don't re-allocate a Date + run
        // the SimpleDateFormat at every bubble recomposition. Same pattern as AudioMessageBubble.
        val time = androidx.compose.runtime.remember(message.date) {
            formatters.time.format(Date(message.date))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
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
}

/**
 * Builds the rounded-corner shape that matches the bubble's position in a burst. Solo and first
 * bubbles keep the "tail" corner (4 dp) on the speaker side; mid / last bubbles flatten the
 * joining edge so the burst reads as one connected stack.
 */
private fun bubbleShape(isOut: Boolean, position: BurstPosition): RoundedCornerShape {
    val tail = 4.dp
    val full = 20.dp
    return when (position) {
        BurstPosition.Solo -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.First -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Middle -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Last -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = full,
            bottomEnd = full,
        )
    }
}

/**
 * v1.3.3 #7 — espacement vertical entre bulles.
 *  - 2 dp à l'intérieur d'un burst (`Middle`) pour serrer les messages consécutifs du
 *    même expéditeur (lecture rapide).
 *  - 8 dp aux frontières (`Solo`, `First`, `Last`) pour séparer visuellement les bursts.
 *  - Avant v1.3.3 : 3 dp partout (trop serré aux frontières) ; user-feedback du 2026-05-16.
 */
private fun bubbleVerticalSpacing(position: BurstPosition) = when (position) {
    BurstPosition.Solo, BurstPosition.First, BurstPosition.Last -> 8.dp
    BurstPosition.Middle -> 2.dp
}

/** v1.28.4 — un MMS sans texte ni pièce jointe : le cas d'une restauration, jamais d'une réception. */
private fun com.filestech.sms.domain.model.Message.estUnMmsSansContenu(): Boolean =
    type == com.filestech.sms.domain.model.Message.Type.MMS && body.isBlank() && attachments.isEmpty()
