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

    /**
     * Fait PROGRESSER le statut d'un envoi (transition `PENDING → SENT / DELIVERED / FAILED`).
     * La promotion est monotone : voir `MessageDao.promoteStatusMonotonic`. Destiné aux accusés
     * d'envoi / de réception et aux échecs — c'est-à-dire à tout ce qui est subi, pas décidé.
     *
     * v1.28.3 — rend `true` si la ligne a REELLEMENT change d'etat. L'ecriture est conditionnelle
     * a deux titres — monotone, et liee a une tentative — donc elle peut ne rien faire, et
     * l'appelant a besoin de le savoir : c'est ce qui empeche la notification d'echec d'envoi de
     * partir sur un accuse qui n'a rien ecrit (l'accuse tardif d'une tentative perimee, ou le
     * deuxieme accuse d'echec d'un SMS multi-parties dont le premier a deja pose l'echec).
     *
     * @param attempt v1.28.3 (F23) — numéro de la tentative dont provient cet accusé. `null`
     *   signifie « quelle que soit la tentative en cours », et reste le bon choix pour tout ce
     *   qui n'est pas un accusé différé : l'échec synchrone écrit juste après la remise à la
     *   pile téléphonie, la sentinelle du chien de garde, le suivi MMS.
     *
     *   Un accusé, lui, doit porter sa tentative : la relance rétrograde délibérément la ligne
     *   en `PENDING`, si bien que l'accusé tardif de la tentative précédente y retrouvait le bas
     *   de l'échelle et s'y appliquait comme s'il était le sien.
     */
    suspend fun updateOutgoingStatus(
        localId: Long,
        status: MessageStatus,
        errorCode: Int? = null,
        attempt: Int? = null,
    ): Boolean

    /**
     * Statut courant d'un message sortant, ou `null` si la ligne n'existe plus.
     *
     * v1.28.3 (F05) — ajouté pour que l'appel de sécurité puisse distinguer « remis à
     * `SmsManager` » de « réellement parti ». `SmsManager.sendMultipartTextMessage` ne rend rien
     * d'utile : elle n'a fait qu'accepter la demande, et le sort du message arrive plus tard par
     * le `PendingIntent` `SENT`, que [updateOutgoingStatus] écrit ici. Sans cette lecture, le
     * seul signal disponible en amont était l'absence d'exception synchrone — ce qui reste vrai
     * en mode avion, sans SIM et hors couverture, c'est-à-dire précisément quand l'alerte
     * échoue.
     */
    suspend fun outgoingStatus(localId: Long): MessageStatus?

    /**
     * v1.26.1 (audit M8) — REMET un envoi en attente, sur action explicite de l'utilisateur
     * (relance d'un message en échec).
     *
     * Distinct de [updateOutgoingStatus] parce que l'intention est l'inverse : c'est une
     * RÉTROGRADATION délibérée, que la règle monotone bloquerait. Séparer les deux évite qu'un
     * futur appelant obtienne l'une en croyant demander l'autre.
     *
     * v1.28.3 (F23) — ouvre du même coup une nouvelle **tentative**, dont le numéro est rendu.
     * L'appelant doit le transmettre à `SmsSender.send` : c'est ce qui rend les `PendingIntent`
     * de suivi distincts d'une tentative à l'autre, et ce qui permet d'écarter l'accusé tardif
     * de la précédente. Rétrograder sans compter, c'était rouvrir la porte que la monotonie
     * venait de fermer.
     *
     * @return le numéro de la tentative qui commence, ou `null` si la ligne n'existe plus.
     */
    suspend fun resetOutgoingForRetry(localId: Long): Int?

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
