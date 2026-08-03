package com.filestech.sms.domain.repository

import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.model.MessageStatus
import java.io.File

/**
 * Port domaine : écriture des messages **sortants** dans le miroir Room (ligne de conversation +
 * ligne de message), consommé par les use-cases d'envoi et par les receivers de suivi de statut.
 *
 * C'est une vue étroite de l'implémentation [com.filestech.sms.data.repository.ConversationMirror]
 * (qui garde par ailleurs sa surface complète — import en masse, cycle de vie des conversations,
 * écriture des messages entrants — consommée directement par la couche system/UI). Les valeurs de
 * retour sont l'id Room local du message miroité.
 */
interface OutgoingMessageMirror {

    /** Miroite un SMS sortant. Renvoie l'id Room local. */
    suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
        initialStatus: MessageStatus = MessageStatus.PENDING,
        replyToMessageId: Long? = null,
        localMirrorBody: String? = null,
    ): Long

    /** Met à jour le statut d'un message sortant (transition PENDING→SENT/DELIVERED/FAILED). */
    /**
     * Fait PROGRESSER le statut d'un envoi. La promotion est monotone : voir
     * `MessageDao.promoteStatusMonotonic`. Destiné aux accusés d'envoi / de réception et aux
     * échecs — c'est-à-dire à tout ce qui est subi, pas décidé.
     */
    suspend fun updateOutgoingStatus(localId: Long, status: MessageStatus, errorCode: Int? = null)

    /**
     * v1.26.1 (audit M8) — REMET un envoi en attente, sur action explicite de l'utilisateur
     * (relance d'un message en échec).
     *
     * Distinct de [updateOutgoingStatus] parce que l'intention est l'inverse : c'est une
     * RÉTROGRADATION délibérée, que la règle monotone bloquerait. Séparer les deux évite qu'un
     * futur appelant obtienne l'une en croyant demander l'autre.
     */
    suspend fun resetOutgoingForRetry(localId: Long)

    /** Miroite un MMS vocal sortant. Renvoie l'id Room local. */
    suspend fun upsertOutgoingMms(
        address: String,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        date: Long,
        subId: Int? = null,
    ): Long

    /** Miroite un MMS multimédia sortant ([attachments] + [textBody]). Renvoie l'id Room local. */
    suspend fun upsertOutgoingMediaMms(
        address: String,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int? = null,
    ): Long
}
