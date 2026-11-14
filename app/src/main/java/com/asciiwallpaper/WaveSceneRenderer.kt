package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * PS3-style bottom waves with three connected states:
 *
 *  - idle: three ribbons leave one shared point on the left edge, fan out
 *    across the lower three quarters of the screen, and return to one shared
 *    point on the right edge;
 *  - phone music: the three ribbons converge into a single spectrum-shaped
 *    wave. There is no separate equalizer object;
 *  - charger connected: the ribbons flatten into one horizontal line whose
 *    height is the battery percentage (top = 100%, bottom = 0%). Everything
 *    below the line is filled green while the state fades in/out.
 *
 * [MusicSignal] detects an active music stream and its volume without a
 * permission. When the user enables [AudioSpectrumSource], real FFT levels are
 * supplied instead; otherwise the band motion is generated procedurally. The
 * microphone is never opened.
 */
class WaveSceneRenderer {
    companion object {
        private const val BAND_COUNT = 12
        private const val TWO_PI = PI.toFloat() * 2f
        private const val WAVE_REGION_TOP = 0.25f
        private const val WAVE_ANCHOR_Y = 0.625f
        private const val MUSIC_WAVE_HEIGHT = 0.34f
        private const val BATTERY_FILL_BRIGHTNESS = 0.74f
    }

    private val wavePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val wavePath = Path()

    private val smoothedLevels = FloatArray(BAND_COUNT)
    private val waveAmplitudes = FloatArray(3)
    private val waveOffsets = floatArrayOf(-0.18f, 0f, 0.18f)
    private val waveFrequencies = floatArrayOf(0.88f, 1.02f, 1.16f)
    private val waveBrightness = floatArrayOf(0.58f, 0.78f, 1.00f)

    private var musicAmount = 0f
    private var batteryAmount = 0f
    private var batteryLevel = 0.5f
    private var batteryTarget = 0.5f

    /**
     * Called when the WAVES style becomes visible. A charger that was already
     * connected should show the meter immediately rather than replaying the
     * plug-in transition; transitions are animated only while the style is on
     * screen.
     */
    fun onShown(charging: Boolean, batteryPercent: Int) {
        batteryTarget = (batteryPercent.coerceIn(0, 100) / 100f)
        batteryLevel = batteryTarget
        batteryAmount = if (charging) 1f else 0f
        musicAmount = 0f
        smoothedLevels.fill(0f)
    }

    fun advance(
        dt: Float,
        charging: Boolean,
        batteryPercent: Int,
        musicActive: Boolean,
        musicVolume: Float,
        timeSeconds: Float,
        realSpectrum: FloatArray? = null
    ) {
        batteryTarget = (batteryPercent.coerceIn(0, 100) / 100f)
        val levelSpeed = if (batteryLevel < batteryTarget) 3.2f else 2.0f
        batteryLevel += (batteryTarget - batteryLevel) *
            (dt * levelSpeed).coerceIn(0f, 1f)

        // No special flash any more: the three ribbons morph into the battery
        // line, and the green fill fades in/out with the same amount.
        val targetBattery = if (charging) 1f else 0f
        batteryAmount += (targetBattery - batteryAmount) *
            (dt * 2.8f).coerceIn(0f, 1f)
        if (batteryAmount < 0.001f) batteryAmount = 0f

        val targetMusic = if (musicActive && !charging) 1f else 0f
        musicAmount += (targetMusic - musicAmount) *
            (dt * 2.6f).coerceIn(0f, 1f)
        if (musicAmount < 0.001f) musicAmount = 0f

        val energy = if (musicActive && !charging) {
            (0.32f + 0.68f * musicVolume.coerceIn(0f, 1f)).coerceIn(0.32f, 1f)
        } else {
            0f
        }
        // A deterministic mix of slow and fast oscillators gives the bands a
        // musical-looking attack/decay when real FFT data is unavailable.
        val beatPhase = (timeSeconds * 1.85f) % 1f
        val kick = exp(-beatPhase * 7.0f)
        val real = realSpectrum
        val volumeGain = (0.58f + 0.42f * musicVolume.coerceIn(0f, 1f))
        for (band in 0 until BAND_COUNT) {
            val target = when {
                energy == 0f -> 0f
                real != null && band < real.size -> (real[band].coerceIn(0f, 1f) *
                    volumeGain).coerceIn(0f, 1f)
                else -> {
                    val phase = band * 1.371f
                    val slow = 0.5f + 0.5f * sin(
                        timeSeconds * (1.20f + band * 0.19f) + phase
                    )
                    val fast = 0.5f + 0.5f * sin(
                        timeSeconds * (2.65f - band * 0.082f) + phase * 1.73f
                    )
                    val texture = slow * 0.56f + fast * 0.44f
                    val bassBias = 1f - band / (BAND_COUNT + 2f)
                    val beat = kick * (0.30f + bassBias * 0.55f)
                    (energy * (0.10f + 0.66f * texture + 0.24f * beat))
                        .coerceIn(0f, 1f)
                }
            }
            val current = smoothedLevels[band]
            val attack = if (target > current) 16f else 5.5f
            smoothedLevels[band] += (target - current) *
                (dt * attack).coerceIn(0f, 1f)
        }
    }

