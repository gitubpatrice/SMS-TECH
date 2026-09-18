package com.filestech.sms.domain.safetycall

/**
 * v1.9.0 — Le CHOIX de modèle de SMS pour le Safety call. Le texte, lui, vit ailleurs.
 *
 * 3 modèles prédéfinis + une option CUSTOM (saisie libre par l'utilisateur,
 * cf. [SafetyCallConfig.customMessage]).
 *
 * **v1.28.12 — les textes sont sortis d'ici, et pourquoi.** Ce KDoc portait la raison de les y
 * avoir mis : *« les SMS sortants doivent rester déterministes et débuguables ; les templates
 * restent en français (cible portfolio FR) »*. C'était une décision assumée, et sa prémisse a
 * expiré : l'application est traduite. Le défaut qu'elle laissait n'était pas théorique — un
 * utilisateur **anglophone** de la version publiée envoyait déjà « URGENCE - j'ai besoin d'aide »
 * à des proches anglophones, et personne ne l'avait vu, parce qu'aucun contrôle ne regarde des
 * chaînes absentes de `strings.xml`.
 *
 * Le déterminisme, lui, est conservé : le texte vient de ressources figées dans l'APK, pas d'une
 * saisie ni du réseau, et il est rendu par une seule implémentation
 * ([com.filestech.sms.domain.safety.SafetyMessageTexts]) que l'aperçu ET l'envoi appellent —
 * précisément pour ne pas recréer deux chemins jumeaux qui divergent.
 *
 * Le placeholder `[DURÉE]` reste reconnu tel quel dans un message CUSTOM : il est déjà écrit dans
 * les messages enregistrés des utilisateurs actuels, et le renommer les casserait en silence.
 * `[DURATION]` en est un alias pour qui ne lit pas le français.
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

    companion object {
        /**
         * Les deux jetons qu'un message CUSTOM peut porter pour y voir apparaître la durée.
         *
         * `[DURÉE]` est le jeton historique : il est déjà écrit dans les messages enregistrés des
         * utilisateurs actuels, et le retirer les casserait sans rien dire. `[DURATION]` en est
         * l'alias, pour qui n'écrit pas en français.
         */
        val JETONS_DE_DUREE: List<String> = listOf("[DURÉE]", "[DURATION]")

        /**
         * v1.9.0 audit fix SEC-5 — le re-cap du message personnalisé, au rendu.
         *
         * Le ViewModel cape déjà à l'enregistrement, mais un DataStore restauré depuis une
         * sauvegarde tierce peut contenir un message plus long, qui ferait partir un SMS
         * multi-segment — une facturation surprise pour quelqu'un qui n'a rien demandé.
         */
        fun capCustom(customMessage: String): String =
            customMessage.take(SafetyCallConfig.MAX_CUSTOM_MESSAGE_LENGTH).ifBlank { "" }

        /** Remplace tous les jetons de durée reconnus par [label]. */
        fun injecterDuree(texte: String, label: String): String =
            JETONS_DE_DUREE.fold(texte) { acc, jeton -> acc.replace(jeton, label) }

        /**
         * v1.27.2 — le nombre de minutes ecoulees qu'annonce la relance numero [index].
         *
         * Le delai annonce doit correspondre au delai REEL : quelqu'un decide d'agir ou non
         * sur sa foi. Il se calcule ici, une fois ; le texte qui l'entoure vient des
         * ressources, cf. [com.filestech.sms.domain.safety.SafetyMessageTexts].
         */
        fun minutesDeRelance(index: Int): Long =
            index * (SafetyCallConfig.RELANCE_INTERVAL_MS / 60_000L)
    }
}
