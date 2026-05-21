package com.filestech.sms.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.filestech.sms.ui.theme.BrandDanger

/**
 * v1.9.0 — Composables Snackbar partagés à l'échelle de l'app.
 *
 * **Pourquoi un fichier dédié** : avant v1.9.0, ce pattern vivait en
 * `private` dans `ThreadScreen.kt` et chaque autre écran (Settings,
 * Backup, SafetyCallSetup…) utilisait le `SnackbarHost` Material 3 par
 * défaut, qui rend en `inverseSurface` = `BrandBlue` (slate-blue marque)
 * — donc les snackbars d'erreur de ces écrans ressemblaient à des
 * confirmations positives. Demande user 2026-05-21 : aligner toute l'app
 * sur le pattern bleu pour les confirmations + **rouge marque
 * [BrandDanger] pour les suppressions / erreurs**.
 *
 * **Pattern** :
 *  - call site qui veut une confirmation positive : `hostState.showSnackbar(msg)`
 *    → rendu bleu marque (default).
 *  - call site qui veut une alerte rouge : `hostState.showError(msg)` ou
 *    `hostState.showSnackbar(SmsTechSnackbarVisuals(msg, isError = true))`.
 *
 * Pour que le rouge soit effectivement appliqué, l'écran DOIT utiliser
 * [SmsTechSnackbarHost] dans son `Scaffold(snackbarHost = …)` au lieu du
 * `SnackbarHost(hostState)` standard. Si un écran continue à utiliser
 * `SnackbarHost`, ses erreurs ressortiront en bleu (sans crasher).
 */
data class SmsTechSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration =
        if (isError) SnackbarDuration.Long else SnackbarDuration.Short,
    override val withDismissAction: Boolean = false,
) : SnackbarVisuals

/**
 * Helper pour les call sites qui veulent signaler une erreur ou une
 * suppression sans reconstruire [SmsTechSnackbarVisuals] à la main.
 * Préfère cette fonction à `showSnackbar(message)` pour tout message
 * destructif / d'échec — sinon le snackbar s'affichera en bleu
 * (confirmation) au lieu de rouge (alerte).
 */
suspend fun SnackbarHostState.showError(message: String) {
    showSnackbar(SmsTechSnackbarVisuals(message = message, isError = true))
}

/**
 * Variante isError pour les messages de confirmation de suppression
 * (ex: "Numéro supprimé", "Conversation supprimée"). Sémantiquement
 * équivalent à [showError] — l'identifier explicite aide à la lecture
 * du code et permet à un futur refacto de différencier les deux si
 * besoin (ex: suppression = orange, erreur = rouge fort).
 */
suspend fun SnackbarHostState.showDestructive(message: String) {
    showSnackbar(SmsTechSnackbarVisuals(message = message, isError = true))
}

/**
 * [SnackbarHost] custom qui rend les [SmsTechSnackbarVisuals] avec :
 *  - `isError = true` → fond [BrandDanger] (`#C62828`, le rouge fort
 *    cohérent avec les boutons destructifs partout dans l'app), texte
 *    blanc, action blanche. Contraste 5.5:1 → WCAG AA ✓.
 *  - `isError = false` ou non-[SmsTechSnackbarVisuals] (legacy
 *    `showSnackbar(message)` direct) → fond `inverseSurface`
 *    (`BrandBlue` slate via le thème) + texte/action `inverseOnSurface`
 *    /`inversePrimary`.
 *
 * À substituer dans tous les `Scaffold` à la place du `SnackbarHost(
 * hostState)` standard.
 */
@Composable
fun SmsTechSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState = hostState) { data ->
        val isError = (data.visuals as? SmsTechSnackbarVisuals)?.isError == true
        Snackbar(
            snackbarData = data,
            containerColor = if (isError) BrandDanger
                else MaterialTheme.colorScheme.inverseSurface,
            contentColor = if (isError) Color.White
                else MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = if (isError) Color.White
                else MaterialTheme.colorScheme.inversePrimary,
        )
    }
}
