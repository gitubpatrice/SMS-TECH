package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MmsDispatcher
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * Sends a voice clip as an MMS to one or more recipients. Mirrors the broadcast semantics of
 * [SendSmsUseCase]: one MMS row + dispatch per non-blocked recipient. For each recipient:
 *
 *  1. Mirror an OUTGOING/PENDING row in Room (with the audio AttachmentEntity)
 *  2. Encode the SendReq PDU and hand it to [MmsDispatcher]
 *  3. On dispatch failure (synchronous), flip the row to FAILED; the network-side
 *     SENT/FAILED outcome arrives asynchronously via [MmsSentReceiver].
 */
class SendVoiceMmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppChecker,
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: OutgoingMessageMirror,
    private val sender: MmsDispatcher,
    private val settings: AppSettingsSource,
    private val attachmentStore: OutgoingAttachmentStore,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        subId: Int? = null,
        /** v1.28.3 (groupes) — cf. [SendSmsUseCase.invoke]. */
        echoInGroup: Boolean = false,
        /** v1.28.4 — MMS de groupe, même règle que [SendMediaMmsUseCase]. */
        groupMms: Boolean = false,
    ): Outcome<SendReport> {
        refusPrealable(recipients, audioFile)?.let { return Outcome.Failure(it) }

        // Audit H3 (v1.14.8) — on évite `flow.first()` (ouverture DataStore) sur chaque envoi.
        //
        // v1.27.2 — mais pas via `state.value`, qui rend les valeurs PAR DÉFAUT sur un processus
        // non hydraté. Aligné sur [SendSmsUseCase] et [SendMediaMmsUseCase] : les trois chemins
        // d'envoi lisent désormais la même chose de la même façon.
        val s = settings.hydratedOrNull() ?: AppSettings()
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        // Promote the recorded clip out of `cacheDir/voice_mms/` (wiped on every lock cycle by
        // AutoLockObserver + pruned by VoiceRecorder.pruneOld) into durable storage BEFORE
        // mirroring/dispatch, so the sent voice bubble keeps playing back after a lock or a reboot.
        // Cf. [OutgoingAttachmentStore].
        val durableAudio = attachmentStore.promoteToDurable(audioFile)

        if (groupMms && recipients.size > 1) {
            return envoiDeGroupe(recipients, durableAudio, mimeType, durationMs, effectiveSubId, deliveryReports)
        }

        val ids = ArrayList<Long>(recipients.size)
        val failed = ArrayList<PhoneAddress>()
        val blocked = ArrayList<PhoneAddress>()
        val now = System.currentTimeMillis()
        for (r in recipients) {
            // v1.28.3 (F21, second passage) — troisieme occurrence du meme `continue` muet.
            //
            // Le correctif n'avait ete pose que sur `SendSmsUseCase`, et la revue de qualite n'a
            // signale que la voie media : celle-ci, la voie VOCALE, portait le meme defaut sans
            // que personne ne la cite. Un message vocal vers un contact bloque disparaissait donc
            // lui aussi sans trace. Les trois chemins d'envoi appliquent maintenant la meme regle,
            // ce qui est tout l'objet de cette relecture.
            if (blockedRepo.isBlocked(r.raw)) {
                blocked += r
                val blockedId = mirror.upsertOutgoingMms(
                    address = r.raw,
                    audioFile = durableAudio,
                    mimeType = mimeType,
                    durationMs = durationMs,
                    date = now,
                    subId = effectiveSubId,
                )
                mirror.updateOutgoingStatus(
                    blockedId,
                    MessageStatus.FAILED,
                    errorCode = SendErrorCode.RECIPIENT_BLOCKED,
                )
                continue
            }
            val localId = mirror.upsertOutgoingMms(
                address = r.raw,
                audioFile = durableAudio,
                mimeType = mimeType,
                durationMs = durationMs,
                date = now,
                subId = effectiveSubId,
            )
            when (sender.sendVoiceMms(
                localMessageId = localId,
                recipients = listOf(r.raw),
                audioFile = durableAudio,
                mimeType = mimeType,
                subId = effectiveSubId,
                requestDeliveryReport = deliveryReports,
            )) {
                is Outcome.Success -> ids += localId
                is Outcome.Failure -> {
                    failed += r
                    mirror.updateOutgoingStatus(
                        localId,
                        MessageStatus.FAILED,
                        errorCode = SendErrorCode.SYNCHRONOUS,
                    )
                }
            }
        }
        // v1.28.3 (groupes) — la copie dans le fil du groupe, cf. [SendSmsUseCase].
        if (echoInGroup && recipients.size > 1) {
            mirror.upsertGroupEcho(
                addresses = recipients,
                body = "",
                date = now,
                subId = effectiveSubId,
                status = if (ids.size == recipients.size) MessageStatus.SENT else MessageStatus.FAILED,
                attachments = listOf(
                    com.filestech.sms.domain.mms.MediaAttachmentSpec(durableAudio, mimeType, durationMs = durationMs),
                ),
            )
        }
        if (ids.isEmpty()) {
            return if (blocked.size == recipients.size) {
                Outcome.Failure(AppError.RecipientBlocked)
            } else {
                Outcome.Failure(AppError.Telephony("no MMS dispatched"))
            }
        }
        return Outcome.Success(SendReport(dispatched = ids, failed = failed, blocked = blocked))
    }

    /** v1.28.3 (F21, second passage) — cf. le jumeau de [SendMediaMmsUseCase]. */
    /** v1.28.4 — le MMS vocal de groupe : un seul PDU, une seule ligne dans le groupe. */
    @Suppress("LongParameterList")
    private suspend fun envoiDeGroupe(
        recipients: List<PhoneAddress>,
        audio: File,
        mimeType: String,
        durationMs: Long,
        subId: Int?,
        deliveryReports: Boolean,
    ): Outcome<SendReport> {
        val blocked = recipients.filter { blockedRepo.isBlocked(it.raw) }
        val cibles = recipients - blocked.toSet()
        if (cibles.isEmpty()) return Outcome.Failure(AppError.RecipientBlocked)
        val localId = mirror.upsertOutgoingGroupMms(
            addresses = recipients,
            attachments = listOf(
                com.filestech.sms.domain.mms.MediaAttachmentSpec(audio, mimeType, durationMs = durationMs),
            ),
            textBody = "",
            date = System.currentTimeMillis(),
            subId = subId,
        )
        return when (
            sender.sendVoiceMms(
                localMessageId = localId,
                recipients = cibles.map { it.raw },
                audioFile = audio,
                mimeType = mimeType,
                subId = subId,
                requestDeliveryReport = deliveryReports,
            )
        ) {
            is Outcome.Success -> Outcome.Success(
                SendReport(dispatched = listOf(localId), failed = emptyList(), blocked = blocked),
            )
            is Outcome.Failure -> {
                mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = SendErrorCode.SYNCHRONOUS)
                Outcome.Failure(AppError.Telephony("no MMS dispatched"))
            }
        }
    }

    private fun refusPrealable(recipients: List<PhoneAddress>, audioFile: File): AppError? = when {
        !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
        recipients.isEmpty() -> AppError.Validation("no recipients")
        !audioFile.exists() || audioFile.length() == 0L ->
            AppError.Validation("audio file missing or empty")
        else -> null
    }
}
