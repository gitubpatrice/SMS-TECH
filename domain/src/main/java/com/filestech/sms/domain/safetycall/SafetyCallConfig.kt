package com.filestech.sms.domain.safetycall

import android.os.SystemClock

/**
 * v1.9.0 — Safety call : envoie automatiquement un SMS prédéfini à 1-4
 * contacts d'urgence si l'utilisateur n'a pas ouvert SMS Tech (ni utilisé le
 * bouton "Je vais bien") pendant une durée configurée.
 *
 * Cas d'usage : personnes seules, voyageurs solo, personnes âgées vivant
 * indépendamment, randonneurs, professions à risque. Sécurité personnelle —
 * **pas** un anti-vol (cf. mode panic pour ce cas).
 *
 * **Opt-in strict** : `enabled = false` par défaut. Tant que désactivé, le
 * `SafetyCallWorker` n'est pas schedulé et aucun timer ne court.
 *
 * **Reset du timer** : à chaque ouverture de SMS Tech (`MainActivity.onResume`)
 * ET via le bouton dédié "Je vais bien" dans Settings → Sécurité.
 *
 * **Notification de pré-trigger** : 6h avant l'expiration du timer, SMS Tech
 * pose une notification persistante (canal HIGH) : "Tu n'as pas ouvert SMS
 * Tech depuis [X]. Confirme que tu vas bien." Tap notif = reset timer.
 *
 * **Trigger** : si `lastActivityAt + timeoutMs < now()` au moment du tick
 * worker (toutes les 60 min), envoi atomique du SMS via [SendSmsUseCase] à
 * tous les `contacts` avec le `template` rendu (placeholder `[DURÉE]`
 * remplacé par la valeur effective).
 *
 * **Fail-safe accepté (option A)** : si l'app est désinstallée, le worker
 * est tué par Android et le SMS n'est pas envoyé. C'est cohérent avec le
 * but du deadman (sécurité personnelle, pas anti-vol). Le mode panic gère
 * déjà le vol via wipe.
 *
 * **v1.10.0 SEC-11 — horloge monotone complémentaire** : [isExpired] et
 * [isInWarningWindow] comparent désormais à LA FOIS la wall-clock
 * ([lastActivityAt] vs `System.currentTimeMillis()`) ET la clock monotonic
 * ([monotonicLastActivityAt] vs `SystemClock.elapsedRealtime()`). Un
 * attaquant root capable d'avancer l'horloge OS ne peut plus déclencher
 * prématurément le SMS d'urgence : la clock monotonic n'est pas
 * manipulable sans root + redémarrage. Migration : une config v1.9.0 sans
 * `monotonicLastActivityAt` retourne `isExpired = false` jusqu'au premier
 * reset post-upgrade (filet de sécurité — pas de trigger surprise).
 */
