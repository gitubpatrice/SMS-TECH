package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import javax.inject.Inject

/**
 * v1.28.4 — **la boucle d'envoi, écrite UNE fois.**
 *
 * `SendSmsUseCase`, `SendMediaMmsUseCase` et `SendVoiceMmsUseCase` portaient chacun la même
 * boucle : destinataire bloqué → ligne miroir passée en échec `RECIPIENT_BLOCKED` ; sinon ligne
 * miroir, remise à la pile, échec synchrone consigné ; puis l'écho de groupe, puis le verdict
 * (tout bloqué → [AppError.RecipientBlocked], rien remis → [AppError.Telephony]). Les deux voies
 * MMS dupliquaient en plus l'envoi de groupe. **Quatre divergences** ont été trouvées sur ces
 * copies en une semaine (F21 trois fois, puis l'envoi programmé qui ignorait le MMS de groupe) :
 * ce n'était plus une duplication esthétique, c'était un générateur de bugs mesuré.
 *
 * Ce qui varie d'une voie à l'autre — la ligne miroir, l'appel à la pile — est passé en
 * fonctions ; ce qui ne varie pas vit ici. Aucun comportement n'a changé : les tests d'envoi
 * existants (comptabilité des destinataires, écho de groupe, MMS de groupe) le vérifient.
 */
class EnvoiParDestinataire @Inject constructor(
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: OutgoingMessageMirror,
) {

    /**
     * Un envoi PAR destinataire : une ligne miroir et une remise à la pile pour chacun.
     *
     * @param miroir crée la ligne locale du destinataire ; `bloque` vaut `true` quand la ligne
     *   ne partira pas (pas de copie système à écrire, par exemple).
     * @param envoi remet la ligne à la pile ; un [Outcome.Failure] passe la ligne en échec
     *   synchrone, le destinataire est compté dans `failed`.
     * @param echoDeGroupe écrit, s'il est fourni et qu'il y a plus d'un destinataire, la copie
     *   dans le fil du groupe avec le statut global de l'envoi.
     * @param sansRemise message de l'erreur rendue quand rien n'est parti sans que tout soit bloqué.
     */
    suspend fun parDestinataire(
        recipients: List<PhoneAddress>,
        sansRemise: String,
        miroir: suspend (destinataire: PhoneAddress, bloque: Boolean) -> Long,
        envoi: suspend (localId: Long, destinataire: PhoneAddress) -> Outcome<*>,
        echoDeGroupe: (suspend (statut: MessageStatus) -> Unit)? = null,
    ): Outcome<SendReport> {
        val ids = ArrayList<Long>(recipients.size)
        val failed = ArrayList<PhoneAddress>()
        val blocked = ArrayList<PhoneAddress>()
        for (r in recipients) {
            if (blockedRepo.isBlocked(r.raw)) {
                blocked += r
                mirror.updateOutgoingStatus(
                    miroir(r, true),
                    MessageStatus.FAILED,
                    errorCode = SendErrorCode.RECIPIENT_BLOCKED,
                )
                continue
            }
            val localId = miroir(r, false)
            when (envoi(localId, r)) {
                is Outcome.Success -> ids += localId
                is Outcome.Failure -> {
                    failed += r
                    mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = SendErrorCode.SYNCHRONOUS)
                }
            }
        }
        if (echoDeGroupe != null && recipients.size > 1) {
            echoDeGroupe(if (ids.size == recipients.size) MessageStatus.SENT else MessageStatus.FAILED)
        }
        return verdict(recipients, ids, failed, blocked, sansRemise)
    }

    /**
     * Un envoi DE GROUPE : une seule ligne miroir dans le groupe, une seule remise à la pile
     * vers tous les destinataires non bloqués. Les bloqués sont écartés et comptés, jamais
     * servis ; s'il ne reste personne, rien n'est écrit.
     *
     * v1.28.5 (sixième note d'Andrew, point 3) — **le miroir reçoit les mêmes [cibles] que le
     * PDU.** Il recevait la liste d'origine : avec un membre bloqué, le groupe local était
     * `A+B+C` et le groupe transmis `A+B`. Le rapprochement à la réception exigeant le même
     * ensemble de membres, la réponse de A revenait dans un second groupe `A+B` au lieu du fil
     * d'origine. Le miroir dit désormais ce qui est parti ; le membre bloqué est dans `blocked`,
     * et c'est l'appelant qui le montre.
     */
    suspend fun enGroupe(
        recipients: List<PhoneAddress>,
        sansRemise: String,
        miroir: suspend (cibles: List<PhoneAddress>) -> Long,
        envoi: suspend (localId: Long, cibles: List<PhoneAddress>) -> Outcome<*>,
    ): Outcome<SendReport> {
        val blocked = recipients.filter { blockedRepo.isBlocked(it.raw) }
        val cibles = recipients - blocked.toSet()
        if (cibles.isEmpty()) return Outcome.Failure(AppError.RecipientBlocked)
        val localId = miroir(cibles)
        return when (envoi(localId, cibles)) {
            is Outcome.Success -> Outcome.Success(
                SendReport(dispatched = listOf(localId), failed = emptyList(), blocked = blocked),
            )
            is Outcome.Failure -> {
                mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = SendErrorCode.SYNCHRONOUS)
                Outcome.Failure(AppError.Telephony(sansRemise))
            }
        }
    }

    private fun verdict(
        recipients: List<PhoneAddress>,
        ids: List<Long>,
        failed: List<PhoneAddress>,
        blocked: List<PhoneAddress>,
        sansRemise: String,
    ): Outcome<SendReport> {
        if (ids.isEmpty()) {
            return if (blocked.size == recipients.size) {
                Outcome.Failure(AppError.RecipientBlocked)
            } else {
                Outcome.Failure(AppError.Telephony(sansRemise))
            }
        }
        return Outcome.Success(SendReport(dispatched = ids, failed = failed, blocked = blocked))
    }
}
