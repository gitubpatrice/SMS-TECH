package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
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
    /**
     * Les trois gardes qui precedent toute ecriture, regroupes en une seule decision.
     *
     * Factorises en v1.28.3 (F21) : l'ajout d'une issue distinguant « tous bloques » d'une panne
     * de telephonie portait `invoke` a cinq sorties. Les regrouper vaut mieux que d'excuser le
     * compte — ces trois refus repondent tous a la meme question, « a-t-on le droit d'envoyer ».
     */
    private fun refusPrealable(recipients: List<PhoneAddress>, body: String): AppError? = when {
        !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
        recipients.isEmpty() -> AppError.Validation("no recipients")
        body.isBlank() -> AppError.Validation("body is blank")
        else -> null
    }

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
        /**
         * v1.28.3 (groupes) — `true` quand l'envoi est fait DEPUIS un fil de groupe : une copie
         * locale est alors écrite dans ce fil, cf. [OutgoingMessageMirror.upsertGroupEcho].
         * Volontairement opt-in : le Safety call et le mode urgence envoient aussi à plusieurs,
         * sans qu'un « groupe » doive apparaître dans la liste.
         */
        echoInGroup: Boolean = false,
    ): Outcome<SendReport> {
        refusPrealable(recipients, body)?.let { return Outcome.Failure(it) }

        // Audit H3 (v1.14.8) — on évite `flow.first()` sur CHAQUE envoi (ouverture DataStore +
        // désérialisation, 5-15 ms).
        //
        // v1.27.2 — mais pas via `state.value`, qui rend les valeurs PAR DÉFAUT tant que le
        // processus n'est pas hydraté. Cet envoi n'est pas toujours déclenché depuis l'interface :
        // le Safety call et le mode urgence passent ici depuis un worker réveillé à froid, et la
        // réponse rapide depuis une notification aussi. On y lisait alors `defaultSubId = null`
        // — le SMS d'urgence partait de la SIM système au lieu de celle choisie, donc d'un autre
        // numéro que celui que les contacts reconnaissent — et une signature vide.
        //
        // [AppSettingsSource.hydratedOrNull] ne coûte rien quand le processus est chaud, ce qui
        // reste le cas de tous les envois faits depuis l'interface.
        //
        // Repli sur les valeurs par défaut si les réglages sont illisibles : ici elles ne
        // dégradent que du confort (pas de signature, SIM système, pas d'accusé de réception).
        // Refuser l'envoi coûterait le message lui-même — y compris un message d'urgence.
        val s = settings.hydratedOrNull() ?: AppSettings()
        val signature = s.conversations.signature?.takeIf { it.isNotBlank() }
        val finalBody = if (appendSignature && signature != null) "$body\n--\n$signature" else body
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        val ids = ArrayList<Long>(recipients.size)
        val failed = ArrayList<PhoneAddress>()
        val blocked = ArrayList<PhoneAddress>()
        val now = System.currentTimeMillis()
        for (r in recipients) {
            // v1.28.3 (F21) — un destinataire bloqué laisse désormais une TRACE.
            //
            // La boucle se contentait d'un `continue` muet : ni ligne, ni message, ni compte. On
            // tapait « Envoyer », et le message disparaissait — sans erreur, sans bulle, sans
            // rien. Sur un envoi à plusieurs, personne ne pouvait même s'apercevoir qu'un
            // destinataire manquait à l'appel.
            //
            // La ligne est écrite dans le miroir local UNIQUEMENT : rien n'est parti sur le
            // réseau, et écrire chez le fournisseur système une ligne « envoyée » qui ne l'est
            // pas mentirait à toutes les autres applications SMS de l'appareil. Le
            // `telephonyUri` reste donc `null`, et le statut est posé en échec dès l'insertion —
            // il n'existe aucune tentative dont un accusé pourrait le faire progresser.
            if (respectBlocklistOnIncoming && blockedRepo.isBlocked(r.raw)) {
                blocked += r
                val blockedId = mirror.upsertOutgoingSms(
                    address = r.raw,
                    body = finalBody,
                    date = now,
                    telephonyUri = null,
                    subId = effectiveSubId,
                    initialStatus = MessageStatus.PENDING,
                    replyToMessageId = replyToMessageId,
                    localMirrorBody = localMirrorBody,
                )
                // En deux temps, et non `initialStatus = FAILED` : `upsertOutgoingSms` n'écrit
                // pas de code d'erreur, et la promotion monotone refuserait ensuite d'en poser
                // un sur une ligne déjà au sommet de l'échelle. Le motif de l'échec doit
                // pourtant être lisible — c'est lui qui distingue « bloqué » de « en panne ».
                mirror.updateOutgoingStatus(
                    blockedId,
                    MessageStatus.FAILED,
                    errorCode = SendErrorCode.RECIPIENT_BLOCKED,
                )
                continue
            }
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
            when (sender.send(localId, r.raw, finalBody, effectiveSubId, deliveryReports)) {
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
        // v1.28.3 (groupes) — la copie dans le fil du groupe. Pas pour une réaction (corps local
        // vide, filtré à l'affichage) ni pour un seul destinataire, qui n'a pas de « groupe ».
        if (echoInGroup && recipients.size > 1 && localMirrorBody != "") {
            mirror.upsertGroupEcho(
                addresses = recipients,
                body = localMirrorBody ?: finalBody,
                date = now,
                subId = effectiveSubId,
                status = if (ids.size == recipients.size) MessageStatus.SENT else MessageStatus.FAILED,
                replyToMessageId = replyToMessageId,
            )
        }
        // v1.28.3 (F21) — l'échec total dit enfin POURQUOI.
        //
        // « Aucun message remis » était rendu comme une erreur de téléphonie, y compris quand
        // aucune pile n'avait été sollicitée parce que tous les destinataires étaient bloqués.
        // L'utilisateur lisait un problème de réseau là où il n'y avait qu'une règle qu'il avait
        // lui-même posée, et attendait donc que « ça repasse ».
        if (ids.isEmpty()) {
            return if (blocked.size == recipients.size) {
                Outcome.Failure(AppError.RecipientBlocked)
            } else {
                Outcome.Failure(AppError.Telephony("no message dispatched"))
            }
        }
        return Outcome.Success(SendReport(dispatched = ids, failed = failed, blocked = blocked))
    }
}
