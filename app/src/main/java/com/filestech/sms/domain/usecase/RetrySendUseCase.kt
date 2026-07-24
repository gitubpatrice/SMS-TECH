package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SendErrorCode
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
) {
    suspend operator fun invoke(messageId: Long): Outcome<Unit> {
        val msg = conversationRepo.findMessageForResend(messageId)
            ?: return Outcome.Failure(AppError.NotFound("message"))
        if (msg.errorCode == SendErrorCode.WATCHDOG_TIMEOUT) {
            Timber.w(
                "Retry of watchdog-timed-out message %d: previous attempt may have reached the recipient",
                messageId,
            )
        }
        mirror.updateOutgoingStatus(messageId, MessageStatus.PENDING, errorCode = null)
        return when (val r = sender.send(messageId, msg.address, msg.body, msg.subId)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                mirror.updateOutgoingStatus(messageId, MessageStatus.FAILED, errorCode = SendErrorCode.SYNCHRONOUS)
                r
            }
        }
    }
}
