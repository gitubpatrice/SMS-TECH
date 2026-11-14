package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import javax.inject.Inject

/**
 * v1.28.4 — **l'aiguillage d'un message sortant, écrit UNE fois.**
 *
 * Le fil de conversation et l'envoi programmé décidaient chacun, avec leurs propres mots, si un
 * message part en SMS, en MMS, ou en MMS de groupe. Ils ont divergé : l'envoi programmé depuis un
 * groupe ignorait le réglage « MMS de groupe » que l'envoi immédiat appliquait. La règle vit
 * désormais ici, et lit elle-même le réglage — un appelant ne peut plus l'oublier.
 *
 * La règle : plusieurs destinataires et réglage « MMS de groupe » actif → un seul MMS à tous,
 * texte seul compris ; sinon des pièces jointes → un MMS par destinataire ; sinon un SMS par
 * destinataire. Le message vocal garde son use case propre (charge utile différente).
 */
class EnvoyerMessageUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val sendMediaMms: SendMediaMmsUseCase,
    private val settings: AppSettingsSource,
    private val blockedRepo: BlockedNumberRepository,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload> = emptyList(),
        subId: Int? = null,
        /** Cible d'une réponse citée — portée par le SMS seulement, comme avant. */
        replyToMessageId: Long? = null,
    ): Outcome<SendReport> {
        val groupe = recipients.size > 1
        val mmsDeGroupe = enMmsDeGroupe(recipients)
        return when {
            mmsDeGroupe -> sendMediaMms.invoke(recipients, attachments, body, subId, groupMms = true)
            attachments.isNotEmpty() -> sendMediaMms.invoke(recipients, attachments, body, subId, echoInGroup = groupe)
            else -> sendSms.invoke(recipients, body, subId, replyToMessageId = replyToMessageId, echoInGroup = groupe)
        }
    }

    /**
     * 2026-11-14 (groupe de 56 sur le S24 de Patrice) — **les membres qu'un MMS de groupe écartera parce qu'ils sont
     * bloqués**, vide quand le message ne partira pas en MMS de groupe.
     *
     * Un MMS de groupe part aux seuls membres non bloqués, et son message est rangé dans la conversation de CES
     * membres (`EnvoiParDestinataire.enGroupe`, choix de la v1.28.5) : avec un membre bloqué, ce n'est plus la
     * conversation où l'on a écrit. Patrice l'a vécu sans le comprendre — son groupe est resté vide, un second est
     * apparu, et rien ne disait qu'un des 56 contacts figurait dans la liste de blocage importée d'Android. L'écran
     * s'en sert pour PRÉVENIR avant l'envoi, puis ouvrir la conversation où le message est parti. Le message vocal
     * suit la même règle (`SendVoiceMmsUseCase`, même réglage). Même test de blocage que l'envoi.
     */
    suspend fun membresEcartesDuGroupe(recipients: List<PhoneAddress>): List<PhoneAddress> =
        if (enMmsDeGroupe(recipients)) recipients.filter { blockedRepo.isBlocked(it.raw) } else emptyList()

    /** La règle de l'aiguillage : plusieurs destinataires et réglage « MMS de groupe » actif. */
    private suspend fun enMmsDeGroupe(recipients: List<PhoneAddress>): Boolean =
        recipients.size > 1 && (settings.hydratedOrNull() ?: AppSettings()).sending.groupMms
}
