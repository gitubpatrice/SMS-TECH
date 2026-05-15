package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.VaultManager
import javax.inject.Inject

class ToggleConversationStateUseCase @Inject constructor(
    private val repo: ConversationRepository,
    private val vault: VaultManager,
) {
    suspend fun setPinned(id: Long, pinned: Boolean) = repo.setPinned(id, pinned)
    suspend fun setArchived(id: Long, archived: Boolean) = repo.setArchived(id, archived)
    suspend fun setMuted(id: Long, muted: Boolean) = repo.setMuted(id, muted)

    /**
     * Audit P0-1 (v1.2.0): vault toggling **must** go through [VaultManager], which gates the
     * operation against the panic-decoy session. Earlier versions called `repo.moveToVault`
     * directly here, which short-circuited the guard — a coerced decoy session could hide
     * (or unhide) any conversation, defeating the whole point of the vault.
     */
    suspend fun moveToVault(id: Long, inVault: Boolean): Outcome<Unit> =
        if (inVault) vault.moveToVault(id) else vault.moveOutOfVault(id)

    suspend fun delete(id: Long) = repo.delete(id)
}
