package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.mms.MmsAttachment
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
 * v1.2.1 — Sends a non-voice MMS (photo / video / file / contact card) to one or more
 * recipients. Mirrors the broadcast semantics of [SendVoiceMmsUseCase]: one MMS row per
 * non-blocked recipient, each independently dispatched via [MmsDispatcher.sendMediaMms].
 *
 * Carrier-friendly cap of **300 KB total payload** per message — matches what most French
 * MMSCs accept (Free is the tightest at ~300 KB; Orange / SFR / Sosh / Bouygues handle up to
 * ~1 MB). The cap is conservative on purpose so the user always gets a usable error before
 * the MMSC rejects the upload.
 */
class SendMediaMmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppChecker,
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: OutgoingMessageMirror,
    private val sender: MmsDispatcher,
    private val settings: AppSettingsSource,
    private val attachmentStore: OutgoingAttachmentStore,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String = "",
        subId: Int? = null,
        /** v1.28.3 (groupes) — cf. [SendSmsUseCase.invoke]. */
        echoInGroup: Boolean = false,
    ): Outcome<SendReport> {
        refusPrealable(recipients, attachments, textBody)?.let { return Outcome.Failure(it) }

        // Promote the UI-staged cache files into durable storage BEFORE mirroring/dispatch so the
        // `AttachmentEntity.localUri` we persist survives the outbound-cache pruners (otherwise the
        // sent image shows an empty tile when the thread is reopened days later). Done once, up
        // front, so every recipient row references the same durable file. Cf. [OutgoingAttachmentStore].
        val durableAttachments = attachments.map { it.copy(file = attachmentStore.promoteToDurable(it.file)) }

        // Audit H3 (v1.14.8) — on évite `flow.first()` (ouverture DataStore) sur chaque envoi.
        //
        // v1.27.2 — mais pas via `state.value` : il rend les valeurs PAR DÉFAUT tant que le
        // processus n'est pas hydraté, et cet envoi n'est pas toujours déclenché depuis
        // l'interface — [com.filestech.sms.system.scheduler.ScheduledSendAttempt] le rejoue depuis
        // un worker réveillé à froid. `defaultSubId` y valait alors `null` : le renvoi partait de
        // la SIM système au lieu de celle choisie. Même correctif que [SendSmsUseCase].
        val s = settings.hydratedOrNull() ?: AppSettings()
        val effectiveSubId = subId ?: s.sending.defaultSubId
        val deliveryReports = s.sending.deliveryReports
        val now = System.currentTimeMillis()

        // Hisses hors de la boucle : ni l'un ni l'autre ne depend du destinataire, et la voie
        // du destinataire bloque a besoin des specs avant le reste.
        val mirrorSpecs = durableAttachments.map {
            MediaAttachmentSpec(
                file = it.file,
                mimeType = it.mimeType,
                width = it.width,
                height = it.height,
                durationMs = it.durationMs,
            )
        }
        val pduAttachments = durableAttachments.map {
            MmsAttachment(
                file = it.file,
                mimeType = it.mimeType,
                kind = when {
                    it.mimeType.startsWith("image/", ignoreCase = true) -> MmsAttachment.Kind.IMAGE
                    it.mimeType.startsWith("video/", ignoreCase = true) -> MmsAttachment.Kind.VIDEO
                    it.mimeType.startsWith("audio/", ignoreCase = true) -> MmsAttachment.Kind.AUDIO
                    else -> MmsAttachment.Kind.OTHER
                },
            )
        }

        val ids = ArrayList<Long>(recipients.size)
        val failed = ArrayList<PhoneAddress>()
        val blocked = ArrayList<PhoneAddress>()
        for (r in recipients) {
            // v1.28.3 (F21, second passage) — le `continue` muet etait ici AUSSI.
            //
            // Le correctif F21 n'avait ete pose que sur `SendSmsUseCase` : un MMS vers un contact
            // bloque disparaissait donc toujours sans ligne, sans message et sans compte — y
            // compris pour un envoi PROGRAMME porteur d'une piece jointe, que
            // `ScheduledSendAttempt` aiguille vers ce chemin-ci. C'est exactement le motif du
            // correctif asymetrique que cette relecture entiere a mis au jour, et je venais de le
            // reproduire. Trouve par la revue de qualite lancee sur mon propre delta.
            if (blockedRepo.isBlocked(r.raw)) {
                blocked += r
                val blockedId = mirror.upsertOutgoingMediaMms(
                    address = r.raw,
                    attachments = mirrorSpecs,
                    textBody = textBody,
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

            val localId = mirror.upsertOutgoingMediaMms(
                address = r.raw,
                attachments = mirrorSpecs,
                textBody = textBody,
                date = now,
                subId = effectiveSubId,
            )

            when (sender.sendMediaMms(
                localMessageId = localId,
                recipients = listOf(r.raw),
                attachments = pduAttachments,
                textBody = textBody.ifBlank { null },
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
                body = textBody,
                date = now,
                subId = effectiveSubId,
                status = if (ids.size == recipients.size) MessageStatus.SENT else MessageStatus.FAILED,
                attachments = mirrorSpecs,
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

    /**
     * v1.28.3 (F21, second passage) — les cinq gardes prealables en une seule decision.
     *
     * Meme raison que dans [SendSmsUseCase] : elles repondent toutes a « a-t-on le droit
     * d'envoyer », et les regrouper vaut mieux que d'excuser leur nombre dans la baseline.
     */
    private fun refusPrealable(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String,
    ): AppError? {
        val totalBytes = attachments.sumOf { it.file.length() }
        return when {
            !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
            recipients.isEmpty() -> AppError.Validation("no recipients")
            attachments.isEmpty() && textBody.isBlank() -> AppError.Validation("no payload")
            attachments.any { !it.file.exists() || it.file.length() == 0L } ->
                AppError.Validation("attachment file missing or empty")
            totalBytes > MAX_PAYLOAD_BYTES ->
                AppError.Validation("payload exceeds 300 KB cap ($totalBytes B)")
            else -> null
        }
    }

    /** Payload describing one attachment, normalised + cached to disk by the UI before send. */
    data class AttachmentPayload(
        val file: File,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
    )

    companion object {
        /**
         * Conservative cap aligned with French MMSC ceilings (Free is the tightest at ~300 KB).
         *
         * v1.26.0 — n'est plus prive : [ScheduleMessageUseCase] applique la MEME limite au moment
         * de programmer, pour que l'utilisateur l'apprenne pendant qu'il peut encore retirer une
         * image, et non des heures plus tard. Une constante partagee plutot que deux valeurs
         * jumelles vouees a diverger.
         */
        /**
         * ⚠️ v1.27.2 — divergence CONNUE et ASSUMÉE avec
         * [com.filestech.sms.core.mms.MmsConstants.CARRIER_PAYLOAD_CAP_BYTES] (280 Ko), qui est
         * le seuil de recompression appliqué par l'interface.
         *
         * Sans effet aujourd'hui : l'UI recompresse toujours sous 280 Ko avant qu'un fichier
         * n'atteigne cet use case, les 20 Ko d'écart ne sont donc jamais exercés.
         *
         * Arbitrage de Patrice, 2026-08-04 : NE PAS unifier. Descendre ce plafond à 280
         * rejetterait à l'envoi un message DÉJÀ PROGRAMMÉ dont la pièce jointe tombe entre les
         * deux valeurs — accepté à la programmation sous l'ancienne limite. Casser un envoi
         * programmé pour une cohérence sans effet observable n'en vaut pas le prix.
         */
        const val MAX_PAYLOAD_BYTES = 300L * 1024L
    }
}
