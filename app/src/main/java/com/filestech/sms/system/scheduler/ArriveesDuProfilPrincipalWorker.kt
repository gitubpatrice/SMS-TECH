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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.system.receiver.ArriveesDuProfilPrincipal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 2026-11-27 (issue #98) — **la passe d'un profil secondaire** : synchroniser et notifier ce que le profil principal
 * a reçu, cf. [ArriveesDuProfilPrincipal]. Trois façons d'être lancée :
 *
 * - [apresUnSms] : par `SMS_RECEIVED` — une passe urgente PAR SMS, puis une relecture ([RELECTURE_SMS_MS]) si le
 *   principal a tardé à écrire le message ;
 * - l'observation du stockage ([assurerLObservation]) : à chaque changement de `content://mms-sms/`, le seul signal
 *   d'un MMS reçu par le principal (il y écrit sa copie) ;
 * - [apresUnDemarrage] : au démarrage de l'utilisateur, pour ce qui est arrivé pendant qu'il était arrêté.
 *
 * # L'observation ne doit jamais s'éteindre
 *
 * Une tâche déclenchée par un changement de contenu ne sert qu'une fois. Deux noms se relaient, et la règle est :
 * après chaque passe, UNE observation attend un changement — en attente (`ENQUEUED`), ou placée derrière une passe qui
 * tourne encore (`BLOCKED`, par `APPEND_OR_REPLACE`). Elle est vérifiée au début de la passe (un changement pendant
 * qu'elle tourne en déclenche une autre) et à la fin (l'observation armée au début a pu servir entre-temps).
 *
 * La première version armait l'autre nom en `KEEP`, au début seulement. Deux observations qui se chevauchaient —
 * la copie d'une photo écrit sa ligne, puis la passe en réception plus de deux secondes après — ne trouvaient chacune
 * que l'autre EN COURS, que `KEEP` laisse en place : une fois les deux finies, plus rien n'attendait, et plus aucun
 * MMS n'était notifié jusqu'à la prochaine ouverture (relectures Claude H1 et GPT).
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
        try {
            if (observation != null) assurerLObservation(applicationContext, observation)
            val raison = observation?.let { "stockage modifie" } ?: inputData.getString(CLE_RAISON) ?: "signal"
            runCatchingCancellable { arrivees.importerEtNotifier(raison) }
                .onSuccess { if (it > 0) Timber.i("Arrivees au profil principal : %d notification(s)", it) }
                .onFailure { Timber.w(it, "Arrivees au profil principal : passe en echec") }
        } finally {
            // Arrêtée en cours de route, la passe laisse quand même une observation derrière elle.
            withContext(NonCancellable) { assurerLObservation(applicationContext, observation) }
        }
        // Jamais d'échec : le signal suivant, ou la relecture, refera la passe ; un nouvel essai en boucle, non.
        return Result.success()
    }

    companion object {
        private const val CLE_OBSERVATION = "observation"
        private const val CLE_RAISON = "raison"
        internal const val OBSERVATION_A = "arrivees-profil-principal-observation-a"
        internal const val OBSERVATION_B = "arrivees-profil-principal-observation-b"
        private const val RELECTURE_SMS = "arrivees-profil-principal-sms-relecture"
        private const val PASSE_DEMARRAGE = "arrivees-profil-principal-demarrage"
        private const val ETIQUETTE = "arrivees-profil-principal"

        /** La relecture après un SMS : le principal écrit le message avant `SMS_RECEIVED`, sauf s'il échoue. */
        const val RELECTURE_SMS_MS: Long = 30_000L

        /** Les écritures d'une copie de MMS (ligne, adresses, parties, passage en réception) comptent pour une. */
        private val APAISEMENT: Duration = Duration.ofSeconds(2)
        private val ATTENTE_MAX: Duration = Duration.ofSeconds(20)

        /** Vérifier puis armer d'un seul tenant : deux passes qui finissent ensemble n'arment pas deux fois. */
        private val verrouDArmement = Mutex()

        /**
         * Après `SMS_RECEIVED`. Une passe par SMS, et non une passe unique en `KEEP` : celle-ci laissait le SMS
         * suivant, arrivé pendant qu'elle tournait, à la relecture, qui n'est pas urgente et attend donc la sortie
         * du Doze (relecture Claude M1). Les passes s'enchaînent sous le verrou de [ArriveesDuProfilPrincipal], et le
         * repère empêche toute double notification. Urgente à partir d'Android 12 seulement, comme `SafetyCallWorker` :
         * avant, WorkManager en ferait un service de premier plan, avec une notification imposée.
         */
        fun apresUnSms(context: Context) {
            if (!ArriveesDuProfilPrincipal.aEcouter(context)) return
            val urgente = requeteDePasse("SMS_RECEIVED").apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }.build()
            val relecture = requeteDePasse("relecture apres SMS")
                .setInitialDelay(RELECTURE_SMS_MS, TimeUnit.MILLISECONDS)
                .build()
            val travaux = WorkManager.getInstance(context)
            travaux.enqueue(urgente)
            travaux.enqueueUniqueWork(RELECTURE_SMS, ExistingWorkPolicy.KEEP, relecture)
        }

        /**
         * Au démarrage de l'utilisateur (`BootReceiver`) : ce qui est arrivé pendant qu'il était arrêté — aucun signal
         * ne l'atteignait — est importé par la synchronisation de démarrage sans être notifié (relectures Claude M2 et
         * GPT). Une passe le notifie, dans les bornes de [ArriveesDuProfilPrincipal].
         */
        suspend fun apresUnDemarrage(context: Context) {
            if (!ArriveesDuProfilPrincipal.aEcouter(context)) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(PASSE_DEMARRAGE, ExistingWorkPolicy.KEEP, requeteDePasse("demarrage").build())
            assurerLObservation(context)
        }

        /**
         * Garantit qu'une observation attend un changement du stockage (cf. la classe). Sans effet hors d'un profil à
         * écouter. Appelée par chaque passe, au démarrage de l'application, à chaque retour au premier plan et par la
         * synchronisation de douze heures : une observation éteinte malgré tout se rallume au plus tard là.
         *
         * @param soi l'observation qui appelle (c'est l'autre nom qui est armé), `null` hors d'une observation.
         */
        suspend fun assurerLObservation(context: Context, soi: String? = null) {
            if (!ArriveesDuProfilPrincipal.aEcouter(context)) return
            runCatchingCancellable {
                verrouDArmement.withLock {
                    val travaux = WorkManager.getInstance(context)
                    val etats: suspend (String) -> List<WorkInfo.State> = { nom ->
                        travaux.getWorkInfosForUniqueWorkFlow(nom).first().map { it.state }
                    }
                    val nom = observationAArmer(etats(OBSERVATION_A), etats(OBSERVATION_B), soi) ?: return@withLock
                    travaux.enqueueUniqueWork(nom, ExistingWorkPolicy.APPEND_OR_REPLACE, requeteDObservation(nom))
                        .await()
                }
            }.onFailure { Timber.w(it, "Observation du stockage partage non armee") }
        }

        private fun requeteDePasse(raison: String) = OneTimeWorkRequestBuilder<ArriveesDuProfilPrincipalWorker>()
            .addTag(ETIQUETTE)
            .setInputData(workDataOf(CLE_RAISON to raison))

        private fun requeteDObservation(nom: String): OneTimeWorkRequest {
            val contraintes = Constraints.Builder()
                .addContentUriTrigger(Telephony.MmsSms.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(APAISEMENT)
                .setTriggerContentMaxDelay(ATTENTE_MAX)
                .build()
            return OneTimeWorkRequestBuilder<ArriveesDuProfilPrincipalWorker>()
                .addTag(ETIQUETTE)
                .setConstraints(contraintes)
                .setInputData(workDataOf(CLE_OBSERVATION to nom))
                .build()
        }
    }
}

/**
 * Le nom de l'observation à armer pour qu'après coup UNE observation attende un changement, ou `null` s'il y en a
 * déjà une : en attente (`ENQUEUED`), ou placée derrière une passe en cours (`BLOCKED`). Une observation EN COURS ne
 * compte pas : elle a déjà servi. [soi] : l'observation qui appelle, dont c'est l'autre nom qui est armé.
 */
internal fun observationAArmer(etatsA: List<WorkInfo.State>, etatsB: List<WorkInfo.State>, soi: String?): String? {
    val attend = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
    if ((etatsA + etatsB).any { it in attend }) return null
    return if (soi == ArriveesDuProfilPrincipalWorker.OBSERVATION_A) {
        ArriveesDuProfilPrincipalWorker.OBSERVATION_B
    } else {
        ArriveesDuProfilPrincipalWorker.OBSERVATION_A
    }
}
