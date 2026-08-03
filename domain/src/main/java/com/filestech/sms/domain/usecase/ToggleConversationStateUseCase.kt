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
    /**
     * v1.26.1 (audit F11) — RETIRÉE de l'API publique du use case.
     *
     * Recensement fait : zéro appelant. Les trois ViewModels passent tous par
     * [requestMoveToVault], qui refuse en session leurre. Cette variante-ci, elle, ne vérifie
     * rien : la laisser exposée à côté de sa jumelle gardée était un piège — le premier futur
     * appelant aurait contourné en silence la politique anti-leurre, c'est-à-dire rouvert le
     * trou que le CHANGELOG décrit comme « P0-1 Vault bypass closed ».
     *
     * Conservée en `private` plutôt que supprimée : c'est elle qui porte l'aiguillage
     * entrée/sortie, réutilisé par [requestMoveToVault].
     */
    private suspend fun moveToVault(id: Long, inVault: Boolean): Outcome<Unit> =
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
