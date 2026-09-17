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
 *  - **Six pays, tous vérifiés en même temps** (v1.28.12). Les listes couvraient la
 *    France seule, ce qui n'a plus tenu le jour où l'application a parlé allemand,
 *    italien et espagnol : la version allemande annonçait détecter `e1ster.de` alors
 *    qu'`elster.de` n'était nulle part dans la liste. Les mots d'urgence, les numéros
 *    surtaxés et les domaines officiels de la France, de l'Allemagne, de l'Italie, de
 *    l'Espagne et du Royaume-Uni sont désormais là, plus les domaines officiels
 *    irlandais — et ils sont TOUS appliqués à chaque message, quel que soit le pays de
 *    l'utilisateur.
 *
 *    L'anglais est la langue SOURCE de l'application et n'avait, jusque-là, aucune liste
 *    à lui. L'Irlande a ses domaines mais pas ses numéros surtaxés : c'est une lacune
 *    connue et écrite, pas un oubli.
 *
 *    Ce n'est pas la même règle que pour les numéros d'urgence, et la différence est
 *    délibérée : là-bas il FAUT choisir un pays, puisqu'on compose un seul numéro. Ici
 *    on ne compose rien, on reconnaît des motifs — et une liste de plus ne coûte qu'un
 *    risque de faux positif. Choisir serait même nuisible : un Français en déplacement
 *    en Allemagne reçoit encore des arnaques françaises, et perdrait sa protection.
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
     * dans 1000c donne ~150 matches × [LABELS_OFFICIELS] ×
     * Levenshtein O(20×20). Cap ici réduit le pire cas d'un ordre de grandeur.
     *
     * ⚠️ Le chiffrage écrit ici parlait de **22** labels officiels. Ils sont **54**
     * (56 domaines dédoublonnés) depuis l'élargissement à cinq pays, et l'on examine
     * désormais TOUS les labels d'un hôte au lieu du premier — jusqu'à quatre. Pire cas
     * mesuré le 2026-09-17 : ~55 000 cellules de programmation dynamique par message,
     * contre ~1 400 avec l'ancienne forme. **Ce n'est pas un déni de service** : le calcul
     * tourne sur le dispatcher IO, il est mis en cache par `message.id` et le job
     * précédent est annulé — de l'ordre de 0,3 ms sur ART. Le défaut était documentaire,
     * et c'est cette borne qu'un relecteur futur aurait crue.
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
     * type `paypa1.fr/update` ou `amel1.fr` cités dans le corps d'un SMS
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
        // ─────────────────────────────── ALLEMAND ───────────────────────────────
        // v1.28.12 — ajoutés avec la livraison de l'allemand. Un SMS d'arnaque allemand
        // n'écrit pas « urgent » : sans ces mots, l'heuristique ne voyait rien.
        //
        // ⚠️ CE QUI A ÉTÉ ÉCRIT PUIS RETIRÉ, et pourquoi : `versandkosten`, `zollgebühr`,
        // `kontodaten`, `spese di spedizione`, `spese doganali`, `gastos de envío`,
        // `tasas de aduana`, `immediato`, `inmediato`. Tous sont écrits par de VRAIS
        // transporteurs et de VRAIES banques — « bonifico immediato » est un virement
        // instantané légitime, les frais de port figurent dans toute confirmation de
        // commande. Relecture externe du 2026-09-17. Ce fichier préfère rater une
        // arnaque à teinter de rouge la confirmation de commande de tout le monde.
        "dringend", "sofort handeln", "umgehend", "läuft ab", "lauft ab",
        "letzte chance", "innerhalb von 24",
        "konto gesperrt", "konto wurde gesperrt", "karte gesperrt",
        "verdächtige transaktion", "verdachtige transaktion", "betrugsversuch",
        "jetzt handeln", "bestätigen sie ihre", "bestatigen sie ihre",
        "verifizieren sie ihre",
        "paket konnte nicht", "mahnschreiben", "inkasso",
        // ──────────────────────────────── ITALIEN ────────────────────────────────
        "scade oggi", "scadrà", "scadra",
        "ultima possibilità", "ultima possibilita",
        "conto bloccato", "conto sospeso", "carta bloccata",
        "azione richiesta", "confermi i suoi", "verifichi i suoi",
        "transazione sospetta", "tentativo di frode", "accesso anomalo",
        "pacco in attesa", "pacco in giacenza", "pacco bloccato",
        // ──────────────────────────────── ESPAGNOL ───────────────────────────────
        "caduca hoy", "caducará", "caducara",
        "última oportunidad", "ultima oportunidad",
        "cuenta bloqueada", "cuenta suspendida", "tarjeta bloqueada",
        "acción requerida", "accion requerida", "confirme sus", "verifique sus",
        "transacción sospechosa", "transaccion sospechosa", "intento de fraude",
        "paquete retenido", "paquete bloqueado",
        "multa no pagada", "pago pendiente", "actualizar sus datos",
        // FR — urgence / pression temporelle
        //
        // ⚠️ `immédiat` / `immediat` isolés ont été RETIRÉS le 2026-09-17. Deux raisons,
        // et la seconde n'a été vue que par un contrôle négatif :
        //  - « virement immédiat » est un virement instantané parfaitement légitime ;
        //  - la forme sans accent `immediat` est un PRÉFIXE de l'italien `immediato`, si
        //    bien qu'un « bonifico immediato ricevuto » — un vrai message de banque
        //    italienne — était signalé par la liste FRANÇAISE. Retirer le mot italien
        //    n'y changeait rien : c'est le français qui mordait.
        // `action immédiate` reste : la formule complète n'est employée que par l'arnaque.
        "urgent", "urgente",
        "expire", "expirera", "expirent", "dernière chance", "derniere chance",
        "avant minuit", "dans 24h", "dans 24 h",
        // FR — compte / blocage / suspension
        "compte bloqué", "compte bloque", "compte suspendu", "compte gelé", "compte gele",
        // ⚠️ `rib` a été RETIRÉ le 2026-09-17 : cherché comme sous-chaîne, il vit dans des
        // mots ordinaires — **distribution**, **contribution**. « Votre colis est en cours
        // de distribution » avec un lien raccourci affichait donc un bandeau d'arnaque sur
        // l'un des SMS les plus banals qui soient. Trois lettres sont trop peu pour qu'une
        // frontière de mot le sauve : `\brib\b` ne signalerait plus rien d'utile.
        // `iban`, lui, est dans [MOTS_URGENCE_A_FRONTIERE] : sa seule collision est
        // « Taliban », et `\biban\b` la ferme sans rien perdre. Il avait été retiré avec
        // `rib` le même jour, alors que le mécanisme qui le sauvait était écrit dans le
        // même commit — relevé par un audit de sécurité le 2026-09-17.
        "carte bloquée", "carte bloquee",
        "action requise", "action immédiate", "action immediate",
        "confirmez", "confirmer votre", "vérifier votre", "verifier votre",
        // FR — anti-fraude (l'arnaque se déguise en alerte anti-fraude)
        "tentative de fraude", "tentative suspecte", "transaction suspecte",
        // FR — colis / livraison (campagnes massives 2024-2025)
        "colis en attente", "colis bloqué", "colis bloque",
        "frais de livraison", "frais de port", "redevance douaniere", "redevance douanière",
        // FR — administration usurpée
        "impots impayés", "impôts impayés", "majoration",
        // EN — common phishing
        "click here", "click below", "verify your", "your account has been",
        "limited time",
    )

    /**
     * Les mots qui ne peuvent PAS être cherchés comme simple sous-chaîne, parce qu'ils
     * vivent à l'intérieur de mots parfaitement innocents.
     *
     * `amende` est dans **amendement** ; `act now` est la fin de **contact now**. Cherchés
     * sans frontière, ils affichaient un bandeau d'arnaque sur « L'amendement a été adopté »
     * et sur « You can contact now our support team ». Relevé le 2026-09-17.
     *
     * Le `s?` final garde les pluriels (`amendes`), que `\b…\b` seul aurait perdus — c'est
     * pour cela que ces mots ont leur propre mécanisme plutôt qu'un `\b` posé partout : sur
     * `dringend` ou `inkasso`, la sous-chaîne est VOULUE (`dringende`, `Inkassobüro`).
     */
    private val MOTS_URGENCE_A_FRONTIERE = listOf("amende", "act now", "iban").map { mot ->
        Regex("""\b${Regex.escape(mot)}s?\b""")
    }

    private fun containsUrgencyKeyword(lowerBody: String): Boolean {
        if (URGENCY_KEYWORDS.any { kw -> lowerBody.contains(kw) }) return true
        return MOTS_URGENCE_A_FRONTIERE.any { it.containsMatchIn(lowerBody) }
    }

    // ──────────────── Heuristique 3 : numéros premium / surtaxés ────────────────

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
        // ───────────────────────────────── FRANCE ─────────────────────────────────
        // Numéros courts surtaxés (3200-3699, 4 chiffres). Border non-digit
        // suffisant — un texte normal qui contient "3211" sera flaggé. Lookaround
        // sur non-digit pour ne pas se faire piéger par un numéro plus long.
        Regex("""(?<!\d)3[2-6]\d{2}(?!\d)"""),
        // 0899 xxx xxx — toujours surtaxé.
        Regex("""(?<!\d)0899\d{6}(?!\d)"""),
        // 081x / 088x / 089x — surtaxés (catégories ≥ 0,80 €/min).
        Regex("""(?<!\d)08(?:1[0-9]|8[0-9]|9[0-9])\d{6}(?!\d)"""),
        // ──────────────────────────────── ALLEMAGNE ───────────────────────────────
        // v1.28.12 — ajoutés avec l'allemand, l'italien et l'espagnol.
        // 0900 = la seule plage premium allemande, 0137 = trafic de masse (votes,
        // jeux) souvent utilisée en arnaque. Le 118xx (renseignements) est
        // VOLONTAIREMENT absent : cinq chiffres seulement, il se confondrait avec
        // un montant ou une référence dans un SMS parfaitement légitime.
        Regex("""(?<!\d)0900\d{6,7}(?!\d)"""),
        Regex("""(?<!\d)0137\d{6,7}(?!\d)"""),
        // ───────────────────────────────── ITALIE ─────────────────────────────────
        // 899 et 895 = les plages les plus chères, neuf chiffres.
        //
        // ⚠️ Le 892xxx / 894xxx (renseignements surtaxés) a été écrit ici puis RETIRÉ :
        // six chiffres, c'est exactement le format d'un code de vérification. « Il tuo
        // codice è 892456 » aurait affiché un bandeau d'arnaque sur le SMS le plus banal
        // et le plus quotidien qui soit. Relevé par une relecture externe le 2026-09-17.
        Regex("""(?<!\d)89[59]\d{6}(?!\d)"""),
        // ───────────────────────────────── ESPAGNE ────────────────────────────────
        // 803 (adultes), 806 (loisirs), 807 (services), 905/907 (appels de masse).
        Regex("""(?<!\d)80[367]\d{6}(?!\d)"""),
        Regex("""(?<!\d)90[57]\d{6}(?!\d)"""),
        // ────────────────────────── ROYAUME-UNI ET IRLANDE ────────────────────────
        // L'application est livrée EN ANGLAIS d'abord, et n'avait aucune liste pour les
        // pays anglophones : un utilisateur britannique n'était protégé par rien.
        // 09xx = premium (11 chiffres), 084x / 087x = coûts majorés (11 chiffres).
        Regex("""(?<!\d)09\d{9}(?!\d)"""),
        Regex("""(?<!\d)08[47]\d{8}(?!\d)"""),
    )

    /**
     * Le séparateur visuel d'un numéro : UN seul caractère, et seulement s'il est
     * directement ENTRE DEUX CHIFFRES.
     *
     * L'ancienne version retirait tous les espaces, points et tirets du message entier.
     * Elle ne normalisait donc pas un numéro : elle **fusionnait des nombres voisins**.
     * « résultat du match, 32 - 11 » devenait `3211`, un numéro court surtaxé français ;
     * « commande réf. 08-99-123-456 » devenait un `0899 xxx xxx`. Signalé par une
     * relecture externe le 2026-09-17.
     *
     * Avec la contrainte « entre deux chiffres », « 32 - 11 » garde ses espaces (celui qui
     * précède le tiret n'est pas suivi d'un chiffre) et ne correspond plus à rien, tandis
     * que « 08 99 12 34 56 » et « 08-12-345-678 » se compactent toujours correctement.
     *
     * `\p{Zs}` plutôt qu'une espace ordinaire : il couvre l'insécable U+00A0 et la fine
     * insécable U+202F, que les claviers et les traitements de texte posent entre les
     * groupes d'un numéro de téléphone français. `\s` de Java ne les contient PAS — la
     * forme d'origine les manquait déjà.
     *
     * ⚠️ **Ce que cette règle NE voit PAS, et c'est assumé.** Exiger un séparateur UNIQUE
     * entre deux chiffres abandonne trois écritures réelles : « 08 - 99 - 12 - 34 - 56 »
     * (espace + tiret + espace), le retour à la ligne au milieu d'un numéro, et la
     * tabulation. Un attaquant qui connaît la règle neutralise l'heuristique 3 en écrivant
     * son numéro avec `" - "`. Les réintroduire ramènerait mécaniquement le faux positif
     * « résultat du match, 32 - 11 » → `3211`, un numéro court surtaxé français : la même
     * forme, au caractère près, sert les deux cas. Arbitré le 2026-09-17 selon la doctrine
     * de l'en-tête — mieux vaut rater une arnaque que coller un bandeau rouge sur un SMS
     * légitime. L'heuristique 3 n'est pas la seule, et il en faut deux pour afficher quoi
     * que ce soit.
     */
    private val SEPARATEUR_DE_NUMERO = Regex("""(?<=\d)[\p{Zs}.\-](?=\d)""")

    private fun containsPremiumNumber(body: String): Boolean {
        // Les frontières (?<!\d)/(?!\d) des motifs gèrent les caractères adjacents non
        // numériques (lettres incluses), ce que `\b` ne ferait pas après compaction.
        val compact = body.replace(SEPARATEUR_DE_NUMERO, "")
        return PREMIUM_PATTERNS.any { it.containsMatchIn(compact) }
    }

    // ──────────────── Heuristique 4 : typosquatting de domaines officiels ────────────────

    /**
     * Domaines officiels souvent usurpés par les campagnes de smishing.
     * Pour chaque domaine officiel, on accepte le domaine exact ; on
     * flagge tout host qui RESSEMBLE à un officiel (distance Levenshtein
     * ≤ 2 sur la partie sans TLD) sans être strictement égal.
     *
     * **Pourquoi TOUS les pays sont vérifiés à la fois, et pas celui de l'utilisateur.**
     * Ce n'est pas la même question que pour les numéros d'urgence, où il FAUT choisir un
     * pays parce qu'on compose un seul numéro. Ici on ne compose rien : on reconnaît des
     * motifs, et vérifier une liste de plus ne coûte rien d'autre qu'un risque de faux
     * positif. Choisir un pays serait même nuisible — un Français en déplacement en
     * Allemagne reçoit encore des arnaques françaises, et perdrait sa propre protection.
     *
     * **Ce qui manque est aussi délibéré que ce qui est là.** `telekom.de` et `anpost.com`
     * ont été écrits puis retirés : leur nom est à une seule faute d'un autre nom bien
     * réel — `telecom`, `inpost` — et les garder aurait signalé des SMS légitimes. Ce
     * fichier préfère explicitement un faux NÉGATIF à un faux POSITIF (voir l'en-tête) :
     * un bandeau rouge sur le SMS d'une vraie banque coûte plus cher qu'une arnaque ratée.
     *
     * À l'inverse, `inps.it`, `bbva.es`, `dhl.de` et `hmrc.gov.uk` — les cibles les plus
     * usurpées de leur pays — ont pu ENTRER grâce à [distanceToleree], qui resserre la
     * tolérance sur les noms courts. Ils étaient exclus tant que la distance valait 2.
     *
     * Exemples qui devraient trigger :
     *  - `1mpots.gouv.fr` (1 au lieu de i)
     *  - `amel1.fr` (1 au lieu de i, sur `ameli`)
     *  - `colissimo-track.fr` (faux site colissimo)
     *
     * ⚠️ Cette liste portait `arnel1.fr` depuis l'origine, donné pour un « lookalike
     * ameli ». Sa distance à `ameli` vaut **3** : il n'a JAMAIS été détecté, à aucune
     * tolérance, ni avant ni après le resserrement. Corrigé le 2026-09-17 — un KDoc qui
     * annonce une propriété de sécurité que le code n'a pas est pire que pas de KDoc.
     *
     * Exemples qui ne doivent PAS trigger :
     *  - `impots.gouv.fr` (domaine exact officiel)
     *  - `ameli.fr` (domaine exact officiel)
     */
    private val DOMAINES_OFFICIELS = setOf(
        // ───────────────────────────────── FRANCE ─────────────────────────────────
        "impots.gouv.fr", "ameli.fr", "laposte.fr", "colissimo.fr",
        "chronopost.fr", "ants.gouv.fr", "service-public.fr",
        "paypal.fr", "paypal.com",
        "amazon.fr", "amazon.com",
        "edf.fr", "engie.fr",
        "orange.fr", "sfr.fr", "free.fr", "bouyguestelecom.fr",
        "crediagricole.fr", "creditagricole.fr", "bnpparibas.fr", "societegenerale.fr",
        "labanquepostale.fr", "caisse-epargne.fr", "cic.fr", "lcl.fr", "boursorama.com",
        // ──────────────────────────────── ALLEMAGNE ───────────────────────────────
        // v1.28.12 — ajoutés avec les trois langues.
        //
        // ⚠️ `telekom.de` a été écrit ici puis RETIRÉ : `telekom` est à une faute de
        // `telecom`, et un SMS légitime citant `telecom.it` aurait été signalé.
        "elster.de", "deutschepost.de", "sparkasse.de", "commerzbank.de",
        "postbank.de", "volksbank.de", "bahn.de", "dhl.de",
        // ───────────────────────────────── ITALIE ─────────────────────────────────
        // `poste.it` est le domaine réellement usurpé, bien plus que posteitaliane.it ;
        // `inps.it` est la première cible du pays. Les deux ne tiennent que grâce à la
        // tolérance dépendant de la longueur — voir [distanceToleree].
        "agenziaentrate.gov.it", "poste.it", "posteitaliane.it", "inps.it",
        "intesasanpaolo.com", "unicredit.it", "enel.it",
        // ───────────────────────────────── ESPAGNE ────────────────────────────────
        "agenciatributaria.gob.es", "seg-social.es", "correos.es",
        "santander.es", "caixabank.es", "iberdrola.es", "endesa.es", "bbva.es",
        // ────────────────────────── ROYAUME-UNI ET IRLANDE ────────────────────────
        // L'anglais est la langue SOURCE de l'application et n'avait aucune liste.
        //
        // ⚠️ `anpost.com` (la poste irlandaise) est volontairement absent : `anpost` est
        // à une faute d'`inpost`, un service de casiers à colis bien réel et répandu.
        "hmrc.gov.uk", "dvla.gov.uk", "royalmail.com", "postoffice.co.uk", "evri.com",
        "revenue.ie", "eflow.ie",
    )

    /**
     * Liste des "labels racines" (partie sans TLD) pour la comparaison
     * Levenshtein. On ne compare PAS le domaine complet (sinon `.gouv.fr`
     * crée des matches parasites) mais seulement le label le plus significatif.
     * Ex: `impots.gouv.fr` → "impots", `paypal.fr` → "paypal".
     */
    private val LABELS_OFFICIELS = DOMAINES_OFFICIELS.map { host ->
        host.substringBefore(".")
    }.toSet()

    /**
     * Les noms officiels qui sont AUSSI des mots ordinaires, ou qui appartiennent à des
     * opérateurs dont les vrais domaines portent un tiret. Exclus de [porteLeNomOfficiel]
     * SEULEMENT — la faute de frappe ([levenshteinAtMost]) et le nom exact sur un domaine
     * de tête à bas coût continuent de les protéger, donc `0range.fr` et `poste.top` sont
     * toujours vus.
     *
     * `free` est un mot anglais courant, `orange` une couleur, `bahn` un chemin de fer,
     * `ants` des fourmis, `poste` un poste ou un bureau de poste, `amazon` un fleuve.
     * `free-mobile.fr` et `orange-pro.fr` sont en outre de VRAIS domaines de ces
     * opérateurs. Fermer cette règle sur eux coûte quelques vraies détections
     * (`secure-orange.fr` n'est plus vu) et c'est le bon sens du compromis : voir
     * l'en-tête du fichier, un bandeau rouge sur un SMS légitime coûte plus cher.
     */
    private val LABELS_TROP_COMMUNS = setOf("free", "orange", "poste", "amazon", "bahn", "ants")

    /**
     * Les domaines de tête à bas coût, ceux que les campagnes achètent par milliers.
     *
     * Ils servent à trancher un cas que ni Levenshtein ni la concaténation ne voyaient :
     * `paypal.top`, `hmrc.help`, `poste.support` portent le nom officiel **exact**, donc
     * une distance de zéro — et la garde « le nom doit être DIFFÉRENT du label » les
     * écartait explicitement. Relevé par une relecture externe le 2026-09-17.
     *
     * La liste est volontairement courte et ne contient AUCUN domaine de pays : signaler
     * le nom exact sur n'importe quel autre TLD ferait rougir `amazon.co.uk` ou
     * `paypal.be`, parfaitement légitimes et simplement absents de notre liste.
     */
    private val TLD_A_RISQUE = setOf(
        "top", "xyz", "click", "link", "help", "support", "live", "online", "site",
        "icu", "cyou", "rest", "quest", "monster", "sbs",
    )

    private fun containsTyposquattedDomain(lowerBody: String): Boolean {
        // On scanne TOUS les tokens domain-like (avec OU sans scheme) — un
        // smishing FR met souvent juste "paypa1.fr/update" en clair, sans
        // http:// devant. URL_REGEX seul raterait ce cas. Cap MAX_DOMAIN_MATCHES
        // (audit S4) pour borner le pire cas Levenshtein × matches sur low-end.
        DOMAIN_LIKE_REGEX.findAll(lowerBody).take(MAX_DOMAIN_MATCHES).forEach { match ->
            val host = match.groupValues[1].lowercase()
            if (DOMAINES_OFFICIELS.contains(host)) return@forEach // domaine exact = légitime
            // Skip tokens qui ne ressemblent pas à un host plausible (au moins
            // 1 point, donc DOMAIN_LIKE_REGEX déjà filtre, mais on cap les
            // sous-domaines pour ne comparer que le label principal).
            // TOUS les labels de l'hôte, pas seulement le premier : `www.paypa1.fr` et
            // `secure.paypa1.fr` échappaient entièrement à la détection, alors que le `www.`
            // est la forme la plus courante d'une URL. Relevé le 2026-09-17.
            val tousLesLabels = host.split(".")
            // Le nom EXACT sur un domaine de tête à bas coût : `paypal.top`, `hmrc.help`,
            // `dhl.top`. Ce n'est pas une faute de frappe, donc Levenshtein ne le voyait
            // pas — et la garde `official != label` l'écartait explicitement.
            //
            // ⚠️ Cette règle est une ÉGALITÉ, pas une comparaison floue. Le filtre de
            // longueur ci-dessous n'existe que pour borner Levenshtein, et il était posé
            // DEVANT elle : les cinq noms officiels de trois lettres — `dhl`, `cic`, `lcl`,
            // `edf`, `sfr` — étaient donc invisibles sur un TLD jetable, alors qu'un nom
            // exact ne peut par construction pas créer de faux positif. Relevé par un audit
            // de sécurité le 2026-09-17 : même motif qu'ailleurs dans ce fichier, une garde
            // écrite pour une règle et laissée devant sa voisine.
            if (host.substringAfterLast(".") in TLD_A_RISQUE &&
                tousLesLabels.any { it in LABELS_OFFICIELS }
            ) {
                return true
            }
            val labels = tousLesLabels.filter { it.length >= 4 }
            val suspicious = labels.any { label ->
                LABELS_OFFICIELS.any { official ->
                    if (official.length < 4 || official == label) return@any false
                    // Deux formes d'usurpation, la seconde échappant à la première :
                    // la faute de frappe (`paypa1`), et le nom exact augmenté
                    // (`inps-sicurezza`).
                    val faute = levenshteinAtMost(label, official, distanceToleree(official))
                    faute || porteLeNomOfficiel(label, official)
                }
            }
            if (suspicious) return true
        }
        return false
    }

    /**
     * La tolérance dépend de la LONGUEUR du nom officiel, et c'est ce qui permet d'y
     * mettre les cibles les plus usurpées.
     *
     * À distance 2, un nom de quatre lettres attrape des mots ordinaires : `inps` contre
     * `info`, `hmrc` contre `here`. C'est ce qui obligeait à écarter l'INPS italien et le
     * fisc britannique — précisément les deux organismes les plus imités de leur pays.
     * À distance 1, ces collisions disparaissent, et les usurpations réelles de ces noms
     * COURTS passent toujours : `1nps` contre `inps`, `amel1` contre `ameli`, `p0ste`
     * contre `poste`, `bbv4` contre `bbva` sont toutes à distance 1.
     *
     * ⚠️ La justification écrite ici citait `1mpots`, `paypa1`, `e1ster` et `correros`.
     * Ces quatre noms officiels font plus de cinq lettres : leur tolérance reste à 2, ils
     * ne sont pas concernés par ce seuil. L'argument ne portait donc pas sur les cas qu'il
     * changeait. Corrigé le 2026-09-17.
     *
     * Ce que le resserrement fait PERDRE, mesuré : les usurpations de noms courts à
     * distance 2 — `arneli` contre `ameli`, `p0st3` contre `poste`, `1nqs` contre `inps`,
     * `hrnrc` contre `hmrc`. C'est le prix assumé pour faire ENTRER `inps`, `hmrc`, `dhl`
     * et `bbva` dans la liste, qui en étaient exclus tant que la tolérance valait 2.
     */
    // Publique comme `levenshteinAtMost` ci-dessous, et pour la meme raison : les
    // tests vivent dans le module `app`, ou `internal` du module `domain` n'est pas
    // visible. Une propriete de securite qu'on ne peut pas tester n'en est pas une.
    fun distanceToleree(nomOfficiel: String): Int =
        if (nomOfficiel.length <= 5) 1 else 2

    /**
     * L'autre forme d'usurpation, que la distance de Levenshtein ne voit PAS.
     *
     * `inps-sicurezza.com` n'est pas une faute de frappe sur `inps` : c'est le nom exact,
     * augmenté d'un mot. La distance entre `inps` et `inps-sicurezza` vaut dix, donc
     * l'heuristique précédente ne voyait rien du tout. Signalé par une relecture externe
     * le 2026-09-17, et c'est la forme que les campagnes emploient aujourd'hui.
     *
     * On exige le nom officiel en PREMIER segment. Chercher une simple sous-chaîne
     * signalerait `mon-correos-perso` mais aussi n'importe quel mot qui contient le nom
     * par hasard ; exiger un segment délimité rend la règle plus précise : `mi-correo.es`
     * (courrier, en espagnol) n'est pas touché, parce que son premier segment vaut `mi`.
     *
     * ⚠️ La première écriture acceptait le nom officiel à N'IMPORTE QUELLE position
     * (`split('-').any { … }`), et c'était un faux positif plus large que les six que ce
     * commit corrigeait : neuf des noms officiels sont aussi des mots ordinaires, à
     * commencer par `free`. « Última oportunidad: 50% en todo el duty-free.es » réunissait
     * alors DEUX heuristiques — `duty-free` lu comme une usurpation de `free.fr`, et
     * « última oportunidad » — et affichait un bandeau rouge sur un SMS commercial
     * parfaitement légitime. Relevé par un audit de sécurité le 2026-09-17.
     *
     * Deux gardes, pas une, parce qu'une seule ne suffit pas :
     *  - le nom officiel en PREMIER ferme `duty-free`, `tax-free`, `auto-bahn`,
     *    `la-poste`, `shop-amazon` — c'est aussi la forme que prennent réellement les
     *    campagnes (`inps-sicurezza`, `hmrc-refund`, `correos-es`) ;
     *  - [LABELS_TROP_COMMUNS] ferme l'autre sens, `free-mobile.fr` et `orange-pro.fr`,
     *    qui sont les VRAIS domaines de ces opérateurs et resteraient signalés sinon.
     */
    fun porteLeNomOfficiel(label: String, nomOfficiel: String): Boolean =
        nomOfficiel !in LABELS_TROP_COMMUNS &&
            label.contains('-') &&
            label.substringBefore('-') == nomOfficiel

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
