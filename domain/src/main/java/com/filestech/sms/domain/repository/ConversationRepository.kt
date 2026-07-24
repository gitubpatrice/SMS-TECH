package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageWindow
import com.filestech.sms.domain.model.PhoneAddress
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Conversation>>
    fun observeVault(): Flow<List<Conversation>>
    fun observeOne(id: Long): Flow<Conversation?>

    /**
     * The **entire** thread. Kept unbounded for consumers that need the full history, such as the
     * PDF export. The thread UI uses [observeMessagesWindow] instead.
     */
    fun observeMessages(conversationId: Long): Flow<List<Message>>

    /**
     * A bounded, growable view of the thread: the most recent messages first paint, older ones on
     * demand.
     *
     * @param limit emits the current window size; raising it widens the window in place.
     */
    fun observeMessagesWindow(conversationId: Long, limit: Flow<Int>): Flow<MessageWindow>

    fun observeUnreadConversationCount(): Flow<Int>

    suspend fun findOrCreate(addresses: List<PhoneAddress>): Outcome<Conversation>
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun setMuted(id: Long, muted: Boolean)
    suspend fun moveToVault(id: Long, inVault: Boolean)

    /**
     * v1.14.8 audit R8 — Move bulk atomique : N conversations vers/depuis le coffre dans une
     * SEULE transaction Room. Avant : la VM bouclait `for (id in ids) moveToVault(id, ...)` et
     * un process-kill au milieu laissait l'état partiellement migré sans feedback. Maintenant
     * `withTransaction { ... }` garantit : soit tout passe, soit rien — pas d'état hybride.
     * Retourne le nombre de rows effectivement mises à jour.
     */
    suspend fun bulkMoveToVault(ids: List<Long>, inVault: Boolean): Int
    suspend fun setDraft(id: Long, draft: String?)
    /**
     * v1.11.0 — apparence personnalisée par conversation (Sujet 5). `null` sur
     * un argument = reset à la valeur par défaut (bleu marque pour la bulle,
     * avatar contact natif). Voir [com.filestech.sms.data.local.db.dao.ConversationDao.setAppearance].
     */
    suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?)
    suspend fun markRead(id: Long)

    /**
     * v1.14.8 audit H4 — "Tout marquer comme lu" centralisé. Avant : la
     * [com.filestech.sms.ui.screens.conversations.ConversationsViewModel] injectait
     * `MessageDao` + `ConversationDao` + `TelephonyReader` et orchestrait elle-même
     * la propagation Room + content://sms+mms. Couche presentation court-circuitée.
     * Maintenant la logique vit dans le repository (qui sait déjà tout ce qu'il faut).
     */
    suspend fun markAllRead()
    suspend fun delete(id: Long)
    suspend fun deleteMessage(messageId: Long)
    suspend fun search(query: String): List<Message>

    /**
     * v1.3.1 — lookup ponctuel d'un message par id. Retourne `null` si purgé ou supprimé
     * entre-temps. Suspend + indexé sur PRIMARY KEY (négligeable). Utilisé par
     * [com.filestech.sms.domain.usecase.SendReactionUseCase] pour cibler le bon
     * expéditeur (anti-bug groupe : on n'envoie qu'à `message.address`, pas à toute la
     * conversation) et pour bloquer les réactions sur messages sortants.
     */
    suspend fun findMessageById(id: Long): Message?

    /**
     * Lookup PRIMARY KEY d'un message pour un **re-dispatch** (retry d'envoi), SANS la garde
     * `inVault` de [findMessageById]. Renvoyer ici un message d'une conversation coffre est
     * légitime : l'appelant ([com.filestech.sms.domain.usecase.RetrySendUseCase]) ré-émet vers
     * le destinataire d'origine et n'expose jamais le corps à l'UI. **Ne pas utiliser pour un
     * affichage** — utiliser [findMessageById] (gardé) pour tout chemin visible par l'utilisateur.
     */
    suspend fun findMessageForResend(id: Long): Message?

    /**
     * v1.3.1 — pose / change / retire la réaction emoji locale sur [messageId] et retourne
     * le type de transition pour permettre au caller (ViewModel) de décider d'un éventuel
     * envoi SMS en aval. Sémantique :
     *
     *   - [SetReactionResult.Noop]    : la valeur stockée était déjà égale à [emoji]
     *                                   (incluant null → null ou même emoji), ou le message
     *                                   n'existe plus (purgé / supprimé concurrently).
     *   - [SetReactionResult.First]   : transition null → emoji non-null. Premier tap.
     *                                   C'est le seul cas qui justifie un envoi SMS.
     *   - [SetReactionResult.Changed] : transition emoji A → emoji B (deux non-null distincts).
     *                                   Pas d'envoi (le user a hésité, on n'en spamme pas un 2ᵉ).
     *   - [SetReactionResult.Removed] : transition emoji → null. Pas d'envoi (silencieux local).
     *
     * Le mapping est strictement déterministe : un seul update Room par appel, jamais d'écho
     * réseau côté repo. L'envoi SMS éventuel est orchestré par le caller via
     * [com.filestech.sms.domain.usecase.SendReactionUseCase].
     */
    suspend fun setReaction(messageId: Long, emoji: String?): SetReactionResult

    /**
     * v1.3.0 — compte combien de messages seraient effacés par un nettoyage manuel à la
     * profondeur [olderThanDays] (jours). Utilisé par le dialog réglages avant confirmation.
     * Ne touche jamais aux favoris ni aux 5 derniers jours (filet interne).
     */
    suspend fun countMessagesToPurge(olderThanDays: Int): Int

    /**
     * v1.3.0 — efface MAINTENANT les messages plus vieux que [olderThanDays] jours, en
     * appliquant le filet interne 5 jours et en épargnant les favoris. Ne touche pas au
     * cycle auto-mensuel (le `lastAutoPurgeAt` n'est pas mis à jour). Retourne le nombre
     * de rows effacées pour feedback UI.
     */
    suspend fun purgeHistoryNow(olderThanDays: Int): Int
}
