package com.filestech.sms.domain.repository

/**
 * v1.3.1 — résultat sémantique d'un appel à [ConversationRepository.setReaction]. Le
 * caller (typiquement [com.filestech.sms.ui.screens.thread.ThreadViewModel]) utilise la
 * sous-classe retournée pour décider d'un éventuel envoi SMS au correspondant.
 *
 * Logique d'envoi côté ViewModel :
 *
 *   - [First]   → seule transition qui peut déclencher un SMS (selon préférence user).
 *   - [Changed] → silencieux (le user a hésité, on ne le spam pas).
 *   - [Removed] → silencieux (retrait local uniquement).
 *   - [Noop]    → rien à faire.
 *
 * Pourquoi un sealed class plutôt qu'un simple Pair/Boolean : la sémantique est explicite,
 * exhaustivement validée par le compilateur (when sans else), et extensible (si on ajoute
 * un jour [com.filestech.sms.domain.repository.ConversationRepository] avec un cas
 * "ConflictDetected" pour la sync multi-device, le caller doit obligatoirement le traiter).
 */
sealed interface SetReactionResult {
    /** Aucun changement DB : valeur déjà égale ou message introuvable. */
    data object Noop : SetReactionResult

    /**
     * Transition `null → emoji`. Premier tap de réaction par l'utilisateur.
     *
     * [messageId] est inclus pour permettre au caller de re-vérifier l'état du message
     * avant un éventuel envoi SMS (anti-race : le user a pu changer/retirer la réaction
     * entre la pose et la confirmation du dialog). Voir
     * [com.filestech.sms.ui.screens.thread.ThreadViewModel.confirmReactionSend].
     */
    data class First(val messageId: Long, val emoji: String) : SetReactionResult

    /** Transition `emojiA → emojiB` (deux non-null distincts). Le user a changé d'avis. */
    data class Changed(val from: String, val to: String) : SetReactionResult

    /** Transition `emoji → null`. Le user a retiré sa réaction. */
    data object Removed : SetReactionResult
}
