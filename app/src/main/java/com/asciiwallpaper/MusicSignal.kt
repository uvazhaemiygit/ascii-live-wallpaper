package com.asciiwallpaper

import android.content.Context
import android.media.AudioManager

/**
 * Permission-free "is something playing music?" detector.
 *
 * Android does not expose another app's audio samples to a regular wallpaper.
 * Reading the output mix or capturing another app's audio requires either the
 * microphone permission, a MediaProjection consent flow, or a privileged
 * system API. None of those is acceptable here: the user explicitly does not
 * want microphone access and a wallpaper cannot keep a MediaProjection alive.
 *
 * AudioManager.isMusicActive() and the music-stream volume are public, do not
 * need a runtime permission, and work for Yandex Music, a local player,
 * streaming apps, etc. The waveform itself is generated procedurally while a
 * music stream is active; it is not an FFT of the track, but it starts/stops
 * with the phone's music and follows the user's media volume without ever
 * touching the microphone.
 */
class MusicSignal(context: Context?) {
    companion object {
        private const val POLL_INTERVAL_NANOS = 350_000_000L
    }

    private val audioManager: AudioManager? = try {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Throwable) {
        null
    }

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var volume: Float = 0f
        private set

    private var lastPollNanos = 0L

    fun poll(timeNanos: Long) {
        val elapsed = timeNanos - lastPollNanos
        if (lastPollNanos != 0L && elapsed in 0 until POLL_INTERVAL_NANOS) {
            return
        }
        lastPollNanos = timeNanos
        try {
            isPlaying = audioManager?.isMusicActive == true
            val maximum = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            volume = if (maximum > 0) {
                (current.toFloat() / maximum.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        } catch (_: Throwable) {
            isPlaying = false
            volume = 0f
        }
    }

    /**
     * The wallpaper pauses while the screen is off. Poll immediately when it
     * comes back instead of showing a stale state from before the pause.
     */
    fun reset() {
        lastPollNanos = 0L
        isPlaying = false
        volume = 0f
    }
}
