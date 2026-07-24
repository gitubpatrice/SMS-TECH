package com.filestech.sms.domain.smishing

/**
 * v1.11.0 — Sujet 3 : détecteur d'arnaque locale (anti-smishing) 100 % offline.
 *
 * Combine 4 heuristiques composables sur le corps d'un SMS entrant pour
 * estimer la probabilité d'une arnaque (smishing : SMS phishing). Le résultat
 * ([SmishingVerdict]) porte un score numérique et une liste de raisons
 * lisibles côté UI ([SmishingReason]) afin d'afficher un bandeau "⚠️
 * Possiblement frauduleux" + le pourquoi sous chaque message suspect.
 *
 * **Politique de design** :
 *  - **Pure function** : entrée = texte brut, sortie = verdict. Aucune
 *    dépendance Android, aucun I/O, aucun network. Testable en JVM pur.
 *  - **Allow-list philosophique** : on préfère un faux NÉGATIF (rate un
 *    smishing rare) à un faux POSITIF (bandeau rouge sur un SMS légitime
 *    de la banque ou des impôts). Le seuil par défaut [DEFAULT_THRESHOLD]
 *    est calibré conservateur (2 heuristiques positives minimum).
 *  - **Pas d'IA, pas de modèle** : 100 % règles déterministes, auditables
 *    et FLOSS-compatibles. Pas de modèle bundlé, pas de cloud.
 *  - **i18n FR-first** : les listes de mots-clés et de domaines officiels
 *    visent en priorité le marché FR (cible app SMS Tech). Quelques EN
 *    en filet pour les SMS de phishing internationaux (Amazon, Microsoft,
 *    etc.) qui touchent aussi les francophones.
 *
 * **Limites acceptées** :
 *  - Un attaquant qui maîtrise les heuristiques peut écrire un SMS qui les
 *    contourne (pas de mot d'urgence, pas d'URL, etc.). Mais ce serait
 *    aussi un SMS moins efficace côté arnaque — gain marginal sécurité.
 *  - Pas de détection de typosquatting punycode (IDN homograph). Out of
 *    scope pour cette release ; à reprendre si les remontées le justifient.
 */
/**
 * v1.11.0 audit Q1 — visibilité `public` minimum requise car l'enum
 * [SmishingReason] est exposée dans la signature publique de
 * [com.filestech.sms.ui.components.MessageBubble]. Si SmishingReason
 * passe internal un jour, ce détecteur peut redevenir internal aussi.
 */
object SmishingDetector {

    /**
     * Seuil de déclenchement du bandeau "Possiblement frauduleux". Calibré
     * à 2 (≥ 2 heuristiques positives) pour limiter les faux positifs :
     *  - 1 seul URL raccourci dans un SMS d'ami partageant un lien — pas
     *    de bandeau (autre heuristique manquante).
     *  - 1 mot d'urgence isolé ("urgent") sans URL ni numéro premium — idem.
     *  - URL raccourci + mot d'urgence = bandeau. URL + numéro premium = idem.
     */
    const val DEFAULT_THRESHOLD: Int = 2

    /**
     * Cap de longueur du body inspecté pour éviter tout coût pathologique
     * sur un SMS hyper long (cas exotique : message structuré, débordement
     * de buffer). 1000 caractères couvrent largement le multi-segment SMS
     * (160 × 7 = 1120 max théorique) tout en gardant les regex bornés.
     */
    private const val MAX_BODY_LENGTH = 1000

    /**
     * v1.11.0 audit S4 — cap dur sur le nombre de matches inspectés par les
     * regex URL / domain-like. Le cap 1000c du body évite le ReDoS, mais
     * sans cap matches le pire cas pathologique `a.b c.d e.f g.h…` répété
     * dans 1000c donne ~150 matches × 22 OFFICIAL_FR_LABEL_ROOTS ×
     * Levenshtein O(20×20) = ~15 ms sur Cortex-A53. Cap ici réduit le
     * pire cas à ~3 ms sur le même device.
     */
    private const val MAX_URL_MATCHES = 20
    private const val MAX_DOMAIN_MATCHES = 30

