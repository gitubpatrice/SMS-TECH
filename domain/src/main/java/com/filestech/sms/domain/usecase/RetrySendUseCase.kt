package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SmsSender
import timber.log.Timber
import javax.inject.Inject

/**
 * Re-dispatches a previously [MessageStatus.FAILED] outgoing message.
 *
 * Idempotence note (audit M-1): when the previous failure was tagged with
 * [SendErrorCode.WATCHDOG_TIMEOUT], the message *may* in fact have been delivered by the radio
 * — we simply never received the sent-broadcast confirmation. Retrying in that case can
 * produce a duplicate SMS at the recipient. We log a clear warning so the dev surfaces this
 * in telemetry; the UI layer is expected to display a confirmation dialog on those retries
 * (covered in v1.1 UX polish, the data side is already wired here).
 */
class RetrySendUseCase @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val sender: SmsSender,
    private val mirror: OutgoingMessageMirror,
    // v1.27.2 (audit de cohérence 2026-08-04) — cf. la garde de liste noire ci-dessous.
    private val blockedRepo: BlockedNumberRepository,
) {
    suspend operator fun invoke(messageId: Long): Outcome<Unit> {
        val msg = conversationRepo.findMessageForResend(messageId)
            ?: return Outcome.Failure(AppError.NotFound("message"))
        // v1.27.2 (audit de cohérence 2026-08-04) — la liste noire garde aussi le RENVOI.
        //
        // Les trois chemins d'envoi la consultent ([SendSmsUseCase], [SendMediaMmsUseCase],
        // [SendVoiceMmsUseCase]) ; ce quatrième, non. Or un message en échec reste affiché dans
        // le fil, et le bloquer n'efface pas sa bulle : bloquer un correspondant puis toucher
        // une bulle rouge antérieure ré-émettait vers le numéro tout juste bloqué, sans le
        // moindre message. Le blocage promis ne tenait pas sur ce chemin.
        //
        // La garde est posée AVANT `resetOutgoingForRetry` : rétrograder le statut puis refuser
        // laisserait la ligne bloquée en `PENDING`, donc un message ni envoyé ni marqué en
        // échec.
        //
        // Inconditionnelle, comme l'est en pratique celle des trois jumeaux :
        // `respectBlocklistOnIncoming` vaut `true` par défaut et aucun appelant ne la surcharge.
        if (blockedRepo.isBlocked(msg.address)) {
            Timber.i("Retry refused: recipient is blocked")
            return Outcome.Failure(AppError.Validation("recipient is blocked"))
        }
        if (msg.errorCode == SendErrorCode.WATCHDOG_TIMEOUT) {
            Timber.w(
                "Retry of watchdog-timed-out message %d: previous attempt may have reached the recipient",
                messageId,
            )
        }
        // v1.26.1 (audit M8) — rétrogradation DÉLIBÉRÉE : `updateOutgoingStatus` est désormais
        // monotone et refuserait ce retour en arrière depuis `FAILED`.
        mirror.resetOutgoingForRetry(messageId)
        return when (val r = sender.send(messageId, msg.address, msg.body, msg.subId)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                mirror.updateOutgoingStatus(messageId, MessageStatus.FAILED, errorCode = SendErrorCode.SYNCHRONOUS)
                r
            }
        }
    }
}
