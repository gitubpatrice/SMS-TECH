package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledMessageSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ScheduledMessageRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ScheduledMessageScheduler {

    override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) {
        val delay = (epochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        // Audit P-P1-8: exponential backoff on transient send failures (no service, RIL busy,
        // SmsManager transient throw). The OS default for `WorkManager.Result.retry()` is a
        // linear 30 s — re-firing every 30 s on a phone in a dead zone hammers the modem for
        // nothing and drains the battery. EXPONENTIAL starts at our 30 s base and doubles
        // (30 s → 60 s → 120 s …) capped by WorkManager at 5 h. Acceptable for a "send was
        // scheduled, retry later" flow; users in a hurry can also manually trigger a retry
        // from the failed-message affordance.
        val work = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putLong(KEY_SCHEDULED_ID, scheduledMessageId).build())
            .addTag(TAG_PREFIX + scheduledMessageId)
            // v1.25.3 — tag commun : permet à [rescheduleAllPending] de lire l'état de TOUS les
            // envois en UNE requête. Une requête par message mettrait le chemin de boot à la
            // merci du nombre de messages programmés, alors que `goAsync()` ne donne que ~10 s.
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(scheduledMessageId),
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    override fun cancel(scheduledMessageId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(scheduledMessageId))
    }

    /**
     * Filet au démarrage : re-planifie les envois `PENDING` dont WorkManager a perdu le job.
     * Le cas nominal est le téléphone éteint à l'heure prévue, mais aussi les OEM qui purgent
     * la base WorkManager sur force-stop (cf. Xiaomi/HyperOS).
     *
     * v1.25.3 — on saute désormais les envois pour lesquels WorkManager tient encore un job
     * vivant. Avant, `enqueueUniqueWork(REPLACE)` écrasait inconditionnellement : tant qu'un
     * échec basculait la ligne en `FAILED` immédiatement, ce chemin était mort. Depuis que la
     * ligne reste `PENDING` pendant les tentatives, un redémarrage tombant dans cette fenêtre
     * remplaçait le job en attente de backoff par un job à délai nul — compteur de tentatives
     * remis à zéro et backoff court-circuité, l'inverse exact de ce que
     * [scheduleAt] cherche à obtenir.
     *
     * Attention : filtrer sur `scheduledAt > now` serait faux. Un envoi prévu à 08:00 avec le
     * téléphone éteint jusqu'à 09:00 a précisément une échéance passée — c'est le cas que cette
     * fonction existe pour rattraper.
     */
    suspend fun rescheduleAllPending() = withContext(io) {
        // Chemin de boot : chaque lecture est plafonnée, `goAsync()` n'achète qu'une dizaine de
        // secondes avant un ANR partiel (cf. le même parti pris dans
        // [com.filestech.sms.system.receiver.BootReceiver]).
        val pending = withTimeoutOrNull(READ_TIMEOUT_MS) {
            runCatching { repo.observePending().first() }.getOrDefault(emptyList())
        } ?: emptyList()
        if (pending.isEmpty()) return@withContext

        val liveIds = liveScheduledIds()
        for (item in pending) {
            if (item.id in liveIds) continue
            scheduleAt(item.id, item.scheduledAt)
        }
    }

    /**
     * Ids dont WorkManager tient encore un job non terminé (`ENQUEUED` en attente de backoff,
     * `RUNNING`, `BLOCKED`). Un job `CANCELLED` / `FAILED` / `SUCCEEDED` alors que la ligne est
     * encore `PENDING` est une incohérence : l'id n'est pas retourné, donc on re-planifie.
     *
     * En cas d'échec ou de dépassement du délai on renvoie un ensemble vide : tout est
     * re-planifié, c'est-à-dire exactement le comportement d'avant ce garde. Mieux vaut
     * re-planifier en trop que laisser un envoi orphelin jusqu'au prochain redémarrage.
     */
    private suspend fun liveScheduledIds(): Set<Long> {
        val infos = withTimeoutOrNull(READ_TIMEOUT_MS) {
            runCatching {
                WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_ALL).first()
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return infos.asSequence()
            .filterNot { it.state.isFinished }
            .flatMap { it.tags.asSequence() }
            .mapNotNull(::scheduledIdFromTag)
            .toSet()
    }

    companion object {
        const val KEY_SCHEDULED_ID = "scheduled_id"
        private const val TAG_PREFIX = "scheduled_sms_"

        /** Tag porté par tous les envois programmés, pour les interroger en une seule requête. */
        private const val TAG_ALL = "scheduled_sms_all"

        /** Plafond par lecture sur le chemin de boot. */
        private const val READ_TIMEOUT_MS = 2_000L

        private fun workName(id: Long) = "scheduled_sms_$id"

        /**
         * Extrait l'id d'envoi d'un tag WorkManager, ou `null` si le tag n'en porte pas.
         *
         * Un `WorkInfo` porte trois familles de tags : le nôtre par id (`scheduled_sms_42`), le
         * tag commun [TAG_ALL] (`scheduled_sms_all`, qui commence par le même préfixe mais ne se
         * termine pas par un nombre), et le nom de classe complet du worker que WorkManager
         * ajoute d'office. Seule la première doit produire un id.
         */
        @VisibleForTesting
        internal fun scheduledIdFromTag(tag: String): Long? =
            tag.removePrefix(TAG_PREFIX).takeIf { it != tag }?.toLongOrNull()
    }
}
