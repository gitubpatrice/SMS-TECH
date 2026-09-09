package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SmsSender
import timber.log.Timber
import javax.inject.Inject

/**
 * Re-dispatches a previously [MessageStatus.FAILED] outgoing message.
 *
 * Idempotence note (audit M-1): when the previous failure was tagged with
 * [SendErrorCode.WATCHDOG_TIMEOUT], the message *may* in fact have been delivered by the radio
 * — we simply never received the sent-broadcast confirmation. Retrying in that case can
 * produce a duplicate SMS at the recipient. We log a clear warning so the dev surfaces this
 * in telemetry; the UI layer is expected to display a confirmation dialog on those retries
 * (covered in v1.1 UX polish, the data side is already wired here).
 */
class RetrySendUseCase @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val sender: SmsSender,
    private val mirror: OutgoingMessageMirror,
    // v1.27.2 (audit de cohérence 2026-08-04) — cf. la garde de liste noire ci-dessous.
    private val blockedRepo: BlockedNumberRepository,
) {
    suspend operator fun invoke(messageId: Long): Outcome<Unit> {
        val msg = conversationRepo.findMessageForResend(messageId)
            ?: return Outcome.Failure(AppError.NotFound("message"))
        refusPrealable(msg)?.let { return Outcome.Failure(it) }
        if (msg.errorCode == SendErrorCode.WATCHDOG_TIMEOUT) {
            Timber.w(
                "Retry of watchdog-timed-out message %d: previous attempt may have reached the recipient",
                messageId,
            )
        }
        // v1.26.1 (audit M8) — rétrogradation DÉLIBÉRÉE : `updateOutgoingStatus` est désormais
        // monotone et refuserait ce retour en arrière depuis `FAILED`.
        //
        // v1.28.3 (F23) — et c'est précisément cette rétrogradation qui rouvrait la porte. En
        // ramenant la ligne au bas de l'échelle monotone, elle rendait de nouveau applicable
        // l'accusé TARDIF de la tentative précédente : un `FAILED` en retard d'une minute
        // écrivait `3`, sommet de l'échelle, que le succès réel de cette nouvelle tentative ne
        // pouvait plus jamais promouvoir — bulle rouge définitive sur un message reçu. La
        // rétrogradation ouvre donc désormais une TENTATIVE numérotée, et c'est ce numéro que
        // l'on transmet à la pile téléphonie pour que ses accusés soient reconnaissables.
        val attempt = mirror.resetOutgoingForRetry(messageId)
            ?: return Outcome.Failure(AppError.NotFound("message"))
        return dispatcher(msg, messageId, attempt)
    }

    /**
     * Les deux refus qui précèdent toute écriture, regroupés en une décision — comme
     * `refusPrealable` dans les trois use cases d'envoi. Tous deux sont posés AVANT
     * `resetOutgoingForRetry` : rétrograder puis refuser laisserait la ligne en `PENDING`, ni
     * envoyée ni en échec.
     *
     * 1. **v1.28.3 (audit global B-1) — ce chemin ne sait renvoyer qu'un SMS**, et rien ne l'en
     *    avertissait. La bulle rouge d'un MMS arrivait ici comme n'importe quelle autre :
     *    `sender.send` repartait avec `msg.body` — la légende, souvent vide — par `SmsManager`,
     *    sans la pièce jointe. Avec une légende, elle partait en SMS et la ligne passait `SENT`
     *    sous une vignette jamais envoyée ; sans légende, `divideMessage("")` échouait sans un
     *    mot. `SECURITY.md` le documentait depuis la v1.3.9 (« tap to retry is dead for MMS »).
     *    Le vrai renvoi MMS demande `MmsDispatcher` et un `requestCode` porteur du numéro de
     *    tentative comme F23 l'a fait côté SMS — un chantier à vérifier sur appareil.
     * 2. **v1.27.2 (audit de cohérence 2026-08-04) — la liste noire garde aussi le RENVOI.** Les
     *    trois chemins d'envoi la consultent ; ce quatrième ne le faisait pas : bloquer un
     *    correspondant puis toucher une bulle rouge antérieure ré-émettait vers le numéro tout
     *    juste bloqué. Inconditionnelle, comme l'est en pratique celle des trois jumeaux.
     *    v1.28.3 (F21) — erreur TYPÉE, pour que l'écran puisse dire pourquoi rien ne repart.
     */
    private suspend fun refusPrealable(msg: Message): AppError? {
        if (msg.type == Message.Type.MMS) {
            Timber.i("Retry refused: message %d is an MMS, this path only re-dispatches SMS", msg.id)
            return AppError.MmsRetryUnsupported
        }
        if (blockedRepo.isBlocked(msg.address)) {
            Timber.i("Retry refused: recipient is blocked")
            return AppError.RecipientBlocked
        }
        return null
    }

    private suspend fun dispatcher(msg: Message, messageId: Long, attempt: Int): Outcome<Unit> {
        return when (val r = sender.send(messageId, msg.address, msg.body, msg.subId, attempt = attempt)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                // `attempt` explicite : cet échec-ci est bien celui de la tentative qu'on vient
                // d'ouvrir, et il ne doit pas s'appliquer si une relance l'a déjà remplacée.
                mirror.updateOutgoingStatus(
                    messageId,
                    MessageStatus.FAILED,
                    errorCode = SendErrorCode.SYNCHRONOUS,
                    attempt = attempt,
                )
                r
            }
        }
    }
}
