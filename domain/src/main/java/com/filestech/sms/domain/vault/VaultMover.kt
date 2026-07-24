package com.filestech.sms.domain.vault

import com.filestech.sms.core.result.Outcome

/**
 * Port domaine : déplacement de conversations dans / hors du coffre chiffré.
 *
 * [com.filestech.sms.domain.usecase.ToggleConversationStateUseCase] ne dépend que de ces quatre
 * opérations. L'implémentation [com.filestech.sms.security.VaultManager] porte toute la politique
 * de sécurité (garde session déverrouillée, refus `PanicDecoy` / `Locked`, re-check anti-race) et
 * conserve par ailleurs sa surface complète (verrou de session, clé Keystore) consommée directement
 * par l'UI et la couche security — hors de ce port (ségrégation d'interface).
 */
interface VaultMover {

    /** Déplace [conversationId] dans le coffre. Exige une session déverrouillée. */
    suspend fun moveToVault(conversationId: Long): Outcome<Unit>

    /** Sort [conversationId] du coffre. Exige une session déverrouillée. */
    suspend fun moveOutOfVault(conversationId: Long): Outcome<Unit>

    /**
     * Déplacement « depuis l'extérieur » du coffre : vérifie l'état de verrouillage (refus
     * `PanicDecoy` / `Locked`) puis auto-déverrouille la session avant de déplacer.
     */
    suspend fun requestMoveToVault(conversationId: Long, intoVault: Boolean): Outcome<Unit>

    /** Déplacement de masse atomique, mêmes gardes que [requestMoveToVault]. Renvoie le nombre de lignes déplacées. */
    suspend fun requestBulkMoveToVault(ids: List<Long>, intoVault: Boolean): Outcome<Int>
}
