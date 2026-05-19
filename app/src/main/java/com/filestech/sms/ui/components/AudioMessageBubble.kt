package com.filestech.sms.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * Audio bubble for an MMS voice message. Renders a compact mini-player (play/pause + slider +
 * duration label) inside a chat bubble that honours the same in/out colouring as [MessageBubble].
 *
 * Playback is delegated to the singleton [VoicePlaybackController]: the [playback] state is
 * read from the parent so we know whether THIS bubble is the active one. Tapping play/pause for
 * a different bubble automatically stops whichever clip was playing — the controller enforces
 * "only one clip at a time".
 *
 * The bubble does NOT instantiate a MediaPlayer itself; it merely emits [onTogglePlay] and
 * [onSeekTo] callbacks. This keeps the composable testable and recomposition-friendly.
 */
@Composable
fun AudioMessageBubble(
    message: Message,
    audio: Attachment,
    /**
     * Audit P-P1-2: the playback state is passed as a **lambda** rather than the raw
     * `PlaybackState` value, so the playback controller's 5 Hz progress ticks (see
     * [VoicePlaybackController.TICK_MS]) only recompose the bubble that is actually
     * active. Without this indirection, every tick invalidated every audio bubble in
     * the thread — a 500-message thread with one playing clip was burning ~2.5 k
     * recompositions/s.
     *
     * The lambda is wrapped in `derivedStateOf` below and the value field that drives
     * the slider (`positionMs`) is only re-read while the slider is mounted in the
     * active bubble's composition.
     */
    playbackProvider: () -> VoicePlaybackController.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onDelete: () -> Unit = {},
    onReply: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F5) — forward the voice clip + sender label to another conversation. No
     * `onCopy` here: a voice MMS has no text payload to copy.
     */
    onForward: (() -> Unit)? = null,
    onRemoveReaction: () -> Unit = {},
    repliedToPreview: ReplyQuotePreview? = null,
    showTimestamp: Boolean = false,
    /** v1.3.3 #7 — étiquette d'expéditeur ; voir [MessageBubble] pour la sémantique. */
    senderLabel: String? = null,
) {
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme
    // v1.2.6 design : la bulle vocale de l'expéditeur (outgoing) est désormais sans fond,
    // juste un contour `cs.primary` pour garder la silhouette. La bulle entrante reste
    // inchangée (fill slate-blue + contrôles primary).
    val bgColor = if (isOut) cs.primary else com.filestech.sms.ui.theme.bubbleIncomingColor(cs)
    // Sans fond plein côté outgoing, on ne peut plus utiliser `onPrimary` (blanc) pour les
    // contrôles : ils deviendraient invisibles sur la surface du thème. Bascule en `primary`
    // pour les deux directions (l'incoming, sur fond slate-blue, reste lisible aussi).
    val controlColor = cs.primary
    val sliderActive = cs.primary
    val sliderInactive = cs.onSurfaceVariant.copy(alpha = 0.3f)
    val labelColor = cs.onSurfaceVariant

    // `derivedStateOf` blocks recomposition unless the *projected* slice changes — so a tick
    // that only moves `positionMs` on a non-active bubble emits the same `LocalPlayback.None`
    // and skips the recompose. The active bubble still ticks at 5 Hz, but it's the only one.
    val key = audio.localUri
    val local by androidx.compose.runtime.remember(key) {
        androidx.compose.runtime.derivedStateOf {
            val st = playbackProvider()
            if (st.activeKey != key) LocalPlayback.Inactive
            else LocalPlayback.Active(
                isPlaying = st.isPlaying,
                positionMs = st.positionMs,
                durationMs = st.durationMs,
            )
        }
    }

    val isActiveClip = local is LocalPlayback.Active
    val isPlaying = (local as? LocalPlayback.Active)?.isPlaying == true

    // The recorded clip's encoded duration (preferred) — fall back to the slow timer captured at
    // recording time if the controller hasn't bound the source yet (durationMs == 0 until prepared).
    val totalMs = when (val s = local) {
        is LocalPlayback.Active -> if (s.durationMs > 0) s.durationMs
            else audio.durationMs?.toInt() ?: 0
        LocalPlayback.Inactive -> audio.durationMs?.toInt() ?: 0
    }
    val positionMs = (local as? LocalPlayback.Active)?.positionMs?.coerceAtMost(
        totalMs.coerceAtLeast(0),
    ) ?: 0
    val sliderValue = if (totalMs <= 0) 0f else positionMs.toFloat() / totalMs

    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOut) 18.dp else 4.dp,
        bottomEnd = if (isOut) 4.dp else 18.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOut) BubbleMenuTrigger(onReply = onReply, onReact = onReact, onForward = onForward, onDelete = onDelete)
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // v1.3.0 — wrap dans BubbleReactionOverlay (no-op sans réaction).
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                onRemoveReaction = onRemoveReaction,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .clip(shape)
                        // v1.2.6 design : outgoing → border seul (allégé). Incoming → fond
                        // slate-blue. v1.4.0 : the incoming bubble also gets a thin border
                        // slightly darker than its fill so it pops off the surrounding
                        // surface (the all-flat slate-blue felt washed-out next to outgoing
                        // bubbles which already carry a rim).
                        .then(
                            if (isOut) {
                                Modifier.border(width = 1.5.dp, color = bgColor, shape = shape)
                            } else {
                                Modifier
                                    .background(bgColor)
                                    .border(
                                        width = 1.dp,
                                        color = androidx.compose.ui.graphics.lerp(
                                            bgColor,
                                            androidx.compose.ui.graphics.Color.Black,
                                            0.18f,
                                        ),
                                        shape = shape,
                                    )
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                  androidx.compose.foundation.layout.Column {
                    if (!senderLabel.isNullOrBlank()) {
                        // Z5 audit fix — utiliser une couleur à haut contraste avec le
                        // fond de la bulle (cf. MessageBubble.textColor). `labelColor`
                        // (cs.onSurfaceVariant) tombait sous WCAG AA sur fond `primary`
                        // pour les bulles outgoing.
                        val senderColor = if (isOut) cs.onPrimary else cs.onSurface
                        Text(
                            text = senderLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = senderColor,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // v1.2.6 : disque Play différencié par direction.
                        //  - outgoing : rond rempli avec **exactement la même couleur** que le contour
                        //    de la bulle (`bgColor`), pour cohérence visuelle. Icône blanche pleine.
                        //  - incoming : disque primary 15 % + icône primary (inchangé).
                        val playBg = if (isOut) bgColor else controlColor.copy(alpha = 0.15f)
                        val playTint = if (isOut) cs.onPrimary else controlColor
                        // Note : `Icons.Outlined.PlayArrow` / `Icons.Outlined.Pause` sont rendues
                        // pleines visuellement par Material (forme triangle/bâtonnets sans contour
                        // creux), donc une flèche `onPrimary` (blanche) sur fond bleu apparaît bien
                        // pleine — pas besoin de basculer vers `Filled.*`.
                        val playIcon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(playBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = playIcon,
                                    contentDescription = stringResource(
                                        if (isPlaying) R.string.voice_action_pause else R.string.voice_action_play,
                                    ),
                                    tint = playTint,
                                )
                            }
                        }
                        // v1.4.0 — decorative waveform at rest. When the clip is NOT playing,
                        // overlay deterministic vertical bars BEHIND the slider track + force
                        // the inactive track to transparent so the bars show through. During
                        // playback the standard Material 3 slider takes over so the progress
                        // animation stays clean (no flicker from bars under the moving thumb).
                        // The bar heights are seeded by [audio.id] so the same voice clip
                        // always renders the same wave silhouette across recompositions.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!isPlaying) {
                                val barHeights = androidx.compose.runtime.remember(audio.id) {
                                    val rng = kotlin.random.Random(audio.id.takeIf { it != 0L } ?: 1L)
                                    IntArray(WAVE_BAR_COUNT) { rng.nextInt(3, 14) }
                                }
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(20.dp),
                                ) {
                                    val n = barHeights.size
                                    val barWidthPx = 2.dp.toPx()
                                    val spacing = size.width / n
                                    val cy = size.height / 2f
                                    for (i in 0 until n) {
                                        val cx = spacing * i + spacing / 2f
                                        val halfH = barHeights[i].dp.toPx() / 2f
                                        drawLine(
                                            color = sliderInactive,
                                            start = androidx.compose.ui.geometry.Offset(cx, cy - halfH),
                                            end = androidx.compose.ui.geometry.Offset(cx, cy + halfH),
                                            strokeWidth = barWidthPx,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = sliderValue.coerceIn(0f, 1f),
                                onValueChange = { f ->
                                    if (totalMs > 0) onSeekTo((f * totalMs).toInt())
                                },
                                enabled = totalMs > 0,
                                colors = SliderDefaults.colors(
                                    thumbColor = sliderActive,
                                    activeTrackColor = sliderActive,
                                    inactiveTrackColor = if (isPlaying) sliderInactive
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = formatBubbleDuration(if (isPlaying && positionMs > 0) positionMs.toLong() else totalMs.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                  } // ferme Column senderLabel + Row
                }
            }
        }
        if (!isOut) BubbleMenuTrigger(onReply = onReply, onReact = onReact, onForward = onForward, onDelete = onDelete)
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
        val time = remember(message.date) { formatters.time.format(Date(message.date)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
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
 * Builds a `content://` (FileProvider) or `file://` Uri usable by [VoicePlaybackController]
 * from the persisted [Attachment.localUri]. Strings stored in the DB might be either form
 * depending on origin (recording cache file vs. MMSC-downloaded payload).
 */
fun Attachment.toPlaybackUri(): Uri {
    val raw = localUri
    return if (raw.startsWith("content://") || raw.startsWith("file://")) {
        Uri.parse(raw)
    } else {
        Uri.fromFile(java.io.File(raw))
    }
}

private fun formatBubbleDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * v1.4.0 — number of decorative waveform bars drawn behind the slider when the audio
 * clip is at rest. 28 reads as a "voice clip" silhouette in the ~280-dp bubble width
 * without crowding (≈ 10 dp / bar including spacing) and stays cheap to draw (single
 * Canvas pass, no recomposition during playback because the wave is hidden then).
 */
private const val WAVE_BAR_COUNT: Int = 28

/**
 * Per-bubble projection of [VoicePlaybackController.PlaybackState]. The bubble derives its
 * value via [androidx.compose.runtime.derivedStateOf], which suppresses recomposition unless
 * the projection actually changes — non-active bubbles always emit [Inactive] and stay frozen
 * even while the controller ticks for the active clip.
 */
private sealed interface LocalPlayback {
    data object Inactive : LocalPlayback
    data class Active(
        val isPlaying: Boolean,
        val positionMs: Int,
        val durationMs: Int,
    ) : LocalPlayback
}
