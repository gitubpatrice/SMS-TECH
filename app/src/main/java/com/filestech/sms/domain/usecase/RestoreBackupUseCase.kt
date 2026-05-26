package com.filestech.sms.domain.usecase

import android.net.Uri
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.backup.BackupService
import javax.inject.Inject

/**
 * v1.15.2 — Restore d'une sauvegarde chiffrée `.smsbk`.
 *
 * Pure orchestration : le déchiffrement, l'import Room et la transaction atomique vivent dans
 * [BackupService.readSmsbk]. Le UseCase est ici pour donner au ViewModel un point d'entrée
 * stable (le service est implementation detail) et pour aligner sur le pattern projet
 * (one UseCase = one user-intent).
 */
class RestoreBackupUseCase @Inject constructor(
    private val backupService: BackupService,
) {
    suspend operator fun invoke(uri: Uri, password: CharArray): Outcome<BackupService.RestoreResult> =
        backupService.readSmsbk(uri, password)
}
