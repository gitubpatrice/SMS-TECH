package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.mms.MmsAttachment
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
    private val envoi = EnvoiParDestinataire(blockedRepo, mirror)

    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String = "",
        subId: Int? = null,
        /** v1.28.3 (groupes) — cf. [SendSmsUseCase.invoke]. */
        echoInGroup: Boolean = false,
        /**
         * v1.28.4 — MMS de groupe : un seul PDU à tous, une seule ligne dans le groupe. Le réglage
         * est lu par l'appelant, qui sait aussi si la conversation est un groupe.
         */
        groupMms: Boolean = false,
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

        if (groupMms && recipients.size > 1) {
            return envoiDeGroupe(recipients, mirrorSpecs, pduAttachments, textBody, effectiveSubId, deliveryReports)
        }

        // v1.28.3 (F21, second passage) — le `continue` muet était ici AUSSI : le correctif
        // n'avait été posé que sur `SendSmsUseCase`. v1.28.4 — la boucle n'existe plus qu'une
        // fois, dans [EnvoiParDestinataire] ; ce chemin ne peut plus diverger du SMS.
        return envoi.parDestinataire(
            recipients = recipients,
            sansRemise = "no MMS dispatched",
            miroir = { r, _ ->
                mirror.upsertOutgoingMediaMms(
                    address = r.raw,
                    attachments = mirrorSpecs,
                    textBody = textBody,
                    date = now,
                    subId = effectiveSubId,
                )
            },
            envoi = { localId, r ->
                sender.sendMediaMms(
                    localMessageId = localId,
                    recipients = listOf(r.raw),
                    attachments = pduAttachments,
                    textBody = textBody.ifBlank { null },
                    subId = effectiveSubId,
                    requestDeliveryReport = deliveryReports,
                )
            },
            echoDeGroupe = if (echoInGroup) {
                { statut ->
                    mirror.upsertGroupEcho(
                        addresses = recipients,
                        body = textBody,
                        date = now,
                        subId = effectiveSubId,
                        status = statut,
                        attachments = mirrorSpecs,
                    )
                }
            } else {
                null
            },
        )
    }

    /**
     * v1.28.3 (F21, second passage) — les cinq gardes prealables en une seule decision.
     *
     * Meme raison que dans [SendSmsUseCase] : elles repondent toutes a « a-t-on le droit
     * d'envoyer », et les regrouper vaut mieux que d'excuser leur nombre dans la baseline.
     */
    /**
     * v1.28.4 — **le MMS de groupe** : les membres bloqués sortent du PDU (et sont comptés), les
     * autres reçoivent UN seul MMS, et le miroir n'a qu'UNE ligne — dans le groupe — que le radio
     * suit par son id. Tous bloqués : refus de blocage, pas panne de téléphonie.
     */
    @Suppress("LongParameterList")
    private suspend fun envoiDeGroupe(
        recipients: List<PhoneAddress>,
        mirrorSpecs: List<MediaAttachmentSpec>,
        pduAttachments: List<MmsAttachment>,
        textBody: String,
        subId: Int?,
        deliveryReports: Boolean,
    ): Outcome<SendReport> = envoi.enGroupe(
        recipients = recipients,
        sansRemise = "no MMS dispatched",
        miroir = {
            mirror.upsertOutgoingGroupMms(
                addresses = recipients,
                attachments = mirrorSpecs,
                textBody = textBody,
                date = System.currentTimeMillis(),
                subId = subId,
            )
        },
        envoi = { localId, cibles ->
            sender.sendMediaMms(
                localMessageId = localId,
                recipients = cibles.map { it.raw },
                attachments = pduAttachments,
                textBody = textBody.ifBlank { null },
                subId = subId,
                requestDeliveryReport = deliveryReports,
            )
        },
    )

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
