package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.local.db.entity.SendErrorCode
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.data.sms.SmsSender
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Public API for sending a text SMS.
 *
 * Performs (in order):
 *  1. Default-SMS-app guard
 *  2. Blocked-number guard for each recipient
 *  3. Optional signature append from settings
 *  4. For each recipient: insert sent row in Android provider, mirror in Room, dispatch SmsManager
 *
 * For multi-recipient broadcasts each recipient becomes its own row (one private SMS each).
 */
class SendSmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppManager,
    private val telephonyReader: TelephonyReader,
    private val sender: SmsSender,
    private val mirror: ConversationMirror,
    private val blockedRepo: BlockedNumberRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        body: String,
        subId: Int? = null,
        respectBlocklistOnIncoming: Boolean = true,
        /**
         * Optional contextual-reply target (#8). When non-null, the persisted outgoing row is
         * tagged with this local message id so the UI can render the quoted excerpt above the
         * bubble. The replied-to row is **not** required to exist in the same conversation; we
         * tolerate dangling refs (deleted source) at the UI layer.
         */
        replyToMessageId: Long? = null,
        /**
         * v1.3.1 — quand `false`, on n'ajoute PAS la signature utilisateur au corps. Default
         * `true` pour la compatibilité ascendante (envois texte/médias standards). Mis à
         * `false` par [SendReactionUseCase] : un emoji de réaction doit rester un emoji seul,
         * sinon (a) le SMS bascule en multi-part = facturation ×2/×3, (b) le destinataire
         * reçoit "❤️\n--\nPat" qui pollue le fil et casse la sémantique "réaction".
         */
        appendSignature: Boolean = true,
    ): Outcome<List<Long>> {
        if (!defaultAppManager.isDefault()) return Outcome.Failure(AppError.NotDefaultSmsApp)
        if (recipients.isEmpty()) return Outcome.Failure(AppError.Validation("no recipients"))
        if (body.isBlank()) return Outcome.Failure(AppError.Validation("body is blank"))

        val s = settings.flow.first()
        val signature = s.conversations.signature?.takeIf { it.isNotBlank() }
        val finalBody = if (appendSignature && signature != null) "$body\n--\n$signature" else body
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        val ids = ArrayList<Long>(recipients.size)
        val now = System.currentTimeMillis()
        for (r in recipients) {
            if (respectBlocklistOnIncoming && blockedRepo.isBlocked(r.raw)) continue
            val systemUri = telephonyReader.insertSentSms(
                address = r.raw,
                body = finalBody,
                date = now,
                subId = effectiveSubId,
            )
            val localId = mirror.upsertOutgoingSms(
                address = r.raw,
                body = finalBody,
                date = now,
                telephonyUri = systemUri?.toString(),
                subId = effectiveSubId,
                initialStatus = MessageStatus.PENDING,
                replyToMessageId = replyToMessageId,
            )
            when (val res = sender.send(localId, r.raw, finalBody, effectiveSubId, deliveryReports)) {
                is Outcome.Success -> ids += localId
                is Outcome.Failure -> mirror.updateOutgoingStatus(
                    localId,
                    MessageStatus.FAILED,
                    errorCode = SendErrorCode.SYNCHRONOUS,
                )
            }
        }
        return if (ids.isEmpty()) Outcome.Failure(AppError.Telephony("no message dispatched"))
        else Outcome.Success(ids)
    }
}
