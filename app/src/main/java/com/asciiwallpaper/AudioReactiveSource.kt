package com.asciiwallpaper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tiny microphone spectrum analyser for the WAVES equalizer.
 *
 * Android does not expose other apps' audio to a wallpaper without
 * MediaProjection and an explicit capture session, so the only practical
 * "any music" source available to a wallpaper is the microphone. The source is
 * opt-in, starts only while the wallpaper is visible, and silently returns
 * zero levels when permission is missing or the OS refuses background audio.
 *
 * A 1024-point FFT is enough for 12 visual bands. Buffers are reused and the
 * UI only ever receives twelve floats, so this does not compete with the
 * renderer for allocation bandwidth.
 */
class AudioReactiveSource(private val context: Context) {
    companion object {
        private const val TAG = "AudioReactive"
        private const val SAMPLE_RATE = 44_100
        private const val FFT_SIZE = 1024
        private const val BAND_COUNT = 12
        private const val LOW_HZ = 55f
        private const val HIGH_HZ = 16_000f
    }

    @Volatile
    private var running = false

    @Volatile
    private var snapshot = FloatArray(BAND_COUNT)

    private var thread: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !hasPermission()) return
        running = true
        thread = Thread({ runLoop() }, "ascii-audio").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            recorder?.stop()
        } catch (_: Throwable) {
            Unit
        }
        val worker = thread
        thread = null
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(400L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
            Unit
        }
        recorder = null
        snapshot = FloatArray(BAND_COUNT)
    }

    fun levels(): FloatArray = snapshot

    private fun runLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running = false
            return
        }
        val bufferBytes = maxOf(minBuffer, FFT_SIZE * 2 * 3)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot create AudioRecord", t)
            running = false
            return
        }
        recorder = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            recorder = null
            running = false
            return
        }

        val samples = ShortArray(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val window = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            window[i] = 0.5f - 0.5f *
                cos((2.0 * PI * i / (FFT_SIZE - 1)).toFloat())
        }
        val bandLow = IntArray(BAND_COUNT)
        val bandHigh = IntArray(BAND_COUNT)
        val ratio = (HIGH_HZ / LOW_HZ).toDouble()
        for (band in 0 until BAND_COUNT) {
            val low = LOW_HZ *
                ratio.pow(band.toDouble() / BAND_COUNT)
            val high = LOW_HZ *
                ratio.pow((band + 1).toDouble() / BAND_COUNT)
            bandLow[band] = ((low / SAMPLE_RATE) * FFT_SIZE).toInt()
                .coerceIn(1, FFT_SIZE / 2 - 1)
            bandHigh[band] = ((high / SAMPLE_RATE) * FFT_SIZE).toInt()
                .coerceIn(bandLow[band] + 1, FFT_SIZE / 2)
        }

        try {
            record.startRecording()
            val levels = FloatArray(BAND_COUNT)
            while (running) {
                val read = record.read(samples, 0, samples.size)
                if (read < 0) {
                    // ERROR_DEAD_OBJECT means the audio server died; the
                    // other negative values are permanent buffer/state
                    // failures. Retrying would only spin a core.
                    Log.w(TAG, "AudioRecord.read failed: $read")
                    break
                }
                if (read == 0) continue
                val count = minOf(read, FFT_SIZE)
                for (i in 0 until count) {
                    real[i] = (samples[i] / 32768f) * window[i]
                    imag[i] = 0f
                }
                for (i in count until FFT_SIZE) {
                    real[i] = 0f
                    imag[i] = 0f
                }
                fft(real, imag)
                for (band in 0 until BAND_COUNT) {
                    var sum = 0f
                    var bins = 0
                    for (bin in bandLow[band] until bandHigh[band]) {
                        val re = real[bin]
                        val im = imag[bin]
                        sum += re * re + im * im
                        bins++
                    }
                    val magnitude = if (bins == 0) 0f else sqrt(sum / bins) / FFT_SIZE
                    val db = 20f * log10(magnitude + 1e-7f)
                    val value = ((db + 70f) / 48f).coerceIn(0f, 1f)
                    // A gentle curve lifts quiet mids without making silence
                    // dance. The renderer applies attack/release smoothing.
                    levels[band] = if (value < 0.04f) 0f else value * value
                }
                snapshot = levels.copyOf()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Microphone loop stopped", t)
        } finally {
            running = false
            snapshot = FloatArray(BAND_COUNT)
            try {
                record.stop()
            } catch (_: Throwable) {
                Unit
            }
            try {
                record.release()
            } catch (_: Throwable) {
                Unit
            }
            recorder = null
        }
    }

    /** In-place iterative radix-2 FFT. Allocation-free after start-up. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wR = 1f
                var wI = 0f
                val half = length shr 1
                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half
                    val oddR = real[odd] * wR - imag[odd] * wI
                    val oddI = real[odd] * wI + imag[odd] * wR
                    real[odd] = real[even] - oddR
                    imag[odd] = imag[even] - oddI
                    real[even] += oddR
                    imag[even] += oddI
                    val nextR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextR
                }
                start += length
            }
            length = length shl 1
        }
    }
}
