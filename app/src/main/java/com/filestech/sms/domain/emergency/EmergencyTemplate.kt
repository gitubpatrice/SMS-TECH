package com.filestech.sms.domain.emergency

/**
 * v1.10.0 — Templates de message SMS pour le Mode urgence.
 *
 * Le placeholder `[LOC]` est remplacé par une URL Google Maps cliquable du
 * format `https://maps.google.com/?q=LAT,LON` (URL universelle, ne dépend
 * PAS de Google Play Services — fonctionne sur F-Droid). Si la géoloc n'est
 * pas disponible (permission refusée, GPS off, timeout), `[LOC]` est
 * remplacé par une mention explicite "(position non disponible)".
 *
 * Volontairement court : un SMS d'urgence doit tenir en 1 segment GSM-7
 * (160 chars) ou UCS-2 (70 chars) si caractères spéciaux. Le template
 * + l'URL Maps doivent rester sous 160 chars pour la fiabilité d'envoi.
 *
 * 3 templates fixes (pas de CUSTOM pour le moment — l'urgence doit être
 * cadrée, pas une fenêtre de saisie libre). L'user pourra demander un
 * CUSTOM en v1.11 si besoin justifié.
 */
enum class EmergencyTemplate {
    /**
     * Aide générale, situation inconfortable.
     *
     * v1.10.0 audit SEC-5 — `-` ASCII (et non `—` U+2014) pour rester en
     * charset GSM-7 et tenir en 1 seul segment SMS (160 chars). Un em dash
     * forçait UCS-2 (70 chars/segment) → multi-segment → risque que le 2e
     * PDU soit perdu en zone radio faible (situation typique d'urgence).
     */
    NEED_HELP {
        override fun renderBody(locationUrl: String?): String =
            // v1.14.5 — emoji `⚠️` (U+26A0 + U+FE0F variation selector) en
            // tête pour que la notif SMS côté destinataire affiche
            // visiblement le caractère d'urgence dans le preview heads-up.
            // Trade-off accepté : l'emoji force UCS-2 (70 chars/segment au
            // lieu de 160 GSM-7) → potentiel multi-segment, mais opérateurs
            // FR 2026 fiables sur multi-segment. La visibilité prime sur la
            // robustesse marginale d'un 1-segment sans emoji.
            "⚠️ URGENCE - j'ai besoin d'aide. Ma position : ${locOrFallback(locationUrl)}"
    },

    /** Danger imminent / agression / accident. Plus pressant. */
    DANGER {
        override fun renderBody(locationUrl: String?): String =
            // v1.14.5 — emoji `⚠️` en tête, cf. KDoc NEED_HELP pour rationale.
            "⚠️ DANGER - situation critique, contacte-moi ou viens. Position : ${locOrFallback(locationUrl)}"
    },

    /**
     * Variante neutre, moins anxiogène, pour signaler malaise sans alarmer.
     * v1.10.0 audit SEC-5 — uniquement chars GSM-7 (Ù U+00D9 hors-charset,
     * remplacé par "Position :" qui ne perd pas le sens).
     *
     * v1.14.5 — **PAS d'emoji ⚠️** ajouté ici : la variante DISCREET est
     * volontairement neutre. Un triangle d'alerte rouge défait le but
     * (signaler un malaise sans alarmer / éviter de révéler la situation
     * d'urgence à un agresseur lookant l'écran). Reste 1-segment GSM-7.
     */
    DISCREET {
        override fun renderBody(locationUrl: String?): String =
            "Peux-tu m'appeler ? J'ai besoin d'aide. Position : ${locOrFallback(locationUrl)}"
    };

    /** Rend le SMS final. [locationUrl] = URL Maps complète OU null. */
    abstract fun renderBody(locationUrl: String?): String

    companion object {
        /**
         * Fallback inséré quand [locationUrl] est `null` (permission refusée,
         * GPS off, timeout 8s atteint). Mention courte et sans ambiguïté
         * pour que le destinataire sache que l'absence d'URL est volontaire,
         * pas un bug.
         */
        const val LOCATION_FALLBACK = "(position non disponible)"

        internal fun locOrFallback(locationUrl: String?): String =
            locationUrl?.takeIf { it.isNotBlank() } ?: LOCATION_FALLBACK
    }
}
