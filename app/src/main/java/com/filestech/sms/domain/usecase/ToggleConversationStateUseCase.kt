package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.vault.VaultMover
import javax.inject.Inject

class ToggleConversationStateUseCase @Inject constructor(
    private val repo: ConversationRepository,
    private val vault: VaultMover,
) {
    suspend fun setPinned(id: Long, pinned: Boolean) = repo.setPinned(id, pinned)
    suspend fun setArchived(id: Long, archived: Boolean) = repo.setArchived(id, archived)
    suspend fun setMuted(id: Long, muted: Boolean) = repo.setMuted(id, muted)

    /**
     * v1.11.0 — Sujet 5 apparence : couleur bulle sortante + avatar custom.
     * `null` sur un argument = reset au défaut (bleu marque / avatar contact).
     */
    suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?) =
        repo.setAppearance(id, bubbleColorArgb, avatarUri)

    /**
     * Audit P0-1 (v1.2.0): vault toggling **must** go through [VaultMover] (impl
     * [com.filestech.sms.security.VaultManager]), which gates the
     * operation against the panic-decoy session. Earlier versions called `repo.moveToVault`
     * directly here, which short-circuited the guard — a coerced decoy session could hide
     * (or unhide) any conversation, defeating the whole point of the vault.
     *
     * Cette signature historique reste utilisée par [com.filestech.sms.ui.screens.vault.VaultScreen]
     * une fois sa session armée. Pour un appel "from outside" (long-press liste,
     * overflow ThreadScreen), utiliser [requestMoveToVault] qui check AppLockState
     * et auto-arme sessionUnlocked.
     */
    suspend fun moveToVault(id: Long, inVault: Boolean): Outcome<Unit> =
        if (inVault) vault.moveToVault(id) else vault.moveOutOfVault(id)

    /**
     * v1.11.0 — wrapper pour appels depuis l'extérieur de VaultScreen. Voir
     * [com.filestech.sms.security.VaultManager.requestMoveToVault] pour la politique de sécurité (refus
     * PanicDecoy + Locked, auto-unlock sinon).
     */
    suspend fun requestMoveToVault(id: Long, intoVault: Boolean): Outcome<Unit> =
        vault.requestMoveToVault(id, intoVault)

    /**
     * v1.14.8 R8 — Bulk move atomique. Wrap [com.filestech.sms.security.VaultManager.requestBulkMoveToVault] qui
     * délègue à [ConversationRepository.bulkMoveToVault] (transaction Room).
     */
    suspend fun requestBulkMoveToVault(ids: List<Long>, intoVault: Boolean): Outcome<Int> =
        vault.requestBulkMoveToVault(ids, intoVault)

    suspend fun delete(id: Long) = repo.delete(id)
}
