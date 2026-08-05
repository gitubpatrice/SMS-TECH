package com.filestech.sms.domain.safetycall

import java.util.concurrent.TimeUnit

/**
 * v1.9.0 — Templates de SMS pour le safety call.
 *
 * 3 templates prédéfinis FR + une option CUSTOM (saisie libre par
 * l'utilisateur, cf. [SafetyCallConfig.customMessage]).
 *
 * Les templates contiennent un placeholder `[DURÉE]` qui est remplacé par
 * la durée effective au moment du rendu (ex: "48 heures", "3 jours").
 *
 * **Pourquoi en dur dans le code et pas en l10n ?** Les SMS sortants
 * doivent rester déterministes et débuguables (logs, tests de garde-
 * régression). L'utilisateur qui veut un wording personnalisé utilise
 * l'option CUSTOM. Les templates restent en français (cible portfolio FR).
 *
 * Sécurité : tous les templates tiennent dans 1 segment SMS UCS-2 (cap 70
 * chars accents inclus) sauf "VERIFY" qui peut dépasser et basculer en
 * 2 segments — accepté car le contenu reste cohérent.
 */
enum class SafetyCallTemplate {
    /**
     * Template "Vérification" — neutre, pas alarmiste. Convient pour la
     * majorité des cas : voyageurs, personnes seules, randonneurs.
     */
    CHECK_IN,

    /**
     * Template "Urgence proche" — plus pressant, demande explicite
     * d'appeler. Pour contacts d'urgence familiaux qui DOIVENT réagir
     * rapidement.
     */
    URGENT,

    /**
     * Template "Suivi" — variant insistant sur le suivi régulier
     * (personnes âgées vivant seules, soins post-opératoires).
     */
    FOLLOW_UP,

    /**
     * Template "Personnalisé" — utilise [SafetyCallConfig.customMessage]
     * comme texte brut (cap 140 chars). Placeholder `[DURÉE]` est aussi
     * remplacé dans le texte custom.
     */
    CUSTOM;

    /**
     * Rend le template en texte SMS final, avec `[DURÉE]` remplacé.
     * Pour [CUSTOM], utilise [customMessage] passé en argument.
     */
    fun render(timeoutMs: Long, customMessage: String = ""): String {
        val durationLabel = formatDuration(timeoutMs)
        val raw = when (this) {
            CHECK_IN -> "Bonjour. Je n'ai pas accédé à mon téléphone depuis [DURÉE]. Pourrais-tu m'appeler ou passer pour vérifier que tout va bien ? Merci."
            URGENT -> "Si tu reçois ce SMS, c'est que je n'ai pas pu utiliser mon téléphone depuis [DURÉE]. Afin d'être certain que tout va bien, appelle-moi STP."
            FOLLOW_UP -> "Message automatique : pas d'activité sur mon téléphone depuis [DURÉE]. Pourrais-tu m'appeler pour t'assurer que tout va bien ? Merci."
            // v1.9.0 audit fix SEC-5 — re-cap au render. Le ViewModel cap
            // déjà au save, mais un DataStore restauré depuis backup tiers
            // peut contenir un message > MAX_CUSTOM_MESSAGE_LENGTH qui
            // ferait partir un SMS multi-segment (facturation surprise).
            CUSTOM -> customMessage.take(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH).ifBlank { "" }
        }
        return raw.replace("[DURÉE]", durationLabel)
    }

    companion object {
        /**
         * v1.27.2 — texte de la **relance** numéro [index] (1 à
         * [SafetyCallConfig.RELANCE_COUNT]).
         *
         * Indépendant du [SafetyCallTemplate] choisi : la relance est un suivi de ce qui est déjà
         * parti, quel qu'il soit — y compris un message personnalisé. Répéter le texte initial mot
         * pour mot aurait ressemblé à un défaut de l'application, pas à une insistance.
         *
         * La progression est explicite — le temps écoulé, puis une consigne qui monte — et **la
         * dernière relance annonce qu'elle est la dernière**. Sans cela, un contact pourrait
         * attendre une suite qui ne viendra jamais au lieu d'agir.
         *
         * v1.27.2 (relecture Gemini du 2026-08-05) — trois défauts de rédaction corrigés :
         *
         *  1. **Chaque texte est désormais autonome.** « Toujours aucun signe de ma part » et
         *     « Deuxième relance » présumaient que le contact avait reçu et lu le message initial.
         *     S'il ne l'a pas reçu — réseau, ou processus tué pendant l'envoi — la relance était
         *     incompréhensible. On parle maintenant d'**absence d'activité**, un fait qui se
         *     suffit à lui-même.
         *  2. **« sans réponse de ma part » est faux** : le contact n'a posé aucune question. Ce
         *     qui manque, c'est de l'activité sur le téléphone, pas une réponse.
         *  3. **L'application est nommée.** Un SMS reçu en pleine nuit disant « vérifie que je
         *     vais bien », sans émetteur identifiable, ressemble à du hameçonnage — et se fait
         *     ignorer.
         *
         * Chaque texte tient dans deux segments SMS UCS-2 (70 caractères accentués par segment),
         * comme les modèles ci-dessus.
         */
        fun renderRelance(index: Int): String {
            val minutes = index * (SafetyCallConfig.RELANCE_INTERVAL_MS / 60_000L)
            return when (index) {
                1 ->
                    "Alerte automatique SMS Tech : toujours aucune activité sur mon téléphone, " +
                        "$minutes minutes plus tard. Merci d'essayer de me joindre."
                2 ->
                    "Alerte automatique SMS Tech : $minutes minutes sans aucune activité de ma " +
                        "part. Si tu ne parviens pas à me joindre, préviens un proche."
                else ->
                    "Dernière alerte automatique SMS Tech : $minutes minutes sans activité. " +
                        "Plus aucun message ne suivra. Merci de vérifier que je vais bien."
            }
        }

        /**
         * Convertit une durée en label français lisible :
         *  - 24 h ou multiple entier d'heures → "X heures"
         *  - Multiple entier de jours → "X jour(s)"
         *  - Sinon (fractions) → arrondi à l'heure : "X heures"
         */
        fun formatDuration(timeoutMs: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(timeoutMs)
            if (hours <= 0L) return "moins d'une heure"
            // Si multiple entier de 24, on bascule en "X jour(s)".
            if (hours % 24L == 0L) {
                val days = hours / 24L
                return if (days == 1L) "1 jour" else "$days jours"
            }
            return if (hours == 1L) "1 heure" else "$hours heures"
        }
    }
}
