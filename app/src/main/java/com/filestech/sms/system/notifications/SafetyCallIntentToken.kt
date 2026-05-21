package com.filestech.sms.system.notifications

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.9.0 audit fix SEC-10 — anti-spoofing token pour
 * [SafetyCallWarningNotifier.ACTION_SAFETY_CALL_RESET].
 *
 * **Pourquoi** : `MainActivity` est `exported="true"` (requis pour le rôle SMS
 * système). Sans protection, une app tierce malveillante pourrait envoyer
 * `Intent("com.filestech.sms.SAFETY_CALL_RESET")` à `MainActivity` et neutraliser
 * en background le timer du Safety call — exactement le scénario de menace
 * que le deadman cherche à couvrir.
 *
 * **Comment** : à chaque pose d'une notification warning, on rote un nonce
 * `Long` aléatoire (SecureRandom, 63 bits significatifs). Le PendingIntent
 * porte ce nonce en extra `EXTRA_RESET_TOKEN`. Quand `MainActivity` reçoit
 * l'intent, il vérifie l'égalité avec [current] et regénère le nonce
 * (consommation atomique mono-usage).
 *
 * Un attaquant qui forge un intent sans connaître le nonce courant verra
 * `match()` retourner `false` → reset ignoré + log warning.
 *
 * **Scope** : `@Singleton`, vit pour la durée du process. Au cold-start de
 * l'app (process kill OEM puis re-cold-start), le nonce est régénéré ; un
 * PendingIntent système préexistant pointant sur l'ancien nonce ne validera
 * plus — l'user verra la notif persistante, le tap n'aura aucun effet
 * jusqu'à ce que le worker tick suivant régénère la notif avec le nouveau
 * nonce (max 60 min d'attente). Trade-off acceptable pour la sécurité.
 */
@Singleton
class SafetyCallIntentToken @Inject constructor() {

    private val random = SecureRandom()

    @Volatile
    private var currentToken: Long = 0L

    /**
     * Génère un nouveau nonce et l'enregistre comme courant. Appelé par
     * [SafetyCallWarningNotifier.showWarning] AVANT la construction du
     * PendingIntent.
     */
    fun rotate(): Long {
        var next: Long
        do {
            next = random.nextLong()
        } while (next == 0L) // 0L = sentinelle "pas de token actif"
        currentToken = next
        return next
    }

    /**
     * Vérifie qu'un token fourni correspond au token courant et l'invalide
     * en cas de match (consommation mono-usage). Retourne `true` ssi le
     * token est valide et a été consommé.
     */
    fun consume(token: Long): Boolean {
        if (token == 0L) return false
        val expected = currentToken
        if (expected == 0L || expected != token) return false
        currentToken = 0L
        return true
    }
}
