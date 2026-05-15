package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsBuilder
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * v1.2.1 — Sends a non-voice MMS (photo / video / file / contact card) to one or more
 * recipients. Mirrors the broadcast semantics of [SendVoiceMmsUseCase]: one MMS row per
 * non-blocked recipient, each independently dispatched via [MmsSender.sendMediaMms].
 *
 * Carrier-friendly cap of **300 KB total payload** per message — matches what most French
 * MMSCs accept (Free is the tightest at ~300 KB; Orange / SFR / Sosh / Bouygues handle up to
 * ~1 MB). The cap is conservative on purpose so the user always gets a usable error before
 * the MMSC rejects the upload.
 */
class SendMediaMmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppManager,
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: ConversationMirror,
    private val sender: MmsSender,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String = "",
        subId: Int? = null,
    ): Outcome<List<Long>> {
        if (!defaultAppManager.isDefault()) return Outcome.Failure(AppError.NotDefaultSmsApp)
        if (recipients.isEmpty()) return Outcome.Failure(AppError.Validation("no recipients"))
        if (attachments.isEmpty() && textBody.isBlank()) {
            return Outcome.Failure(AppError.Validation("no payload"))
        }
        for (a in attachments) {
            if (!a.file.exists() || a.file.length() == 0L) {
                return Outcome.Failure(AppError.Validation("attachment file missing or empty"))
            }
        }
        val totalBytes = attachments.sumOf { it.file.length() }
        if (totalBytes > MAX_PAYLOAD_BYTES) {
            return Outcome.Failure(AppError.Validation("payload exceeds 300 KB cap ($totalBytes B)"))
        }

        val s = settings.flow.first()
        val effectiveSubId = subId ?: s.sending.defaultSubId
        val deliveryReports = s.sending.deliveryReports
        val now = System.currentTimeMillis()

        val ids = ArrayList<Long>(recipients.size)
        for (r in recipients) {
            if (blockedRepo.isBlocked(r.raw)) continue

            val mirrorSpecs = attachments.map {
                ConversationMirror.MediaAttachmentSpec(
                    file = it.file,
                    mimeType = it.mimeType,
                    width = it.width,
                    height = it.height,
                    durationMs = it.durationMs,
                )
            }
            val localId = mirror.upsertOutgoingMediaMms(
                address = r.raw,
                attachments = mirrorSpecs,
                textBody = textBody,
                date = now,
                subId = effectiveSubId,
            )

            val pduAttachments = attachments.map {
                MmsBuilder.MmsAttachment(
                    file = it.file,
                    mimeType = it.mimeType,
                    kind = when {
                        it.mimeType.startsWith("image/", ignoreCase = true) -> MmsBuilder.MmsAttachment.Kind.IMAGE
                        it.mimeType.startsWith("video/", ignoreCase = true) -> MmsBuilder.MmsAttachment.Kind.VIDEO
                        it.mimeType.startsWith("audio/", ignoreCase = true) -> MmsBuilder.MmsAttachment.Kind.AUDIO
                        else -> MmsBuilder.MmsAttachment.Kind.OTHER
                    },
                )
            }

            when (val res = sender.sendMediaMms(
                localMessageId = localId,
                recipients = listOf(r.raw),
                attachments = pduAttachments,
                textBody = textBody.ifBlank { null },
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

    /** Payload describing one attachment, normalised + cached to disk by the UI before send. */
    data class AttachmentPayload(
        val file: File,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
    )

    private companion object {
        // Conservative cap aligned with French MMSC ceilings (Free is the tightest at ~300 KB).
        const val MAX_PAYLOAD_BYTES = 300L * 1024L
    }
}
