package com.filestech.sms.system.scheduler

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase
import com.filestech.sms.security.AppLockManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * v1.9.0 — Worker périodique du Safety call.
 *
 * Tick toutes les **60 minutes**. À chaque tick :
 *  1. Lit la config courante depuis [SettingsRepository].
 *  2. Si `enabled = false` → no-op (mais le worker périodique reste schedulé,
 *     coût négligeable d'un tick par heure).
 *  3. Si `isExpired()` → délègue à [TriggerSafetyCallUseCase]
 *     qui envoie les SMS aux contacts et désactive la config.
 *  4. Sinon, rien : il **n'affiche plus aucune notification**.
 *
 * ⚠️ **v1.27.2 (audit Codex du 2026-08-05, P-05) — ce worker n'est plus un afficheur.**
 * L'avertissement de pré-déclenchement comme le suivi de séquence appartiennent au réconciliateur
 * unique de [com.filestech.sms.MainApplication], qui décide sur trois états exclusifs via
 * [com.filestech.sms.domain.safetycall.SafetyCallNotice]. Deux écrivains sans état commun
 * laissaient les deux notifications coexister, et l'avertissement survivait à l'entrée en mode
 * leurre.
 *
 * Le jalon monotone écrit à chaque tick suffit à réveiller ce réconciliateur : c'est cette écriture
 * qui fait descendre le compte à rebours affiché.
 *
 * **Granularité 60 min** : compromis entre précision et batterie, désormais doublé par le réveil à
 * l'échéance de [SafetyCallAlarmScheduler]. Ce tick reste le filet — les alarmes ne survivent pas à
 * un redémarrage, et certains OEM les effacent à la fermeture forcée.
 *
 * **Idempotence** : `KEEP` policy au schedule — si le worker est déjà
 * schedulé, on garde l'existant (évite reset du compteur de période à
 * chaque cold-start).
 */
