package com.filestech.sms.domain.emergency

import android.os.SystemClock

/**
 * v1.10.0 — Mode urgence : SMS d'urgence ACTIF déclenché par l'user via un
 * bouton hold 3 secondes dans l'écran dédié. Distinct du Safety call (qui
 * est passif / déclenché par timer d'inactivité).
 *
 * **Cas d'usage** : situation où l'user est CONSCIENT et capable d'agir
 * mais en danger ou en difficulté — agression, accident, malaise. L'appui
 * long sur le bouton URGENCE envoie un SMS à 1-4 contacts d'urgence (les
 * mêmes que le Safety call, cohérence) contenant un message + sa géoloc.
 *
 * **Opt-in strict** : `enabled = false` par défaut. Tant que désactivé,
 * aucun bouton ne s'affiche nulle part dans l'app.
 *
 * **Contacts** : réutilise [com.filestech.sms.domain.safetycall.SafetyCallContact]
 * (clé DataStore `security.safetyCall.contactsJson`). Pas de liste séparée
 * pour éviter la duplication et garder une UX simple. Si l'user n'a pas
 * de contacts Safety Call configurés, le bouton est grisé avec un lien
 * vers le setup.
 *
 * **Géolocalisation** :
 *  - Si [includeLocation] = true ET permission `ACCESS_FINE_LOCATION` accordée :
 *    fix unique via `LocationManager` (GPS + NETWORK), timeout 8s, fallback
 *    `lastKnownLocation` capé à 5 min. URL `https://maps.google.com/?q=LAT,LON`.
 *  - Sinon : SMS envoyé sans coordonnées + snackbar warning à l'user.
 *  - Pas de tracking continu — un seul fix, puis fin.
 *
 * **Anti-spam** : [lastTriggeredAt] est utilisé par l'UI pour griser le
 * bouton pendant 60s post-trigger. Évite les double-taps paniqués. Mais
 * la feature reste ACTIVABLE (pas auto-disable comme le Safety call qui
 * est un événement unique) — l'urgence est répétable si le user en a
 * besoin (suite d'incidents, mise à jour de position…).
 *
 * **Garde panic-decoy** : si l'app est en session
 * [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy], le
 * trigger est refusé (ne pas révéler les contacts à un agresseur).
 */
data class EmergencyConfig(
    /** `false` par défaut. Si false, le bouton URGENCE n'apparaît nulle part. */
    val enabled: Boolean = false,
    /** Template du message envoyé. */
    val template: EmergencyTemplate = EmergencyTemplate.NEED_HELP,
    /**
     * Si `true`, tente de récupérer la position GPS et l'inclut dans le SMS
     * sous forme d'URL Maps. Si `false`, le SMS est envoyé sans coordonnées
     * (même si la permission est accordée). Permet aux user qui ne veulent
     * pas partager leur position d'utiliser quand même le bouton.
     */
    val includeLocation: Boolean = true,
    /**
     * Timestamp epoch ms du dernier trigger réussi. `0L` = jamais déclenché.
     * Utilisé exclusivement par l'UI pour griser le bouton pendant
     * [ANTI_SPAM_WINDOW_MS] post-trigger.
     */
    val lastTriggeredAt: Long = 0L,
    /**
     * v1.10.0 audit S2 — snapshot `SystemClock.elapsedRealtime()` au moment
     * du dernier trigger. Couple wall+mono identique à SEC-11 SafetyCall.
     * Empêche un attaquant root d'avancer la wall-clock de +61s pour
     * neutraliser le cooldown anti-spam et envoyer un 2e SMS d'urgence
     * immédiatement (en situation d'agression où l'attaquant contrôle le
     * device). `0L` = jamais déclenché ou config héritée v1.10.0-pre.
     */
    val monotonicLastTriggeredAt: Long = 0L,
) {
    /**
     * Retourne `true` si le bouton doit être grisé en raison de l'anti-spam
     * post-trigger. Cohérent avec SEC-11 : exige que LES DEUX clocks soient
     * encore dans la fenêtre. Si une seule est dans la fenêtre, on
     * considère qu'on est encore en cooldown (overestime le cooldown vs.
     * un attaquant qui cherche à bypass, déféfensif).
     */
    fun isInAntiSpamWindow(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (lastTriggeredAt == 0L) return false
        val wallInWindow = (nowMs - lastTriggeredAt) < ANTI_SPAM_WINDOW_MS
        if (monotonicLastTriggeredAt == 0L) {
            // Config v1.10.0 pré-fix S2 : on retombe sur le seul check
            // wall-clock par compat ascendante (le premier trigger
            // post-upgrade posera la valeur monotonique).
            return wallInWindow
        }
        // v1.10.0 audit SEC-4 — fail-safe sur underflow Long. Post-reboot
        // avant drift recovery async (MainApplication.onCreate), nowMono
        // est petit (uptime) et monotonicLastTriggeredAt grand (valeur
        // pré-reboot persistée). Le delta est NÉGATIF, ce qui en Long
        // signé reste négatif → `< ANTI_SPAM_WINDOW_MS` retournerait true
        // par sémantique normale, mais on l'explicite : tout delta négatif
        // = "cooldown actif" (fail-safe contre tentative root reboot+clock
        // forward avant drift recovery).
        val monoDelta = nowMonoMs - monotonicLastTriggeredAt
        val monoInWindow = monoDelta < 0L || monoDelta < ANTI_SPAM_WINDOW_MS
        // Cooldown actif si AU MOINS UNE clock dit "encore en fenêtre".
        // Un attaquant qui avance la wall ne fait pas sortir la mono.
        return wallInWindow || monoInWindow
    }

    companion object {
        /** Fenêtre anti-double-tap post-trigger (60 secondes). */
        const val ANTI_SPAM_WINDOW_MS: Long = 60_000L
    }
}
