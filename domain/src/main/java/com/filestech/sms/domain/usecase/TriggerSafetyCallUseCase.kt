package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.9.0 — Évalue l'état du Safety call deadman et déclenche l'envoi des
 * SMS d'urgence si le timer a expiré.
 *
 * Appelé depuis [com.filestech.sms.system.scheduler.SafetyCallWorker] au tick périodique (60 min)
 * et par le travail ponctuel qui porte chaque relance (15 min).
 *
 * **Garde panic-decoy (audit fix CRITICAL)** : si l'app est en session
 * [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy], retourne [Result.PanicSuppressed]
 * sans envoi. Sinon l'agresseur sous contrainte verrait partir les SMS
 * d'urgence devant lui, révélant le réseau de soutien de la victime. Le
 * worker du tick suivant retentera dès que la session decoy est quittée.
 *
 * **v1.27.2 — le désarmement préemptif de SEC-3 est RENVERSÉ.**
 *
 * L'ancien texte de ce bloc décrivait un `disableSafetyCall()` appelé AVANT la boucle d'envoi,
 * pour qu'un plantage entre deux envois ne relance pas le déclenchement au tick suivant. Le prix
 * n'avait pas été pesé : quand **tous** les envois échouaient — pas de réseau, mode avion, SIM
 * absente — le deadman se désarmait quand même, définitivement et en silence. La protection
 * s'éteignait exactement au moment où elle échouait.
 *
 * Le contrat est désormais :
 *  1. le créneau est **réservé de façon atomique** avant l'envoi, ce qui couvre le doublon que
 *     SEC-3 cherchait à éviter — y compris entre le tick périodique et une relance qui se
 *     croiseraient ;
 *  2. si **aucun** envoi n'aboutit, le créneau est **rendu** : le deadman reste armé et le tick
 *     suivant retentera ;
 *  3. le désarmement n'a lieu qu'à la **fin de la séquence** de [SafetyCallConfig.TOTAL_MESSAGES]
 *     messages, ou sur une configuration invalide (aucun contact, message vide).
 *
 * Un plantage entre l'envoi et la libération du créneau coûte au pire **un message en double** —
 * bien moins grave qu'une protection éteinte sans le dire.
 *
 * **Garde-fous** :
 *  - Refuse si liste de contacts vide.
 *  - Refuse si template = CUSTOM avec message vide.
 *  - Skippe les contacts au numéro invalide (log warn redacté) mais les
 *    autres reçoivent le SMS — un seul mauvais numéro ne bloque pas tout.
 *
 * **Logs (audit fix SEC-1)** : aucun `phoneNumber` n'est loggé en clair.
 *
 * **v1.10.0 — refacto C1** : ex-`SafetyCallTriggerService` (état non
 * pertinent, l'état vit dans [SettingsRepository] déjà `@Singleton`).
 * Migré vers le pattern UseCase dominant du projet avec `operator invoke()`.
 */
class TriggerSafetyCallUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val settings: AppSettingsSource,
    private val panicState: PanicStateProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Result = withContext(io) {
        if (panicState.isPanicDecoyActive) {
            Timber.i("TriggerSafetyCallUseCase: PanicDecoy active, suppressing trigger")
            return@withContext Result.PanicSuppressed
        }

        var current = settings.flow.first().security.safetyCall
        if (!current.enabled) {
            Timber.d("TriggerSafetyCallUseCase: disabled, skipping")
            return@withContext Result.Disabled
        }

        // v1.27.2 (relecture Gemini du 2026-08-05) - REPRISE D UN CRENEAU ABANDONNE.
        //
        // La reservation ci-dessous incremente `messagesSent` AVANT d envoyer. Si le processus
        // meurt dans cet intervalle - memoire insuffisante, mise a jour du systeme, batterie
        // critique - le tick suivant lit `messagesSent = 1` et croit le message initial parti.
        // Les proches ne recevraient JAMAIS le message qui explique la situation : ils
        // decouvriraient l affaire par un « Toujours aucun signe de ma part, 15 minutes plus
        // tard ». Ce n est pas un doublon, c est une PERTE - et mon commentaire d origine
        // affirmait le contraire.
        //
        // Le bail rend la reservation reversible. Passe `CLAIM_LEASE_MS` sans conclusion, le
        // creneau est repris ici et l envoi sera retente plus bas dans la meme execution.
        //
        // Le repli va volontairement vers le DOUBLON : reprendre un creneau dont l envoi avait en
        // realite abouti coute un message en double, sans commune mesure avec une alerte muette.
        if (current.isClaimAbandoned()) {
            Timber.w(
                "TriggerSafetyCallUseCase: creneau %d abandonne, reprise",
                current.messagesSent,
            )
            settings.update { s ->
                val cfg = s.security.safetyCall
                if (!cfg.isClaimAbandoned()) {
                    s
                } else {
                    val rolledBack = (cfg.messagesSent - 1).coerceAtLeast(0)
                    s.copy(
                        security = s.security.copy(
                            safetyCall = cfg.copy(
                                messagesSent = rolledBack,
                                // Plus aucun message parti : la sequence n a jamais commence.
                                triggeredAt = if (rolledBack == 0) 0L else cfg.triggeredAt,
                                claimedAt = 0L,
                            ),
                        ),
                    )
                }
            }
            current = settings.flow.first().security.safetyCall
            if (!current.enabled) return@withContext Result.Disabled
        }

        // v1.27.2 - deux entrees possibles : le declenchement initial, ou une relance de la
        // sequence deja ouverte. `isExpired` rend `false` des que `triggeredAt` est pose : les
        // deux conditions sont donc exclusives, jamais concurrentes.
        val isRelance = current.isTriggered
        if (isRelance && !current.hasRelancePending) {
            // 🔴 v1.27.2 (audit Codex du 2026-08-05, C-02) — un creneau RESERVE n est pas une
            // sequence TERMINEE.
            //
            // `messagesSent` compte les creneaux reserves, pas les envois conclus. L etat
            // `messagesSent = 4, claimedAt > 0` est exactement celui qui persiste entre la
            // reservation du dernier message et sa conclusion. Sans cette garde, un second
            // controle demarrant dans cette fenetre validait la sequence et desarmait : la
            // derniere alerte n aurait JAMAIS ete retentee.
            //
            // La reprise des creneaux abandonnes est passee juste au-dessus : si l on arrive ici
            // avec un bail pose, c est qu il est encore VALIDE, donc qu un envoi est en vol.
            if (current.claimedAt != 0L) {
                Timber.i("TriggerSafetyCallUseCase: dernier creneau en vol, on ne conclut pas")
                return@withContext Result.AlreadySent
            }
            // Sequence terminee sans que le desarmement ait pu s ecrire - processus tue juste
            // apres le dernier envoi. On finit le travail plutot que de laisser un etat bancal.
            Timber.i("TriggerSafetyCallUseCase: relance sequence complete, disarming")
            disableSafetyCall()
            return@withContext Result.SequenceComplete
        }
        if (!isRelance && !current.isExpired()) {
            Timber.d(
                "TriggerSafetyCallUseCase: armed but not expired (lastActivity=%d, timeout=%dh)",
                current.lastActivityAt, current.timeoutMs / 3_600_000L,
            )
            return@withContext Result.NotExpired
        }
        if (isRelance && !current.isRelanceDue()) {
            Timber.d("TriggerSafetyCallUseCase: relance %d not due yet", current.messagesSent)
            return@withContext Result.NotExpired
        }
        if (current.contacts.isEmpty()) {
            Timber.w("TriggerSafetyCallUseCase: expired but no contacts configured - disabling")
            disableSafetyCall()
            return@withContext Result.NoContacts
        }
        val body = if (isRelance) {
            SafetyCallTemplate.renderRelance(current.messagesSent)
        } else {
            current.template.render(current.timeoutMs, current.customMessage)
        }.trim()
        if (body.isBlank()) {
            Timber.w("TriggerSafetyCallUseCase: rendered body is blank - disabling")
            disableSafetyCall()
            return@withContext Result.EmptyBody
        }

        // v1.27.2 - RESERVATION ATOMIQUE du creneau, AVANT l envoi.
        //
        // `settings.update` s execute sous le verrou d ecriture de DataStore : le test et
        // l increment sont donc indivisibles. Sans cela, le tick periodique (60 min) et la
        // relance ponctuelle (15 min) pourraient se croiser et envoyer deux fois le meme message.
        //
        // La reservation echoue si quelqu un a deja pris ce creneau : on ne fait alors rien.
        val nowMs = System.currentTimeMillis()
        var claimed = false
        settings.update { s ->
            val cfg = s.security.safetyCall
            // `claimedAt != 0L` = un envoi est deja en vol et son bail court encore. On ne double
            // pas, meme si le compteur coincide.
            val leaseHeld = cfg.claimedAt != 0L && !cfg.isClaimAbandoned(nowMs)
            // v1.27.2 (audit Codex du 2026-08-05, SC-03) — 🔴 UNE FAUSSE ALERTE APRÈS « JE VAIS
            // BIEN ». La condition ne comparait que `enabled` et `messagesSent`. Or une remise a
            // zero de l utilisateur - ouverture de l application, tap sur la notification, bouton
            // dedie - deplace `lastActivityAt` SANS toucher au compteur. Un worker parti avec un
            // instantane d avant la confirmation reservait donc le creneau et envoyait quand meme :
            // les proches recevaient une urgence a la seconde ou la personne venait de confirmer
            // aller bien. En comparant l horodatage d activite, l instantane perime est rejete.
            val activityMoved = cfg.lastActivityAt != current.lastActivityAt
            // Regroupe pour rester sous le seuil de complexite de detekt, et parce que les
            // deux termes disent la meme chose : l instantane sur lequel ce worker a decide
            // est perime.
            val staleSnapshot = cfg.messagesSent != current.messagesSent || activityMoved
            if (!cfg.enabled || staleSnapshot || leaseHeld) {
                s
            } else {
                claimed = true
                s.copy(
                    security = s.security.copy(
                        safetyCall = cfg.copy(
                            triggeredAt = if (cfg.isTriggered) cfg.triggeredAt else nowMs,
                            messagesSent = cfg.messagesSent + 1,
                            claimedAt = nowMs,
                        ),
                    ),
                )
            }
        }
        if (!claimed) {
            Timber.i(
                "TriggerSafetyCallUseCase: slot %d already claimed, skipping",
                current.messagesSent,
            )
            return@withContext Result.AlreadySent
        }

        Timber.i(
            "TriggerSafetyCallUseCase: SEND #%d - %d contact(s), relance=%s",
            current.messagesSent + 1,
            current.contacts.size,
            isRelance,
        )

        var sent = 0
        var failed = 0
        current.contacts.forEachIndexed { index, contact ->
            if (!contact.isValid()) {
                Timber.w("TriggerSafetyCallUseCase: skipping invalid contact #%d", index)
                failed++
                return@forEachIndexed
            }
            val target = PhoneAddress.of(contact.phoneNumber)
            if (target.normalized.isEmpty()) {
                Timber.w("TriggerSafetyCallUseCase: skipping unresolvable contact #%d", index)
                failed++
                return@forEachIndexed
            }
            val outcome = sendSms(
                recipients = listOf(target),
                body = body,
                appendSignature = false,
            )
            when (outcome) {
                is Outcome.Success -> sent++
                is Outcome.Failure -> {
                    Timber.w("TriggerSafetyCallUseCase: send failed for contact #%d", index)
                    failed++
                }
            }
        }

        // v1.27.2 - ANNULATION DE LA RESERVATION si RIEN n est parti.
        //
        // C est le renversement assume du correctif SEC-3, qui desarmait AVANT la boucle d envoi
        // pour qu un plantage entre deux envois ne relance pas le declenchement. Le prix n avait
        // pas ete pese : sans reseau, en mode avion ou sans SIM, le deadman se desarmait quand
        // meme - definitivement et en silence, exactement au moment ou il echouait.
        //
        // La reservation atomique ci-dessus couvre deja le doublon. Ici on REND le creneau pour
        // que le tick suivant retente. Un plantage entre l envoi et cette ligne coute au pire UN
        // message en double : bien moins grave qu une protection eteinte sans le dire.
        if (sent == 0) {
            Timber.w("TriggerSafetyCallUseCase: nothing sent - releasing slot for next tick")
            settings.update { s ->
                val cfg = s.security.safetyCall
                if (cfg.messagesSent != current.messagesSent + 1) {
                    s
                } else {
                    s.copy(
                        security = s.security.copy(
                            safetyCall = cfg.copy(
                                triggeredAt = if (isRelance) cfg.triggeredAt else 0L,
                                messagesSent = current.messagesSent,
                                claimedAt = 0L,
                            ),
                        ),
                    )
                }
            }
            return@withContext Result.SendFailed(failed = failed)
        }

        // Envoi conclu : le bail est leve. Sans cette ligne, le creneau suivant serait bloque
        // pendant deux minutes, et une relance due dans cet intervalle serait sautee.
        settings.update { s ->
            s.copy(
                security = s.security.copy(
                    safetyCall = s.security.safetyCall.copy(claimedAt = 0L),
                ),
            )
        }

        val messagesSentNow = current.messagesSent + 1
        val remaining = SafetyCallConfig.TOTAL_MESSAGES - messagesSentNow
        if (remaining <= 0) {
            Timber.i(
                "TriggerSafetyCallUseCase: sequence complete (%d messages), disarming",
                messagesSentNow,
            )
            disableSafetyCall()
        }
        Result.Triggered(
            sent = sent,
            failed = failed,
            nextRelanceInMs = if (remaining > 0) SafetyCallConfig.RELANCE_INTERVAL_MS else null,
        )
    }

    private suspend fun disableSafetyCall() {
        settings.update { s ->
            s.copy(security = s.security.copy(safetyCall = s.security.safetyCall.copy(enabled = false)))
        }
    }

    sealed interface Result {
        /** SafetyCall désactivé dans Settings (no-op normal). */
        data object Disabled : Result

        /** SafetyCall armé mais pas encore expiré. */
        data object NotExpired : Result

        /** Expiré mais liste de contacts vide (config invalide auto-corrigée). */
        data object NoContacts : Result

        /** Expiré mais body rendu vide (template CUSTOM avec message vide). */
        data object EmptyBody : Result

        /**
         * Session [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy] active — trigger
         * supprimé pour ne pas révéler les contacts d'urgence à l'agresseur.
         * Le worker tick suivant retentera.
         */
        data object PanicSuppressed : Result

        /**
         * Trigger effectué. [sent] = SMS envoyés, [failed] = contacts pour
         * lesquels l'envoi a échoué (numéro invalide, default SMS app,
         * blocklist, échec sender).
         */
        data class Triggered(
            val sent: Int,
            val failed: Int,
            /** v1.27.2 - delai avant la prochaine relance, ou `null` si la sequence est finie. */
            val nextRelanceInMs: Long? = null,
        ) : Result

        /** v1.27.2 - le creneau avait deja ete pris par un autre tick. Rien n a ete envoye. */
        data object AlreadySent : Result

        /** v1.27.2 - la sequence de relances est terminee ; le deadman vient d etre desarme. */
        data object SequenceComplete : Result

        /**
         * v1.27.2 - AUCUN envoi n a abouti. Le creneau a ete rendu : le deadman reste arme et le
         * tick suivant retentera. Ne JAMAIS desarmer sur ce chemin.
         */
        data class SendFailed(val failed: Int) : Result
    }
}
