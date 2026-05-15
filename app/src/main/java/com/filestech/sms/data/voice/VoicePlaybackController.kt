package com.filestech.sms.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide audio playback for voice messages. Only ONE clip plays at a time — starting a new
 * playback automatically stops any previous one. This is the canonical pattern used by
 * messengers (Signal/Telegram/WhatsApp): tap one bubble while another is playing → the first
 * pauses.
 *
 * Identity: each playable source is keyed by a stable [String] (we use the attachment URI).
 * The active key is exposed through [state.activeKey] so UI bubbles can highlight themselves.
 *
 * Resource hygiene:
 *   - The underlying [MediaPlayer] is created lazily and destroyed on [stop]/[release].
 *   - The progress ticker (50 ms) runs only while playing.
 *   - Call [release] from the host Activity's `onDestroy` to be safe — though typical use never
 *     leaks because we drop the player at the end of every clip.
 */
@Singleton
class VoicePlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class PlaybackState(
        /** Stable identity of the source currently bound to the underlying player. */
        val activeKey: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null

    /**
     * Plays the clip at [uri], keyed by [key] (typically the attachment URI string). If [key]
     * matches the currently active clip:
     *  - paused  → resume
     *  - playing → pause
     * Otherwise the previous clip is stopped and a new player is created.
     */
    fun toggle(key: String, uri: Uri) {
        val st = _state.value
        if (st.activeKey == key && player != null) {
            if (st.isPlaying) pause() else resume()
            return
        }
        startFresh(key, uri)
    }

    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopTicker()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun resume() {
        val mp = player ?: return
        if (!mp.isPlaying) {
            mp.start()
            startTicker()
            _state.update { it.copy(isPlaying = true) }
        }
    }

    /** Seeks within the active clip. No-op if no clip is loaded. */
    fun seekTo(positionMs: Int) {
        val mp = player ?: return
        val clamped = positionMs.coerceIn(0, mp.duration.coerceAtLeast(0))
        mp.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    /**
     * Stops + releases the underlying player. Resets [state] to idle.
     *
     * Audit M-9: the player reference is **atomically captured-and-nulled** under a
     * monitor before any native call. Without this, two concurrent callers (typically
     * ViewModel-cleared + Activity-onDestroy on a screen rotation with playback active)
     * could each read the same `MediaPlayer` reference and each invoke `release()` on it.
     * MediaPlayer reacts to a double-release with undefined behaviour (the AOSP code path
     * is reachable: native `mediaplayer.cpp` asserts then SIGSEGV in some OEM builds).
     * The captured-and-nulled pattern guarantees only one caller ever holds the reference.
     */
    fun stop() {
        stopTicker()
        val captured = synchronized(this) {
            val ref = player
            player = null
            ref
        } ?: run {
            // Nothing to release — but still reset the state so a stale "playing" sticker
            // doesn't survive (cheap idempotent operation).
            _state.value = PlaybackState()
            return
        }
        runCatching { if (captured.isPlaying) captured.stop() }
        runCatching { captured.reset() }
        runCatching { captured.release() }
        _state.value = PlaybackState()
    }

    /** Called from the host activity's `onDestroy` to be sure no MediaPlayer is leaked. */
    fun release() {
        stop()
    }

    private fun startFresh(key: String, uri: Uri) {
        stop()
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        try {
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener { ready ->
                _state.update {
                    it.copy(
                        activeKey = key,
                        isPlaying = true,
                        positionMs = 0,
                        durationMs = ready.duration.coerceAtLeast(0),
                    )
                }
                ready.start()
                startTicker()
            }
            mp.setOnCompletionListener {
                stopTicker()
                _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
            }
            mp.setOnErrorListener { _, what, extra ->
                Timber.w("MediaPlayer error what=$what extra=$extra")
                stop()
                true
            }
            mp.prepareAsync()
            player = mp
            _state.update { it.copy(activeKey = key, isPlaying = false, positionMs = 0, durationMs = 0) }
        } catch (t: Throwable) {
            Timber.w(t, "MediaPlayer.setDataSource failed")
            runCatching { mp.release() }
            player = null
            _state.value = PlaybackState()
        }
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (isActive) {
                val mp = player ?: break
                if (!mp.isPlaying) break
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                _state.update { it.copy(positionMs = pos) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        /**
         * Position-poll cadence. Audit P-P1-3: the original 50 ms ticked the seekbar at 20 Hz,
         * which is overkill for a progress indicator (the eye cannot resolve it) and drove a
         * 20 Hz recomposition wave through **every** audio bubble in the visible thread — a
         * 500-message thread playing one clip was burning ~10 k recompositions/s. 200 ms (5 Hz)
         * keeps the bar smooth without taxing the framework.
         */
        private const val TICK_MS: Long = 200L
    }
}
