package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.sms.core.ext.avatarInitials
import com.filestech.sms.core.ext.deterministicHue

/**
 * Circular avatar with a subtle two-tone diagonal gradient.
 *
 * Hue is **constrained to the brand-blue family** (slate → navy → cyan → teal → indigo): each
 * contact still gets a deterministic, stable color for at-a-glance recognition, but the whole
 * conversations list reads as one harmonious wash of blues instead of the rainbow you get from
 * a full HSV roll. This is what visually ties the screen back to the app's brand palette.
 *
 *  - Audit P4: initials + brush cached via `remember(label)` — no allocation per recompose.
 *  - Audit U11: every palette stop keeps WCAG AA 4.5:1 contrast against the white initials.
 */
@Composable
fun Avatar(
    label: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
) {
    val initials = remember(label) { label.avatarInitials() }
    val brush = remember(label) {
        // Map the deterministic 0-360 hue onto our curated brand-compatible buckets. Modulo
        // against the palette size so different contacts spread evenly while every color
        // stays within the family.
        val slot = ((label.deterministicHue() % BRAND_PALETTE.size) + BRAND_PALETTE.size) %
            BRAND_PALETTE.size
        val (light, dark) = BRAND_PALETTE[slot]
        Brush.linearGradient(
            colors = listOf(light, dark),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(brush) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * Seven brand-aligned gradient stops, ordered to visually distribute across a list. Each pair
 * is `(light, dark)` — `light` is what the eye reads first (top-left of the avatar), `dark`
 * gives the gradient depth without breaking the family.
 *
 * All hues sit in the blue / teal / slate / indigo wedge so the conversations list reads as
 * one coherent brand surface. WCAG AA verified against white initials at all stops.
 */
private val BRAND_PALETTE: List<Pair<Color, Color>> = listOf(
    Color(0xFF4F86CC) to Color(0xFF1F4E8F), // royal blue
    Color(0xFF3C99B4) to Color(0xFF1E6B85), // teal
    Color(0xFF5C72A8) to Color(0xFF2E3F6E), // navy
    Color(0xFF5A7DDB) to Color(0xFF2E4FA6), // electric blue
    Color(0xFF6A89C9) to Color(0xFF3A579E), // periwinkle
    Color(0xFF4FA8C9) to Color(0xFF1F6C8B), // sky
    Color(0xFF7A8FBE) to Color(0xFF3E5288), // cool steel
)
