package com.filestech.sms.ui.components

import android.util.Patterns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * v1.3.2 — affiche le corps d'un message en détectant les URLs (http/https + domaines
 * nus genre `google.com`) et en les rendant **cliquables** : tap ouvre l'URL dans le
 * navigateur système via [androidx.compose.ui.platform.UriHandler] (qu'utilise
 * automatiquement [LinkAnnotation.Url]).
 *
 * Sécurité :
 *   - Le scheme est normalisé à `https://` pour les domaines nus (`google.com` →
 *     `https://google.com`) afin de ne JAMAIS générer une intent ouvrant un scheme
 *     exotique. Si l'URL contient déjà `http://` ou `https://`, on garde tel quel.
 *   - La regex [Patterns.WEB_URL] est l'implémentation Android stable (les autres
 *     apps SMS s'en servent), couvre les TLDs courants + IDN + IP.
 *   - Strip des caractères de queue qui sont quasi toujours de la ponctuation et
 *     pas de l'URL (`.`, `,`, `;`, `:`, `!`, `?`, `)`) — évite `google.com.` qui
 *     pointerait nulle part.
 *
 * Style :
 *   - Le lien est souligné, en gras léger, couleur = couleur du texte parent
 *     (lisible sur fond clair comme foncé sans hardcode).
 */
@Composable
fun MessageTextWithLinks(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // Recalcule l'AnnotatedString uniquement quand le texte change, pas à chaque
    // recomposition (la regex Patterns.WEB_URL n'est pas gratuite sur un long SMS).
    val annotated = remember(text) { buildLinkifiedText(text) }
    // Note : le SpanStyle des liens (dans buildLinkifiedText) ne fixe PAS de `color`,
    // de sorte que le lien hérite de la couleur du texte parent (gérée ici par
    // [color]). Ça garantit la lisibilité en thème clair ET sombre sans hardcode.
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

/**
 * v1.3.2 — cap d'entrée pour la regex `Patterns.WEB_URL`. Cette regex Java NFA peut
 * approcher un comportement quadratique sur certaines entrées pathologiques (ReDoS).
 * Aucun SMS standard ne dépasse cette taille en pratique (160 chars GSM-7, ~1 600
 * chars MMS texte concaténé). Au-delà, on rend le texte brut sans linkify — pas de
 * perte fonctionnelle, juste pas de clic auto sur les URLs d'un message géant.
 */
private const val LINKIFY_INPUT_CAP = 2_000

/**
 * v1.3.2 — style centralisé du lien (instance unique, pas réalloué à chaque appel).
 * Pas de `color = …` : on hérite de LocalContentColor du Text parent pour rester
 * lisible en thème clair ET sombre.
 */
private val LINK_STYLE = TextLinkStyles(
    style = SpanStyle(
        textDecoration = TextDecoration.Underline,
        fontWeight = FontWeight.Medium,
    ),
)

/** v1.3.2 — caractères de ponctuation strippés en queue d'URL avant linkify. */
private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '"', '\'')

/**
 * v1.3.2 — construit l'AnnotatedString avec les URLs détectées en [LinkAnnotation.Url].
 * Public-internal pour test JUnit isolé (sans Compose runtime).
 *
 * Sécurité :
 *
 *   - **Cap entrée** : si [text] excède [LINKIFY_INPUT_CAP] (2 000 chars), on
 *     court-circuite — pas de regex, juste un append brut. Anti-ReDoS pour les
 *     entrées pathologiques.
 *   - **Whitelist scheme** : seules `http://` / `https://` sont permises comme
 *     liens cliquables. Une URL détectée avec un autre scheme (`javascript:`,
 *     `data:`, `file:`, `content:`, `intent:` …) est rendue en texte brut SANS
 *     `withLink`. Aucun risque qu'une app installée déclarant un intent-filter
 *     exotique intercepte un tap utilisateur sur un body malicieux.
 *   - **Bare domain** (`google.com`) : normalisé en `https://google.com`.
 *   - **Strip ponctuation queue** : `Hello google.com.` → URL = `google.com`,
 *     le `.` final reste hors-lien.
 */
internal fun buildLinkifiedText(text: String): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    if (text.length > LINKIFY_INPUT_CAP) {
        append(text)
        return@buildAnnotatedString
    }
    val matcher = Patterns.WEB_URL.matcher(text)
    var cursor = 0
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        // Texte avant l'URL (peut être vide si URL en tête).
        if (start > cursor) {
            append(text.substring(cursor, start))
        }
        // Extraction + strip ponctuation finale (`google.com.` → `google.com` +
        // le `.` reste dans le texte hors-lien).
        val raw = text.substring(start, end)
        val stripped = raw.trimEnd(*URL_TRAILING_PUNCTUATION)
        val safeTarget = stripped.toSafeHttpsTargetOrNull()
        if (safeTarget != null) {
            withLink(LinkAnnotation.Url(url = safeTarget, styles = LINK_STYLE)) {
                append(stripped)
            }
        } else {
            // Scheme exotique détecté (`javascript:`, `data:` …) — rendu en texte
            // brut, pas cliquable. Aucun intent système n'est généré.
            append(stripped)
        }
        // Le reliquat de ponctuation strip-é est rendu en texte normal hors du lien.
        if (stripped.length < raw.length) {
            append(raw.substring(stripped.length))
        }
        cursor = end
    }
    // Texte après la dernière URL (ou tout le texte si aucune URL).
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

/**
 * v1.3.2 — retourne une URL cible sûre pour `LinkAnnotation.Url` :
 *
 *   - `https://google.com` (déjà bon scheme) → tel quel
 *   - `google.com` (bare domain, pas de scheme) → `https://google.com`
 *   - `javascript:alert(1)` / `data:…` / `intent:…` / autre → `null` (refusé)
 *
 * Le test "pas de scheme" est strict : présence d'un `:` AVANT le 1ᵉʳ `/` indique
 * un scheme ; si ce scheme n'est ni http(s), on refuse.
 */
internal fun String.toSafeHttpsTargetOrNull(): String? {
    if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
        return this
    }
    // Détecte un scheme suspect : `xxx:yyy` avant tout `/`.
    val colonIdx = indexOf(':')
    val slashIdx = indexOf('/')
    val hasExoticScheme = colonIdx > 0 && (slashIdx < 0 || colonIdx < slashIdx)
    if (hasExoticScheme) return null
    return "https://$this"
}
