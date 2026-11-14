package com.asciiwallpaper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Optional real FFT source for the WAVES style.
 *
 * Android's [Visualizer] effect can report the frequency content of the global
 * output mix. It is not an AudioRecord/microphone capture, but the platform
 * protects it with the RECORD_AUDIO permission; the user explicitly opts in
 * from the settings and the microphone is never opened. Session 0 (the output
 * mix) additionally needs MODIFY_AUDIO_SETTINGS.
 *
 * Visualizer support varies between OEMs, and some Android releases restrict
 * the global mix. [start] therefore returns false instead of throwing; the
 * renderer then keeps its permission-free procedural fallback.
 */
class AudioSpectrumSource(private val context: Context?) {
    companion object {
        const val BAND_COUNT = 12

        private const val TAG = "AsciiSpectrum"
        private const val MIN_HZ = 55f
        private const val MAX_HZ = 16_000f
        // A component byte pair is at most sqrt(128^2 + 128^2).
        private const val FFT_COMPONENT_SCALE = 181f
        private const val NOISE_FLOOR = 0.025f
        private const val OUTPUT_GAMMA = 0.62f
        private const val MIN_AGC_REFERENCE = 0.16f
    }

    private val levelLock = Any()
    private val latestLevels = FloatArray(BAND_COUNT)
    private var visualizer: Visualizer? = null

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Exponential peak follower for quiet tracks; only touched by the callback. */
    private var agcPeak = 0f

    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
            visualizer: Visualizer?,
            waveform: ByteArray?,
            samplingRate: Int
        ) {
            // FFT only; no waveform or microphone samples are retained.
        }

        override fun onFftDataCapture(
            visualizer: Visualizer?,
            fft: ByteArray?,
            samplingRate: Int
        ) {
            if (fft == null || fft.size < 4) return
            if (!isAvailable) return
            updateBands(fft, normalizeSampleRate(visualizer, samplingRate))
        }
    }

    /**
     * Starts the global-mix visualizer if the user granted the permission and
     * the device supports it. Does not prompt for permission itself.
     */
    @Synchronized
    fun start(): Boolean {
        val ctx = context ?: return false
        if (visualizer != null) return isAvailable
        if (!hasPermission(ctx, Manifest.permission.RECORD_AUDIO) ||
            !hasPermission(ctx, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        ) {
            return false
        }

        var created: Visualizer? = null
        try {
            created = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            val minimum = range.getOrNull(0) ?: 1024
            val maximum = range.getOrNull(1) ?: 1024
            val captureSize = 1024.coerceIn(minimum, maximum)
            if (created.setCaptureSize(captureSize) != Visualizer.SUCCESS) {
                Log.w(TAG, "Visualizer rejected capture size $captureSize")
            }
            try {
                created.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            } catch (e: Exception) {
                Log.w(TAG, "Normalized scaling unavailable: ${e.message}")
            }

            val maxRate = Visualizer.getMaxCaptureRate().coerceAtLeast(10_000)
            val result = created.setDataCaptureListener(
                captureListener,
                maxRate,
                false,
                true
            )
            if (result != Visualizer.SUCCESS) {
                throw IllegalStateException("Visualizer listener error $result")
            }
            val enableResult = created.setEnabled(true)
            if (enableResult != Visualizer.SUCCESS) {
                throw IllegalStateException("Visualizer enable error $enableResult")
            }

            visualizer = created
            agcPeak = 0f
            synchronized(levelLock) { latestLevels.fill(0f) }
            isAvailable = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Real spectrum unavailable: ${e.message}")
            if (created != null) {
                try {
                    created.setEnabled(false)
                } catch (_: Exception) {
                    Unit
                }
                try {
                    created.release()
                } catch (_: Exception) {
                    Unit
                }
            }
            isAvailable = false
            synchronized(levelLock) { latestLevels.fill(0f) }
            return false
        }
    }

    @Synchronized
    fun stop() {
        val active = visualizer
        visualizer = null
        isAvailable = false
        if (active != null) {
            try {
                active.setDataCaptureListener(null, 0, false, false)
            } catch (_: Exception) {
                Unit
            }
            try {
                active.setEnabled(false)
            } catch (_: Exception) {
                Unit
            }
            try {
                active.release()
            } catch (_: Exception) {
                Unit
            }
        }
        synchronized(levelLock) { latestLevels.fill(0f) }
    }

    /**
     * Copies the latest 12 normalized levels. Returns false when real capture
     * is not active, which tells the renderer to use its procedural fallback.
     */
    fun readInto(destination: FloatArray): Boolean {
        if (!isAvailable || destination.isEmpty()) return false
        synchronized(levelLock) {
            val count = min(destination.size, BAND_COUNT)
            latestLevels.copyInto(destination, 0, 0, count)
            if (destination.size > count) {
                destination.fill(0f, count)
            }
        }
        return true
    }

    private fun updateBands(fft: ByteArray, sampleRate: Int) {
        val n = fft.size
        if (sampleRate <= 0 || n < 4) return
        val bins = n / 2 + 1
        val levels = FloatArray(BAND_COUNT)
        var maximum = 0f

        for (band in 0 until BAND_COUNT) {
            val startFraction = band / BAND_COUNT.toFloat()
            val endFraction = (band + 1) / BAND_COUNT.toFloat()
            val startHz = hzAt(startFraction)
            val endHz = hzAt(endFraction)
            var lowBin = ceil(startHz * n / sampleRate).toInt()
                .coerceIn(1, bins - 1)
            var highBin = floor(endHz * n / sampleRate).toInt()
                .coerceIn(lowBin, bins - 1)
            if (highBin < lowBin) highBin = lowBin

            var sumSquares = 0f
            var peak = 0f
            var count = 0
            for (bin in lowBin..highBin) {
                val magnitude = magnitudeAt(fft, bin, n) / FFT_COMPONENT_SCALE
                val value = magnitude.coerceIn(0f, 1f)
                sumSquares += value * value
                if (value > peak) peak = value
                count++
            }
            if (count == 0) continue
            val rms = sqrt(sumSquares / count)
            // A narrow tone should remain visible instead of disappearing in
            // an average over many high-frequency bins.
            val raw = (rms * 0.62f + peak * 0.38f)
            val shaped = ((raw - NOISE_FLOOR) / (1f - NOISE_FLOOR))
                .coerceIn(0f, 1f)
                .pow(OUTPUT_GAMMA)
            levels[band] = shaped
            if (shaped > maximum) maximum = shaped
        }

        // A gentle automatic gain makes quiet albums visible without letting
        // an idle noise floor paint the whole spectrum. The renderer applies
        // its own attack/decay smoothing afterwards.
        if (maximum < 0.012f) {
            agcPeak *= 0.90f
            synchronized(levelLock) { latestLevels.fill(0f) }
            return
        }
        agcPeak = max(maximum, agcPeak * 0.94f)
        val reference = max(agcPeak, MIN_AGC_REFERENCE)
        synchronized(levelLock) {
            for (band in 0 until BAND_COUNT) {
                latestLevels[band] = (levels[band] / reference)
                    .coerceIn(0f, 1f)
                    .pow(0.88f)
            }
        }
    }

    private fun magnitudeAt(fft: ByteArray, bin: Int, n: Int): Float {
        if (bin == 0) return abs(fft[0].toInt()).toFloat()
        if (bin == n / 2) return abs(fft[1].toInt()).toFloat()
        val index = bin * 2
        if (index + 1 >= fft.size) return 0f
        return hypot(fft[index].toFloat(), fft[index + 1].toFloat())
    }

    private fun hzAt(fraction: Float): Float {
        val ratio = MAX_HZ / MIN_HZ
        return MIN_HZ * ratio.pow(fraction.coerceIn(0f, 1f))
    }

    /**
     * Callback implementations differ between releases/OEMs: some report Hz,
     * some report milliHertz, and a few report 0 until the first capture.
     */
    private fun normalizeSampleRate(visualizer: Visualizer?, callbackRate: Int): Int {
        val candidate = when {
            callbackRate > 0 -> callbackRate
            else -> try {
                visualizer?.samplingRate ?: 0
            } catch (_: Exception) {
                0
            }
        }
        return when {
            candidate <= 0 -> 44_100
            candidate > 1_000_000 -> candidate / 1000
            candidate < 8_000 -> candidate * 1000
            else -> candidate
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
