package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.backup.BackupRestorer
import com.filestech.sms.domain.backup.RestoreResult
import javax.inject.Inject

/**
 * v1.15.2 — Restore d'une sauvegarde chiffrée `.smsbk`.
 *
 * Pure orchestration : le déchiffrement, l'import Room et la transaction atomique vivent dans
 * l'implémentation de [BackupRestorer]. Le UseCase est ici pour donner au ViewModel un point
 * d'entrée stable (le service est implementation detail) et pour aligner sur le pattern projet
 * (one UseCase = one user-intent). La source est reçue sous forme d'URI chaîne (l'UI fournit
 * `uri.toString()`), pour que `domain/` reste sans import `android.net.Uri`.
 */
class RestoreBackupUseCase @Inject constructor(
    private val backupRestorer: BackupRestorer,
) {
    suspend operator fun invoke(uriString: String, password: CharArray): Outcome<RestoreResult> =
        backupRestorer.restore(uriString, password)
}
