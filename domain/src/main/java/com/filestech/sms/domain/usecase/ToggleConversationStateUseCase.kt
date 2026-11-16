package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.domain.repository.ConversationDeleteResult
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

    /** v1.28.3 — nommer un groupe ; la saisie brute est normalisée ici, une fois pour les deux entrées. */
    suspend fun rename(id: Long, raw: String?) =
        repo.setCustomName(id, com.filestech.sms.domain.model.GroupName.normalize(raw))

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
     * [com.filestech.sms.security.VaultManager.requestMoveToVault] pour la politique de sécurité
     * (refus PanicDecoy + Locked ; v1.27.2 : plus d'auto-unlock de la session coffre, et la
     * SORTIE du coffre exige une session coffre déjà déverrouillée).
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

    /**
     * 2026-11-16 (v1.31.0, courriel du 2026-11-11) — archive ou désarchive une sélection, en une transaction.
     * Rend le nombre de conversations réellement modifiées.
     */
    suspend fun setArchivedAll(ids: List<Long>, archived: Boolean): Int = repo.bulkSetArchived(ids, archived)

    /**
     * 2026-11-16 (v1.31.0, courriel du 2026-11-11) — supprime une sélection, une conversation après l'autre.
     *
     * Chacune passe par [delete], c'est-à-dire par le même effacement que la suppression d'une seule (copie du
     * téléphone, pièces jointes, PDU, notifications), avec ses propres règles de conservation : un lot n'en
     * contourne aucune. Pas de transaction d'ensemble, volontairement — l'effacement de la copie système n'est pas
     * transactionnel, et une conversation conservée ne doit pas en retenir une autre. Une exception sur l'une compte
     * comme une conversation conservée et n'arrête pas les suivantes.
     */
    suspend fun deleteAll(ids: List<Long>): BilanSuppression {
        var supprimees = 0
        var conservees = 0
        for (id in ids) {
            val resultat = runCatchingCancellable { repo.delete(id) }.getOrNull()
            if (resultat == ConversationDeleteResult.DELETED) supprimees++ else conservees++
        }
        return BilanSuppression(supprimees = supprimees, conservees = conservees)
    }

    /** Ce qu'il est advenu d'une sélection supprimée : [conservees] = toujours là, quelle qu'en soit la raison. */
    data class BilanSuppression(val supprimees: Int, val conservees: Int)
}
