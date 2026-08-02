package com.filestech.sms.domain.model

data class Conversation(
    val id: Long,
    val threadId: Long,
    val addresses: List<PhoneAddress>,
    val displayName: String?,
    val lastMessageAt: Long,
    val lastMessagePreview: String?,
    val unreadCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val muted: Boolean,
    val inVault: Boolean,
    val draft: String?,
    /** v1.11.0 — couleur ARGB de la bulle sortante. `null` = bleu marque par défaut. */
    val bubbleColorArgb: Int? = null,
    /** v1.11.0 — URI `content://` d'un avatar custom choisi par l'user. `null` = fallback contact natif. */
    val avatarUri: String? = null,
    /**
     * v1.25.3 — vrai quand **toutes** les adresses du fil sont dans la liste noire.
     *
     * Calculé à la volée par `ConversationRepositoryImpl.observeAll`, jamais persisté : la liste
     * noire est la seule source de vérité, et une conversation redevient normale au déblocage.
     */
    val blocked: Boolean = false,
) {
    val isGroup: Boolean get() = addresses.size > 1
    val firstAddress: PhoneAddress? get() = addresses.firstOrNull()
}
