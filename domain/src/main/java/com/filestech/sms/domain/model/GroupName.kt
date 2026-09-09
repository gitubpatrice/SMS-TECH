package com.filestech.sms.domain.model

import com.filestech.sms.core.ext.stripInvisibleChars

/**
 * v1.28.3 — **le nom qu'on donne à un groupe.**
 *
 * Il ne quitte jamais ce téléphone : le SMS ne transporte pas de nom de groupe, les membres
 * continuent de voir un numéro. C'est donc une étiquette locale, comme une couleur de bulle —
 * et comme elle, elle prime sur tout ce qu'on calcule ([GroupTitle]) tant qu'elle existe.
 *
 * Une seule règle de normalisation, ici, pour les deux entrées (menu du fil, appui long dans
 * la liste) : espaces rognés, caractères invisibles retirés (un nom importé d'une vCard peut
 * porter un U+202E qui inverse le rendu de la barre de titre), [MAX] caractères. Vide, c'est
 * `null` : « retirer le nom » n'est pas un cas à part, c'est un nom vide.
 */
object GroupName {

    const val MAX: Int = 40

    fun normalize(raw: String?): String? =
        raw?.stripInvisibleChars()?.trim()?.take(MAX)?.takeIf { it.isNotBlank() }
}
