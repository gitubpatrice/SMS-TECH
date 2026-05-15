package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.os.Build
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.data.voice.VoiceRecorder
import com.filestech.sms.domain.repository.BlockedNumberRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Safety-net worker that drives SMS sync periodically (every 12 h) and on demand (boot,
 * fresh install, manual refresh). It covers:
 *
 *  - First-run import (cold install): `lastSyncedSmsId == 0`, full back-scan of `content://sms`.
 *  - Steady-state delta: `lastSyncedSmsId > 0`, stream only rows with `_ID > sinceId`.
 *  - Force-killed process recovery: if the broadcast-receiver path missed deliveries, the
 *    next worker tick converges.
 *
 * v1.1.1 note: the rich `TelephonySyncManager` (ContentObserver + reconciliation) is stubbed
 * to unblock a KSP regression; until it is restored, this worker is the **single import path**
 * for non-live messages. The receiver path (`SmsDeliverReceiver`) still handles live arrival.
 */
@HiltWorker
class TelephonySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reader: TelephonyReader,
    private val mirror: ConversationMirror,
    private val settings: SettingsRepository,
    private val voiceRecorder: VoiceRecorder,
    private val messageDao: MessageDao,
    private val blockedRepo: BlockedNumberRepository,
    private val blockedSystem: BlockedNumberSystem,
    private val mmsSystemWriteback: MmsSystemWriteback,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            runImport()
            // Audit M-6: opportunistic housekeeping. Outbound MMS / voice caches accumulate when
            // the `SmsSentReceiver` never fires (process force-killed, OS skips the broadcast);
            // we prune anything older than 24 h here. Inbound caches are intentionally **not**
            // touched — they back [AttachmentEntity.localUri] and dropping them would surface
            // broken audio bubbles in old threads. A future orphan-scan can address those.
            pruneStaleOutboundCaches(applicationContext)
            // Voice-recorder drafts have their own pruner; we just trigger it on the same cadence.
            runCatching { voiceRecorder.pruneOld() }
                .onFailure { Timber.w(it, "VoiceRecorder.pruneOld failed") }
            // Audit M-5: stalls-watchdog. Promote outgoing messages stuck in PENDING past 15 min
            // to FAILED so the UI stops showing the "Sending…" spinner indefinitely. The 15 min
            // threshold is loose on purpose — a real-world carrier roundtrip is usually < 30 s,
            // anything past 15 min is almost certainly the result of a missed sent-broadcast.
            runCatching {
                val cutoff = System.currentTimeMillis() - PENDING_TIMEOUT_MS
                val promoted = messageDao.timeoutStalePending(cutoff)
                if (promoted > 0) Timber.i("Watchdog: %d stale PENDING -> FAILED", promoted)
            }.onFailure { Timber.w(it, "PENDING watchdog failed") }
            // v1.2.2 audit F1: companion watchdog for the system-provider OUTBOX. The local
            // PENDING watchdog above flips our Room mirror, but a row inserted by
            // MmsSystemWriteback whose MmsSentReceiver never fired (process killed, force-stop,
            // Doze + reboot) would remain `msg_box = 4` in `content://mms` forever — polluting
            // the conversation in other SMS apps AND coming back as a phantom MMS at the next
            // reimport. Same 15 min threshold for symmetry.
            runCatching {
                val deleted = mmsSystemWriteback.purgeStaleOutbox(PENDING_TIMEOUT_MS)
                if (deleted > 0) Timber.i("Watchdog: %d stale system OUTBOX rows purged", deleted)
            }.onFailure { Timber.w(it, "OUTBOX system watchdog failed") }
            Result.success()
        } catch (t: Throwable) {
            Timber.w(t, "TelephonySyncWorker failed")
            Result.retry()
        }
    }

    /**
     * Reads the persisted cursor, fingerprints the system provider, and imports the delta in
     * 500-row pages via [ConversationMirror.bulkImportFromTelephony]. Idempotent: the
     * `telephony_uri` UNIQUE index + `OnConflictStrategy.IGNORE` make re-runs safe.
     */
    private suspend fun runImport() {
        val sinceId = settings.flow.first().advanced.lastSyncedSmsId
        val fp = runCatching { reader.snapshotSmsFingerprint() }.getOrNull()
        if (fp == null) {
            Timber.w("Sync: fingerprint query failed (READ_SMS revoked?) — skipping import pass")
            return
        }
        if (fp.maxId <= sinceId) {
            Timber.d("Sync: no new SMS (provider maxId=%d, cursor=%d)", fp.maxId, sinceId)
            return
        }
        Timber.i("Sync: starting (cursor=%d, provider maxId=%d, count=%d)", sinceId, fp.maxId, fp.count)
        // Snapshot the blocklist ONCE — re-reading on every page would be a needless N×M.
        // We union TWO sources to avoid the boot-time race between `BlockedNumbersImporter`
        // (mirroring system→Room) and this worker (starting in parallel from MainApplication):
        //  1. Room snapshot — what we've already mirrored locally, including app-initiated
        //     blocks that may not be in the system provider yet.
        //  2. Live system snapshot — fresh read of `BlockedNumberContract`, so a Téléphone /
        //     Samsung Messages entry blocks SMS at first import even before the importer has
        //     had a chance to mirror it.
        // Suffix-8 matching absorbs international vs. national format mismatches.
        val roomBlocked = runCatching { blockedRepo.blockedNormalizedSnapshot() }
            .getOrDefault(emptySet())
        val systemBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { blockedSystem.listSystemBlocked() }.getOrDefault(emptyList())
        } else emptyList()
        val blockedSuffixes = (roomBlocked.asSequence() + systemBlocked.asSequence())
            .map { it.phoneSuffix8() }
            .filter { it.isNotEmpty() }
            .toHashSet()
        var imported = 0
        val maxSeen = reader.readSmsSince(sinceId = sinceId, pageSize = 500) { page ->
            val filtered = if (blockedSuffixes.isEmpty()) page
            else page.filter { it.entity.address.phoneSuffix8() !in blockedSuffixes }
            if (filtered.isNotEmpty()) {
                mirror.bulkImportFromTelephony(filtered.map { it.entity })
            }
            imported += filtered.size
        }
        if (maxSeen > sinceId) {
            settings.update { it.copy(advanced = it.advanced.copy(lastSyncedSmsId = maxSeen)) }
            Timber.i("Sync: imported %d rows, cursor advanced to %d", imported, maxSeen)
        }
    }

    /**
     * Deletes outbound staging files older than [STALE_CACHE_AGE_MS]. Targets:
     *  - `cache/mms_outgoing/` — built `.pdu` files for in-flight MMS. The SmsSentReceiver
     *    normally deletes its own file on completion; we catch the orphans (process died, OS
     *    dropped the receiver, etc.).
     */
    private fun pruneStaleOutboundCaches(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_CACHE_AGE_MS
        val dirs = listOf(File(context.cacheDir, "mms_outgoing"))
        for (dir in dirs) {
            if (!dir.exists()) continue
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
                }
            }.onFailure { Timber.w(it, "Cache prune failed for %s", dir.absolutePath) }
        }
    }

    companion object {
        const val PERIODIC_NAME = "telephony_sync_periodic"
        const val ONE_SHOT_NAME = "telephony_sync_oneshot"
        private const val STALE_CACHE_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val PENDING_TIMEOUT_MS = 15L * 60L * 1_000L

        /**
         * Enqueues the recurring 12 h job. Uses [ExistingPeriodicWorkPolicy.KEEP] so repeated
         * calls (every app launch) are no-ops once scheduled.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val request = PeriodicWorkRequestBuilder<TelephonySyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Enqueues a one-shot sync now (used by [com.filestech.sms.system.receiver.BootReceiver]
         * after [Intent.ACTION_BOOT_COMPLETED]). Uses [ExistingWorkPolicy.REPLACE] so the
         * freshest request wins if multiple boot triggers arrive in close succession.
         */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<TelephonySyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
