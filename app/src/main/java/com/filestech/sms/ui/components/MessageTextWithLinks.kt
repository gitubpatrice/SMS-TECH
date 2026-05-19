package com.filestech.sms.ui.components

import android.util.Patterns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    /**
     * v1.3.11 (F4) — invoked when the user taps a phone number detected inside [text].
     * Default no-op so existing call sites keep working until they opt in. Wrapped in
     * [rememberUpdatedState] below so the lambda identity can change between
     * recompositions without invalidating the (potentially expensive) regex pass.
     */
    onPhoneClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // v1.3.11 (F4) — keep the closure inside the AnnotatedString stable so the regex
    // pass below only re-runs when [text] changes, while the actual phone callback the
    // listener forwards to always points to the latest [onPhoneClick].
    val phoneClickState = rememberUpdatedState(onPhoneClick)
    val annotated = remember(text) {
        buildLinkifiedText(text) { number -> phoneClickState.value(number) }
    }
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
 * v1.3.11 (F4) — minimum / maximum digit count for a `Patterns.PHONE` match to be
 * accepted as a clickable phone number. `Patterns.PHONE` is intentionally lax (matches
 * any digit-with-separators run); without this band we'd surface a tap action on order
 * IDs, postal codes and CB last-4 digits.
 *
 * 7 is the floor of E.164 subscriber lengths (Niue / Tokelau); 15 is the spec ceiling
 * (E.164 §6.2.1). Matches Google Messages' own filter empirically.
 */
private const val PHONE_MIN_DIGITS = 7
private const val PHONE_MAX_DIGITS = 15

private enum class HitKind { Url, Phone }
private data class LinkHit(val start: Int, val end: Int, val kind: HitKind)

/**
 * v1.3.2 (URL) + v1.3.11 F4 (phone) — construit l'AnnotatedString avec les URLs ET les
 * numéros de téléphone détectés. Public-internal pour test JUnit isolé (sans Compose
 * runtime).
 *
 * Sécurité :
 *
 *   - **Cap entrée** : si [text] excède [LINKIFY_INPUT_CAP] (2 000 chars), on
 *     court-circuite — pas de regex, juste un append brut. Anti-ReDoS pour les
 *     entrées pathologiques.
 *   - **Whitelist scheme URL** : seules `http://` / `https://` sont permises comme
 *     liens cliquables. Une URL détectée avec un autre scheme (`javascript:`,
 *     `data:`, `file:`, `content:`, `intent:` …) est rendue en texte brut SANS
 *     `withLink`. Aucun risque qu'une app installée déclarant un intent-filter
 *     exotique intercepte un tap utilisateur sur un body malicieux.
 *   - **Bare domain** (`google.com`) : normalisé en `https://google.com`.
 *   - **Strip ponctuation queue** : `Hello google.com.` → URL = `google.com`,
 *     le `.` final reste hors-lien.
 *   - **Filtre digit count téléphone** : un match `Patterns.PHONE` n'est promu
 *     cliquable que si son nombre de chiffres tombe dans `[PHONE_MIN_DIGITS,
 *     PHONE_MAX_DIGITS]` — évite la pollution sur les codes courts (CB derniers
 *     4 digits, codes promo) et les chaînes de chiffres pathologiques.
 *   - **Pas d'intent direct** : le tap téléphone ne déclenche AUCUN intent depuis
 *     ce composable. Il appelle [onPhoneClick] qui owns le dialog de confirmation
 *     (cf. [PhoneActionsDialog]) avec actions explicites.
 *
 * Chevauchements URL ↔ téléphone : URL gagne (priorité 0 vs 1 dans le tri). Évite
 * qu'une URL contenant des chiffres soit fragmentée en un lien URL + un sous-lien
 * téléphone décalé.
 */
internal fun buildLinkifiedText(
    text: String,
    onPhoneClick: (String) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    if (text.length > LINKIFY_INPUT_CAP) {
        append(text)
        return@buildAnnotatedString
    }

    val hits = collectLinkHits(text)
    if (hits.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }

    var cursor = 0
    for (hit in hits) {
        if (hit.start > cursor) {
            append(text.substring(cursor, hit.start))
        }
        val raw = text.substring(hit.start, hit.end)
        when (hit.kind) {
            HitKind.Url -> {
                val stripped = raw.trimEnd(*URL_TRAILING_PUNCTUATION)
                val safeTarget = stripped.toSafeHttpsTargetOrNull()
                if (safeTarget != null) {
                    withLink(LinkAnnotation.Url(url = safeTarget, styles = LINK_STYLE)) {
                        append(stripped)
                    }
                } else {
                    // Scheme exotique → rendu brut, pas cliquable.
                    append(stripped)
                }
                if (stripped.length < raw.length) {
                    append(raw.substring(stripped.length))
                }
            }
            HitKind.Phone -> {
                // Bind the raw match into a local so the listener closes over a stable
                // String — not over a loop variable that could mutate between matches.
                val number = raw
                withLink(
                    LinkAnnotation.Clickable(
                        tag = number,
                        styles = LINK_STYLE,
                        linkInteractionListener = { onPhoneClick(number) },
                    ),
                ) {
                    append(number)
                }
            }
        }
        cursor = hit.end
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

/**
 * v1.3.11 (F4) — runs the URL and PHONE regexes once each, then merges + dedupes
 * overlapping ranges. URL wins on conflict (priority 0 vs 1) to keep things like
 * `https://example.com/+33612345678` rendered as a single URL link.
 */
private fun collectLinkHits(text: String): List<LinkHit> {
    val all = ArrayList<LinkHit>(8)
    val webMatcher = Patterns.WEB_URL.matcher(text)
    while (webMatcher.find()) {
        all += LinkHit(webMatcher.start(), webMatcher.end(), HitKind.Url)
    }
    val phoneMatcher = Patterns.PHONE.matcher(text)
    while (phoneMatcher.find()) {
        val start = phoneMatcher.start()
        val end = phoneMatcher.end()
        val digits = countDigits(text, start, end)
        if (digits in PHONE_MIN_DIGITS..PHONE_MAX_DIGITS) {
            all += LinkHit(start, end, HitKind.Phone)
        }
    }
    if (all.isEmpty()) return emptyList()
    // Sort by start (asc) then URL-first on equal starts.
    all.sortWith(compareBy({ it.start }, { if (it.kind == HitKind.Url) 0 else 1 }))
    // Drop any hit whose range overlaps the previous accepted one.
    val accepted = ArrayList<LinkHit>(all.size)
    var lastEnd = 0
    for (hit in all) {
        if (hit.start >= lastEnd) {
            accepted += hit
            lastEnd = hit.end
        }
    }
    return accepted
}

/**
 * v1.3.11 (F4) — internal so JUnit can pin the digit-count band filter that drives the
 * phone-link promotion decision. Pure helper, no Android dependency.
 */
internal fun countDigits(text: String, start: Int, end: Int): Int {
    var n = 0
    for (i in start until end) {
        if (text[i].isDigit()) n++
    }
    return n
}

/** v1.3.11 (F4) — internal for JUnit visibility; mirrors the const used in the filter. */
internal const val PHONE_DIGITS_MIN: Int = PHONE_MIN_DIGITS
internal const val PHONE_DIGITS_MAX: Int = PHONE_MAX_DIGITS

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
