package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * v1.28.9 (F17) — le travail qui porte la reprise des PDU gardés ([RepriseMms]).
 *
 * Planifié par le receveur dès qu'il garde un PDU, et replanifié par [TelephonySyncWorker] toutes les 12 h
 * s'il en reste un à reprendre — le filet d'un processus tué avant la planification. Nom unique et `KEEP` :
 * un travail en attente verra aussi les PDU gardés après lui, et une seconde passe concurrente n'apporterait
 * rien — la clé de transaction la rendrait de toute façon inoffensive.
 *
 * Premier passage au bout d'un quart d'heure — ce qui vient d'échouer, disque plein ou base indisponible, a
 * peu de chances d'aller mieux tout de suite —, puis un pas LINÉAIRE d'une demi-heure tant qu'un PDU reste à
 * reprendre : 30, 60, 90 minutes… jusqu'aux vingt heures au-delà desquelles [RepriseMms] ne réessaie plus.
 */
@HiltWorker
class RepriseMmsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reprise: RepriseMms,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val bilan = reprise.passe(System.currentTimeMillis())
        Timber.i("Reprise MMS: %d repris, %d a reessayer, %d laisses", bilan.repris, bilan.aReessayer, bilan.laisses)
        if (bilan.aReessayer > 0) Result.retry() else Result.success()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        // Une passe ne lève pas : chaque PDU est traité sous filet. Ceci est la défense en profondeur, BORNÉE —
        // une erreur systématique ne doit pas réveiller le téléphone indéfiniment ; le filet de 12 h reste.
        Timber.w(t, "Reprise MMS: passe echouee")
        if (runAttemptCount < TENTATIVES_MAX) Result.retry() else Result.success()
    }

    companion object {
        const val NOM = "mms_reprise"
        private const val DELAI_INITIAL_MIN = 15L
        private const val PAS_MIN = 30L
        private const val TENTATIVES_MAX = 10

        fun planifier(context: Context) {
            val requete = OneTimeWorkRequestBuilder<RepriseMmsWorker>()
                .setInitialDelay(DELAI_INITIAL_MIN, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, PAS_MIN, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOM, ExistingWorkPolicy.KEEP, requete)
            Timber.i("Reprise MMS: planifiee")
        }
    }
}
