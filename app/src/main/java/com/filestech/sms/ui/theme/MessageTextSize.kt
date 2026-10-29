package com.filestech.sms.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.filestech.sms.domain.settings.TextScale

/*
 * 2026-10-29 — TAILLE DU TEXTE DES MESSAGES (Réglages → Apparence), demandée par un utilisateur
 * malvoyant. Le réglage `Appearance.textScale` existait depuis la v1.2.0, persisté et restauré,
 * mais rien ne l'affichait ni ne l'appliquait.
 *
 * # Un seul mécanisme, quatre périmètres
 *
 * [MessageTextSize] enveloppe : les éléments du fil (`ThreadMessageItem` : bulles, séparateurs de
 * date, citations), le champ de saisie, la colonne de texte de `ConversationRow` et celle de
 * `MessageSearchRow`. Barres d'outils, boutons et écrans de réglage ne changent pas.
 *
 * Il pose un `MaterialTheme` IMBRIQUÉ dont la typographie est multipliée par le coefficient du
 * cran. Tout texte qui lit `MaterialTheme.typography` ou `LocalTextStyle` dans le périmètre
 * grandit, y compris ceux qu'on y ajoutera plus tard : rien à retenir texte par texte. Une taille
 * écrite en dur (`fontSize = 14.sp`, l'émoji d'une réaction) ne bouge pas, à dessein.
 *
 * Le coefficient S'AJOUTE à la taille de police d'Android : les `sp` restent des `sp`, convertis
 * par la densité du système, courbe non linéaire d'Android 14 comprise.
 *
 * # Écarté : réécrire `LocalDensity`
 *
 * Plus court, mais une `Density(density, fontScale)` est LINÉAIRE : elle aurait remplacé la courbe
 * d'Android 14 et changé le rendu de chaque texte du périmètre, taille par défaut comprise.
 *
 * # Neutralité
 *
 * Au cran [TextScale.MEDIUM] (le défaut, coefficient 1), [MessageTextSize] appelle son contenu tel
 * quel : aucun thème imbriqué, aucun local réécrit — le rendu d'avant ce réglage.
 *
 * ⚠️ Un conteneur de taille FIXE qui porte du texte casse sous ce coefficient (texte coupé) : ne
 * pas envelopper l'avatar (initiales dans un cercle fixe), dimensionner une pastille en `heightIn`.
 *
 * # Menus et dialogues ouverts depuis le périmètre
 *
 * Un menu ou un dialogue composé DANS le périmètre hérite de la taille : le menu ⋮ d'une bulle,
 * l'explication anti-hameçonnage. C'est voulu — ils servent le même lecteur, et un menu resté
 * petit sous un message agrandi serait le seul texte illisible de l'écran. Conséquence : tout
 * dialogue composé dans le périmètre doit DÉFILER (`SmishingExplainDialog` le fait).
 */

/**
 * Coefficient appliqué au texte des messages pour chaque cran. Croissant dans l'ordre de
 * déclaration de [TextScale] ; [TextScale.MEDIUM] vaut exactement 1 (cf. la neutralité ci-dessus).
 */
internal fun TextScale.factor(): Float = when (this) {
    TextScale.XS -> 0.85f
    TextScale.S -> 0.92f
    TextScale.MEDIUM -> 1f
    TextScale.L -> 1.2f
    TextScale.XL -> 1.45f
    TextScale.XXL -> 1.75f
}

/** Posé par [SmsTechTheme] depuis le réglage ; 1 hors thème (aperçus, tests). */
internal val LocalMessageTextFactor = staticCompositionLocalOf { 1f }

/** Agrandit (ou réduit) le texte de [content] selon le réglage « Taille du texte des messages ». */
@Composable
fun MessageTextSize(content: @Composable () -> Unit) {
    val factor = LocalMessageTextFactor.current
    if (factor == 1f) {
        content()
        return
    }
    val typography = MaterialTheme.typography
    val textStyle = LocalTextStyle.current
    val scaledTypography = remember(typography, factor) { typography.scaledBy(factor) }
    val scaledTextStyle = remember(textStyle, factor) { textStyle.scaledBy(factor) }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = scaledTypography,
    ) {
        CompositionLocalProvider(
            // `MaterialTheme` repose `LocalTextStyle` sur `bodyLarge` : on rend celui du parent.
            LocalTextStyle provides scaledTextStyle,
            // Déjà appliqué : un périmètre imbriqué dans celui-ci ne multiplie pas une seconde fois.
            LocalMessageTextFactor provides 1f,
            content = content,
        )
    }
}

internal fun Typography.scaledBy(factor: Float): Typography = if (factor == 1f) {
    this
} else {
    copy(
        displayLarge = displayLarge.scaledBy(factor),
        displayMedium = displayMedium.scaledBy(factor),
        displaySmall = displaySmall.scaledBy(factor),
        headlineLarge = headlineLarge.scaledBy(factor),
        headlineMedium = headlineMedium.scaledBy(factor),
        headlineSmall = headlineSmall.scaledBy(factor),
        titleLarge = titleLarge.scaledBy(factor),
        titleMedium = titleMedium.scaledBy(factor),
        titleSmall = titleSmall.scaledBy(factor),
        bodyLarge = bodyLarge.scaledBy(factor),
        bodyMedium = bodyMedium.scaledBy(factor),
        bodySmall = bodySmall.scaledBy(factor),
        labelLarge = labelLarge.scaledBy(factor),
        labelMedium = labelMedium.scaledBy(factor),
        labelSmall = labelSmall.scaledBy(factor),
    )
}

internal fun TextStyle.scaledBy(factor: Float): TextStyle = if (factor == 1f) {
    this
} else {
    copy(fontSize = fontSize.scaledBy(factor), lineHeight = lineHeight.scaledBy(factor))
}

/** Seuls les `sp` sont multipliés : un `em` suit déjà la police agrandie, `Unspecified` le reste. */
private fun TextUnit.scaledBy(factor: Float): TextUnit = if (isSp) this * factor else this
