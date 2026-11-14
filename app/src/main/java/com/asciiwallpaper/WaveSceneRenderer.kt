package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Three PS3-style bottom waves.
 *
 * States:
 *  - idle: three independent sine ribbons undulate in the lower 3/4;
 *  - charger connected: the ribbons ease into one large upward arc, pulse
 *    green for a few seconds (the colour blend is applied by AsciiRenderer)
 *    and then keep the arc until the cable is removed;
 *  - microphone audio: 12 log-spaced bands drive a 12-step equalizer. The
 *    three ribbons remain behind it at reduced brightness.
 *
 * Only one Canvas pass over ~120 points per ribbon and 12 rounded bars is
 * used, so this style is substantially cheaper than the 3D meshes.
 */
class WaveSceneRenderer {
    companion object {
        private const val BAND_COUNT = 12
        private const val TWO_PI = PI.toFloat() * 2f
    }

    private val wavePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val barPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val capPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val wavePath = Path()
    private val barRect = RectF()
    private val smoothedLevels = FloatArray(BAND_COUNT)
    private val waveAmplitudes = FloatArray(3)
    private val waveBases = floatArrayOf(0.29f, 0.53f, 0.77f)
    private val waveFrequencies = floatArrayOf(0.88f, 1.02f, 1.16f)
    private val waveBrightness = floatArrayOf(0.58f, 0.78f, 1.00f)
    private var arcAmount = 0f
    private var chargeFlash = 0f
    private var wasCharging = false
    private var musicAmount = 0f

    /**
     * Called when the WAVES style becomes visible. The waveform is advanced
     * even in other styles, so this re-arms the charging arc and its green
     * flash instead of showing a fully formed but colourless arc.
     */
    fun onShown(charging: Boolean) {
        arcAmount = 0f
        chargeFlash = if (charging) 1f else 0f
        wasCharging = charging
    }

    fun advance(dt: Float, charging: Boolean, levels: FloatArray) {
        if (charging && !wasCharging) {
            chargeFlash = 1f
        }
        wasCharging = charging
        val targetArc = if (charging) 1f else 0f
        val arcSpeed = if (charging) 1.7f else 1.2f
        arcAmount += (targetArc - arcAmount) *
            (dt * arcSpeed).coerceIn(0f, 1f)
        if (arcAmount < 0.001f) arcAmount = 0f
        chargeFlash = (chargeFlash - dt / 4.2f).coerceAtLeast(0f)

        var loudest = 0f
        for (index in 0 until BAND_COUNT) {
            val target = if (index < levels.size) {
                levels[index].coerceIn(0f, 1f)
            } else {
                0f
            }
            val current = smoothedLevels[index]
            val attack = if (target > current) 14f else 4.5f
            smoothedLevels[index] += (target - current) *
                (dt * attack).coerceIn(0f, 1f)
            if (smoothedLevels[index] > loudest) loudest = smoothedLevels[index]
        }
        musicAmount += (loudest - musicAmount) * (dt * 5f).coerceIn(0f, 1f)
    }

    fun chargeColorMix(): Float = chargeFlash.coerceIn(0f, 1f)

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        timeSeconds: Float,
        lineWidthScale: Float,
        speedScale: Float
    ) {
        if (width <= 1 || height <= 1) return
        val cell = width.toFloat() / columns.coerceAtLeast(1)
        val widthScale = lineWidthScale.coerceIn(
            WallpaperSettings.MIN_LINE_WIDTH,
            WallpaperSettings.MAX_LINE_WIDTH
        )
        val stroke = (cell * 1.20f * widthScale).coerceAtLeast(1.4f)
        val samples = (columns * 2).coerceIn(64, 180)
        var bass = 0f
        for (i in 0..4) bass += smoothedLevels[i]
        bass /= 5f
        var treble = 0f
        for (i in 7..11) treble += smoothedLevels[i]
        treble /= 5f
        val chargeDominant = arcAmount > 0.35f && musicAmount < 0.28f

        // 1) The three idle ribbons. When charging, each point is blended
        // towards an upward parabola; when audio takes over they fade.
        waveAmplitudes[0] = height * (0.070f + 0.020f * bass)
        waveAmplitudes[1] = height * (0.080f + 0.024f * treble)
        waveAmplitudes[2] = height * (0.090f + 0.028f * bass)
        val idleAlpha = (1f - musicAmount * 0.58f).coerceIn(0.34f, 1f)

        for (layer in 0 until 3) {
            wavePath.rewind()
            val baseY = height * waveBases[layer]
            val amplitude = waveAmplitudes[layer]
            val frequency = waveFrequencies[layer]
            val phase = timeSeconds * speedScale * (0.95f + layer * 0.18f) +
                layer * 1.7f
            val arcTop = height * (0.20f + layer * 0.065f)
            val arcBase = height * 0.88f

            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val x = t * width
                val idleY = baseY +
                    sin(t * TWO_PI * frequency + phase) * amplitude +
                    sin(t * TWO_PI * (frequency * 1.73f) - phase * 0.62f) *
                    amplitude * 0.30f
                // A parabolic arch that reaches its apex near x = 0.5.
                val parabola = 1f - (2f * t - 1f).let { it * it }
                val arcY = arcBase - (arcBase - arcTop) * parabola
                val y = idleY + (arcY - idleY) * arcAmount
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }

            val brightness = waveBrightness[layer] * idleAlpha *
                if (chargeDominant) 1.30f else 1f
            wavePaint.color = SceneColor.gray(brightness.coerceIn(0f, 1f))
            wavePaint.strokeWidth = stroke * (0.85f + layer * 0.20f)
            canvas.drawPath(wavePath, wavePaint)
        }

        // 2) Twelve-step equalizer. It is driven by microphone bands and is
        // intentionally drawn last so it stays legible after ASCII sampling.
        if (musicAmount > 0.035f) {
            val gap = width * 0.024f
            val barWidth = (width - gap * (BAND_COUNT + 1)) / BAND_COUNT
            val baseline = height * 0.91f
            val maxBarHeight = height * 0.70f
            val alpha = (0.30f + musicAmount * 0.70f).coerceIn(0.30f, 1f)
            for (band in 0 until BAND_COUNT) {
                val level = (smoothedLevels.getOrElse(band) { 0f } * 1.20f)
                    .coerceIn(0f, 1f)
                val barHeight = maxBarHeight * (0.045f + level * 0.955f)
                val left = gap + band * (barWidth + gap)
                val top = baseline - barHeight
                val right = left + barWidth
                barPaint.color = SceneColor.gray(
                    (0.34f + level * 0.66f) * alpha
                )
                barRect.set(left, top, right, baseline)
                val radius = min(barWidth * 0.42f, height * 0.012f)
                canvas.drawRoundRect(barRect, radius, radius, barPaint)

                capPaint.color = SceneColor.gray(
                    (0.70f + level * 0.30f) * alpha
                )
                canvas.drawCircle(
                    left + barWidth * 0.5f,
                    top + radius * 0.25f,
                    barWidth * 0.28f,
                    capPaint
                )
            }
        }
    }
}
