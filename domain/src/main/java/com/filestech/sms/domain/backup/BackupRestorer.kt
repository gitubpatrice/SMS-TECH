package com.filestech.sms.domain.backup

import com.filestech.sms.core.result.Outcome

/**
 * Port domaine : restauration d'une sauvegarde chiffrée `.smsbk`.
 *
 * [com.filestech.sms.domain.usecase.RestoreBackupUseCase] en dépend. L'implémentation
 * [com.filestech.sms.data.backup.BackupService] gère le déchiffrement, l'import Room et la
 * transaction atomique. La source est passée sous forme d'URI **chaîne** (l'UI fournit
 * `uri.toString()`), de sorte que `domain/` reste sans import `android.net.Uri`.
 */
interface BackupRestorer {

    /**
     * Lit, déchiffre et importe le fichier `.smsbk` pointé par [uriString] avec [password].
     * Renvoie le bilan d'import, ou une [Outcome.Failure] (URI illisible, mot de passe erroné,
     * format invalide).
     */
    suspend fun restore(uriString: String, password: CharArray): Outcome<RestoreResult>
}
