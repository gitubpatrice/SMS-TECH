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
    /**
     * v1.28.3 — messages importes **sans leurs pieces jointes**, parce que la sauvegarde n'en
     * transporte aucune.
     *
     * # Pourquoi ce compteur existe
     *
     * Le format `.smsbk` ne porte que les conversations et les messages : ni la table
     * `attachments`, ni les fichiers (decision de la v1.26.0, et `toLocalRow` remet donc
     * `attachmentsCount` a zero depuis la v1.26.1 pour ne pas annoncer N pieces jointes
     * introuvables).
     *
     * La consequence n'etait dite NULLE PART. Un MMS sans legende restaure devient meme une
     * ligne au corps vide, sans piece jointe et sans reaction — exactement la forme que cinq
     * requetes de `MessageDao` excluent pour masquer les sentinelles de reaction. Il est donc
     * importe, compte comme importe, et n'apparait dans aucun fil.
     *
     * Ce compteur ne repare pas cela — le contenu n'a jamais ete dans le fichier, il n'y a rien a
     * retrouver. Il le DIT, ce qui est la seule chose honnete a faire tant que le remede de fond
     * (un drapeau explicite au lieu d'une reconnaissance par forme) n'est pas pose. Une perte
     * enoncee vaut infiniment mieux qu'une perte muette.
     */
    val messagesWithoutAttachments: Int = 0,
) {
    val totalConversationsInBackup: Int get() = conversationsReused + conversationsCreated
    val totalMessagesInBackup: Int get() = messagesImported + messagesSkipped
}
