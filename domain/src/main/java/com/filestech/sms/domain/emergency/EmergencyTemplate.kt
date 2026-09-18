package com.filestech.sms.domain.emergency

/**
 * v1.10.0 — Le CHOIX de message pour le Mode urgence. Le texte, lui, vit ailleurs.
 *
 * **v1.28.12 — les trois corps de SMS sont sortis d'ici.** Ils étaient écrits en français, en
 * dur, et partaient donc en français quelle que soit la langue de l'application : un utilisateur
 * anglophone alertait ses proches par « ⚠️ URGENCE - j'ai besoin d'aide ». Le texte est désormais
 * dans `app/src/main/res/values-xx/strings.xml` et rendu par
 * [com.filestech.sms.domain.safety.SafetyMessageTexts], que l'aperçu ET l'envoi appellent.
 *
 * 3 modèles fixes (pas de CUSTOM — l'urgence doit être cadrée, pas une fenêtre de saisie libre).
 *
 * **Les contraintes de rédaction restent, et valent dans CHAQUE langue** :
 *  - un SMS d'urgence doit tenir court : 160 caractères en GSM-7, 70 en UCS-2 ;
 *  - l'URL Maps (`https://maps.google.com/?q=LAT,LON`, universelle, sans Play Services) compte
 *    dans ce budget ;
 *  - pas de tiret cadratin U+2014, qui bascule tout le message en UCS-2 ;
 *  - quand la position manque (permission refusée, GPS coupé, délai dépassé), une mention
 *    explicite la remplace, pour que le destinataire sache que c'est voulu et non un défaut.
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
    NEED_HELP,

    /** Danger imminent / agression / accident. Plus pressant. */
    DANGER,

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
    DISCREET,
}
