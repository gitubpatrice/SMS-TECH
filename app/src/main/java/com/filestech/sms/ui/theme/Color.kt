package com.filestech.sms.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val BrandBlue = Color(0xFF2460AB)
internal val BrandBlueDark = Color(0xFFA9C7FF)

/**
 * Bleu foncé du liseré d'accent gauche des cartes de citation ([com.filestech.sms.ui.components
 * .ReplyQuoteCard]) côté SORTANT. Volontairement plus sombre que [BrandBlue] pour trancher sur
 * le fond bleu de la bulle envoyée (qui, lui, garde sa couleur par défaut). Couleur fixe (non
 * dérivée du thème) : elle reste distinctement « bleu foncé » aussi bien sur le fond primary du
 * thème clair que sur le fond bleu clair du thème sombre.
 */
internal val ReplyStripeOutgoing = Color(0xFF0D2B54)

/**
 * Single source of truth for the "destructive intent" red used by Swipe-to-delete, panic
 * dialogs, and the system-blocklist purge confirm button. Defined once in the theme so any
 * future re-brand only touches one constant; copies of `0xFFC62828` scattered in screen files
 * are migrated to this constant during the v1.2.0 dedup pass.
 */
internal val BrandDanger = Color(0xFFC62828)

/**
 * v1.25.3 — orange du **blocage**, délibérément distinct de [BrandDanger].
 *
 * Le rouge est réservé à ce qui détruit et ne se rattrape pas : suppression d'une conversation,
 * purge, retrait du verrou. Bloquer, lui, se défait d'un tap — même famille d'avertissement, mais
 * pas la même gravité. Deux teintes, deux messages : l'utilisateur ne doit pas hésiter entre le
 * geste réversible et celui qui ne l'est pas.
 *
 * `0xFFE65100` et non un orange plus clair : le blanc posé dessus atteint 4,87:1, au-dessus du
 * seuil AA de 4,5:1 exigé pour du texte de taille normale (libellé de bouton). Les oranges plus
 * vifs tombent sous ce seuil et rendent le libellé blanc illisible.
 */
internal val BrandBlocked = Color(0xFFE65100)

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
 * Fond « gris/bleu » des cartes de groupe de l'écran Réglages — volontairement un peu plus clair
 * et plus bleuté que le `surfaceContainerHigh` neutre dérivé par Material, pour que chaque groupe
 * se détache du fond de page tout en portant l'identité bleue de la marque. Deux tons sélectionnés
 * par luminance (cf. [settingsBlockColor]) pour rester corrects sous DarkTech / Amoled.
 *
 *  - Light : `#E4EBF6` (gris-bleu pâle, plus clair que le gris neutre).
 *  - Dark  : `#2B3444` (gris-bleu ardoise, plus clair que le conteneur sombre neutre).
 */
internal val SettingsBlockLight = Color(0xFFE4EBF6)
internal val SettingsBlockDark = Color(0xFF2B3444)

/**
 * v1.27.0 — or du **favori**, appliqué à la seule étoile PLEINE (état « ce message est favori »).
 *
 * L'étoile creuse garde la couleur d'icône du menu : c'est l'état par défaut, il ne doit rien
 * signaler. Seul l'état actif porte la couleur, sur le même principe que [BrandBlocked] — la
 * teinte marque un état, elle ne décore pas.
 *
 * Deux tons sélectionnés par luminance (cf. [starColor]), parce qu'un or unique ne peut pas tenir
 * sur les deux fonds : l'or vif est illisible sur le `surfaceContainer` clair d'un menu, et l'or
 * profond disparaît sur DarkTech / Amoled.
 *
 *  - Light : `#A57C00` (or profond) — 3,12:1 sur le `surfaceContainer` clair, au-dessus du seuil
 *            AA de 3:1 applicable aux éléments non textuels (WCAG 1.4.11).
 *  - Dark  : `#FFC107` (or vif) — ~10:1 sur le conteneur sombre, marge confortable sous Amoled.
 *
 * Le libellé du menu (« Favori » / « Retirer des favoris ») porte l'information de son côté : la
 * couleur reste un renfort, jamais le seul véhicule de l'état.
 */
internal val BrandStarLight = Color(0xFFA57C00)
internal val BrandStarDark = Color(0xFFFFC107)

/**
 * Slate-blue palette for [Snackbar] / inverse-surface widgets. The default Material 3 inverse
 * pair is grey/near-black, which looks foreign on a brand-blue app. Both light and dark schemes
 * share the same pair: a confirmation toast always reads against this stable identity, no
 * matter the user's theme.
 */
// v1.3.7 — palette snackbar bascule de BrandDanger (rouge) → BrandBlue (slate-blue brand).
// Règle UX confirmée par user 2026-05-16 : rouge réservé aux destructives (suppression,
// panique, échec critique). Les snackbars de confirmation positive ("Message vocal envoyé",
// "Numéro bloqué", "PJ envoyée"…) doivent porter l'identité bleue de la marque, pas alerter
// faussement. Si un snackbar dangereux est requis ponctuellement, le call site doit poser
// `containerColor = BrandDanger` explicitement (overrides le default brand). White text sur
// BrandBlue (#2460AB) donne ~5.8:1 — WCAG AA pour texte normal et grand.
internal val SnackbarBg = BrandBlue
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

/**
 * Fond gris/bleu des cartes de l'écran Réglages, choisi selon la luminance de la surface du thème
 * (comme [bubbleIncomingColor]) pour couvrir correctement les thèmes clair, sombre, DarkTech et
 * Amoled.
 */
internal fun settingsBlockColor(scheme: ColorScheme): Color {
    val s = scheme.surface
    val luma = 0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue
    return if (luma < 0.5f) SettingsBlockDark else SettingsBlockLight
}

/**
 * Or de l'étoile « favori » active, choisi selon la luminance de la surface du thème (comme
 * [bubbleIncomingColor]) pour couvrir les thèmes clair, sombre, DarkTech et Amoled.
 */
internal fun starColor(scheme: ColorScheme): Color {
    val s = scheme.surface
    val luma = 0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue
    return if (luma < 0.5f) BrandStarDark else BrandStarLight
}