    /** 0 = normal waves, 1 = green battery meter. */
    fun chargeColorMix(): Float = batteryAmount.coerceIn(0f, 1f)

    /** Top of the green battery fill, as a fraction of screen height. */
    fun batteryFillTopFraction(): Float = (1f - batteryLevel).coerceIn(0f, 1f)

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        timeSeconds: Float,
        lineWidthScale: Float,
        idleAmplitudeScale: Float
    ) {
        if (width <= 1 || height <= 1) return
        val cell = width.toFloat() / columns.coerceAtLeast(1)
        val widthScale = lineWidthScale.coerceIn(
            WallpaperSettings.MIN_LINE_WIDTH,
            WallpaperSettings.MAX_LINE_WIDTH
        )
        val stroke = (cell * 1.20f * widthScale).coerceAtLeast(1.4f)
        val samples = (columns * 2).coerceIn(64, 180)
        val anchorY = height * WAVE_ANCHOR_Y
        val regionTop = height * WAVE_REGION_TOP
        val regionBottom = height * 0.998f

        var bass = 0f
        for (i in 0..4) bass += smoothedLevels[i]
        bass /= 5f
        var treble = 0f
        for (i in 7..11) treble += smoothedLevels[i]
        treble /= 5f

        // The battery fill is drawn before the line so the top edge stays
        // crisp. It reaches all the way to the bottom edge of the screen.
        if (batteryAmount > 0.001f) {
            val fillTop = height * (1f - batteryLevel)
            fillPaint.color = SceneColor.gray(
                BATTERY_FILL_BRIGHTNESS * batteryAmount
            )
            canvas.drawRect(0f, fillTop, width.toFloat(), height.toFloat(), fillPaint)
        }

        // The user-controlled height scales only the oscillation, not the
        // fixed vertical offsets that keep the three idle ribbons separated.
        // At 200% the tallest ribbon still stays inside the lower three
        // quarters; at 20% the waves become calm without collapsing into one
        // line.
        val amplitudeScale = idleAmplitudeScale.coerceIn(0.2f, 2f)
        waveAmplitudes[0] = height * (0.064f + 0.019f * bass) * amplitudeScale
        waveAmplitudes[1] = height * (0.076f + 0.022f * treble) * amplitudeScale
        waveAmplitudes[2] = height * (0.068f + 0.020f * bass) * amplitudeScale

        val batteryY = height * (1f - batteryLevel)
        for (layer in 0 until 3) {
            wavePath.rewind()
            val amplitude = waveAmplitudes[layer]
            val frequency = waveFrequencies[layer]
            val offset = waveOffsets[layer] * height
            val phase = timeSeconds * (0.95f + layer * 0.18f) + layer * 1.7f

            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val x = t * width
                // Both edges have exactly zero displacement, so all three
                // ribbons start and end at the same two anchor points.
                val edgeEnvelope = sin(PI.toFloat() * t).coerceIn(0f, 1f)
                val idleY = anchorY + (
                    offset +
                        sin(t * TWO_PI * frequency + phase) * amplitude +
                        sin(t * TWO_PI * (frequency * 1.73f) - phase * 0.62f) *
                        amplitude * 0.30f
                    ) * edgeEnvelope

                val spectrum = sampleSpectrum(t)
                val mergedY = anchorY -
                    spectrum * height * MUSIC_WAVE_HEIGHT * edgeEnvelope

                var y = idleY.coerceIn(regionTop, regionBottom)
                if (musicAmount > 0f) {
                    y += (mergedY.coerceIn(regionTop, regionBottom) - y) *
                        musicAmount
                }
                if (batteryAmount > 0f) {
                    y += (batteryY - y) * batteryAmount
                }
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }

            val layerBoost = 1f + musicAmount * 0.18f
            wavePaint.color = SceneColor.gray(
                (waveBrightness[layer] * layerBoost).coerceIn(0f, 1f)
            )
            wavePaint.strokeWidth = stroke * (0.85f + layer * 0.20f)
            canvas.drawPath(wavePath, wavePaint)
        }
    }

    /**
     * Continuous version of the 12 generated bands. Linear interpolation with
     * a smoothstep weight creates rounded peaks instead of visible steps, so
     * the result still reads as one wave rather than a bar chart.
     */
    private fun sampleSpectrum(t: Float): Float {
        val position = t.coerceIn(0f, 1f) * (BAND_COUNT - 1)
        val lower = position.toInt().coerceIn(0, BAND_COUNT - 1)
        val upper = (lower + 1).coerceAtMost(BAND_COUNT - 1)
        val fraction = position - lower
        val smooth = fraction * fraction * (3f - 2f * fraction)
        return smoothedLevels[lower] * (1f - smooth) +
            smoothedLevels[upper] * smooth
    }
}
