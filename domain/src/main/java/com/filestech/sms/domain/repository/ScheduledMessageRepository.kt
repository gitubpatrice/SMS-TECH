package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

interface ScheduledMessageRepository {
    fun observePending(): Flow<List<ScheduledMessage>>

    /** v1.25.3 (audit H6) — envois abandonnés, les plus récents d'abord. */
    fun observeFailed(): Flow<List<ScheduledMessage>>

    /**
     * v1.26.0 — [attachments] non vide programme un **MMS**. Les fichiers doivent deja etre
     * durables : c'est [com.filestech.sms.domain.usecase.ScheduleMessageUseCase] qui les promeut.
     */
    suspend fun schedule(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        scheduledAt: Long,
        subId: Int?,
        attachments: List<com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload> =
            emptyList(),
    ): Outcome<Long>
    suspend fun cancel(id: Long): Outcome<Unit>
    suspend fun markSent(id: Long)
    suspend fun markFailed(id: Long)

    /**
     * v1.25.3 (audit H6) — remet un envoi abandonné en attente pour [scheduledAt]. Ne re-planifie
     * rien par lui-même : c'est [com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase]
     * qui enchaîne avec le scheduler.
     */
    suspend fun rearmPending(id: Long, scheduledAt: Long)

    /** v1.25.3 (audit H6) — retire définitivement un envoi de la liste. */
    suspend fun delete(id: Long)

    /**
     * v1.26.0 — comme [delete], mais efface aussi les pieces jointes durables de l'envoi.
     * Voir [com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase] : les fichiers vivent
     * tant que la ligne existe.
     */
    suspend fun deleteWithAttachments(id: Long)

    /**
     * v1.26.0 — efface les pieces jointes durables d'un envoi **sans toucher a la ligne**.
     *
     * Pour l'annulation : la ligne subsiste en `CANCELLED` comme trace, mais ses fichiers ne
     * servent plus a rien — l'envoi ne partira jamais. Sans ce menage ils resteraient sur le
     * telephone **definitivement** : aucune liste n'affiche les lignes annulees, donc plus
     * personne ne pourrait jamais les atteindre pour les supprimer.
     */
    suspend fun clearAttachments(id: Long)
}
