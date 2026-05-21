package com.filestech.sms.domain.safetycall

/**
 * v1.9.0 — Contact d'urgence du safety call.
 *
 * Représente un destinataire du SMS automatique envoyé en cas d'expiration
 * du timer. Le contact est saisi manuellement par l'utilisateur depuis le
 * wizard de configuration (`SafetyCallSetupScreen`) — pas de couplage avec le
 * carnet de contacts Android pour rester portable et éviter qu'un contact
 * supprimé invalide silencieusement la configuration safetyCall.
 *
 * @property displayName Nom libre à afficher dans l'UI (ex: "Marie", "Papa").
 *   Null ou vide = on affichera le [phoneNumber] tel quel dans la liste.
 *   Max 40 chars pour ne pas déborder dans la UI.
 *
 * @property phoneNumber Numéro de téléphone en format dialable (E.164
 *   préféré, ou format national accepté). Validé via [isValid] : pas
 *   d'alpha-numérique, ≥ 4 digits, format normalisé attendu côté
 *   `PhoneAddress.of`.
 */
data class SafetyCallContact(
    val displayName: String? = null,
    val phoneNumber: String,
) {
    /**
     * Vérifie que [phoneNumber] est dialable : pas de lettres ASCII, ≥ 4
     * digits, caractères autorisés `+`, digits, espaces, tirets, parens.
     * Pattern identique à [com.filestech.sms.domain.usecase.SendReactionUseCase]
     * pour cohérence — un contact deadman doit pouvoir recevoir un SMS
     * réel comme un destinataire normal.
     */
    fun isValid(): Boolean {
        val cleaned = phoneNumber.trim()
        if (cleaned.isEmpty()) return false
        if (cleaned.any { it in 'A'..'Z' || it in 'a'..'z' }) return false
        if (!cleaned.matches(Regex("^[+0-9 .()\\-]+$"))) return false
        return cleaned.count { it.isDigit() } >= 4
    }

    /**
     * Sanitize le [displayName] : trim + cap 40 chars + strip caractères
     * de contrôle Unicode (anti-injection visuelle, cohérent avec
     * [com.filestech.sms.domain.sender.SenderNameProvider]).
     */
    fun sanitizedDisplayName(): String? {
        val raw = displayName?.trim() ?: return null
        if (raw.isEmpty()) return null
        val cleaned = raw
            .replace(FORBIDDEN_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    companion object {
        private val FORBIDDEN_CHARS = Regex(
            "[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]",
        )
    }
}
