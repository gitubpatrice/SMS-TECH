package com.filestech.sms.domain.backup

/**
 * Bilan d'une restauration de sauvegarde `.smsbk` (domaine). Type de données pur — aucune
 * dépendance Android/data.
 */
data class RestoreResult(
    val conversationsReused: Int,
    val conversationsCreated: Int,
    val messagesImported: Int,
    val messagesSkipped: Int,
) {
    val totalConversationsInBackup: Int get() = conversationsReused + conversationsCreated
    val totalMessagesInBackup: Int get() = messagesImported + messagesSkipped
}
