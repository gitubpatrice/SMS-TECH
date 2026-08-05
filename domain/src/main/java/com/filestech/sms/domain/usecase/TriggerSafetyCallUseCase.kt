package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallContact
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
            current = reclaimAbandonedSlot(current.messagesSent)
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
        // v1.27.2 (audit Codex du 2026-08-05, C-03 / C-04) — IDENTITE DU PROPRIETAIRE.
        //
        // Le bail seul disait qu UN envoi etait en cours, pas LEQUEL. Un worker bloque plus de
        // `CLAIM_LEASE_MS` voyait son creneau repris - ce qui est voulu - puis, en revenant
        // tardivement, concluait ou restituait la reservation du SECOND. Et une remise a zero
        // faite pendant la boucle d envoi ne l arretait pas.
        //
        // On retient donc deux jetons : le numero de reservation qu on vient d ecrire, et la
        // generation du cycle sur laquelle on a decide. Toute ecriture ulterieure les exige.
        val myGeneration = current.generation
        var myClaimId = 0L
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
            // La generation est le jeton PROPRE : `lastActivityAt` peut coincider par hasard, une
            // generation non. Les deux sont compares, le second est le garde-fou du premier.
            val staleSnapshot = cfg.messagesSent != current.messagesSent ||
                activityMoved ||
                cfg.generation != myGeneration
            if (!cfg.enabled || staleSnapshot || leaseHeld) {
                s
            } else {
                claimed = true
                myClaimId = cfg.claimId + 1
                s.copy(
                    security = s.security.copy(
                        safetyCall = cfg.copy(
                            triggeredAt = if (cfg.isTriggered) cfg.triggeredAt else nowMs,
                            messagesSent = cfg.messagesSent + 1,
                            claimedAt = nowMs,
                            claimId = myClaimId,
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

        val tally = sendToContacts(current.contacts, body, myClaimId, myGeneration)
        val sent = tally.sent
        val failed = tally.failed
        val superseded = tally.superseded

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
        // v1.27.2 (audit Codex, C-04) — le creneau nous a ete retire en cours de route : une
        // remise a zero de l utilisateur, ou une reprise apres expiration du bail. On ne touche
        // plus a rien — ni restitution, ni conclusion, ni desarmement : l etat appartient
        // desormais a quelqu un d autre.
        if (superseded) {
            return@withContext Result.Superseded
        }

        if (sent == 0) {
            Timber.w("TriggerSafetyCallUseCase: nothing sent - releasing slot for next tick")
            settings.update { s ->
                val cfg = s.security.safetyCall
                // Restitution conditionnee a la PROPRIETE, pas au seul compteur : sans
                // `claimId`, un worker revenu tardivement restituait la reservation d un autre,
                // ramenant le compteur en arriere pendant un envoi reel.
                if (cfg.claimId != myClaimId || cfg.generation != myGeneration) {
                    s
                } else {
                    s.copy(
                        security = s.security.copy(
                            safetyCall = cfg.copy(
                                triggeredAt = if (isRelance) cfg.triggeredAt else 0L,
                                messagesSent = current.messagesSent,
                                claimedAt = 0L,
                                claimId = 0L,
                            ),
                        ),
                    )
                }
            }
            return@withContext Result.SendFailed(failed = failed)
        }

        // Envoi conclu : le bail est leve. Sans cette ligne, le creneau suivant serait bloque
        // pendant deux minutes, et une relance due dans cet intervalle serait sautee.
        //
        // v1.27.2 (audit Codex, C-03) — la levee est CONDITIONNEE a la propriete. Sans elle, un
        // worker revenu tardivement levait le bail d un envoi en cours et ouvrait la porte a un
        // troisieme concurrent.
        val messagesSentNow = current.messagesSent + 1
        val remaining = SafetyCallConfig.TOTAL_MESSAGES - messagesSentNow
        var concluded = false
        settings.update { s ->
            val cfg = s.security.safetyCall
            if (cfg.claimId != myClaimId || cfg.generation != myGeneration) {
                s
            } else {
                concluded = true
                s.copy(
                    security = s.security.copy(
                        // Le desarmement de fin de sequence est ecrit DANS la meme transaction
                        // que la levee du bail. Separes, un reset glisse entre les deux pouvait
                        // desactiver le cycle tout neuf que l utilisateur venait d ouvrir.
                        safetyCall = cfg.copy(
                            claimedAt = 0L,
                            claimId = 0L,
                            enabled = if (remaining <= 0) false else cfg.enabled,
                        ),
                    ),
                )
            }
        }
        if (!concluded) {
            Timber.i("TriggerSafetyCallUseCase: creneau perdu apres envoi, conclusion abandonnee")
            return@withContext Result.Superseded
        }
        if (remaining <= 0) {
            Timber.i(
                "TriggerSafetyCallUseCase: sequence complete (%d messages), disarming",
                messagesSentNow,
            )
        }
        Result.Triggered(
            sent = sent,
            failed = failed,
            nextRelanceInMs = if (remaining > 0) SafetyCallConfig.RELANCE_INTERVAL_MS else null,
        )
    }

    /** Bilan d'une passe d'envoi. [superseded] = le creneau nous a ete retire en cours de route. */
    private data class SendTally(val sent: Int, val failed: Int, val superseded: Boolean)

    /**
     * Envoie [body] a chaque contact, en verifiant AVANT CHAQUE ENVOI que le creneau nous
     * appartient toujours.
     *
     * v1.27.2 (audit Codex, C-04) — la verification est dans la boucle, pas avant elle. Comparer
     * `lastActivityAt` fermait la fenetre « instantane -> reservation », pas
     * « reservation -> envoi » : quelqu'un qui confirmait aller bien PENDANT la boucle ne
     * l'arretait pas, et les SMS d'urgence continuaient de partir vers ses proches. Un SMS deja
     * parti ne se rattrape pas — le cout est une lecture DataStore chaude par destinataire, sur un
     * chemin qui en compte quatre au maximum.
     *
     * Un contact invalide n'interrompt PAS les autres : un seul mauvais numero ne doit pas priver
     * les trois autres proches de l'alerte.
     */
    private suspend fun sendToContacts(
        contacts: List<SafetyCallContact>,
        body: String,
        claimId: Long,
        generation: Long,
    ): SendTally {
        var sent = 0
        var failed = 0
        for ((index, contact) in contacts.withIndex()) {
            if (!stillOwnsClaim(claimId, generation)) {
                Timber.i("TriggerSafetyCallUseCase: creneau perdu en cours d envoi, arret")
                return SendTally(sent, failed, superseded = true)
            }
            val target = contact.takeIf { it.isValid() }?.let { PhoneAddress.of(it.phoneNumber) }
            if (target == null || target.normalized.isEmpty()) {
                Timber.w("TriggerSafetyCallUseCase: skipping unusable contact #%d", index)
                failed++
                continue
            }
            when (sendSms(recipients = listOf(target), body = body, appendSignature = false)) {
                is Outcome.Success -> sent++
                is Outcome.Failure -> {
                    Timber.w("TriggerSafetyCallUseCase: send failed for contact #%d", index)
                    failed++
                }
            }
        }
        return SendTally(sent, failed, superseded = false)
    }

    /**
     * v1.27.2 — reprend un creneau dont le bail a expire, et rend la configuration RELUE.
     *
     * Extrait de `invoke` pour la lisibilite autant que pour la complexite : la reprise est une
     * operation a part entiere, avec sa propre garde de concurrence (`isClaimAbandoned` est
     * re-teste DANS la transaction, car un autre worker a pu reprendre le creneau entre-temps).
     */
    private suspend fun reclaimAbandonedSlot(slot: Int): SafetyCallConfig {
        Timber.w("TriggerSafetyCallUseCase: creneau %d abandonne, reprise", slot)
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
                            claimId = 0L,
                        ),
                    ),
                )
            }
        }
        return settings.flow.first().security.safetyCall
    }

    /**
     * v1.27.2 (audit Codex, C-03 / C-04) — le créneau réservé nous appartient-il **toujours** ?
     *
     * Deux façons de le perdre : un autre worker a repris un bail qu'il croyait abandonné, ou
     * l'utilisateur a confirmé aller bien et ouvert une nouvelle génération de cycle. Dans les
     * deux cas, ce worker n'a plus rien à envoyer ni rien à écrire.
     */
    private suspend fun stillOwnsClaim(claimId: Long, generation: Long): Boolean {
        val cfg = settings.flow.first().security.safetyCall
        return cfg.claimId == claimId && cfg.generation == generation
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

        /**
         * v1.27.2 (audit Codex, C-03 / C-04) — le creneau nous a ete RETIRE en cours de route :
         * reprise par un autre worker apres expiration du bail, ou nouvelle generation de cycle
         * ouverte par une confirmation « je vais bien ».
         *
         * Distinct de [AlreadySent], qui signifie « on n a jamais obtenu le creneau ». Ici on
         * l avait, et on a pu envoyer une partie des messages avant de le perdre. L appelant ne
         * doit RIEN conclure de cet etat : il appartient desormais a quelqu un d autre.
         */
        data object Superseded : Result

        /** v1.27.2 - la sequence de relances est terminee ; le deadman vient d etre desarme. */
        data object SequenceComplete : Result

        /**
         * v1.27.2 - AUCUN envoi n a abouti. Le creneau a ete rendu : le deadman reste arme et le
         * tick suivant retentera. Ne JAMAIS desarmer sur ce chemin.
         */
        data class SendFailed(val failed: Int) : Result
    }
}
