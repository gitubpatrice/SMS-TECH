package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MmsDispatcher
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.model.PhoneAddress
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
    private val envoi = EnvoiParDestinataire(blockedRepo, mirror)

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

        val now = System.currentTimeMillis()
        // v1.28.3 (F21, second passage) — troisième occurrence du même `continue` muet, sur la
        // voie VOCALE que personne ne citait. v1.28.4 — la boucle n'existe plus qu'une fois,
        // dans [EnvoiParDestinataire] ; les trois voies ne peuvent plus diverger.
        return envoi.parDestinataire(
            recipients = recipients,
            sansRemise = "no MMS dispatched",
            miroir = { r, _ ->
                mirror.upsertOutgoingMms(
                    address = r.raw,
                    audioFile = durableAudio,
                    mimeType = mimeType,
                    durationMs = durationMs,
                    date = now,
                    subId = effectiveSubId,
                )
            },
            envoi = { localId, r ->
                sender.sendVoiceMms(
                    localMessageId = localId,
                    recipients = listOf(r.raw),
                    audioFile = durableAudio,
                    mimeType = mimeType,
                    subId = effectiveSubId,
                    requestDeliveryReport = deliveryReports,
                )
            },
            echoDeGroupe = if (echoInGroup) {
                { statut ->
                    mirror.upsertGroupEcho(
                        addresses = recipients,
                        body = "",
                        date = now,
                        subId = effectiveSubId,
                        status = statut,
                        attachments = listOf(
                            com.filestech.sms.domain.mms.MediaAttachmentSpec(
                                durableAudio,
                                mimeType,
                                durationMs = durationMs,
                            ),
                        ),
                    )
                }
            } else {
                null
            },
        )
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
    ): Outcome<SendReport> = envoi.enGroupe(
        recipients = recipients,
        sansRemise = "no MMS dispatched",
        miroir = {
            mirror.upsertOutgoingGroupMms(
                addresses = recipients,
                attachments = listOf(
                    com.filestech.sms.domain.mms.MediaAttachmentSpec(audio, mimeType, durationMs = durationMs),
                ),
                textBody = "",
                date = System.currentTimeMillis(),
                subId = subId,
            )
        },
        envoi = { localId, cibles ->
            sender.sendVoiceMms(
                localMessageId = localId,
                recipients = cibles.map { it.raw },
                audioFile = audio,
                mimeType = mimeType,
                subId = subId,
                requestDeliveryReport = deliveryReports,
            )
        },
    )

    private fun refusPrealable(recipients: List<PhoneAddress>, audioFile: File): AppError? = when {
        !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
        recipients.isEmpty() -> AppError.Validation("no recipients")
        !audioFile.exists() || audioFile.length() == 0L ->
            AppError.Validation("audio file missing or empty")
        else -> null
    }
}
