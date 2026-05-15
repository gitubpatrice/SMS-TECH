package com.filestech.sms.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.filestech.sms.data.local.datastore.Appearance
import com.filestech.sms.data.local.datastore.ThemeMode

@Composable
fun SmsTechTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDarkTech = appearance.themeMode == ThemeMode.DARK_TECH
    val useDark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.DARK_TECH -> true
    }
    val ctx = LocalContext.current
    // Dark Tech is an opinionated, fixed palette: ignore dynamic colors and AMOLED override.
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        appearance.dynamicColors && !isDarkTech

    val baseScheme: ColorScheme = when {
        isDarkTech -> darkTechScheme()
        dynamicAvailable && useDark -> dynamicDarkColorScheme(ctx).maybeAmoled(appearance.amoledTrueBlack)
            .withBrandSnackbar()
        dynamicAvailable && !useDark -> dynamicLightColorScheme(ctx).withBrandSnackbar()
        useDark -> darkScheme(appearance.amoledTrueBlack)
        else -> lightScheme()
    }

    // The custom accent doesn't make sense over the Dark Tech palette (which is the whole point).
    val scheme = appearance.customAccentArgb
        ?.takeIf { !isDarkTech }
        ?.let { argb -> baseScheme.copy(primary = Color(argb)) }
        ?: baseScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            // `Window.statusBarColor` is deprecated on API 35+ (Android 15) — the new
            // edge-to-edge model paints the system bars transparently by default once
            // `enableEdgeToEdge()` is called on the Activity. Until we migrate the whole app
            // to edge-to-edge (separate chantier — needs Scaffold inset handling audit), we
            // keep the explicit transparent assignment so the bar stays correct on API < 35.
            // The `@Suppress` is scoped to this single line on purpose.
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

private fun ColorScheme.maybeAmoled(amoled: Boolean): ColorScheme =
    if (amoled) copy(background = Color.Black, surface = Color.Black) else this

/**
 * Forces our brand slate-blue `Snackbar` palette onto a Material You / dynamic-color scheme.
 * Without this, `dynamicDarkColorScheme` / `dynamicLightColorScheme` derive `inverseSurface`
 * from the wallpaper, which on Samsung One UI commonly resolves to near-black — turning what
 * should be a brand-coloured slate-blue toast into a "black Material 3 default" indistinguishable
 * from the OS shell. Reported as v1.2.2 UX regression.
 */
private fun ColorScheme.withBrandSnackbar(): ColorScheme =
    copy(inverseSurface = SnackbarBg, inverseOnSurface = SnackbarOn)
