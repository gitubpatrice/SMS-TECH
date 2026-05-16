package com.filestech.sms.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.filestech.sms.core.result.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a single voice clip to an AAC/M4A file in the app's private cache, suitable for
 * attaching to an outgoing MMS. The encoder is the system AAC encoder (hardware-accelerated on
 * every Android 5+ device); no third-party library is used.
 *
 * Hard caps:
 *   - duration: [MAX_DURATION_MS] (120 s)
 *   - file size: [MAX_SIZE_BYTES] (280 KB) — empirically the tightest cap shared by all
 *     French MMSCs (Free, Orange, SFR, Bouygues). The v1.3.0 → v1.3.2 cap of 450 KB caused
 *     dispatch failures on SFR (observed user report 2026-05-16) for clips > ~90 s @ 24 kbps.
 *
 * Concurrency: only one recording at a time. A second [record] call while one is active emits
 * [Event.Failed] with [AppError.Telephony] "recorder busy" and closes immediately.
 *
 * Security:
 *   - Files live in [Context.getCacheDir]/voice_mms (app-private, not on external storage).
 *   - No content is logged — only timing/state.
 *   - [pruneOld] purges files older than 24 h. Caller is responsible for invoking it (typically
 *     on app start) so a forgotten draft does not linger on disk indefinitely.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Event {
        /** Emitted every [TICK_INTERVAL_MS] with elapsed time and current peak amplitude (0..32767). */
        data class Tick(val elapsedMs: Long, val amplitude: Int) : Event

        /**
         * Recording finished cleanly. [cappedByLimit] is true when the MediaRecorder hit
         * [MAX_DURATION_MS] or [MAX_SIZE_BYTES] on its own; false when the caller asked to stop.
         */
        data class Completed(val result: Result, val cappedByLimit: Boolean) : Event

        /** Recording failed or stop() threw (e.g., recorded < 1 s — codec rejects). */
        data class Failed(val error: AppError) : Event
    }

    data class Result(
        val file: File,
        val durationMs: Long,
        val sizeBytes: Long,
        val mimeType: String,
    )

    private class Session(
        val recorder: MediaRecorder,
        val file: File,
        val startNs: Long,
        val channel: ProducerScope<Event>,
    ) {
        /** Latched once [finalize] / [cancel] has run; subsequent calls become no-ops. */
        @Volatile var finalized: Boolean = false
    }

    private val lock = Any()

    /** Active session, if any. Reads and writes go through [lock]. */
    @Volatile private var current: Session? = null

    /**
     * Starts a recording. Collect the returned cold [Flow] from a coroutine. The recording stops
     * when the consumer calls [stop] (clean stop → [Event.Completed]), [cancel] (file deleted →
     * Flow closes silently), the hard duration/size cap is reached (→ [Event.Completed] with
     * `cappedByLimit = true`), or an underlying [MediaRecorder] error is reported.
     *
     * The Flow auto-closes after the terminal event so a single collection corresponds to a
     * single recording session.
     */
    fun record(): Flow<Event> = callbackFlow {
        // Reject re-entry. We do NOT auto-cancel the previous session — that would surprise the
        // caller; surface "busy" so the UI can react explicitly.
        synchronized(lock) {
            if (current != null) {
                trySend(Event.Failed(AppError.Telephony("recorder busy")))
                close()
                return@callbackFlow
            }
        }

        val dir = recordingsDir().also { it.mkdirs() }
        val file = File(dir, "voice-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.m4a")

        val recorder = newMediaRecorder()
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BITRATE_BPS)
                setMaxDuration(MAX_DURATION_MS)
                setMaxFileSize(MAX_SIZE_BYTES)
                setOutputFile(file.absolutePath)
            }
        } catch (t: Throwable) {
            // Configuration errors (e.g., AudioSource.MIC rejected by SecurityManager when the
            // RECORD_AUDIO permission is missing) surface here.
            Timber.w(t, "MediaRecorder configuration failed")
            safeRelease(recorder)
            file.delete()
            trySend(Event.Failed(mapError(t)))
            close()
            return@callbackFlow
        }

        val session = Session(recorder, file, System.nanoTime(), this)

        recorder.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                    finalize(session, cappedByLimit = true)
            }
        }
        recorder.setOnErrorListener { _, what, extra ->
            Timber.w("MediaRecorder error what=$what extra=$extra")
            cancelInternal(session, AppError.Telephony("MediaRecorder error $what/$extra"))
        }

        try {
            recorder.prepare()
            recorder.start()
        } catch (t: Throwable) {
            Timber.w(t, "MediaRecorder.start() threw")
            safeRelease(recorder)
            file.delete()
            trySend(Event.Failed(mapError(t)))
            close()
            return@callbackFlow
        }

        synchronized(lock) { current = session }

        // Amplitude ticker. Runs off the main thread so a stalled UI doesn't drop ticks.
        val tickerJob = launch(Dispatchers.Default) {
            while (isActive && !session.finalized) {
                val elapsed = (System.nanoTime() - session.startNs) / 1_000_000
                val amp = runCatching { session.recorder.maxAmplitude }.getOrDefault(0)
                trySend(Event.Tick(elapsed, amp))
                delay(TICK_INTERVAL_MS)
            }
        }

        awaitClose {
            tickerJob.cancel()
            // Defensive: if the consumer cancelled the Flow without calling stop()/cancel(),
            // treat it as a cancel — release the recorder and drop the partial file.
            //
            // Audit M-8: the `finalized` check + write must happen **inside the same
            // synchronized block**. The previous code read `!session.finalized` outside the
            // lock and only then claimed it inside, leaving a window where a concurrent
            // [stop] or [cancel] could finalise the session AND release the recorder
            // between the two — yielding a double `MediaRecorder.release()` call, which
            // pushes the native code into undefined state (occasional crash).
            val didClaimHere = synchronized(lock) {
                if (current === session && !session.finalized) {
                    session.finalized = true
                    current = null
                    true
                } else {
                    false
                }
            }
            if (didClaimHere) {
                safeRelease(session.recorder)
                session.file.delete()
            }
        }
    }

    /**
     * Asks the active recording to stop cleanly. Finalises the M4A file and emits
     * [Event.Completed] on the active Flow, then closes it. No-op if no session is active.
     */
    fun stop() {
        val session = synchronized(lock) { current } ?: return
        finalize(session, cappedByLimit = false)
    }

    /**
     * Cancels the active recording, releases the recorder, and deletes the audio file. The
     * active Flow closes without a terminal [Event.Completed]. No-op if no session is active.
     */
    fun cancel() {
        val session = synchronized(lock) { current } ?: return
        cancelInternal(session, error = null)
    }

    /**
     * Removes recording files older than 24 h from the cache. Safe to call at app start.
     * Active recordings are protected by the [current] reference (the in-flight file is held
     * open by [MediaRecorder] anyway).
     */
    fun pruneOld() {
        val dir = recordingsDir()
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - PRUNE_AGE_MS
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }

    private fun finalize(session: Session, cappedByLimit: Boolean) {
        synchronized(lock) {
            if (session.finalized) return
            session.finalized = true
        }
        val stopOk = runCatching { session.recorder.stop() }.isSuccess
        safeRelease(session.recorder)
        synchronized(lock) { if (current === session) current = null }

        if (!stopOk || session.file.length() == 0L) {
            // recorder.stop() throws RuntimeException when the clip is too short (no frames
            // committed). Treat as a soft failure — the caller can retry.
            session.file.delete()
            session.channel.trySend(Event.Failed(AppError.Telephony("recording too short")))
            session.channel.close()
            return
        }

        val durationMs = (System.nanoTime() - session.startNs) / 1_000_000
        val result = Result(
            file = session.file,
            durationMs = durationMs,
            sizeBytes = session.file.length(),
            mimeType = MIME_AUDIO_M4A,
        )
        session.channel.trySend(Event.Completed(result, cappedByLimit))
        session.channel.close()
    }

    private fun cancelInternal(session: Session, error: AppError?) {
        synchronized(lock) {
            if (session.finalized) return
            session.finalized = true
        }
        safeRelease(session.recorder)
        session.file.delete()
        synchronized(lock) { if (current === session) current = null }
        if (error != null) session.channel.trySend(Event.Failed(error))
        session.channel.close()
    }

    private fun recordingsDir(): File = File(context.cacheDir, RECORDINGS_DIR)

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    private fun safeRelease(recorder: MediaRecorder) {
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    private fun mapError(t: Throwable): AppError = when (t) {
        is SecurityException -> AppError.Permission("android.permission.RECORD_AUDIO")
        else -> AppError.Telephony("recorder failed", t)
    }

    companion object {
        const val MIME_AUDIO_M4A: String = "audio/mp4"

        /**
         * Hard duration cap. v1.3.0 : 60 s → 120 s. À 16 kHz mono AAC [BITRATE_BPS]
         * (16 kbps depuis v1.3.3), 120 s génère ~240 KB encodé (16_000 b/s × 120 s =
         * 240 000 B de payload + ~5 KB d'overhead conteneur MP4 / esds / moov), ce qui
         * reste sous la limite [MAX_SIZE_BYTES] ; sans ça MediaRecorder couperait avant
         * 120 s pour cause de taille, et la promesse "120 s" serait mensongère.
         */
        const val MAX_DURATION_MS: Int = 120_000

        /**
         * Hard size cap. **v1.3.3** : 280 KB — cap empirique compatible avec **tous** les
         * MMSCs FR (Free, Orange, SFR, Bouygues). Le cap antérieur (450 KB v1.3.0–v1.3.2)
         * provoquait des `RESULT_ERROR_GENERIC_FAILURE` côté radio SFR (~300 KB limite
         * observée 2026-05-16). 280 KB laisse une marge pour les headers MMS + SMIL
         * sous le ceiling carrier le plus strict.
         */
        const val MAX_SIZE_BYTES: Long = 280L * 1024L

        const val SAMPLE_RATE_HZ: Int = 16_000

        /**
         * **v1.3.3** : 24 kbps → 16 kbps. Qualité voix mono à 16 kHz AAC reste totalement
         * intelligible (référence : WhatsApp push-to-talk ≈ 16 kbps, GSM AMR ≈ 12.2 kbps).
         * Permet 120 s sous 240 KB → marge confortable sous [MAX_SIZE_BYTES] (280 KB) →
         * envoi MMS qui ne risque PLUS le `RESULT_ERROR_GENERIC_FAILURE` chez SFR & co.
         */
        const val BITRATE_BPS: Int = 16_000

        const val TICK_INTERVAL_MS: Long = 100L

        private const val RECORDINGS_DIR: String = "voice_mms"
        private const val PRUNE_AGE_MS: Long = 24L * 60L * 60L * 1000L
    }
}
