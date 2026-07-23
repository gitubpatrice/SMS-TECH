package com.filestech.sms.system.startup

import android.content.Context
import com.filestech.sms.data.local.datastore.AdvancedSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.IoDispatcher
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the one-shot cold-start migrations that **open the SQLCipher database**.
 *
 * v1.24.0 — before this collaborator, `MainApplication.onCreate` launched three of these as
 * separate `appScope.launch` blocks, each with its own `settingsRepository.flow.first()` read.
 * They all hit the same encrypted database, so running them in parallel produced only lock
 * contention, and every cold start re-read the settings three times just to check flags that were
 * usually already set.
 *
 * Here they run **sequentially, after a single settings read**, and — the real win — a global
 * guard ([AdvancedSettings.startupDbMigrationsDone]) lets an up-to-date install return **without
 * opening SQLCipher at all**. Each migration keeps its own flag as the source of truth; the global
 * guard is only set once all three individual flags are, so it can never skip work that has not
 * actually completed.
 *
 * The lighter cold-start repairs that never touch the database (emergency-state repair, monotonic
 * drift, blocklist import) stay in `MainApplication`: moving them here would add risk without any
 * database-contention benefit.
 *
 * The DAOs are [Lazy] because this class is resolved from `MainApplication` on the main thread; a
 * direct injection would provision `AppDatabase` there. [run] is called from an IO coroutine, where
 * `get()` is safe.
 */
