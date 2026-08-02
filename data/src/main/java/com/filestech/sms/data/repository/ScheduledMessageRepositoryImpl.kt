package com.filestech.sms.data.repository

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.ScheduledAttachmentCodec
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.model.ScheduledState
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledMessageRepositoryImpl @Inject constructor(
    private val dao: ScheduledMessageDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ScheduledMessageRepository {

    override fun observePending(): Flow<List<ScheduledMessage>> =
        dao.observePending().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeFailed(): Flow<List<ScheduledMessage>> =
        dao.observeFailed().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override suspend fun schedule(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        scheduledAt: Long,
        subId: Int?,
        attachments: List<com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload>,
    ): Outcome<Long> = withContext(io) {
        // v1.26.0 — un corps vide est desormais valide s'il y a une piece jointe : un MMS ne
        // portant qu'une image, sans legende, est un envoi parfaitement normal. La regle
        // d'origine (`body.isBlank()` rejete) datait d'un planificateur qui ne savait envoyer
        // que du texte.
        if (addresses.isEmpty() || (body.isBlank() && attachments.isEmpty())) {
            return@withContext Outcome.Failure(AppError.Validation("addresses or payload invalid"))
        }
        val now = System.currentTimeMillis()
        if (scheduledAt <= now) {
            return@withContext Outcome.Failure(AppError.Validation("scheduledAt must be in the future"))
        }
        val id = dao.upsert(
            ScheduledMessageEntity(
                conversationId = conversationId,
                addressesCsv = addresses.toCsv(),
                body = body,
                scheduledAt = scheduledAt,
                subId = subId,
                attachmentsJson = ScheduledAttachmentCodec.encode(attachments),
                state = ScheduledState.PENDING,
                createdAt = now,
            ),
        )
        Outcome.Success(id)
    }

    override suspend fun cancel(id: Long): Outcome<Unit> = withContext(io) {
        dao.setState(id, ScheduledState.CANCELLED)
        Outcome.Success(Unit)
    }
    override suspend fun markSent(id: Long) = withContext(io) { dao.setState(id, ScheduledState.SENT) }
    override suspend fun markFailed(id: Long) = withContext(io) { dao.setState(id, ScheduledState.FAILED) }
    override suspend fun rearmPending(id: Long, scheduledAt: Long) = withContext(io) {
        dao.rearmPending(id, scheduledAt)
    }
    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    /**
     * v1.26.0 — efface la ligne ET ses pieces jointes durables.
     *
     * Ordre volontaire : les fichiers d'abord, la ligne ensuite. Si le processus meurt entre les
     * deux, il reste une ligne dont les fichiers ont disparu — l'envoi echoue proprement a la
     * validation et l'utilisateur le voit dans « Echecs ». L'ordre inverse laisserait des fichiers
     * orphelins que plus rien ne reference, donc que plus rien ne pourra jamais supprimer.
     *
     * Chaque suppression est isolee : un fichier deja disparu ou verrouille ne doit pas empecher
     * d'effacer la ligne.
     */
    override suspend fun deleteWithAttachments(id: Long) = withContext(io) {
        val entity = runCatching { dao.findById(id) }.getOrNull()
        if (entity != null) {
            for (a in ScheduledAttachmentCodec.decode(entity.attachmentsJson)) {
                runCatching { a.file.delete() }
                    .onFailure { Timber.w(it, "Scheduled: suppression piece jointe %s echouee", a.file.name) }
            }
        }
        dao.delete(id)
    }

    /**
     * v1.26.0 — supprime les fichiers ET vide la colonne, pour que la ligne conservee ne pointe
     * plus vers des chemins morts. Voir le contrat pour la raison d'etre de ce menage.
     */
    override suspend fun clearAttachments(id: Long) = withContext(io) {
        val entity = runCatching { dao.findById(id) }.getOrNull() ?: return@withContext
        for (a in ScheduledAttachmentCodec.decode(entity.attachmentsJson)) {
            runCatching { a.file.delete() }
                .onFailure { Timber.w(it, "Scheduled: suppression piece jointe %s echouee", a.file.name) }
        }
        if (entity.attachmentsJson != null) {
            runCatching { dao.upsert(entity.copy(attachmentsJson = null)) }
                .onFailure { Timber.w(it, "Scheduled: purge attachmentsJson #%d echouee", id) }
        }
    }
}
