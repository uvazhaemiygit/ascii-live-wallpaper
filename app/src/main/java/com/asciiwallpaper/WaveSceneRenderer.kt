package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * PS3-style bottom waves with three connected states:
 *
 *  - idle: three ribbons leave one shared point on the left edge, fan out
 *    across the lower three quarters of the screen, and return to one shared
 *    point on the right edge;
 *  - phone music: the three ribbons remain separate and draw the same
 *    equalizer three times, at the live moment and with 200 ms / 500 ms
 *    echoes. This makes the motion visibly follow the track instead of
 *    collapsing into one synthetic-looking line;
 *  - charger connected: the ribbons first flatten into one horizontal line at
 *    the wave anchor, then the line moves to the battery percentage height
 *    (top = 100%, bottom = 0%). Only after the line has arrived does a green
 *    fill grow downward from it. Unplugging reverses the sequence: the fill
 *    drains first, then the line returns and finally unfolds into waves.
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
        private const val MUSIC_WAVE_HEIGHT = 0.27f
        // The three ribbons keep a little vertical fan during music so the
        // delayed echoes stay readable instead of becoming one thick stroke.
        private const val MUSIC_ECHO_SPREAD = 0.55f
        private const val HISTORY_CAPACITY = 96
        private const val BATTERY_FILL_BRIGHTNESS = 0.74f

        // Charger choreography. The line owns the first part of the plug-in
        // transition, then the fill is allowed to start. On unplugging the
        // dependencies reverse: fill first, line last. Both line stages are
        // driven by lineAmount: its first 55% flattens the ribbons, the last
        // 45% travels from the wave anchor to the battery level.
        private const val LINE_CONNECT_SECONDS = 1.05f
        private const val FILL_CONNECT_SECONDS = 1.15f
        private const val FILL_DISCONNECT_SECONDS = 0.85f
        private const val LINE_DISCONNECT_SECONDS = 0.90f
        private const val FLATTEN_PORTION = 0.55f
        private const val LINE_SETTLED = 0.999f
        private const val FILL_EMPTY = 0.0005f
        private val ECHO_DELAY_SECONDS = floatArrayOf(0f, 0.2f, 0.5f)
        // During music the live ribbon is the clearest one; older echoes fade
        // back instead of looking like the primary line.
        private val MUSIC_BRIGHTNESS = floatArrayOf(1.00f, 0.68f, 0.44f)
        private val MUSIC_STROKE_SCALE = floatArrayOf(1.14f, 0.92f, 0.72f)
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

    // One history sample per rendered frame. The current ribbon reads the
    // newest frame, the second reads 200 ms ago and the third 500 ms ago.
    private val historyLevels = Array(HISTORY_CAPACITY) { FloatArray(BAND_COUNT) }
    private val historyTimes = FloatArray(HISTORY_CAPACITY)
    private var historyHead = 0
    private var historyCount = 0
    private var historyClock = 0f
    private val echoLevels = Array(3) { FloatArray(BAND_COUNT) }

    private var musicAmount = 0f
    private var wasMusicActive = false
    private var lineAmount = 0f
    private var fillAmount = 0f
    private var batteryLevel = 0.5f
    private var batteryTarget = 0.5f

    /**
     * Called when the WAVES style becomes visible. A charger that was already
     * connected should show the final meter immediately rather than replaying
     * the plug-in transition; transitions are animated only while the style is
     * on screen.
     */
    fun onShown(charging: Boolean, batteryPercent: Int) {
        batteryTarget = (batteryPercent.coerceIn(0, 100) / 100f)
        batteryLevel = batteryTarget
        lineAmount = if (charging) 1f else 0f
        fillAmount = if (charging) 1f else 0f
        musicAmount = 0f
        wasMusicActive = false
        smoothedLevels.fill(0f)
        clearHistory()
        for (levels in echoLevels) levels.fill(0f)
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

        advanceChargeSequence(dt, charging)

        val targetMusic = if (musicActive && !charging) 1f else 0f
        musicAmount += (targetMusic - musicAmount) *
            (dt * 2.6f).coerceIn(0f, 1f)
        if (musicAmount < 0.001f) musicAmount = 0f

        // Do not play stale idle samples when a new track starts. The first
        // three echoes are identical and then separate naturally.
        if (musicActive && !wasMusicActive) clearHistory()
        wasMusicActive = musicActive

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

        appendHistory(dt)
        smoothedLevels.copyInto(echoLevels[0])
        sampleDelayed(ECHO_DELAY_SECONDS[1], echoLevels[1])
        sampleDelayed(ECHO_DELAY_SECONDS[2], echoLevels[2])
    }

    /**
     * Runs the charger choreography. Plugging in fills [lineAmount] first;
     * [fillAmount] cannot move until the line has reached its battery position.
     * Unplugging drains [fillAmount] first and only then lets the line return
     * to the waves. This is what makes the sequence readable instead of
     * flattening and filling at the same time.
     */
    private fun advanceChargeSequence(dt: Float, charging: Boolean) {
        val step = dt.coerceIn(0f, 0.1f)
        if (charging) {
            lineAmount = (lineAmount + step / LINE_CONNECT_SECONDS)
                .coerceAtMost(1f)
            if (lineAmount >= LINE_SETTLED) {
                fillAmount = (fillAmount + step / FILL_CONNECT_SECONDS)
                    .coerceAtMost(1f)
            }
        } else {
            fillAmount = (fillAmount - step / FILL_DISCONNECT_SECONDS)
                .coerceAtLeast(0f)
            if (fillAmount <= FILL_EMPTY) {
                fillAmount = 0f
                lineAmount = (lineAmount - step / LINE_DISCONNECT_SECONDS)
                    .coerceAtLeast(0f)
            }
        }
    }

    private fun smoothstep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 0 = waves, 1 = completely flattened line. */
    private fun flattenMix(): Float = smoothstep(lineAmount / FLATTEN_PORTION)

    /** 0 = line at the wave anchor, 1 = line at the battery level. */
    private fun placeMix(): Float =
        smoothstep((lineAmount - FLATTEN_PORTION) / (1f - FLATTEN_PORTION))

    /** 0 = no fill, 1 = full green battery fill. */
    private fun fillMix(): Float = smoothstep(fillAmount)

    private fun clearHistory() {
        historyHead = 0
        historyCount = 0
        historyClock = 0f
    }

    private fun appendHistory(dt: Float) {
        if (historyCount > 0 && dt <= 0f) return
        historyClock += dt.coerceIn(0f, 0.1f)
        smoothedLevels.copyInto(historyLevels[historyHead])
        historyTimes[historyHead] = historyClock
        historyHead = (historyHead + 1) % HISTORY_CAPACITY
        if (historyCount < HISTORY_CAPACITY) historyCount++
    }

    /**
     * Fetches a past spectrum snapshot. Linear interpolation between adjacent
     * frames keeps the delay exact even when the wallpaper runs at 30, 60 or
     * 120 fps. Before enough history exists, the oldest sample is repeated.
     */
    private fun sampleDelayed(delaySeconds: Float, destination: FloatArray) {
        if (delaySeconds <= 0f || historyCount == 0) {
            smoothedLevels.copyInto(destination)
            return
        }
        val targetTime = historyClock - delaySeconds
        var newer = -1
        var older = -1
        for (step in 0 until historyCount) {
            val index = (historyHead - 1 - step + HISTORY_CAPACITY) %
                HISTORY_CAPACITY
            if (historyTimes[index] <= targetTime) {
                older = index
                break
            }
            newer = index
        }
        if (older < 0) {
            // The requested echo is older than the available history.
            older = (historyHead - historyCount + HISTORY_CAPACITY) %
                HISTORY_CAPACITY
            historyLevels[older].copyInto(destination)
            return
        }
        if (newer < 0) {
            historyLevels[older].copyInto(destination)
            return
        }
        val span = historyTimes[newer] - historyTimes[older]
        val mix = if (span > 0.000001f) {
            ((targetTime - historyTimes[older]) / span).coerceIn(0f, 1f)
        } else {
            0f
        }
        val oldLevels = historyLevels[older]
        val newLevels = historyLevels[newer]
        for (band in 0 until BAND_COUNT) {
            destination[band] = oldLevels[band] +
                (newLevels[band] - oldLevels[band]) * mix
        }
    }

    /**
     * Green tint strength. It follows the fill, not the line, so the glyphs
     * stay in the user's colour while the waves flatten and fade to green only
     * as the battery strip grows downward.
     */
    fun chargeColorMix(): Float = fillMix()

    /** Top of the green battery fill, as a fraction of screen height. */
    fun batteryFillTopFraction(): Float = (1f - batteryLevel).coerceIn(0f, 1f)

    /**
     * Bottom of the visible fill. The strip starts at the battery line and
     * grows toward the bottom edge as [fillAmount] rises; on unplugging the
     * same fraction shrinks and the strip drains back up.
     */
    fun batteryFillBottomFraction(): Float {
        val top = batteryFillTopFraction()
        return (top + (1f - top) * fillMix()).coerceIn(0f, 1f)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        timeSeconds: Float,
        lineWidthScale: Float,
        idleAmplitudeScale: Float,
        idleSpreadScale: Float
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

        val batteryY = height * (1f - batteryLevel)
        val fill = fillMix()
        // The fill starts exactly on the line and grows downward. It is drawn
        // before the line so the top edge stays crisp.
        if (fill > 0.001f) {
            val fillBottom = batteryY + (height - batteryY) * fill
            fillPaint.color = SceneColor.gray(BATTERY_FILL_BRIGHTNESS)
            canvas.drawRect(0f, batteryY, width.toFloat(), fillBottom, fillPaint)
        }

        // "Beat" scales only the oscillation; "spread" scales only the fixed
        // vertical offsets between the three idle ribbons. Both anchors at the
        // screen edges remain shared. If an extreme beat + spread combination
        // would leave the lower three quarters, one common fit factor shrinks
        // both geometry values so the ribbons never clip at the region edges.
        val beatScale = idleAmplitudeScale.coerceIn(0.2f, 2f)
        val spreadScale = idleSpreadScale.coerceIn(0.5f, 1.5f)
        for (layer in 0 until 3) {
            val levels = echoLevels[layer]
            var layerBass = 0f
            for (i in 0..4) layerBass += levels[i]
            layerBass /= 5f
            var layerTreble = 0f
            for (i in 7..11) layerTreble += levels[i]
            layerTreble /= 5f
            waveAmplitudes[layer] = height * (
                when (layer) {
                    0 -> 0.064f + 0.019f * layerBass
                    1 -> 0.076f + 0.022f * layerTreble
                    else -> 0.068f + 0.020f * layerBass
                }
                ) * beatScale
        }

        val availableVertical = minOf(
            anchorY - regionTop,
            regionBottom - anchorY
        )
        var fit = 1f
        for (layer in 0 until 3) {
            val required = abs(waveOffsets[layer]) * height * spreadScale +
                waveAmplitudes[layer] * 1.30f
            if (required > availableVertical && required > 0f) {
                fit = minOf(fit, availableVertical / required)
            }
        }
        fit = fit.coerceIn(0f, 1f)

        // The music echo fan gets its own fit. All three spectrum lines can
        // reach the peak at the same time, so the tallest possible line and
        // the widest offset must both fit inside the wave region.
        var musicRequired = 0f
        for (layer in 0 until 3) {
            musicRequired = maxOf(
                musicRequired,
                abs(waveOffsets[layer]) * height * spreadScale *
                    MUSIC_ECHO_SPREAD +
                    height * MUSIC_WAVE_HEIGHT
            )
        }
        val musicFit = if (musicRequired > availableVertical && musicRequired > 0f) {
            (availableVertical / musicRequired).coerceIn(0f, 1f)
        } else {
            1f
        }

        val flatten = flattenMix()
        val place = placeMix()

        for (layer in 0 until 3) {
            wavePath.rewind()
            val levels = echoLevels[layer]
            val amplitude = waveAmplitudes[layer] * fit
            val frequency = waveFrequencies[layer]
            val offset = waveOffsets[layer] * height * spreadScale * fit
            val musicOffset = waveOffsets[layer] * height * spreadScale *
                MUSIC_ECHO_SPREAD * musicFit
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

                val spectrum = sampleSpectrum(t, levels)
                val musicY = anchorY + (
                    musicOffset -
                        spectrum * height * MUSIC_WAVE_HEIGHT * musicFit
                    ) * edgeEnvelope

                var y = idleY.coerceIn(regionTop, regionBottom)
                if (musicAmount > 0f) {
                    y += (musicY.coerceIn(regionTop, regionBottom) - y) *
                        musicAmount
                }
                // Plug-in phase 1: collapse the ribbon around its own anchor,
                // without moving to the battery level yet.
                if (flatten > 0f) {
                    y = anchorY + (y - anchorY) * (1f - flatten)
                }
                // Plug-in phase 2: only the already-flat line travels to the
                // height that represents the current charge.
                if (place > 0f) {
                    y += (batteryY - anchorY) * place
                }
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }

            val layerBoost = 1f + musicAmount * 0.10f
            val brightness = waveBrightness[layer] +
                (MUSIC_BRIGHTNESS[layer] - waveBrightness[layer]) *
                musicAmount
            val idleStroke = 0.85f + layer * 0.20f
            val strokeScale = idleStroke +
                (MUSIC_STROKE_SCALE[layer] - idleStroke) * musicAmount
            wavePaint.color = SceneColor.gray(
                (brightness * layerBoost).coerceIn(0f, 1f)
            )
            wavePaint.strokeWidth = stroke * strokeScale
            canvas.drawPath(wavePath, wavePaint)
        }
    }

    /**
     * Continuous version of the 12 generated bands. Linear interpolation with
     * a smoothstep weight creates rounded peaks instead of visible steps, so
     * the result still reads as one wave rather than a bar chart.
     */
    private fun sampleSpectrum(t: Float, levels: FloatArray): Float {
        val position = t.coerceIn(0f, 1f) * (BAND_COUNT - 1)
        val lower = position.toInt().coerceIn(0, BAND_COUNT - 1)
        val upper = (lower + 1).coerceAtMost(BAND_COUNT - 1)
        val fraction = position - lower
        val smooth = fraction * fraction * (3f - 2f * fraction)
        return levels[lower] * (1f - smooth) +
            levels[upper] * smooth
    }
}
