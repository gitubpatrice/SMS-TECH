package com.filestech.sms.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.BlockedNumberRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.1.1 sync manager — cursor-based, single-flight, no ContentObserver.
 *
 * The richer ContentObserver-driven variant kept triggering an opaque KSP `PROCESSING_ERROR`
 * during the v1.1 build that we could not isolate. Until kotlinc surfaces a real error, the
 * live-arrival path is covered by the `SmsDeliverReceiver` + `MmsDownloadedReceiver`, and a
 * 12 h `TelephonySyncWorker` plays safety-net.
 *
 * What this class still owns:
 *  - **First-run bulk import** triggered from [start] when [AdvancedSettings.lastSyncedSmsId] is
 *    still `0L` (fresh install or post-panic wipe). One Room transaction per page of 500 rows,
 *    so a 5000-message inbox commits in ~10 batches without flooding the UI with intermediate
 *    invalidations.
 *  - **On-demand delta sync** via [requestSync] — reads `content://sms` with `_ID > cursor`,
 *    bulk-imports the delta, advances + persists the cursor. Called by `MainActivity`'s pull-to-
 *    refresh and by the `TelephonySyncWorker`.
 *  - **Single-flight gate**: a `Mutex` serializes concurrent calls so two pull-to-refreshes
 *    cannot race and double-import the same rows. Idempotency is also enforced by the UNIQUE
 *    index on `messages.telephony_uri` (`OnConflictStrategy.IGNORE`), but the mutex avoids the
 *    wasted I/O.
 */
@Singleton
class TelephonySyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val telephonyReader: TelephonyReader,
    private val mirror: ConversationMirror,
    private val blockedRepo: BlockedNumberRepository,
    private val blockedSystem: BlockedNumberSystem,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    sealed interface State {
        data object Idle : State
        data class Running(val isFirstRun: Boolean, val importedSoFar: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Serializes sync work. `tryLock` semantics elsewhere would silently drop a refresh request;
     * we prefer to *queue* (await the lock) so the user-visible state machine always converges,
     * but the queue depth is naturally bounded by Mutex's single-waiter behaviour combined with
     * the conflated dispatch from the receivers/worker.
     */
    private val syncMutex = Mutex()

    @Volatile private var started: Boolean = false

    /**
     * Called from [com.filestech.sms.MainApplication.onCreate]. Idempotent — the `started` guard
     * keeps repeated calls (Application instances on configuration change, defensive call from
     * `TelephonySyncWorker.doWork`) cheap.
     *
     * Kicks off an initial bulk import in the background when the cursor is still zero. We do
     * NOT await it: the UI lights up empty for a brief moment, then the first batch commits and
     * the `ConversationDao.observe` Flow re-emits with the imported rows. Awaiting here would
     * block `Application.onCreate` on a potentially 5 s import.
     */
    fun start() {
        if (started) return
        started = true
        Timber.i("TelephonySyncManager.start()")
        if (!hasReadSmsPermission()) {
            Timber.i("READ_SMS not granted yet; skipping initial import (will retry on requestSync)")
            return
        }
        appScope.launch { runSync(reason = "start") }
    }

    fun stop() {
        // Cursor-based sync has no live resources to release. Kept for API symmetry with the
        // historical ContentObserver variant — callers in `MainApplication` / future tests
        // expect `stop()` to exist.
        Timber.i("TelephonySyncManager.stop()")
    }

    /**
     * Queues a delta sync. Safe to call from any thread / context (broadcast receiver, worker,
     * pull-to-refresh). The actual work is dispatched on [io] inside the mutex.
     */
    fun requestSync(reason: String) {
        if (!hasReadSmsPermission()) {
            Timber.i("requestSync(%s) skipped — no READ_SMS", reason)
            return
        }
        appScope.launch { runSync(reason = reason) }
    }

    private suspend fun runSync(reason: String) = withContext(io) {
        if (syncMutex.isLocked) {
            // A sync is already in-flight; the new call will await its turn. We log here so the
            // logcat shows back-pressure, not silent waits.
            Timber.i("runSync(%s) waiting for in-flight sync", reason)
        }
        syncMutex.withLock {
            // Read a fresh cursor snapshot inside the lock; another concurrent caller may have
            // just advanced it.
            val current = settings.flow.first().advanced.lastSyncedSmsId
            val isFirstRun = current == 0L
            _state.value = State.Running(isFirstRun = isFirstRun, importedSoFar = 0)
            Timber.i("runSync(%s) starting; cursor=%d firstRun=%b", reason, current, isFirstRun)

            // MMS import is one-shot: the SMS-cursor delta (below) doesn't catch `content://mms`
            // rows, and re-reading the entire MMS table on every sync would be wasteful. We
            // trigger it on the very first run (cursor == 0L, fresh install or panic-wipe) only.
            // The receiver path (`MmsDownloadedReceiver` + `MmsSentReceiver`) covers live arrivals.
            if (isFirstRun) {
                runCatching {
                    val mmsRows = telephonyReader.readAllMms()
                    if (mmsRows.isNotEmpty()) {
                        mirror.bulkImportMmsFromTelephony(mmsRows)
                        Timber.i("runSync(%s) imported %d historical MMS rows", reason, mmsRows.size)
                    }
                }.onFailure { Timber.w(it, "MMS import failed") }
            }
            // Union the Room mirror with a fresh read of the system blocklist. The Room snapshot
            // covers app-initiated blocks; the live system read covers entries the user already
            // had in Téléphone / Samsung Messages before the importer had a chance to mirror them.
            // Suffix-8 matching absorbs international vs national format mismatches.
            val roomBlocked = runCatching { blockedRepo.blockedNormalizedSnapshot() }.getOrDefault(emptySet())
            val systemBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { blockedSystem.listSystemBlocked() }.getOrDefault(emptyList())
            } else emptyList()
            val blockedSuffixes = (roomBlocked.asSequence() + systemBlocked.asSequence())
                .map { it.phoneSuffix8() }
                .filter { it.isNotEmpty() }
                .toHashSet()
            Timber.i("runSync(%s) blocked sources: room=%d system=%d → suffixes=%d", reason, roomBlocked.size, systemBlocked.size, blockedSuffixes.size)

            var imported = 0
            var skipped = 0
            val newCursor = try {
                telephonyReader.readSmsSince(sinceId = current, pageSize = 500) { page ->
                    val filtered = if (blockedSuffixes.isEmpty()) page
                    else page.filter { it.entity.address.phoneSuffix8() !in blockedSuffixes }
                    skipped += page.size - filtered.size
                    if (filtered.isNotEmpty()) {
                        mirror.bulkImportFromTelephony(filtered.map { it.entity })
                        imported += filtered.size
                    }
                    _state.value = State.Running(isFirstRun = isFirstRun, importedSoFar = imported)
                }
            } catch (t: Throwable) {
                Timber.w(t, "runSync(%s) failed", reason)
                _state.value = State.Idle
                return@withLock
            }
            if (newCursor != current) {
                settings.update { s ->
                    s.copy(advanced = s.advanced.copy(lastSyncedSmsId = newCursor))
                }
                Timber.i("runSync(%s) done; imported=%d skipped(blocked)=%d cursor=%d→%d", reason, imported, skipped, current, newCursor)
            } else {
                Timber.i("runSync(%s) done; no new rows (skipped(blocked)=%d)", reason, skipped)
            }
            _state.value = State.Idle
        }
    }

    private fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
