package com.filestech.sms.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.filestech.sms.data.local.datastore.AutoLockDelay
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the app's process lifecycle and triggers [AppLockManager.forceLock] after the configured
 * auto-lock delay once the app moves to background.
 *
 * Audit F13: when the app gets locked, we also purge `files/exports/` (PDFs, eventual `.smsbk`
 * staging). Without this, conversation PDFs stay readable to anyone with file-access. The user
 * already shared them — they can re-export — so eager deletion is the right trade-off.
 */
@Singleton
class AutoLockObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLock: AppLockManager,
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private var pendingLock: Job? = null

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        pendingLock?.cancel()
        pendingLock = null
    }

    override fun onStop(owner: LifecycleOwner) {
        pendingLock?.cancel()
        pendingLock = scope.launch {
            val s = settings.flow.first()
            // Audit F33: vault relocks immediately when the user opts in.
            if (s.security.lockVaultOnLeave) vault.lock()
            val ms = when (s.security.autoLockDelay) {
                AutoLockDelay.IMMEDIATE -> 0L
                AutoLockDelay.FIFTEEN_SECONDS -> 15_000L
                AutoLockDelay.ONE_MINUTE -> 60_000L
                AutoLockDelay.FIVE_MINUTES -> 5 * 60_000L
                AutoLockDelay.NEXT_LAUNCH -> Long.MAX_VALUE
            }
            if (ms == Long.MAX_VALUE) return@launch
            if (ms > 0) delay(ms)
            appLock.forceLock()
            // Audit F13 + S-P2-3: when the lock kicks in, purge generated PDFs, export staging
            // AND the transient audio caches (un-sent voice MMS drafts, sent-PDU staging). Each
            // of those holds plaintext sensitive bytes that could survive a force-stop or a
            // post-mortem analysis. The user always retains the ability to re-record or re-export.
            purgeTransientCaches()
        }
    }

    /**
     * Cleans the plaintext caches that hold sensitive bytes between sessions:
     *
     *  - `files/exports/` — generated PDFs and `.smsbk` staging (audit F13)
     *  - `cache/voice_mms/` — un-sent voice-message drafts (audit S-P2-3)
     *  - `cache/mms_outgoing/` — built PDU files for in-flight MMS (audit S-P2-3)
     *
     * Recursive delete + isolated `runCatching` per folder so a partial failure on one path does
     * not skip the others. Inbound caches (`cache/mms_incoming/`, `cache/mms_incoming_audio/`) are
     * intentionally **not** purged here: they are referenced by `AttachmentEntity.localUri` for
     * in-app playback / display, and dropping them would surface broken bubbles after every
     * lock cycle. They are wiped instead by [PanicService.nukeEverything].
     */
    private fun purgeTransientCaches() {
        // Audit P1-5 (v1.2.0): `deleteRecursively()` walks every depth — the previous
        // `listFiles()` only swept the first level and would have left any future
        // sub-directory (re-encode staging, tmp ffmpeg work-dirs, etc.) on disk.
        val targets = listOf(
            File(context.filesDir, "exports"),
            File(context.cacheDir, "voice_mms"),
            File(context.cacheDir, "mms_outgoing"),
        )
        for (dir in targets) {
            runCatching { if (dir.exists()) dir.deleteRecursively() }
                .onFailure { Timber.w(it, "AutoLockObserver: purge of %s failed", dir.absolutePath) }
        }
    }
}
