package com.filestech.sms.system.scheduler

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledState
import com.filestech.sms.domain.usecase.SendSmsUseCase
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
    }

    /**
     * @param runAttemptCount compteur WorkManager, `0` à la première exécution. Le nombre de
     *   tentatives consommées en incluant celle-ci vaut donc `runAttemptCount + 1`.
     */
    suspend operator fun invoke(id: Long, runAttemptCount: Int): Verdict {
        val entity = dao.findById(id) ?: return Verdict.UNKNOWN_ID
        if (entity.state != ScheduledState.PENDING) return Verdict.ALREADY_SETTLED
        // v1.26.1 (audit H6) — revendication ATOMIQUE avant tout appel réseau.
        //
        // Le test d'état ci-dessus ne suffisait pas : il était évalué AVANT l'envoi, et l'état
        // ne passait à `SENT` qu'APRÈS. Une mort du processus dans cet intervalle — tueur OEM
        // Samsung/Xiaomi, OOM, force-stop — faisait ré-exécuter le travail par WorkManager, qui
        // retrouvait `PENDING` et **renvoyait le message** : deux SMS reçus, deux facturés.
        //
        // `claimForSending` rend le nombre de lignes modifiées : 0 signifie qu'une autre
        // exécution a déjà pris cet envoi, on abandonne sans rien envoyer.
        if (dao.claimForSending(id) != 1) return Verdict.ALREADY_SETTLED
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
            sendSms.invoke(recipients, entity.body, entity.subId)
        } else {
            sendMediaMms.invoke(recipients, attachments, entity.body, entity.subId)
        }
        return when (outcome) {
            is Outcome.Success -> {
                dao.setState(id, ScheduledState.SENT)
                Verdict.SENT
            }
            is Outcome.Failure -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
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
    }
}