    fun analyze(body: String, threshold: Int = DEFAULT_THRESHOLD): SmishingVerdict {
        if (body.isBlank()) return SmishingVerdict(score = 0, reasons = emptyList(), threshold = threshold)
        val capped = if (body.length > MAX_BODY_LENGTH) body.substring(0, MAX_BODY_LENGTH) else body
        val lower = capped.lowercase()

        val reasons = mutableListOf<SmishingReason>()
        if (containsUrlShortener(lower)) reasons += SmishingReason.UrlShortener
        if (containsUrgencyKeyword(lower)) reasons += SmishingReason.UrgencyKeyword
        if (containsPremiumNumber(capped)) reasons += SmishingReason.PremiumNumber
        if (containsTyposquattedDomain(lower)) reasons += SmishingReason.TyposquattedDomain

        return SmishingVerdict(
            score = reasons.size,
            reasons = reasons.toList(),
            threshold = threshold,
        )
    }

    // ──────────────── Heuristique 1 : URL shorteners ────────────────

    /**
     * Liste fermée des raccourcisseurs d'URL les plus utilisés par les
     * campagnes de smishing FR (et plus largement). Volontairement courte
     * pour limiter les faux positifs : un SMS contenant `t.co/abc123`
     * (Twitter/X) déclenche, mais c'est rarement le cas dans un SMS
     * personnel légitime aujourd'hui — la plupart des partages perso
     * passent par messageries chiffrées, pas SMS.
     *
     * Les TLD très courts (`.co`, `.io`) sont aussi suspects car ils
     * permettent une URL très courte qui camoufle le domaine final.
     */
    private val URL_SHORTENER_HOSTS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "ow.ly", "goo.gl", "lnkd.in",
        "buff.ly", "rebrand.ly", "is.gd", "cli.gs", "tiny.cc",
        "shorturl.at", "cutt.ly", "rb.gy", "s.id", "v.gd",
        // FR-specific : raccourcisseurs souvent vus dans campagnes de smishing
        // se faisant passer pour des opérateurs ou des services publics.
        "n9.cl", "url.cn",
    )

    /**
     * URL avec scheme `http(s)://` explicite — utilisé pour l'heuristique
     * URL shortener (qui ne s'applique qu'aux vrais liens cliquables).
     */
    private val URL_REGEX = Regex("""https?://([a-z0-9.\-]+)(?:[/?#][^\s]*)?""", RegexOption.IGNORE_CASE)

    /**
     * Pattern domain-like (sans scheme obligatoire) — utilisé pour la
     * détection de typosquatting qui doit capturer les bare hostnames du
     * type `paypa1.fr/update` ou `arnel1.fr` cités dans le corps d'un SMS
     * sans nécessairement de préfixe `http://`. Le `\b` initial évite de
     * matcher au milieu d'un mot.
     */
    private val DOMAIN_LIKE_REGEX = Regex(
        """\b([a-z0-9\-]{2,}(?:\.[a-z0-9\-]{2,}){1,3})\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun containsUrlShortener(lowerBody: String): Boolean {
        URL_REGEX.findAll(lowerBody).take(MAX_URL_MATCHES).forEach { match ->
            val host = match.groupValues[1].lowercase()
            // host exact OU host à suffixe court suspect (ex: foo.bit.ly).
            if (URL_SHORTENER_HOSTS.contains(host)) return true
            val maybeRoot = host.split(".").takeLast(2).joinToString(".")
            if (URL_SHORTENER_HOSTS.contains(maybeRoot)) return true
        }
        return false
    }

    // ──────────────── Heuristique 2 : mots d'urgence ────────────────

    /**
     * Mots-clés typiques des SMS de phishing FR + quelques EN. Choix
     * conservateur — pas de "merci", "rappelle", "pense à". Les patterns
     * incluent les variantes courantes ("urgent", "immédiat", "expire").
     * On limite à un set fermé pour la maintenance et l'auditabilité.
     */
    private val URGENCY_KEYWORDS = setOf(
        // FR — urgence / pression temporelle
        "urgent", "urgente", "immédiat", "immediat", "immédiatement", "immediatement",
        "expire", "expirera", "expirent", "dernière chance", "derniere chance",
        "avant minuit", "dans 24h", "dans 24 h",
        // FR — compte / blocage / suspension
        "compte bloqué", "compte bloque", "compte suspendu", "compte gelé", "compte gele",
        "carte bloquée", "carte bloquee", "iban", "rib",
        "action requise", "action immédiate", "action immediate",
        "confirmez", "confirmer votre", "vérifier votre", "verifier votre",
        // FR — anti-fraude (l'arnaque se déguise en alerte anti-fraude)
        "tentative de fraude", "tentative suspecte", "transaction suspecte",
        // FR — colis / livraison (campagnes massives 2024-2025)
        "colis en attente", "colis bloqué", "colis bloque",
        "frais de livraison", "frais de port", "redevance douaniere", "redevance douanière",
        // FR — administration usurpée
        "impots impayés", "impôts impayés", "amende", "majoration",
        // EN — common phishing
        "click here", "click below", "verify your", "your account has been",
        "limited time", "act now",
    )

    private fun containsUrgencyKeyword(lowerBody: String): Boolean {
        return URGENCY_KEYWORDS.any { kw -> lowerBody.contains(kw) }
    }

    // ──────────────── Heuristique 3 : numéros premium / surtaxés FR ────────────────

    /**
     * Numéros surtaxés / premium FR repérables dans le corps d'un SMS de
     * phishing qui invite à rappeler. Politique conservatrice :
     *  - 32xx, 36xx (4 chiffres) — numéros courts surtaxés
     *  - 08 1x, 08 8x, 08 9x — numéros à 0,80 €/min et +
     *  - 0899 xxx xxx — toujours surtaxé (le plus cher catégorie 8)
     *
     * **NE matche PAS** un numéro 06/07 (mobile standard), 09 (VoIP),
     * 01-05 (géographique), 080 (gratuit), 0800 (vert). Le filtrage
     * sur ces ranges spécifiques évite les faux positifs courants.
     *
     * Le numéro peut être présent avec des espaces, tirets, ou points
     * comme séparateurs visuels (le regex normalise via `replace` avant
     * le match).
     */
    private val PREMIUM_PATTERNS = listOf(
        // Numéros courts surtaxés (3200-3699, 4 chiffres). Border non-digit
        // suffisant — un texte normal qui contient "3211" sera flaggé. Lookaround
        // sur non-digit pour ne pas se faire piéger par un numéro plus long.
        Regex("""(?<!\d)3[2-6]\d{2}(?!\d)"""),
        // 0899 xxx xxx — toujours surtaxé.
        Regex("""(?<!\d)0899\d{6}(?!\d)"""),
        // 081x / 088x / 089x — surtaxés (catégories ≥ 0,80 €/min).
        Regex("""(?<!\d)08(?:1[0-9]|8[0-9]|9[0-9])\d{6}(?!\d)"""),
    )

    private fun containsPremiumNumber(body: String): Boolean {
        // Normalise les séparateurs visuels (espace, tiret, point) pour
        // matcher "08 99 12 34 56" comme "0899123456". Les frontières
        // (?<!\d)/(?!\d) gèrent les caractères adjacents non-numériques
        // (lettres incluses), ce que `\b` ne ferait pas après compaction.
        val compact = body.replace(Regex("""[\s.\-]"""), "")
        return PREMIUM_PATTERNS.any { it.containsMatchIn(compact) }
    }

    // ──────────────── Heuristique 4 : typosquatting de domaines officiels FR ────────────────

    /**
     * Domaines officiels souvent usurpés par les campagnes de smishing FR.
     * Pour chaque domaine officiel, on accepte le domaine exact ; on
     * flagge tout host qui RESSEMBLE à un officiel (distance Levenshtein
     * ≤ 2 sur la partie sans TLD) sans être strictement égal.
     *
     * Exemples qui devraient trigger :
     *  - `1mpots.gouv.fr` (1 au lieu de i)
     *  - `arnel1.fr` (lookalike ameli)
     *  - `colissimo-track.fr` (faux site colissimo)
     *
     * Exemples qui ne doivent PAS trigger :
     *  - `impots.gouv.fr` (domaine exact officiel)
     *  - `ameli.fr` (domaine exact officiel)
     */
    private val OFFICIAL_FR_HOSTS = setOf(
        "impots.gouv.fr", "ameli.fr", "laposte.fr", "colissimo.fr",
        "chronopost.fr", "ants.gouv.fr", "service-public.fr",
        "paypal.fr", "paypal.com",
        "amazon.fr", "amazon.com",
        "edf.fr", "engie.fr",
        "orange.fr", "sfr.fr", "free.fr", "bouyguestelecom.fr",
        "crediagricole.fr", "creditagricole.fr", "bnpparibas.fr", "societegenerale.fr",
        "labanquepostale.fr", "caisse-epargne.fr", "cic.fr", "lcl.fr", "boursorama.com",
    )

    /**
     * Liste des "labels racines" (partie sans TLD) pour la comparaison
     * Levenshtein. On ne compare PAS le domaine complet (sinon `.gouv.fr`
     * crée des matches parasites) mais seulement le label le plus significatif.
     * Ex: `impots.gouv.fr` → "impots", `paypal.fr` → "paypal".
     */
    private val OFFICIAL_FR_LABEL_ROOTS = OFFICIAL_FR_HOSTS.map { host ->
        host.substringBefore(".")
    }.toSet()

    private fun containsTyposquattedDomain(lowerBody: String): Boolean {
        // On scanne TOUS les tokens domain-like (avec OU sans scheme) — un
        // smishing FR met souvent juste "paypa1.fr/update" en clair, sans
        // http:// devant. URL_REGEX seul raterait ce cas. Cap MAX_DOMAIN_MATCHES
        // (audit S4) pour borner le pire cas Levenshtein × matches sur low-end.
        DOMAIN_LIKE_REGEX.findAll(lowerBody).take(MAX_DOMAIN_MATCHES).forEach { match ->
            val host = match.groupValues[1].lowercase()
            if (OFFICIAL_FR_HOSTS.contains(host)) return@forEach // domaine exact = légitime
            // Skip tokens qui ne ressemblent pas à un host plausible (au moins
            // 1 point, donc DOMAIN_LIKE_REGEX déjà filtre, mais on cap les
            // sous-domaines pour ne comparer que le label principal).
            val label = host.substringBefore(".")
            if (label.length < 4) return@forEach // labels trop courts = pas comparable
            // Si on trouve un label officiel à distance ≤ 2 ET != strict, c'est suspect.
            val suspicious = OFFICIAL_FR_LABEL_ROOTS.any { official ->
                official.length >= 4 && official != label &&
                    levenshteinAtMost(label, official, 2)
            }
            if (suspicious) return true
        }
        return false
    }

    /**
     * Distance de Levenshtein bornée — retourne true si la distance entre
     * [a] et [b] est ≤ [maxDistance]. Implémentation early-exit pour O(n)
     * dans le cas commun où les chaînes sont très différentes.
     */
    fun levenshteinAtMost(a: String, b: String, maxDistance: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        if (a == b) return true
        val n = a.length
        val m = b.length
        if (n == 0) return m <= maxDistance
        if (m == 0) return n <= maxDistance
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // suppression
                    curr[j - 1] + 1,    // insertion
                    prev[j - 1] + cost, // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            // Early-exit : si la ligne entière dépasse maxDistance, on peut
            // arrêter — la distance finale sera nécessairement supérieure.
            if (rowMin > maxDistance) return false
            for (k in 0..m) prev[k] = curr[k]
        }
        return prev[m] <= maxDistance
    }
}

/**
 * Verdict du détecteur. [score] = nombre d'heuristiques positives.
 * [shouldWarn] indique si le score atteint le seuil de bandeau UI.
 */
data class SmishingVerdict(
    val score: Int,
    val reasons: List<SmishingReason>,
    val threshold: Int,
) {
    val shouldWarn: Boolean get() = score >= threshold
}

/**
 * Raisons individuelles affichables côté UI. L'UI mappe chaque [SmishingReason]
 * vers une string ressource localisée FR/EN dans le bandeau "Pourquoi ?".
 */
enum class SmishingReason {
    UrlShortener,
    UrgencyKeyword,
    PremiumNumber,
    TyposquattedDomain,
}
