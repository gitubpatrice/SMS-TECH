package com.filestech.sms.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val BrandBlue = Color(0xFF2460AB)
internal val BrandBlueDark = Color(0xFFA9C7FF)

/**
 * Single source of truth for the "destructive intent" red used by Swipe-to-delete, panic
 * dialogs, and the system-blocklist purge confirm button. Defined once in the theme so any
 * future re-brand only touches one constant; copies of `0xFFC62828` scattered in screen files
 * are migrated to this constant during the v1.2.0 dedup pass.
 */
internal val BrandDanger = Color(0xFFC62828)

/**
 * Incoming chat-bubble background — a slate-blue ("gris bleu") that reads warmer than the
 * default `surfaceContainerHigh` and visually pairs with the outgoing brand-blue bubble. Two
 * tones so the bubble stays legible in both light and dark themes.
 *
 *  - Light theme : `#DDE5F0` (very pale slate-blue, ~5% saturated).
 *  - Dark theme  : `#37414F` (deep slate-blue, kept under `BrandBlueDark` so the outgoing
 *                   bubble still pops above the incoming one).
 */
internal val BubbleIncomingLight = Color(0xFFDDE5F0)
internal val BubbleIncomingDark = Color(0xFF37414F)

/**
 * Slate-blue palette for [Snackbar] / inverse-surface widgets. The default Material 3 inverse
 * pair is grey/near-black, which looks foreign on a brand-blue app. Both light and dark schemes
 * share the same pair: a confirmation toast always reads against this stable identity, no
 * matter the user's theme.
 */
// Snackbar palette mirrors the BrandDanger of the delete button per user request — strong
// signal toast in the same identity as the destructive dialog buttons. White text on this red
// gives ~5.5:1 contrast (WCAG AA pass for normal text).
internal val SnackbarBg = BrandDanger
internal val SnackbarOn = Color.White

private val LightPalette = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = Color(0xFF555F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF705574),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAD8FC),
    onTertiaryContainer = Color(0xFF28132E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,
    inverseSurface = SnackbarBg,
    inverseOnSurface = SnackbarOn,
)

private val DarkPalette = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF00315E),
    primaryContainer = Color(0xFF004788),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDEBCDF),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44464F),
    scrim = Color.Black,
    inverseSurface = SnackbarBg,
    inverseOnSurface = SnackbarOn,
)

/**
 * "Dark Tech" palette — developer-friendly dark theme tuned for long reading sessions.
 * Deep slate-blue background, calm sky-blue accent, success green, danger red.
 */
private val DarkTechPalette = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1F6FEB),
    onPrimaryContainer = Color(0xFFCDE3FF),
    secondary = Color(0xFF3FB950),
    onSecondary = Color(0xFF052E0E),
    secondaryContainer = Color(0xFF1A7F37),
    onSecondaryContainer = Color(0xFFCCFFD4),
    tertiary = Color(0xFFD29922),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF7D4E00),
    onTertiaryContainer = Color(0xFFFFE7B3),
    error = Color(0xFFF85149),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8E1519),
    onErrorContainer = Color(0xFFFFD8D3),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFC9D1D9),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFC9D1D9),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceTint = Color(0xFF58A6FF),
    inverseSurface = SnackbarBg,
    inverseOnSurface = SnackbarOn,
    inversePrimary = Color(0xFF1F6FEB),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    scrim = Color(0xFF010409),
    surfaceContainerLowest = Color(0xFF010409),
    surfaceContainerLow = Color(0xFF0D1117),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1F242C),
    surfaceContainerHighest = Color(0xFF262C34),
)

internal fun lightScheme(): ColorScheme = LightPalette
internal fun darkScheme(amoled: Boolean): ColorScheme =
    if (amoled) DarkPalette.copy(background = Color.Black, surface = Color.Black) else DarkPalette
internal fun darkTechScheme(): ColorScheme = DarkTechPalette

/**
 * Returns the slate-blue bubble background appropriate for the current scheme. We pick the
 * variant from the scheme's surface luminance rather than `isSystemInDarkTheme()` so it works
 * correctly under the DarkTech / Amoled paths too.
 */
internal fun bubbleIncomingColor(scheme: ColorScheme): Color {
    // Material's surface is dark in the dark schemes and light in the light scheme; we cut on
    // the rough mid-grey (~50% luminance) to pick the matching slate-blue.
    val s = scheme.surface
    val luma = 0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue
    return if (luma < 0.5f) BubbleIncomingDark else BubbleIncomingLight
}
