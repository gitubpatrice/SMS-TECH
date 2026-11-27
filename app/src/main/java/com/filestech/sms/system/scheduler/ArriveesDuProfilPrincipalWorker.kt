package com.filestech.sms.system.scheduler

import android.content.Context
import android.os.Build
import android.provider.Telephony
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.system.receiver.ArriveesDuProfilPrincipal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 2026-11-27 (issue #98) — **la passe d'un profil secondaire** : synchroniser et notifier ce que le profil principal
 * a reçu, cf. [ArriveesDuProfilPrincipal]. Deux façons d'être lancée :
 *
 * - [apresUnSms] : par `SMS_RECEIVED` — une passe urgente, puis une relecture ([RELECTURE_SMS_MS]) si le principal a
 *   tardé à écrire le message ;
 * - [observerLeStockage] : à chaque changement de `content://mms-sms/` — le seul signal d'un MMS reçu par le
 *   principal (il y écrit sa copie). Une tâche ainsi déclenchée ne sert qu'une fois : chaque passe arme la suivante,
 *   sous L'AUTRE de deux noms, AVANT de travailler — un changement survenu pendant la passe déclenche donc la suivante
 *   au lieu d'attendre le prochain.
 */
@HiltWorker
class ArriveesDuProfilPrincipalWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val arrivees: ArriveesDuProfilPrincipal,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val observation = inputData.getString(CLE_OBSERVATION)
        if (!ArriveesDuProfilPrincipal.aEcouter(applicationContext)) return Result.success()
        if (observation != null) {
            armer(applicationContext, if (observation == OBSERVATION_A) OBSERVATION_B else OBSERVATION_A)
        }
        val raison = if (observation != null) "stockage modifie" else "SMS_RECEIVED"
        runCatchingCancellable { arrivees.importerEtNotifier(raison) }
            .onSuccess { if (it > 0) Timber.i("Arrivees au profil principal : %d notification(s)", it) }
            .onFailure { Timber.w(it, "Arrivees au profil principal : passe en echec") }
        // Jamais d'échec : le signal suivant, ou la relecture, refera la passe ; un nouvel essai en boucle, non.
        return Result.success()
    }

    companion object {
        private const val CLE_OBSERVATION = "observation"
        private const val OBSERVATION_A = "arrivees-profil-principal-observation-a"
        private const val OBSERVATION_B = "arrivees-profil-principal-observation-b"
        private const val PASSE_SMS = "arrivees-profil-principal-sms"
        private const val RELECTURE_SMS = "arrivees-profil-principal-sms-relecture"

        /** La relecture après un SMS : le principal écrit le message avant `SMS_RECEIVED`, sauf s'il échoue. */
        const val RELECTURE_SMS_MS: Long = 30_000L

        /** Les écritures d'une copie de MMS (ligne, adresses, parties, passage en réception) comptent pour une. */
        private val APAISEMENT: Duration = Duration.ofSeconds(2)
        private val ATTENTE_MAX: Duration = Duration.ofSeconds(20)

        /**
         * Après `SMS_RECEIVED`. Urgente à partir d'Android 12 seulement, comme `SafetyCallWorker` : avant, WorkManager
         * en ferait un service de premier plan, avec une notification imposée.
         */
        fun apresUnSms(context: Context) {
            if (!ArriveesDuProfilPrincipal.aEcouter(context)) return
            val urgente = OneTimeWorkRequestBuilder<ArriveesDuProfilPrincipalWorker>().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }.build()
            val relecture = OneTimeWorkRequestBuilder<ArriveesDuProfilPrincipalWorker>()
                .setInitialDelay(RELECTURE_SMS_MS, TimeUnit.MILLISECONDS)
                .build()
            val travaux = WorkManager.getInstance(context)
            // KEEP : une passe en attente ou en cours prendra aussi ce SMS-ci, et la relecture le rattrape sinon.
            travaux.enqueueUniqueWork(PASSE_SMS, ExistingWorkPolicy.KEEP, urgente)
            travaux.enqueueUniqueWork(RELECTURE_SMS, ExistingWorkPolicy.KEEP, relecture)
        }

        /** Au démarrage de l'application et à chaque retour au premier plan : sans effet hors d'un profil à écouter. */
        fun observerLeStockage(context: Context) {
            if (!ArriveesDuProfilPrincipal.aEcouter(context)) return
            armer(context, OBSERVATION_A)
        }

        private fun armer(context: Context, nom: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(nom, ExistingWorkPolicy.KEEP, requeteDObservation(nom))
        }

        private fun requeteDObservation(nom: String): OneTimeWorkRequest {
            val contraintes = Constraints.Builder()
                .addContentUriTrigger(Telephony.MmsSms.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(APAISEMENT)
                .setTriggerContentMaxDelay(ATTENTE_MAX)
                .build()
            return OneTimeWorkRequestBuilder<ArriveesDuProfilPrincipalWorker>()
                .setConstraints(contraintes)
                .setInputData(workDataOf(CLE_OBSERVATION to nom))
                .build()
        }
    }
}
