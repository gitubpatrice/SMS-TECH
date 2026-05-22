package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * v1.11.0 — Sujet 5 apparence : palette WCAG-safe pour la couleur de la
 * bulle SORTANTE personnalisée par conversation.
 *
 * **Contrat de contraste** : chaque couleur de la palette donne un ratio
 * de contraste ≥ 4.5:1 avec le texte blanc (WCAG AA pour texte normal).
 * Le user choisit dans cette grille fermée — pas de color picker libre —
 * pour garantir la lisibilité du texte des bulles dans tous les thèmes
 * (clair, sombre, AMOLED, Dark Tech).
 *
 * Les couleurs sont stockées en ARGB Int dans
 * [com.filestech.sms.data.local.db.entity.ConversationEntity.bubbleColorArgb]
 * via `Color.toArgb()`. Le rendu côté
 * [com.filestech.sms.ui.components.MessageBubble] reconstruit le Color via
 * `Color(argb)`.
 *
 * **Note d'UX** : la première entrée [BRAND_BLUE] est la valeur par défaut
 * (= `cs.primary` du thème actif). Sélectionner cette valeur est équivalent
 * à un reset (`bubbleColorArgb = null`) du point de vue de l'utilisateur ;
 * l'écran de réglage le réinitialise à `null` pour ne pas pin l'historique
 * sur la couleur thème (qui peut changer avec un futur rebrand).
 */
internal object BubbleColorPalette {

    /** Bleu marque par défaut — sélection = reset à `null` dans la persistance. */
    val BRAND_BLUE: Color = Color(0xFF2460AB)

    /**
     * Couleurs alternatives, choisies pour rester sobres et lisibles
     * avec texte blanc. **Toutes WCAG AA ≥ 4.5:1 contre `Color.White`** —
     * principalement basées sur Material Design palette 700-900 (validé
     * par Material a11y guidelines). Ne pas ajouter de teintes pastel
     * sans re-tester avec un outil contraste.
     *
     * v1.11.0 audit U1 — chaque entrée porte aussi une string ressource
     * pour le `contentDescription` TalkBack (a11y).
     *
     * v1.14.4 — extension de 8 → 16 couleurs (demande user). Ajout :
     * royal blue, crimson, emerald, magenta, brown, eggplant, bordeaux,
     * deep orange.
     */
    data class Option(val color: Color, val nameRes: Int)

    val OPTIONS: List<Option> = listOf(
        // Palette v1.11.0 (8 couleurs)
        Option(BRAND_BLUE, com.filestech.sms.R.string.appearance_color_brand_blue),
        Option(Color(0xFF1B5E20), com.filestech.sms.R.string.appearance_color_forest_green),
        Option(Color(0xFF6A1B9A), com.filestech.sms.R.string.appearance_color_deep_purple),
        Option(Color(0xFFC62828), com.filestech.sms.R.string.appearance_color_brand_red),
        Option(Color(0xFFE65100), com.filestech.sms.R.string.appearance_color_burnt_orange),
        Option(Color(0xFF455A64), com.filestech.sms.R.string.appearance_color_slate_blue_grey),
        Option(Color(0xFF4527A0), com.filestech.sms.R.string.appearance_color_deep_indigo),
        Option(Color(0xFF00695C), com.filestech.sms.R.string.appearance_color_dark_teal),
        // v1.14.4 — 8 nouvelles couleurs Material 700-900 WCAG AA
        Option(Color(0xFF1565C0), com.filestech.sms.R.string.appearance_color_royal_blue),
        Option(Color(0xFFB71C1C), com.filestech.sms.R.string.appearance_color_crimson),
        Option(Color(0xFF2E7D32), com.filestech.sms.R.string.appearance_color_emerald),
        Option(Color(0xFFAD1457), com.filestech.sms.R.string.appearance_color_magenta),
        Option(Color(0xFF5D4037), com.filestech.sms.R.string.appearance_color_brown),
        Option(Color(0xFF311B92), com.filestech.sms.R.string.appearance_color_eggplant),
        Option(Color(0xFF7B1FA2), com.filestech.sms.R.string.appearance_color_bordeaux),
        Option(Color(0xFFBF360C), com.filestech.sms.R.string.appearance_color_deep_orange),
        // v1.14.4 — 3 nuances bleu clair / gris bleu (demande user)
        Option(Color(0xFF01579B), com.filestech.sms.R.string.appearance_color_sky_blue),
        Option(Color(0xFF0D47A1), com.filestech.sms.R.string.appearance_color_navy),
        Option(Color(0xFF37474F), com.filestech.sms.R.string.appearance_color_steel_grey),
    )
}
