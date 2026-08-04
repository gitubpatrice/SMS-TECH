package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.system.notifications.SafetyCallWarningNotifier
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
 *  4. Sinon, si `isInWarningWindow()` (6h avant expiration) → pose une
 *     notification persistante "Confirme que tu vas bien" via
 *     [SafetyCallWarningNotifier]. Tap notif = reset timer.
 *  5. Sinon, hors fenêtre : annule toute notif warning éventuellement
 *     présente (cas où l'user a reset depuis dehors et la notif traîne).
 *
 * **Granularité 60 min** : compromis entre précision et batterie. Un trigger
 * peut donc se déclencher avec jusqu'à 60 min de retard sur le seuil exact
 * (acceptable pour des durées ≥ 24h). Pour la fenêtre de warning, ça veut
 * dire que la notif peut apparaître entre 6h et 5h avant expiration —
 * largement assez pour que l'user voie et reset.
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
    private val warningNotifier: SafetyCallWarningNotifier,
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
                warningNotifier.dismiss()
                return Result.success()
            }
            // Audit H3/PERF-M5 (v1.14.8) — `state.value` zéro-I/O. Le snapshot StateFlow est
            // hydraté au boot (SharingStarted.Eagerly) ; tant que le processus est vivant
            // (et il l'est ici puisque WorkManager nous a réveillés), pas besoin d'ouvrir DataStore.
            val currentBeforeCheckpoint = settings.state.value.security.safetyCall
            if (!currentBeforeCheckpoint.enabled) {
                Timber.d("SafetyCallWorker: disabled, skipping tick")
                warningNotifier.dismiss()
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
                current.isExpired() -> {
                    Timber.i("SafetyCallWorker: timer expired, delegating to trigger use case")
                    warningNotifier.dismiss()
                    triggerSafetyCall()
                }
                current.isInWarningWindow() -> {
                    val msToExpiry = (current.lastActivityAt + current.timeoutMs) -
                        System.currentTimeMillis()
                    Timber.i(
                        "SafetyCallWorker: in warning window (%d min before expiry)",
                        msToExpiry / 60_000L,
                    )
                    warningNotifier.showWarning(msToExpiryMs = msToExpiry)
                }
                else -> {
                    // Hors fenêtre de warning : on s'assure qu'aucune notif
                    // résiduelle ne traîne (cas où l'user a reset depuis
                    // ailleurs et le badge système est encore présent).
                    warningNotifier.dismiss()
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Timber.w(t, "SafetyCallWorker: tick failed, will retry on next schedule")
            Result.success() // on ne retry pas — le prochain tick (60 min) reprendra
        }
    }

    companion object {
        const val WORK_NAME = "safety_call_check_periodic"

        /** Période entre 2 ticks. 60 min = compromis précision/batterie. */
        private const val TICK_PERIOD_MINUTES: Long = 60L

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