@Singleton
class StartupMigrations @Inject constructor(
    private val settings: SettingsRepository,
    private val messageDao: Lazy<MessageDao>,
    private val conversationDao: Lazy<ConversationDao>,
    private val attachmentDao: Lazy<AttachmentDao>,
    private val conversationMirror: Lazy<ConversationMirror>,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Runs every pending database migration, then records completion globally.
     *
     * Must be called from a background coroutine — it opens SQLCipher, which the caller must never
     * do on the main thread (cf. `LegacyZeroKeyRekey`).
     */
    suspend fun run() = withContext(io) {
        val advanced = runCatching { settings.flow.first().advanced }.getOrNull() ?: return@withContext

        // v1.24.0 — réparation one-shot des aperçus de conversation périmés par des suppressions
        // sous ≤ 1.23.4. INDÉPENDANTE de la garde globale : elle doit tourner même sur une install
        // déjà migrée qui passe à 1.24.0. Ciblée (ne touche que les conversations dont le dernier
        // message a été supprimé), donc sûre à exécuter une fois.
        if (!advanced.staleConversationPreviewsRepairedV1240) {
            runCatching {
                val fixed = messageDao.get().repairStaleConversationPreviews()
                settings.update { s ->
                    s.copy(advanced = s.advanced.copy(staleConversationPreviewsRepairedV1240 = true))
                }
                if (fixed > 0) Timber.i("Repaired %d stale conversation preview(s)", fixed)
            }.onFailure { Timber.w(it, "stale conversation preview repair failed") }
        }

        // Global short-circuit: an up-to-date install does no migration work and never opens the
        // database. This is the whole point of the consolidation.
        if (advanced.startupDbMigrationsDone) return@withContext

        runCatching { unreadResetV180(advanced) }
            .onFailure { Timber.w(it, "v1.8.0 migration unreadResetV180 failed") }
        runCatching { migrateAttachmentsToFilesDirIfNeeded(advanced) }
            .onFailure { Timber.w(it, "v1.14.7 attachments migration failed") }
        runCatching { dedupeSameNumberConversations(advanced) }
            .onFailure { Timber.w(it, "dedupeSameNumberConversations failed") }

        maybeMarkAllDone()
    }

    /**
     * Sets the global short-circuit only when all three individual flags are set. Re-reads the
     * settings once (the flags may have flipped during this run). Safe by construction: a migration
     * still pending keeps its own flag false, so the guard stays off and we re-run next cold start.
     */
    private suspend fun maybeMarkAllDone() {
        runCatching {
            val a = settings.flow.first().advanced
            val allDone = a.unreadResetV180 && a.attachmentsMovedToFilesDirV147 && a.dedupSameNumberV1230
            if (allDone && !a.startupDbMigrationsDone) {
                settings.update { s -> s.copy(advanced = s.advanced.copy(startupDbMigrationsDone = true)) }
            }
        }.onFailure { Timber.w(it, "startupDbMigrationsDone flag update failed") }
    }

    // ---------------------------------------------------------------------------------------------
    // Migration bodies — moved VERBATIM from MainApplication (v1.24.0). Only the injected
    // collaborator names changed; the logic and audit history are unchanged.
    // ---------------------------------------------------------------------------------------------

    /**
     * v1.8.0 (post-audit fix unread badges) — purge l'état legacy v1.7.1 : marque tous les
     * INCOMING lus + recompute les compteurs. Le flag `unreadResetV180` empêche le rejeu.
     */
    private suspend fun unreadResetV180(advanced: AdvancedSettings) {
        if (advanced.unreadResetV180) return
        val touched = messageDao.get().markAllIncomingAsRead()
        conversationDao.get().recomputeAllUnreadCounts()
        settings.update { s -> s.copy(advanced = s.advanced.copy(unreadResetV180 = true)) }
        Timber.i(
            "v1.8.0 migration unreadResetV180 done: marked %d incoming messages as read",
            touched,
        )
    }

    /**
     * v1.22.x — dédup one-heal des doublons de conversations du même numéro. On ne mémorise la
     * complétion QUE lorsque la base est propre (retour `false` = rien à fusionner) : tant que la
     * passe fusionne encore des doublons, on re-scanne au prochain cold-start.
     */
    private suspend fun dedupeSameNumberConversations(advanced: AdvancedSettings) {
        if (advanced.dedupSameNumberV1230) return
        val merged = conversationMirror.get().dedupeSameNumberConversations()
        if (!merged) {
            settings.update { s -> s.copy(advanced = s.advanced.copy(dedupSameNumberV1230 = true)) }
        }
    }

    /**
     * v1.14.7 — déplace les attachments MMS reçus de `cacheDir/mms_incoming/` (volatile) vers
     * `filesDir/mms_attachments/` (persistant) et réécrit les `AttachmentEntity.local_uri`.
     *
     * Corps déplacé verbatim ; `cacheDir`/`filesDir` deviennent `context.cacheDir`/`context.filesDir`.
     */
    private suspend fun migrateAttachmentsToFilesDirIfNeeded(advanced: AdvancedSettings) {
        if (advanced.attachmentsMovedToFilesDirV147) return

        val oldDir = File(context.cacheDir, "mms_incoming")
        val newDir = File(context.filesDir, "mms_attachments").apply { mkdirs() }
        // v1.14.7 audit S1 — canonicalFile sur newDir pour le path-traversal check ultérieur.
        val newDirCanonical = runCatching { newDir.canonicalFile }.getOrNull() ?: run {
            Timber.w("v1.14.7 migration: cannot canonicalize newDir %s", newDir.absolutePath)
            return
        }
        val oldPrefixAbs = oldDir.absolutePath + File.separator

        val candidates = runCatching {
            attachmentDao.get().findByLocalUriPrefix(oldPrefixAbs)
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) {
            // Pas de migration à faire — on flippe le flag pour éviter de re-tenter à chaque cold-start.
            runCatching {
                settings.update { s -> s.copy(advanced = s.advanced.copy(attachmentsMovedToFilesDirV147 = true)) }
            }
            return
        }

        Timber.i("v1.14.7 attachments migration: %d rows to move from cacheDir to filesDir", candidates.size)

        var moved = 0
        var skipped = 0
        for (att in candidates) {
            val srcFile = File(att.localUri)
            val fileName = srcFile.name
            val dstFile = File(newDir, fileName)
            val dstCanonical = runCatching { dstFile.canonicalFile }.getOrNull()
            if (dstCanonical == null || !dstCanonical.toPath().startsWith(newDirCanonical.toPath())) {
                Timber.w("v1.14.7 migration: path traversal rejected for %s → %s", att.localUri, dstFile.absolutePath)
                continue
            }
            runCatching {
                if (!srcFile.exists()) {
                    // Fichier source disparu (cache déjà clearé par Android) — on flippe quand même
                    // le local_uri vers le nouveau chemin pour ne pas confondre ce row orphelin avec
                    // les futurs MMS reçus qui iront direct dans filesDir.
                    skipped++
                    if (att.localUri != dstFile.absolutePath) {
                        attachmentDao.get().updateLocalUri(att.id, dstFile.absolutePath)
                    }
                    return@runCatching
                }
                if (dstFile.exists()) {
                    // Destination existe déjà (re-migration partielle, ou collision de nom) — on
                    // garde la destination + supprime la source pour ne pas laisser de doublon.
                    runCatching { srcFile.delete() }
                    attachmentDao.get().updateLocalUri(att.id, dstFile.absolutePath)
                    moved++
                    return@runCatching
                }
                val renamed = srcFile.renameTo(dstFile)
                if (!renamed) {
                    // Fallback : copy + delete (cas partition différente, rare sur Android moderne).
                    srcFile.inputStream().use { input ->
                        dstFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    runCatching { srcFile.delete() }
                }
                attachmentDao.get().updateLocalUri(att.id, dstFile.absolutePath)
                moved++
            }.onFailure { Timber.w(it, "v1.14.7 migration: failed to move %s", att.localUri) }
        }

        Timber.i("v1.14.7 attachments migration: moved=%d skipped=%d", moved, skipped)
        runCatching {
            settings.update { s -> s.copy(advanced = s.advanced.copy(attachmentsMovedToFilesDirV147 = true)) }
        }
    }
}
