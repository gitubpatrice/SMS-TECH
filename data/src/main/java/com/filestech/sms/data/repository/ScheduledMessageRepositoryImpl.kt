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
    // v1.28.3 (F02) — la visibilité du coffre s'applique enfin ici aussi. Les deux mêmes
    // collaborateurs que `ConversationRepositoryImpl`, pour la même règle : ce qui est protégé
    // ne doit pas se lire par une autre porte que celle qui demande le second facteur.
    private val appLock: com.filestech.sms.security.AppLockManager,
    private val vaultSession: com.filestech.sms.security.VaultSessionState,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ScheduledMessageRepository {

    override fun observePending(): Flow<List<ScheduledMessage>> =
        masquerLeCoffre(dao.observePending())

    override fun observeFailed(): Flow<List<ScheduledMessage>> =
        masquerLeCoffre(dao.observeFailed())

    /**
     * v1.28.3 (F02) — applique au flux des envois programmés la politique de visibilité du
     * coffre, mot pour mot celle de `ConversationRepositoryImpl.observeOne`.
     *
     * Un message programmé porte son corps et ses destinataires en clair. L'écran « Messages
     * programmés » les affichait sans jamais demander le second facteur, et sans distinguer la
     * session leurre — alors que la conversation d'où ils viennent, elle, reste masquée. Le
     * contenu protégé se lisait donc par une porte dérobée.
     *
     * La règle est celle des conversations, et elle doit le rester : masqué tant que le coffre
     * n'a pas été ouvert DANS CETTE SESSION, et masqué en session leurre quoi qu'il arrive.
     * `vaultSession.unlocked` est un `StateFlow` pour que la liste se révèle au moment même où
     * l'utilisateur ouvre son coffre, et se referme quand `AutoLockObserver` le referme.
     */
    private fun masquerLeCoffre(
        source: Flow<List<com.filestech.sms.data.local.db.dao.ScheduledWithVaultFlag>>,
    ): Flow<List<ScheduledMessage>> =
        kotlinx.coroutines.flow.combine(
            source,
            appLock.state,
            vaultSession.unlocked,
        ) { lignes, lockState, coffreOuvert ->
            val leurre = lockState is com.filestech.sms.security.AppLockManager.LockState.PanicDecoy
            lignes
                .filterNot { it.inVault && (leurre || !coffreOuvert) }
                .map { it.message.toDomain() }
        }.flowOn(io)

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

    override suspend fun cancel(id: Long): Outcome<Boolean> = withContext(io) {
        // v1.26.1 (audit B2) — transition CONDITIONNELLE : on n'annule que si l'envoi est
        // encore `PENDING`. Un `setState` inconditionnel ecrasait l'etat d'un envoi deja
        // revendique par le worker, et l'appelant supprimait ensuite ses fichiers pendant que
        // le PDU etait en cours de construction.
        Outcome.Success(dao.cancelIfPending(id) == 1)
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