data class SafetyCallConfig(
    /** `false` par défaut. Tant que `false`, aucun worker ni timer actif. */
    val enabled: Boolean = false,
    /**
     * Durée en millisecondes après laquelle le SMS est déclenché si pas
     * d'activité. Valeurs prédéfinies : 24h / 48h / 72h. Custom 1h-720h
     * via slider Setup. Cap absolu : 720h (30 jours) pour rester aligné
     * sur le cas d'usage "voyage solo de 1 mois" sans avoir à reset
     * manuellement le timer en cours de route.
     */
    val timeoutMs: Long = TIMEOUT_48H_MS,
    /**
     * Timestamp epoch ms de la dernière activité enregistrée (= dernier
     * reset). `0L` = pas encore initialisé (l'enable initial pose cette
     * valeur à `now()`).
     *
     * Modifié par :
     *  - [com.filestech.sms.MainActivity.onResume] (auto)
     *  - bouton "Je vais bien" dans Settings → Sécurité (manuel)
     *  - tap notif pré-trigger (depuis [SafetyCallWarningNotifier])
     */
    val lastActivityAt: Long = 0L,
    /**
     * v1.10.0 SEC-11 — Snapshot de `SystemClock.elapsedRealtime()` au moment
     * du même reset que [lastActivityAt]. `0L` = config v1.9.0 héritée ou
     * jamais initialisée. Cette valeur est NON manipulable depuis Settings
     * Android (ne peut être altérée que par root + reboot, ce qui la
     * réinitialise — cf. logique drift post-boot dans
     * [com.filestech.sms.MainApplication]).
     *
     * Modifié EN COUPLE avec [lastActivityAt] à chaque reset (cf. doc
     * ci-dessus). Toute écriture qui pose `lastActivityAt = now()` doit
     * AUSSI poser `monotonicLastActivityAt = SystemClock.elapsedRealtime()`.
     */
    val monotonicLastActivityAt: Long = 0L,
    /**
     * 1 à 4 contacts d'urgence. Plus de 4 = perte de pertinence (un
     * deadman doit cibler les proches qui vont VRAIMENT réagir). Liste
     * vide = config invalide, [enabled] est forcé à `false` au save.
     */
    val contacts: List<SafetyCallContact> = emptyList(),
    /** Template du SMS envoyé. */
    val template: SafetyCallTemplate = SafetyCallTemplate.CHECK_IN,
    /**
     * Si [template] = [SafetyCallTemplate.CUSTOM], texte saisi par l'user.
     * Max 140 chars pour rester dans 1 segment SMS GSM-7 (ou 70 UCS-2 si
     * accents). Ignoré pour les autres templates.
     */
    val customMessage: String = "",
) {
    /**
     * Retourne `true` quand le timer a expiré du point de vue WALL-CLOCK
     * ET MONOTONIC. Faux si [enabled] = false, si [lastActivityAt] = 0L
     * (jamais initialisé), ou si [monotonicLastActivityAt] = 0L (config
     * v1.9.0 sans monotonic — filet de sécurité post-upgrade).
     *
     * Pourquoi les deux : un attaquant root qui AVANCE la wall-clock OS
     * (`Settings.Global.AUTO_TIME=0` puis `date`) peut faire passer
     * `nowMs - lastActivityAt >= timeoutMs` immédiatement. Mais
     * `SystemClock.elapsedRealtime()` continue à compter le temps réel
     * écoulé depuis le boot, indépendamment de la wall-clock. Les deux
     * checks doivent matcher pour trigger.
     */
    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!enabled || lastActivityAt == 0L || monotonicLastActivityAt == 0L) return false
        val wallExpired = (nowMs - lastActivityAt) >= timeoutMs
        val monoExpired = (nowMonoMs - monotonicLastActivityAt) >= timeoutMs
        return wallExpired && monoExpired
    }

    /**
     * Retourne `true` quand le timer entre dans la fenêtre de pré-trigger
     * (6h avant expiration). Pendant cette fenêtre, SMS Tech pose une
     * notification persistante "Confirme que tu vas bien". Comme pour
     * [isExpired], les deux horloges doivent matcher (cohérence) — un
     * attaquant ne peut donc pas non plus déclencher la notification
     * prématurément.
     */
    fun isInWarningWindow(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!enabled || lastActivityAt == 0L || monotonicLastActivityAt == 0L) return false
        val elapsedWall = nowMs - lastActivityAt
        val elapsedMono = nowMonoMs - monotonicLastActivityAt
        val wallInWindow = elapsedWall >= (timeoutMs - WARNING_WINDOW_MS) &&
            elapsedWall < timeoutMs
        val monoInWindow = elapsedMono >= (timeoutMs - WARNING_WINDOW_MS) &&
            elapsedMono < timeoutMs
        return wallInWindow && monoInWindow
    }

    companion object {
        const val TIMEOUT_24H_MS: Long = 24 * 60 * 60 * 1000L
        const val TIMEOUT_48H_MS: Long = 48 * 60 * 60 * 1000L
        const val TIMEOUT_72H_MS: Long = 72 * 60 * 60 * 1000L
        const val TIMEOUT_MIN_MS: Long = 1 * 60 * 60 * 1000L     // 1h
        const val TIMEOUT_MAX_MS: Long = 720 * 60 * 60 * 1000L   // 30 jours

        /** Fenêtre de pré-trigger : notification 6h avant expiration. */
        const val WARNING_WINDOW_MS: Long = 6 * 60 * 60 * 1000L

        /** Nombre maximum de contacts d'urgence. */
        const val MAX_CONTACTS: Int = 4

        /** Cap du message custom (1 segment SMS UCS-2 sûr avec marge). */
        const val MAX_CUSTOM_MESSAGE_LENGTH: Int = 140
    }
}
