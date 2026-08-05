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
            val advanced = settings.flow.first().advanced
            val current = advanced.lastSyncedSmsId
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
            // v1.27.2 (audit Codex du 2026-08-05, P-10) — 🔴 `hasAnyMms` ne prouve RIEN d'autre
            // qu'une page écrite.
            //
            // L'import écrit page par page. Une exception après la première page laissait
            // `hasAnyMms = true` et, le curseur SMS avançant juste après, `isFirstRun = false` :
            // `needsMmsImport` devenait faux et **les pages restantes n'étaient jamais relues**.
            // L'historique MMS restait durablement amputé, sans que rien ne le signale.
            //
            // Le marqueur ci-dessous est la seule preuve de complétion, et il n'est posé qu'après
            // la DERNIÈRE page. Tant qu'il manque, on rejoue — l'insertion est idempotente.
            val hasAnyMms = runCatching { messageDao.hasAnyMms() }.getOrDefault(false)
            val needsMmsImport = !advanced.mmsImportCompleted || isFirstRun || !hasAnyMms
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
                    // v1.27.2 (audit Codex, P-10) — LA DERNIÈRE PAGE EST PASSÉE.
                    //
                    // `readMmsBatched` est revenu normalement : toutes les pages ont été lues et
                    // écrites. C'est le seul endroit où poser le marqueur — le poser plus haut
                    // referait exactement le défaut que ce champ ferme. Une exception ou une
                    // annulation saute cette ligne, et la passe suivante rejouera tout.
                    settings.update { s ->
                        s.copy(advanced = s.advanced.copy(mmsImportCompleted = true))
                    }
                    if (imported > 0) {
                        Timber.i("runSync(%s) imported %d MMS rows (firstRun=%b hasAnyMms=%b)", reason, imported, isFirstRun, hasAnyMms)
                    } else {
                        Timber.i("runSync(%s) MMS import: 0 rows in system provider", reason)
                    }
                }.onFailure {
                    // v1.27.2 (relecture Gemini du 2026-08-05) — une annulation n'est PAS un
                    // échec. `runCatching` attrape `Throwable`, donc `CancellationException`
                    // comprise : une mise en veille pendant l'import était journalisée comme
                    // « MMS import failed » et la coroutine continuait comme si de rien n'était,
                    // alors qu'elle est censée s'arrêter. Le motif a déjà mordu sur ce dépôt.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Timber.w(it, "MMS import failed")
                }
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
     *  2. **Jamais sur une lecture impossible.** `readAllSmsIds()` rend désormais `null` quand la
     *     requête échoue (permission retirée en cours de route, fournisseur indisponible) et un
     *     tableau vide quand le fournisseur est réellement vide — v1.27.2, audit Codex P-09. Les
     *     deux étaient indiscernables, et le repli échouait du mauvais côté sur le seul chemin de
     *     l'application qui efface. `null` interdit toute suppression ; un fournisseur vidé est
     *     traité comme une suppression massive, donc soumis à la règle 5.
     *  3. **Découpage sous la limite SQLite** de 999 paramètres hôtes.
     *
     * v1.27.2 (audit externe 2026-08-04 #6) — 4ᵉ règle : **toute suppression recalcule les
     * aperçus**, dans la même transaction (même contrat que `deleteMessage` v1.24.0 et
     * `purgeHistoryNow` H9).
     *
     * ⚠️ Le recalcul est **CIBLÉ** sur les seules conversations touchées, relevées avant le
     * `DELETE`. La première version employait `refreshAllConversationPreviewsAfterPurge`, qui
     * réécrit TOUTES les conversations depuis `messages.body` : une suppression dans un fil
     * vidait alors l'aperçu d'un MMS sans légende situé dans un fil **étranger** à l'opération.
     * Défaut trouvé par la relecture Codex, figé par `ReconcileDeletionsPreviewTest`.
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
            // v1.27.2 (audit Codex du 2026-08-05, P-09) — GARDE-FOU 2, RÉÉCRIT.
            //
            // Il refusait toute suppression sur une lecture vide, parce que `readAllSmsIds()`
            // rendait un tableau vide aussi bien sur un fournisseur vide que sur une requête
            // échouée. La lecture distingue désormais les deux : `null` = on n'a rien pu lire.
            val systemIds = telephonyReader.readAllSmsIds()
            if (systemIds == null) {
                Timber.w("reconcileDeletions: lecture du fournisseur impossible — rien supprime")
                return@runCatching
            }
            // v1.27.2 (audit Codex du 2026-08-05, P-09) — GARDE-FOU 5 : PROPORTIONNALITÉ,
            // **CONFIRMÉE PAR UNE SECONDE LECTURE** au lieu d'un refus définitif.
            //
            // Le principe reste : une lecture partielle mais non vide franchit toutes les autres
            // gardes, et tout ce qui manque à la page passe alors pour supprimé côté système. Ce
            // chemin efface définitivement des messages valides ; la conséquence est irréversible.
            //
            // 🔴 Mais la première version se contentait de REFUSER, et ne convergeait donc jamais.
            // Une suppression parfaitement légitime — 80 SMS sur 100 effacés depuis une autre
            // application — produisait le même refus à chaque passe, indéfiniment. Les messages
            // supprimés, potentiellement sensibles, restaient affichés ici et dans les aperçus,
            // jusqu'à ce que de nouveaux SMS diluent assez le ratio. Une garde de sécurité qui
            // fige durablement des données que l'utilisateur a voulu effacer n'est pas une
            // sécurité, c'est une fuite.
            //
            // La règle devient donc : au-delà du seuil — ou sur un fournisseur devenu vide, qui en
            // est le cas extrême — on redemande la liste complète. Deux lectures indépendantes qui
            // rendent EXACTEMENT le même ensemble ne peuvent raisonnablement pas être deux
            // troncatures identiques ; c'est la signature d'un fournisseur qui dit vrai. Si elles
            // divergent, on refuse cette passe et la suivante retentera — l'opération converge
            // dans les deux cas.
            val alive = HashSet<String>(systemIds.size)
            systemIds.forEach { alive += "$SMS_URI_PREFIX$it" }
            val gone = mirrored.filterNot { it in alive }
            if (gone.isEmpty()) return@runCatching
            val massive = systemIds.isEmpty() || gone.size > mirrored.size * MAX_DELETION_RATIO
            if (massive && !confirmsSameAliveSet(systemIds)) {
                Timber.e(
                    "reconcileDeletions: %d/%d lignes manquantes NON confirmees par une 2e lecture, ANNULE",
                    gone.size,
                    mirrored.size,
                )
                return@runCatching
            }
            if (massive) {
                Timber.w(
                    "reconcileDeletions: suppression massive CONFIRMEE (%d/%d) par une 2e lecture",
                    gone.size,
                    mirrored.size,
                )
            }
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
                // v1.27.2 (relecture Codex 2026-08-04) — recalcul CIBLÉ, et non plus global.
                //
                // La première version appelait `refreshAllConversationPreviewsAfterPurge()`,
                // qui réécrit l'aperçu de TOUTES les conversations à partir de
                // `messages.body`. Or pour un MMS sans légende, `body` est VIDE : le libellé
                // utile (« 🖼️ photo.jpg », sujet…) vit uniquement dans
                // `conversations.last_message_preview`, posé à l'insertion par
                // `ConversationMirror.touchConversation`. Une suppression dans le fil B vidait
                // donc l'aperçu du fil A, parfaitement étranger à l'opération.
                //
                // On relève les conversations concernées AVANT le `DELETE` — après, les lignes
                // n'existent plus — et on ne recalcule que celles-là.
                val affected = gone.chunked(SQLITE_HOST_PARAM_LIMIT)
                    .flatMap { batch -> messageDao.findConversationIdsByTelephonyUris(batch) }
                    .toSet()
                gone.chunked(SQLITE_HOST_PARAM_LIMIT).forEach { batch ->
                    removed += messageDao.deleteByTelephonyUris(batch)
                }
                if (removed > 0) {
                    affected.forEach { convId -> messageDao.refreshConversationPreview(convId) }
                }
            }
            Timber.i("reconcileDeletions: %d local row(s) dropped (deleted system-side)", removed)
        }.onFailure { Timber.w(it, "reconcileDeletions failed") }
    }

    /**
     * v1.27.2 (audit Codex du 2026-08-05, P-09) — une **seconde lecture complète** rend-elle
     * exactement le même ensemble d'identifiants vivants que [first] ?
     *
     * C'est la preuve de complétude que le seuil de proportionnalité, seul, ne fournissait pas.
     * Deux lectures indépendantes tronquées au même endroit sont invraisemblables ; deux lectures
     * saines coïncident toujours. Un SMS reçu entre les deux fait échouer la confirmation — la
     * passe suivante retentera, et c'est le bon sens de l'erreur.
     *
     * Comparer les tailles puis l'appartenance suffit : les identifiants du fournisseur sont
     * uniques, donc même cardinal + inclusion ⇒ même ensemble.
     */
    private fun confirmsSameAliveSet(first: LongArray): Boolean {
        val second = telephonyReader.readAllSmsIds() ?: return false
        if (second.size != first.size) return false
        val firstSet = HashSet<Long>(first.size)
        first.forEach { firstSet += it }
        return second.all { it in firstSet }
    }

    private fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** v1.26.1 (audit F6) — seules les lignes SMS sont réconciliables, cf. [reconcileDeletions]. */
        const val SMS_URI_PREFIX = "content://sms/"

        /**
         * v1.27.2 — proportion de lignes manquantes au-delà de laquelle [reconcileDeletions]
         * refuse de supprimer quoi que ce soit.
         *
         * La moitié : assez haut pour ne jamais gêner un usage réel — personne n'efface la moitié
         * de sa messagerie depuis une autre application entre deux passes de synchronisation —
         * et assez bas pour arrêter net une lecture système incomplète.
         */
        const val MAX_DELETION_RATIO = 0.5

        /** SQLite plafonne `IN (…)` à 999 paramètres hôtes ; on reste dessous. */
        const val SQLITE_HOST_PARAM_LIMIT = 900
    }
}
