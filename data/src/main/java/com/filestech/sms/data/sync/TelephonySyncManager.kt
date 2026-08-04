package com.filestech.sms.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.MessageDao
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
    private val messageDao: MessageDao,
    // v1.27.2 (audit externe 2026-08-04 #6) — transaction Room pour [reconcileDeletions] :
    // suppression + recalcul des aperçus atomiques, même recette que `purgeHistoryNow` (H9).
    private val database: AppDatabase,
    private val blockedRepo: BlockedNumberRepository,
    private val blockedSystem: BlockedNumberSystem,
    /**
     * v1.8.0 (audit bug "numéros bloqués importés") — la blocklist système était
     * mirror-ée vers Room **uniquement** au cold-start (`MainApplication.onCreate`).
     * Conséquence : sur fresh install d'un user qui était sur Samsung Messages avec
     * des numéros bloqués via le Téléphone Samsung (qui posent dans
     * `BlockedNumberContract` AOSP standard), si SMS Tech n'avait pas encore le
     * rôle SMS-default au cold-start, `listSystemBlocked()` retournait empty
     * (lecture refusée par l'OS). Une fois le rôle accordé, le mirror n'était
     * plus jamais re-tenté tant que l'app n'était pas redémarrée. Les
     * conversations des numéros bloqués étaient donc importées sans filtre puis
     * persistaient indéfiniment.
     *
     * Le fix : appeler `importFromSystem()` (qui mirror la blocklist ET purge
     * les conversations matching) au début de chaque `runSync()`. Idempotent et
     * rapide (~50 ms typique d'après doc `MainApplication.kt`).
     */
    private val blockedNumbersImporter: com.filestech.sms.data.blocking.BlockedNumbersImporter,
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
            // v1.8.0 fix — mirror system blocklist into Room AT THE START of every sync.
            // Critical for the fresh-install case where the user grants SMS-default role
            // AFTER the first cold-start sync: at that first sync `listSystemBlocked()`
            // returned empty (no role yet), the messages were imported unfiltered, and
            // the blocklist mirror was never retried until app restart. Now each sync
            // re-mirrors + re-purges, so the very first `requestSync("permission granted")`
            // after the user accepts the SMS role purges any blocked conversation that
            // slipped in during the role-less import. Idempotent: re-running on every
            // tick is cheap (~50 ms) when nothing has changed.
            //
            // Wrapped in runCatching so a blocklist failure (provider quirks on OEM ROMs)
            // never blocks the SMS sync itself — silent log + continue.
            runCatching { blockedNumbersImporter.importFromSystem() }
                .onFailure {
                    Timber.w(it, "runSync(%s): system blocklist mirror failed; continuing", reason)
                }

            // Read a fresh cursor snapshot inside the lock; another concurrent caller may have
            // just advanced it.
            val current = settings.flow.first().advanced.lastSyncedSmsId
            val isFirstRun = current == 0L
            _state.value = State.Running(isFirstRun = isFirstRun, importedSoFar = 0)
            Timber.i("runSync(%s) starting; cursor=%d firstRun=%b", reason, current, isFirstRun)

            // MMS import : the SMS-cursor delta (below) doesn't catch `content://mms` rows
            // and re-reading the entire MMS table on every sync would be wasteful. We trigger
            // it when EITHER the cursor is still zero (first install) OR Room currently has
            // zero MMS rows (reinstall under a different package id — typical when switching
            // from the debug-suffixed package to the release package, the SMS cursor in the
            // release DataStore was populated by a previous install while Room is fresh).
            // Idempotent: telephony_uri UNIQUE + OnConflictStrategy.IGNORE.
            val hasAnyMms = runCatching { messageDao.hasAnyMms() }.getOrDefault(false)
            val needsMmsImport = isFirstRun || !hasAnyMms
            if (needsMmsImport) {
                runCatching {
                    // v1.2.4 audit P3: paged read + per-chunk insert. The previous
                    // `readAllMms()` materialised the entire MMS table (with all part bytes
                    // resolved) in memory before the first Room insert — a power user with
                    // 500+ MMS spiked RSS by 200-400 MB and held the dispatch queue for
                    // 5-10 s. The chunk size is 200, balancing transaction lock duration
                    // against per-page overhead.
                    var imported = 0
                    telephonyReader.readMmsBatched(pageSize = 200) { page ->
                        // v1.8.0 (post-audit fix badges fresh install S24) — au 1er
                        // sync (Room vide), tous les messages historiques sont
                        // considérés comme déjà vus. Sans ça, l'user voit des
                        // badges sur des messages parfois vieux de plusieurs jours/
                        // mois à la 1ʳᵉ ouverture, alors qu'il les a évidemment
                        // déjà lus dans son ancienne app SMS. Comportement aligné
                        // sur Google Messages / Samsung Messages.
                        // Les vrais nouveaux MMS arrivent ensuite via
                        // `MmsDownloadedReceiver` avec `read=false` → badge normal.
                        val pageForImport = if (isFirstRun) {
                            page.map { it.copy(read = true) }
                        } else page
                        mirror.bulkImportMmsFromTelephony(pageForImport)
                        imported += pageForImport.size
                    }
                    if (imported > 0) {
                        Timber.i("runSync(%s) imported %d MMS rows (firstRun=%b hasAnyMms=%b)", reason, imported, isFirstRun, hasAnyMms)
                    } else {
                        Timber.i("runSync(%s) MMS import: 0 rows in system provider", reason)
                    }
                }.onFailure { Timber.w(it, "MMS import failed") }
            }
            // Union the Room mirror with a fresh read of the system blocklist. The Room snapshot
            // covers app-initiated blocks; the live system read covers entries the user already
            // had in Téléphone / Samsung Messages before the importer had a chance to mirror them.
            // v1.26.1 (audit H4) — `blockKey()` des DEUX côtés, plus `phoneSuffix8()`. Jumeau
            // exact de [TelephonySyncWorker] : voir là-bas le détail des deux effets (perte
            // silencieuse et définitive de messages d'un numéro NON bloqué qui partage ses
            // 8 derniers chiffres avec un numéro bloqué ; et blocage inopérant sur les
            // expéditeurs alphanumériques, dont la clé se réduisait à la chaîne vide).
            val roomBlocked = runCatching { blockedRepo.blockedNormalizedSnapshot() }.getOrDefault(emptySet())
            val systemBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { blockedSystem.listSystemBlocked() }.getOrDefault(emptyList())
            } else emptyList()
            val blockedKeys = (roomBlocked.asSequence() + systemBlocked.asSequence())
                .map { it.blockKey() }
                .filter { it.isNotEmpty() }
                .toHashSet()
            Timber.i(
                "runSync(%s) blocked sources: room=%d system=%d → keys=%d",
                reason,
                roomBlocked.size,
                systemBlocked.size,
                blockedKeys.size,
            )

            var imported = 0
            var skipped = 0
            val newCursor = try {
                telephonyReader.readSmsSince(sinceId = current, pageSize = 500) { page ->
                    val filtered = if (blockedKeys.isEmpty()) {
                        page
                    } else {
                        page.filter { it.entity.address.blockKey() !in blockedKeys }
                    }
                    skipped += page.size - filtered.size
                    if (filtered.isNotEmpty()) {
                        // v1.8.0 (post-audit fix badges fresh install S24) — au
                        // 1er sync, on force `read = true` sur tous les messages
                        // historiques. Sinon l'user voit des badges sur des
                        // messages vieux de plusieurs jours/mois à l'ouverture
                        // initiale (le système Android conserve `READ=0` pour
                        // les messages jamais notifiés "lus" par une app SMS,
                        // ce qui est très fréquent après changement d'app).
                        // Les vrais nouveaux SMS arrivent ensuite via
                        // `SmsDeliverReceiver` avec `read=false` → badge normal.
                        val entitiesForImport = if (isFirstRun) {
                            filtered.map { it.entity.copy(read = true) }
                        } else {
                            filtered.map { it.entity }
                        }
                        mirror.bulkImportFromTelephony(entitiesForImport)
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
            reconcileDeletions()
            _state.value = State.Idle
        }
    }

    /**
     * v1.26.1 (audit F6) — passe de réconciliation des suppressions.
     *
     * Le triptyque `listMirroredTelephonyUris` / `readAllSmsIds` / `deleteByTelephonyUris`
     * existait, complet, avec **zéro appelant** — alors que les trois KDoc affirmaient la
     * fonctionnalité implémentée. Conséquence : un SMS effacé depuis une autre application ou
     * depuis les réglages système restait visible indéfiniment ici, l'utilisateur croyant l'avoir
     * supprimé.
     *
     * ⚠️ C'est la passe la plus dangereuse de l'application : elle EFFACE des messages. Trois
     * garde-fous, chacun bloquant, parce que ce chemin naïf détruit toute la base :
     *
     *  1. **SMS uniquement.** `listMirroredTelephonyUris()` rend aussi les URI de MMS
     *     (`ConversationMirror` écrit `content://mms/<id>`), or `readAllSmsIds()` ne lit que
     *     `content://sms`. Sans ce filtre, TOUS les MMS miroités seraient vus comme supprimés.
     *  2. **Jamais sur une lecture vide.** `readAllSmsIds()` rend un tableau vide aussi bien
     *     quand le fournisseur est vide que quand la requête ÉCHOUE (permission retirée en
     *     cours de route, provider indisponible) — les deux sont indiscernables. On refuse donc
     *     d'effacer quoi que ce soit tant qu'on a des lignes miroir et que le système n'en rend
     *     aucune.
     *  3. **Découpage sous la limite SQLite** de 999 paramètres hôtes.
     *
     * v1.27.2 (audit externe 2026-08-04 #6) — 4ᵉ règle : **toute suppression recalcule les
     * aperçus**, dans la même transaction (même contrat que `deleteMessage` v1.24.0 et
     * `purgeHistoryNow` H9). Le recalcul passe par `refreshAllConversationPreviewsAfterPurge`,
     * qui re-dérive `last_message_preview`/`last_message_at` de chaque conversation depuis ses
     * propres messages — idempotent, y compris pour le coffre.
     *
     * Le coffre est déjà exclu par la requête elle-même (`c.in_vault = 0`) : ses messages
     * n'existent que dans notre base et n'ont rien à réconcilier.
     */
    private suspend fun reconcileDeletions() {
        if (!hasReadSmsPermission()) return
        runCatching {
            val mirrored = messageDao.listMirroredTelephonyUris()
                .filter { it.startsWith(SMS_URI_PREFIX) }
            if (mirrored.isEmpty()) return@runCatching
            val systemIds = telephonyReader.readAllSmsIds()
            if (systemIds.isEmpty()) {
                // Garde-fou 2 : indiscernable d'un échec de lecture. On ne détruit rien.
                Timber.w("reconcileDeletions: provider returned 0 SMS while %d mirrored — skipped", mirrored.size)
                return@runCatching
            }
            val alive = HashSet<String>(systemIds.size)
            systemIds.forEach { alive += "$SMS_URI_PREFIX$it" }
            val gone = mirrored.filterNot { it in alive }
            if (gone.isEmpty()) return@runCatching
            // v1.27.2 (audit externe 2026-08-04 #6) — suppression ET recalcul des aperçus dans
            // la MÊME transaction. Ce chemin était le troisième jumeau oublié du correctif
            // v1.24.0 de `deleteMessage` (déjà porté à `purgeHistoryNow` en v1.26.1 H9) : il
            // effaçait les lignes sans jamais toucher `conversations.last_message_preview`,
            // qui conservait donc le CORPS EN CLAIR d'un message supprimé côté système — et
            // `last_message_at` faussait le tri. Définitivement, de surcroît :
            // `repairStaleConversationPreviews` est one-shot et déjà consommée sur les
            // installations existantes. La transaction n'est pas du zèle : sans elle, une mort
            // du processus entre le DELETE et le refresh recrée exactement l'état permanent
            // que H9 a fermé — au tour suivant `gone` serait vide et le refresh jamais rejoué.
            var removed = 0
            database.withTransaction {
                gone.chunked(SQLITE_HOST_PARAM_LIMIT).forEach { batch ->
                    removed += messageDao.deleteByTelephonyUris(batch)
                }
                if (removed > 0) {
                    messageDao.refreshAllConversationPreviewsAfterPurge()
                }
            }
            Timber.i("reconcileDeletions: %d local row(s) dropped (deleted system-side)", removed)
        }.onFailure { Timber.w(it, "reconcileDeletions failed") }
    }

    private fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** v1.26.1 (audit F6) — seules les lignes SMS sont réconciliables, cf. [reconcileDeletions]. */
        const val SMS_URI_PREFIX = "content://sms/"

        /** SQLite plafonne `IN (…)` à 999 paramètres hôtes ; on reste dessous. */
        const val SQLITE_HOST_PARAM_LIMIT = 900
    }
}
