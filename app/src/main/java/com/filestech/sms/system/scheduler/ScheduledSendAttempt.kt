package com.filestech.sms.system.scheduler

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledState
import com.filestech.sms.domain.usecase.SendSmsUseCase
import timber.log.Timber
import javax.inject.Inject

/**
 * Une tentative d'envoi d'un message programmé, extraite de [ScheduledMessageWorker] pour être
 * testable sans le harnais WorkManager — même découpage que
 * [com.filestech.sms.system.startup.StartupMigrations].
 *
 * v1.25.3 (audit C2) — le worker marquait la ligne `FAILED` **avant** de rendre `Result.retry()`.
 * Au replay, le garde « déjà réglé » (`state != PENDING`) la considérait terminée et rendait
 * `Result.success()` sans jamais retenter l'envoi : le backoff exponentiel configuré avec soin
 * dans [ScheduledMessageSchedulerImpl] ne pouvait rien rejouer et le moindre échec devenait
 * définitif. La ligne ne bascule désormais en `FAILED` qu'une fois [MAX_ATTEMPTS] épuisé ;
 * entre-temps elle reste `PENDING`, ce qui est précisément la condition que le replay teste.
 */
class ScheduledSendAttempt @Inject constructor(
    private val dao: ScheduledMessageDao,
    private val sendSms: SendSmsUseCase,
    // v1.26.0 — un envoi programme peut porter des pieces jointes ; il faut alors la voie MMS.
    private val sendMediaMms: com.filestech.sms.domain.usecase.SendMediaMmsUseCase,
) {

    /**
     * Verdict d'une tentative, exprimé sans type WorkManager pour que la décision reste
     * vérifiable en test unitaire pur. [ScheduledMessageWorker] fait seul la traduction en
     * `ListenableWorker.Result`.
     */
    enum class Verdict {
        /** Envoyé, ligne passée en `SENT`. */
        SENT,

        /** Échec, tentatives restantes — ligne laissée `PENDING` pour que le replay la reprenne. */
        RETRY,

        /** Échec après [MAX_ATTEMPTS] tentatives, ligne passée en `FAILED`. */
        GAVE_UP,

        /** Id absent de la base (ligne purgée entre la planification et le réveil). */
        UNKNOWN_ID,

        /** Déjà envoyé, annulé ou échoué — rien à faire, le replay ne doit pas ré-envoyer. */
        ALREADY_SETTLED,

        /**
         * v1.28.3 (F20) — la ligne est revendiquée et son bail court encore : une exécution est
         * peut-être en train d'envoyer. On ne touche à rien et on **revient plus tard** — c'est
         * la différence avec [ALREADY_SETTLED], qui, lui, termine le travail.
         */
        IN_FLIGHT,

        /**
         * v1.28.3 (F20) — la ligne était revendiquée, son bail a expiré : l'exécution qui l'avait
         * prise est morte sans la régler. Passée en `INTERRUPTED`, elle devient enfin
         * **atteignable** depuis l'écran. On ne renvoie pas : l'issue est inconnue, et c'est à
         * l'utilisateur d'en décider.
         */
        INTERRUPTED,
    }

    /**
     * @param runAttemptCount compteur WorkManager, `0` à la première exécution. Le nombre de
     *   tentatives consommées en incluant celle-ci vaut donc `runAttemptCount + 1`.
     * @param now horloge injectable — le bail de [SEND_LEASE_MS] se mesure dessus, et un test ne
     *   peut pas attendre un quart d'heure.
     */
    suspend operator fun invoke(
        id: Long,
        runAttemptCount: Int,
        now: Long = System.currentTimeMillis(),
    ): Verdict {
        val entity = dao.findById(id) ?: return Verdict.UNKNOWN_ID
        if (entity.state != ScheduledState.PENDING) return verdictSurLigneDejaPrise(id, entity.state, now)
        // v1.26.1 (audit H6) — revendication ATOMIQUE avant tout appel réseau.
        //
        // Le test d'état ci-dessus ne suffisait pas : il était évalué AVANT l'envoi, et l'état
        // ne passait à `SENT` qu'APRÈS. Une mort du processus dans cet intervalle — tueur OEM
        // Samsung/Xiaomi, OOM, force-stop — faisait ré-exécuter le travail par WorkManager, qui
        // retrouvait `PENDING` et **renvoyait le message** : deux SMS reçus, deux facturés.
        //
        // `claimForSending` rend le nombre de lignes modifiées : 0 signifie qu'une autre
        // exécution a déjà pris cet envoi, on abandonne sans rien envoyer.
        if (dao.claimForSending(id, now) != 1) return Verdict.ALREADY_SETTLED
        val recipients = PhoneAddress.list(entity.addressesCsv)
        // v1.26.0 — aiguillage SMS / MMS.
        //
        // Le worker n'appelait que `sendSms`, si bien qu'un envoi programme avec une piece jointe
        // partait ampute de celle-ci : le texte arrivait, l'image jamais. La colonne
        // `attachments_json` etait remplie de rien, personne ne la lisant.
        //
        // Les fichiers ont ete rendus DURABLES au moment de programmer
        // (`ScheduleMessageUseCase`), donc ils sont encore la des heures plus tard. Si l'un d'eux
        // a malgre tout disparu, `SendMediaMmsUseCase` rend un echec de validation, ce qui
        // enclenche le meme cycle de reprise que n'importe quel autre echec : la ligne reste
        // `PENDING` et l'utilisateur la retrouve dans « Echecs » plutot que de croire l'envoi
        // parti.
        // Decodage via le mapper de l'entite : une seule voie de lecture, partagee avec la liste
        // des envois programmes. Le codec lui-meme reste interne au module `data`.
        val attachments = entity.toDomain().attachments
        val outcome = if (attachments.isEmpty()) {
            sendSms.invoke(recipients, entity.body, entity.subId, echoInGroup = recipients.size > 1)
        } else {
            sendMediaMms.invoke(recipients, attachments, entity.body, entity.subId, echoInGroup = recipients.size > 1)
        }
        return when (outcome) {
            is Outcome.Success -> {
                // v1.28.3 (F21, troisieme passage) — le rapport est enfin LU.
                //
                // Il etait jete : un envoi programme vers trois personnes dont une bloquee
                // passait `SENT` sans reserve, exactement le silence que F21 venait de fermer
                // sur le chemin interactif. Trouve par l'audit de coherence lance sur cette
                // branche, apres deux occurrences deja du meme motif.
                //
                // La ligne reste malgre tout `SENT`, et c'est un choix : la marquer en echec
                // ferait proposer une relance qui RE-ENVERRAIT aux destinataires deja servis —
                // le doublon facture que F20 s'est justement interdit d'ouvrir. Chaque
                // destinataire refuse a desormais sa propre ligne dans sa propre conversation
                // (F21), et c'est la que l'utilisateur agit. Ce qui manquait ici n'est donc pas
                // un etat, c'est une TRACE : sans elle, un envoi partiel etait indiscernable
                // d'un envoi parfait, y compris dans un rapport de diagnostic.
                val rapport = outcome.value
                if (!rapport.isComplete) {
                    Timber.w(
                        "ScheduledSendAttempt: envoi %d PARTIEL — %d remis, %d en echec, %d bloques",
                        id,
                        rapport.dispatched.size,
                        rapport.failed.size,
                        rapport.blocked.size,
                    )
                }
                dao.setState(id, ScheduledState.SENT)
                Verdict.SENT
            }
            // v1.28.3 (F21, troisieme passage) — un BLOCAGE ne se retente pas.
            //
            // `AppError.RecipientBlocked` tombait dans la branche generique et repartait pour
            // jusqu'a cinq tentatives, avec leur backoff exponentiel. Or ce refus vient d'une
            // regle que l'utilisateur a lui-meme posee : il ne se resorbe pas tout seul, et
            // aucune des quatre tentatives suivantes ne pouvait aboutir. Le message final
            // annonçait en outre « echec apres plusieurs tentatives », qui designe une panne
            // reseau — la mauvaise cause, donc la mauvaise action.
            is Outcome.Failure -> if (outcome.error is AppError.RecipientBlocked) {
                Timber.i("ScheduledSendAttempt: envoi %d abandonne — destinataire(s) bloque(s)", id)
                dao.setState(id, ScheduledState.FAILED)
                Verdict.GAVE_UP
            } else if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                dao.setState(id, ScheduledState.FAILED)
                Verdict.GAVE_UP
            } else {
                // v1.26.1 (audit H6) — la ligne est désormais en `SENDING` (revendiquée) : il
                // faut explicitement la RENDRE, sinon la reprise suivante trouverait un état
                // non-`PENDING` et abandonnerait. Avant la revendication, l'état n'avait pas
                // bougé et « ne rien écrire » suffisait — ce n'est plus vrai.
                dao.setState(id, ScheduledState.PENDING)
                Verdict.RETRY
            }
        }
    }

    /**
     * v1.28.3 (F20) — verdict sur une ligne qui n'est plus `PENDING`. **L'état `SENDING` n'est
     * plus une impasse.**
     *
     * Il tombait avec les autres dans [Verdict.ALREADY_SETTLED], que le worker traduit en
     * `Result.success()` : une exécution morte en vol laissait donc la ligne `SENDING` POUR
     * TOUJOURS. Elle restait affichée en « attente » avec une échéance dépassée, son bouton
     * « Annuler » ne pouvait rien contre elle (`cancelIfPending` ne matche que `PENDING`), et la
     * section « Échecs » ne la voyait pas. Aucune sortie — le motif exact relevé en v1.28.2 sous
     * « un garde sans issue est une impasse ».
     *
     * Le bail tranche, et c'est la seule chose qu'il tranche : envoi peut-être en cours, ou
     * exécution disparue. Dans les deux cas on s'interdit de ré-envoyer — le processus a pu
     * mourir APRÈS que `SmsManager` a accepté le message, et un renvoi automatique rouvrirait le
     * double envoi que la revendication a fermé en v1.26.1.
     */
    private suspend fun verdictSurLigneDejaPrise(id: Long, etat: ScheduledState, now: Long): Verdict {
        if (etat != ScheduledState.SENDING) return Verdict.ALREADY_SETTLED
        val conclue = dao.markInterruptedIfStale(id, cutoff = now - SEND_LEASE_MS) == 1
        return if (conclue) Verdict.INTERRUPTED else Verdict.IN_FLIGHT
    }

    companion object {
        /**
         * Tentatives totales avant abandon. Avec le backoff exponentiel de 30 s de
         * [ScheduledMessageSchedulerImpl], les replays tombent à +30 s, +1 min, +2 min puis
         * +4 min — environ 8 minutes de fenêtre.
         *
         * Pourquoi ne pas plus : [SendSmsUseCase] ne rend `Failure` que sur un échec
         * **synchrone** (pas d'app SMS par défaut, pas de SIM, `SmsManager` qui jette). Une
         * absence de réseau, elle, remonte de façon asynchrone via le `PendingIntent` d'envoi
         * et laisse le use case en `Success` — elle ne passe donc jamais par ce chemin. Au-delà
         * de quelques minutes, une cause synchrone n'a plus de raison de se résoudre seule, et
         * chaque tentative recrée une ligne d'envoi dans le fil.
         */
        const val MAX_ATTEMPTS = 5

        /**
         * v1.28.3 (F20) — durée de vie d'une revendication d'envoi.
         *
         * Bornée par WorkManager lui-même : un `CoroutineWorker` dispose de **dix minutes**
         * d'exécution, au-delà desquelles le système l'arrête. Passé ce délai, aucune exécution
         * légitime ne peut donc encore tenir la ligne, et une marge de moitié couvre l'écart
         * entre l'horloge du téléphone au moment de la revendication et celle d'aujourd'hui
         * (changement de fuseau, correction NTP).
         *
         * Trop court ferait conclure `INTERRUPTED` un envoi qui aboutit — l'utilisateur lirait
         * « issue inconnue » sur un message bel et bien parti. Trop long laisserait la ligne
         * coincée d'autant. Un quart d'heure est le compromis, et l'erreur qu'il commet est du
         * bon côté : elle retarde une information, elle n'en invente pas.
         */
        const val SEND_LEASE_MS = 15 * 60 * 1000L
    }
}
