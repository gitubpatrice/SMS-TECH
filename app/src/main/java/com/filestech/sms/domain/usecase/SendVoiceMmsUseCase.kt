package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * Sends a voice clip as an MMS to one or more recipients. Mirrors the broadcast semantics of
 * [SendSmsUseCase]: one MMS row + dispatch per non-blocked recipient. For each recipient:
 *
 *  1. Mirror an OUTGOING/PENDING row in Room (with the audio AttachmentEntity)
 *  2. Encode the SendReq PDU and hand it to [MmsSender]
 *  3. On dispatch failure (synchronous), flip the row to FAILED; the network-side
 *     SENT/FAILED outcome arrives asynchronously via [MmsSentReceiver].
 */
class SendVoiceMmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppManager,
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: ConversationMirror,
    private val sender: MmsSender,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        subId: Int? = null,
    ): Outcome<List<Long>> {
        if (!defaultAppManager.isDefault()) return Outcome.Failure(AppError.NotDefaultSmsApp)
        if (recipients.isEmpty()) return Outcome.Failure(AppError.Validation("no recipients"))
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return Outcome.Failure(AppError.Validation("audio file missing or empty"))
        }

        val s = settings.flow.first()
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        val ids = ArrayList<Long>(recipients.size)
        val now = System.currentTimeMillis()
        for (r in recipients) {
            if (blockedRepo.isBlocked(r.raw)) continue
            val localId = mirror.upsertOutgoingMms(
                address = r.raw,
                audioFile = audioFile,
                mimeType = mimeType,
                durationMs = durationMs,
                date = now,
                subId = effectiveSubId,
            )
            when (val res = sender.sendVoiceMms(
                localMessageId = localId,
                recipients = listOf(r.raw),
                audioFile = audioFile,
                mimeType = mimeType,
                subId = effectiveSubId,
                requestDeliveryReport = deliveryReports,
            )) {
                is Outcome.Success -> ids += localId
                is Outcome.Failure -> mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = -1)
            }
        }
        return if (ids.isEmpty()) Outcome.Failure(AppError.Telephony("no MMS dispatched"))
        else Outcome.Success(ids)
    }
}
