package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettingsSource
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
    private val defaultAppManager: DefaultSmsAppChecker,
    private val sentSmsRecorder: SentSmsRecorder,
    private val sender: SmsSender,
    private val mirror: OutgoingMessageMirror,
    private val blockedRepo: BlockedNumberRepository,
    private val settings: AppSettingsSource,
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
        /**
         * v1.4.1 — when non-null, overrides what the Room mirror stores as the row's
         * `body` (the on-wire SMS body sent via `SmsManager` and mirrored into the
         * system inbox `content://sms` remains the regular [body] / `finalBody`,
         * untouched). Used by [SendReactionUseCase] to send a Tapback reaction to
         * the correspondent while NOT painting a redundant outgoing text bubble in
         * the reactor's own thread — the empty `""` row is filtered out at the DAO
         * `observeForConversation` query level. Default `null` = mirror the wire
         * body as-is (regular text SMS).
         */
        localMirrorBody: String? = null,
    ): Outcome<List<Long>> {
        if (!defaultAppManager.isDefault()) return Outcome.Failure(AppError.NotDefaultSmsApp)
        if (recipients.isEmpty()) return Outcome.Failure(AppError.Validation("no recipients"))
        if (body.isBlank()) return Outcome.Failure(AppError.Validation("body is blank"))

        // Audit H3 (v1.14.8) — `state.value` est le snapshot chaud StateFlow (Eagerly hydraté
        // au boot via [SettingsRepository.state]) → lecture zéro-I/O. Avant : `flow.first()`
        // ouvrait DataStore + désérialisation Protobuf sur CHAQUE envoi SMS (5-15 ms).
        val s = settings.state.value
        val signature = s.conversations.signature?.takeIf { it.isNotBlank() }
        val finalBody = if (appendSignature && signature != null) "$body\n--\n$signature" else body
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        val ids = ArrayList<Long>(recipients.size)
        val now = System.currentTimeMillis()
        for (r in recipients) {
            if (respectBlocklistOnIncoming && blockedRepo.isBlocked(r.raw)) continue
            val systemUri = sentSmsRecorder.insertSentSms(
                address = r.raw,
                body = finalBody,
                date = now,
                subId = effectiveSubId,
            )
            val localId = mirror.upsertOutgoingSms(
                address = r.raw,
                body = finalBody,
                date = now,
                telephonyUri = systemUri,
                subId = effectiveSubId,
                initialStatus = MessageStatus.PENDING,
                replyToMessageId = replyToMessageId,
                localMirrorBody = localMirrorBody,
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
