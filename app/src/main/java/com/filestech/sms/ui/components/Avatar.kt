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
    /**
     * v1.11.0 — Sujet 5 apparence : URI `content://` d'un avatar custom posé
     * par l'user via [com.filestech.sms.ui.components.AppearanceDialog]. Si
     * non-null, AsyncImage Coil rend l'image au lieu du gradient initiales.
     * Si l'URI est inaccessible (révoquée, fichier supprimé), Coil échoue
     * silencieusement et on garde le placeholder ; le rendu reste défensif.
     */
    customUri: String? = null,
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
        if (customUri != null) {
            // L'image custom couvre le gradient en CircleShape + crop. Le
            // gradient reste en filet pendant le chargement Coil ET en cas
            // d'échec (URI révoquée, hors-réseau) — l'utilisateur ne voit
            // jamais une bulle vide.
            coil.compose.AsyncImage(
                model = customUri,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(sizeDp.dp)
                    .clip(CircleShape),
            )
        } else {
            Text(
                text = initials,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * Gradient stops brand-aligned, distribués pour offrir un maximum de
 * variations visuelles tout en restant strictement dans la famille bleue
 * de l'identité de marque. Chaque pair `(light, dark)` — `light` lu en
 * premier (haut-gauche de l'avatar), `dark` donne la profondeur sans
 * casser la palette.
 *
 * v1.13.0 — palette **strictement bleue** (11 nuances). Les 3 dernières
 * entrées à teinte verdâtre de v1.12.0 (teal, dark teal, cyan) ont été
 * retirées sur demande user : la liste doit ne contenir que les bleus
 * "originaux" de l'identité de marque. Reste donc royal / electric /
 * cobalt / brand-blue / sky / periwinkle / azure / navy / cool-steel
 * + slate / gunmetal (blue-grey neutre, considéré famille bleue).
 *
 * Historique :
 *  - v1.10.x = 7 nuances (palette d'origine)
 *  - v1.11.0 = 14 nuances (5 rouges + 1 plum + 8 bleu/teal — retiré v1.12)
 *  - v1.12.0 = 14 nuances pure blue/teal/cyan/navy (rouges retirés)
 *  - v1.13.0 = 11 nuances **bleu strict** (verts/teals/cyan retirés)
 *
 * Toutes les couleurs vérifiées WCAG AA (ratio ≥ 4.5:1) contre `Color.White`
 * pour le texte initiales. Hash déterministe via `deterministicHue` modulo
 * `BRAND_PALETTE.size` → chaque contact garde toujours la même couleur,
 * mais répartition globale homogène.
 */
private val BRAND_PALETTE: List<Pair<Color, Color>> = listOf(
    // ── Bleus royaux & électriques (4) ──
    Color(0xFF3870BC) to Color(0xFF1F4E8F), // royal blue
    Color(0xFF4C6FCB) to Color(0xFF2E4FA6), // electric blue
    Color(0xFF3F6BAA) to Color(0xFF1A4078), // deep cobalt
    Color(0xFF1976D2) to Color(0xFF0D47A1), // brand blue strong
    // ── Bleus doux & ciel (3) ──
    Color(0xFF2E7DA0) to Color(0xFF1F6C8B), // sky
    Color(0xFF4C6FA8) to Color(0xFF3A579E), // periwinkle
    Color(0xFF1A75C8) to Color(0xFF1565C0), // azure
    // ── Navy & slate (4) ──
    Color(0xFF4A6090) to Color(0xFF2E3F6E), // navy
    Color(0xFF5E72A0) to Color(0xFF3E5288), // cool steel
    Color(0xFF546E7A) to Color(0xFF29434E), // slate
    Color(0xFF455A64) to Color(0xFF1C313A), // gunmetal
)