@HiltWorker
class SafetyCallWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val triggerSafetyCall: TriggerSafetyCallUseCase,
    private val appLock: AppLockManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // v1.9.0 audit fix CRITICAL — défense en profondeur : si l'app
            // est en session panic-decoy, on dismiss toute notif warning et
            // on saute le tick. Le TriggerService garde aussi cette check
            // (ceinture+bretelles, le worker pouvant aussi être déclenché
            // par d'autres chemins).
            if (appLock.state.value is AppLockManager.LockState.PanicDecoy) {
                Timber.i("SafetyCallWorker: PanicDecoy active, suppressing tick")
                // v1.27.2 (audit Codex du 2026-08-05, P-05) — plus aucun retrait de notification
                // ici : le réconciliateur unique de `MainApplication` observe déjà `appLock.state`
                // et retire LES DEUX notifications à l'entrée en mode leurre. Le worker n'en
                // retirait qu'une, et l'autre survivait à la session sous contrainte.
                //
                // v1.27.2 (relecture Gemini du 2026-08-05) — l'alarme d'échéance vient peut-être
                // d'être CONSOMMÉE par ce tick supprimé. Sans ce rappel, on retomberait sur le
                // tick horaire — donc sur le défaut que ce lot corrige — juste après une session
                // sous contrainte, c'est-à-dire précisément quand l'alerte compte le plus.
                //
                // Aucune information n'est écrite ni affichée : le rappel est invisible pour qui
                // tient le téléphone.
                SafetyCallAlarmScheduler.retryIn(applicationContext, PANIC_RETRY_MS)
                return Result.success()
            }
            // Audit H3/PERF-M5 (v1.14.8) — `state.value` zéro-I/O. Le snapshot StateFlow est
            // hydraté au boot (SharingStarted.Eagerly) ; tant que le processus est vivant
            // (et il l'est ici puisque WorkManager nous a réveillés), pas besoin d'ouvrir DataStore.
            // v1.27.2 (défaut remonté par Patrice le 2026-08-05 : « le Safety Call n'envoie
            // jamais rien ») — lecture SUSPENDUE de DataStore, et non plus l'instantané chaud.
            //
            // `settings.state` est un `stateIn(..., SharingStarted.Eagerly, AppSettings())` : sa
            // valeur initiale est la configuration PAR DÉFAUT, dans laquelle `safetyCall.enabled`
            // vaut **false**. L'hydratation depuis DataStore est asynchrone.
            //
            // Or ce worker est réveillé par WorkManager, c'est-à-dire le plus souvent sur un
            // processus qui vient de DÉMARRER. La garde ci-dessous lisait donc `enabled = false`
            // avant que DataStore n'ait répondu, concluait « Safety call désactivé », **effaçait
            // la notification d'avertissement** et rendait `success()`. Le deadman ne partait
            // jamais — silencieusement, et d'autant plus sûrement que le processus était mort,
            // c'est-à-dire exactement quand la personne n'utilise plus son téléphone.
            //
            // ⚠️ Le commentaire d'origine affirmait le contraire : « le processus est vivant ici
            // puisque WorkManager nous a réveillés ». C'est précisément l'inverse — être réveillé
            // signifie que le processus vient de naître. Un repli qui échoue du mauvais côté sur
            // une fonction de sécurité personnelle.
            //
            // `TriggerSafetyCallUseCase` lit déjà, lui, via `settings.flow.first()`.
            val currentBeforeCheckpoint = settings.flow.first().security.safetyCall
            if (!currentBeforeCheckpoint.enabled) {
                Timber.d("SafetyCallWorker: disabled, skipping tick")
                return Result.success()
            }
            // v1.27.2 (audit externe Gemini 2026-08-04) — JALON du temps monotone.
            //
            // On capitalise le segment écoulé depuis le dernier jalon et on re-cale l'ancre.
            // C'est ce qui permet au compteur monotone de survivre à un redémarrage : sans
            // jalon, `elapsedRealtime()` repartant de zéro, la récupération de dérive
            // ramenait le compteur à zéro et redémarrer plus souvent que le délai empêchait
            // le deadman de partir — indéfiniment.
            //
            // Le calcul est fait DANS le `update` (lecture-modification-écriture atomique de
            // DataStore) : le lire dehors laisserait une fenêtre où un « Je vais bien »
            // concurrent remettrait les compteurs à zéro et où l'on ré-écrirait par-dessus un
            // capital périmé — un reset utilisateur silencieusement annulé.
            //
            // Aucun `lastActivityAt` n'est touché : un jalon n'est PAS une activité de
            // l'utilisateur, il ne repousse jamais l'échéance.
            val nowMonoTick = android.os.SystemClock.elapsedRealtime()
            settings.update { s ->
                val cfg = s.security.safetyCall
                if (!cfg.enabled || cfg.monotonicLastActivityAt == 0L) {
                    s
                } else {
                    s.copy(
                        security = s.security.copy(
                            safetyCall = cfg.copy(
                                monotonicAccumulatedMs = cfg.monoElapsedMs(nowMonoTick),
                                monotonicLastActivityAt = nowMonoTick,
                            ),
                        ),
                    )
                }
            }
            // On décide sur l'instantané d'AVANT le jalon, et non sur une relecture de
            // `settings.state` : ce `StateFlow` se ré-hydrate depuis DataStore de façon
            // asynchrone, une relecture immédiate rendrait donc peut-être encore l'ancienne
            // valeur. Le jalon est de toute façon neutre pour la décision — il déplace du
            // temps de `(nowMono - ancre)` vers `monotonicAccumulatedMs` sans en changer la
            // somme, et `monoElapsedMs` rend la même chose des deux côtés à cet instant.
            val current = currentBeforeCheckpoint
            when {
                // v1.27.2 — une séquence de relances est ouverte : elle a la priorité sur tout le
                // reste. `isExpired` et `isInWarningWindow` rendent déjà `false` dans cet état,
                // mais l'ordre est explicite pour que la lecture ne laisse aucun doute.
                current.hasRelancePending -> {
                    if (current.isRelanceDue()) {
                        Timber.i(
                            "SafetyCallWorker: relance %d due, delegating",
                            current.messagesSent,
                        )
                        reflectSequence(triggerSafetyCall())
                    } else {
                        // Pas encore l'heure : on se contente de (re)poser le rendez-vous, au cas
                        // où le travail ponctuel aurait été perdu — redémarrage, nettoyage OEM.
                        current.nextRelanceAt()?.let { at ->
                            scheduleRelance(
                                applicationContext,
                                (at - System.currentTimeMillis()).coerceAtLeast(0L),
                            )
                        }
                    }
                }
                current.isExpired() -> {
                    Timber.i("SafetyCallWorker: timer expired, delegating to trigger use case")
                    reflectSequence(triggerSafetyCall())
                }
                // v1.27.2 (audit Codex du 2026-08-05, P-05) — CE WORKER N'AFFICHE PLUS RIEN.
                //
                // Il posait ici l'avertissement de pré-déclenchement et le retirait dans l'`else`.
                // Deux écrivains pour deux notifications, sans état commun : à l'expiration, la
                // branche d'envoi ci-dessus ne retirait jamais l'avertissement, et le
                // réconciliateur publiait la séquence à côté. 🔴 En entrant en mode leurre, seule
                // la séquence disparaissait — l'avertissement, lui, révélait durablement à
                // l'agresseur qu'une fonction d'alerte existait.
                //
                // L'affichage appartient désormais en entier au réconciliateur unique de
                // `MainApplication`, qui décide via [SafetyCallNotice.decide] sur trois états
                // exclusifs. Le jalon monotone écrit plus haut suffit à le réveiller : c'est cette
                // écriture qui fait descendre le compte à rebours affiché, à chaque tick.
                else -> Timber.d("SafetyCallWorker: rien a declencher a ce tick")
            }
            Result.success()
        } catch (t: Throwable) {
            Timber.w(t, "SafetyCallWorker: tick failed, will retry on next schedule")
            Result.success() // on ne retry pas — le prochain tick (60 min) reprendra
        }
    }

    /**
     * v1.27.2 — pose le rendez-vous de la relance suivante quand le use case en annonce une.
     *
     * Un travail **ponctuel** est indispensable : le tick périodique est à 60 min alors que
     * les relances sont à 15 min. Sans lui, la séquence s'étirerait sur quatre heures au lieu
     * de quarante-cinq minutes.
     */
    private fun planNextRelance(result: TriggerSafetyCallUseCase.Result) {
        val next = (result as? TriggerSafetyCallUseCase.Result.Triggered)?.nextRelanceInMs ?: return
        scheduleRelance(applicationContext, next)
    }

    /**
     * v1.27.2 — pose le rendez-vous suivant **et** met la notification en accord avec l'état réel
     * de la séquence.
     *
     * Les deux vont ensemble et c'est délibéré : programmer une relance sans laisser à
     * l'utilisateur le moyen de l'arrêter, c'est ce que faisait la version précédente. Le seul
     * moyen d'y couper court était d'ouvrir l'application et de fouiller les Réglages.
     *
     * ⚠️ **La décision est prise sur l'ÉTAT PERSISTÉ, pas sur le type du résultat.**
     *
     * La première version énumérait les résultats et retirait la notification dans tout le reste
     * (`else`). Deux cas y tombaient à tort, et ce sont précisément ceux où la séquence est
     * **encore ouverte** :
     *
     *  - [TriggerSafetyCallUseCase.Result.SendFailed] sur une relance — le créneau est rendu, le
     *    deadman reste armé, les relances suivantes vont partir ; retirer la notification privait
     *    l'utilisateur de son seul moyen de les arrêter jusqu'au prochain réveil ;
     *  - [TriggerSafetyCallUseCase.Result.AlreadySent] — un autre worker a pris le créneau et
     *    envoie ; la séquence court toujours.
     *
     * Énumérer des cas pour décider d'une garde est le motif de défaut qui revient le plus souvent
     * sur ce projet : la liste vieillit à chaque résultat ajouté. On relit donc l'état, qui est la
     * seule source de vérité — et qui donne aussi le vrai compteur, là où `messagesSent + 1`
     * supposait que la réservation avait réussi.
     */
    private fun reflectSequence(result: TriggerSafetyCallUseCase.Result) {
        planNextRelance(result)
        // v1.27.2 (audit Codex du 2026-08-05, C-07 / C-08) — CE WORKER NE TOUCHE PLUS À LA
        // NOTIFICATION DE SÉQUENCE.
        //
        // Il la posait ici, après l'envoi. Deux trous en découlaient :
        //
        //  - **C-07** : entre sa garde panic-decoy et cette ligne, l'application pouvait entrer en
        //    mode leurre. L'observateur retirait bien la notification, puis le worker la
        //    REPUBLIAIT depuis l'état persistant — et plus rien ne venait l'effacer. Sous
        //    contrainte, l'agresseur voyait réapparaître qu'une alerte et un réseau de proches
        //    existaient.
        //  - **C-08** : à l'inverse, si le processus mourait entre le dernier envoi et le
        //    `dismiss`, Android conservait une notification « alerte en cours » que personne ne
        //    réconciliait au redémarrage.
        //
        // Les deux viennent de la même cause : deux écrivains pour un même affichage, sans état
        // commun. `MainApplication` en est désormais l'unique propriétaire, et il décide sur le
        // couple (configuration persistée, état du verrou) — donc en réagissant aussi bien à
        // l'entrée en leurre qu'à un « Je vais bien », et en se rejouant à chaque démarrage.
    }

    companion object {
        const val WORK_NAME = "safety_call_check_periodic"

        /** v1.27.2 — nom unique du travail ponctuel qui porte la prochaine relance. */
        const val RELANCE_WORK_NAME = "safety_call_relance_oneshot"

        /** v1.27.2 — nom unique du contrôle immédiat déclenché par l'alarme d'échéance. */
        const val IMMEDIATE_WORK_NAME = "safety_call_check_now"

        /**
         * v1.27.2 — annule le rendez-vous de relance en attente.
         *
         * Constaté sur appareil le 2026-08-05 : après avoir arrêté le Safety call en pleine
         * séquence, le travail ponctuel de la relance suivante restait **en file**. Il était
         * inoffensif — le worker relit la configuration au réveil et ne trouve plus rien à
         * envoyer — mais c'est un réveil orphelin, qui sort le téléphone de veille pour rien et
         * survit à l'événement qui l'avait justifié.
         *
         * Appelé depuis l'observateur unique de `MainApplication`, dès que la séquence cesse
         * d'être ouverte : fin normale, « Je vais bien », désactivation, ou simple ouverture de
         * l'application.
         */
        fun cancelRelance(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(RELANCE_WORK_NAME)
            Timber.d("SafetyCallWorker: rendez-vous de relance annule")
        }

        /**
         * v1.27.2 — contrôle **immédiat**, déclenché par
         * [com.filestech.sms.system.receiver.SafetyCallAlarmReceiver] à l'échéance exacte.
         *
         * C'est ce qui rend le déclenchement ponctuel : sans lui, une échéance atteinte à 14:25
         * attendait le tick horaire suivant — 14:48 le 2026-08-05, 23 minutes de retard sur un
         * délai d'une heure.
         */
        fun enqueueNow(context: Context) {
            val builder = OneTimeWorkRequestBuilder<SafetyCallWorker>()
            // 🔴 v1.27.2 (relecture Gemini du 2026-08-05, F-02) — TOUTE LA PONCTUALITE SE JOUAIT
            // SUR CETTE LIGNE.
            //
            // WorkManager s'appuie sur JobScheduler, qui **diffère les tâches ordinaires pendant le
            // Doze**. L'alarme réveillait donc bien le téléphone, le receveur s'exécutait bien, il
            // mettait ce travail en file… et le système le rangeait jusqu'à la prochaine fenêtre de
            // maintenance. L'ordonnanceur d'alarme, l'arrondi vers le futur, le réveil
            // d'avertissement : tout était neutralisé au dernier maillon.
            //
            // ⚠️ **Uniquement à partir d'Android 12, et c'est délibéré.** Sur 8 à 11, WorkManager
            // exécute un travail urgent comme un **service de premier plan**, ce qui impose
            // `getForegroundInfo()` et affiche une **notification obligatoire** — publiée AVANT que
            // `doWork` n'ait pu exécuter sa garde mode leurre. Sous contrainte, une notification
            // SMS Tech apparaîtrait pendant que la personne prétend n'avoir rien à cacher. On
            // échangerait un défaut de ponctualité contre un défaut de SÉCURITÉ ; sur cette
            // application, l'échange n'est pas acceptable.
            //
            // À partir d'Android 12, le travail urgent passe par un job à haute priorité **sans
            // aucune notification** : le gain est gratuit, on le prend. En deçà, le tick horaire
            // reste le filet — exactement le comportement d'avant ce lot, sans régression.
            //
            // `RUN_AS_NON_EXPEDITED_WORK_REQUEST` : quota d'urgence épuisé, le travail s'exécute
            // normalement au lieu d'échouer. Tard vaut mieux que jamais sur un homme-mort.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = builder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                // v1.27.2 (audit Codex du 2026-08-05, SC-02) — 🔴 `KEEP`, surtout pas `REPLACE`.
                //
                // `REPLACE` ANNULE le travail deja actif. Si l alarme etait livree deux fois, le
                // second appel tuait le premier worker — potentiellement APRES qu il ait reserve
                // son creneau mais AVANT le premier envoi. Le compteur restait alors a « un
                // message parti » sans qu aucun SMS ne soit parti, et la sequence enchainait sur
                // une relance : les proches n auraient JAMAIS recu le message initial.
                //
                // Deux controles identiques n ont aucune raison de s annuler l un l autre : le
                // second n apporte rien que le premier ne fasse deja. On garde donc celui qui
                // tourne. Le bail (`claimedAt`) couvre le cas ou il meurt malgre tout.
                ExistingWorkPolicy.KEEP,
                request,
            )
            Timber.i("SafetyCallWorker: controle immediat mis en file")
        }

        /**
         * v1.27.2 — programme la prochaine relance dans [delayMs].
         *
         * `REPLACE` et non `KEEP` : si un rendez-vous plus ancien traîne — relance perdue,
         * worker rejoué après un redémarrage, nettoyage OEM — c'est **toujours le calcul le
         * plus récent** qui fait foi. Deux rendez-vous concurrents ne pourraient de toute
         * façon pas envoyer deux fois : la réservation atomique du créneau, côté use case,
         * s'en charge.
         */
        fun scheduleRelance(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<SafetyCallWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RELANCE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Timber.i("SafetyCallWorker: relance scheduled in %d min", delayMs / 60_000L)
        }

        /** Période entre 2 ticks. 60 min = compromis précision/batterie. */
        private const val TICK_PERIOD_MINUTES: Long = 60L

        /**
         * v1.27.2 — délai de rappel quand un tick est supprimé par la garde panic-decoy.
         *
         * Quinze minutes : assez court pour ne pas perdre la ponctualité si la session leurre est
         * brève, assez long pour ne pas transformer une session prolongée en réveil en boucle.
         */
        private const val PANIC_RETRY_MS: Long = 15 * 60 * 1000L

        /**
         * Schedule le worker périodique. Idempotent (policy KEEP).
         * Appelé depuis [com.filestech.sms.MainApplication.onCreate] —
         * même si le deadman est désactivé, on schedule quand même, ainsi
         * un enable ultérieur n'a pas besoin de tâche supplémentaire.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SafetyCallWorker>(
                TICK_PERIOD_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Timber.i("SafetyCallWorker: scheduled periodic tick every %d min", TICK_PERIOD_MINUTES)
        }
    }
}
