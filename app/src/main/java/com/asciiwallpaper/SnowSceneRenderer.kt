package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural snowfall for the Snow style.
 *
 * Snowflakes are stateless particles: each one has a deterministic horizontal
 * position, size and phase. The phase distributes them over the screen at
 * startup, and the vertical position is simply elapsed time multiplied by the
 * selected speed. When a flake leaves the bottom, its cycle counter advances
 * and it respawns above the top with a new pseudo-random X coordinate. There
 * are no particle objects, no per-frame allocations and no expensive physics
 * for a Snapdragon 425.
 */
class SnowSceneRenderer {
    private val corePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.WHITE
    }
    private val glowPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.WHITE
    }
    private val armPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        timeSeconds: Float,
        snowCount: Int,
        snowSizePercent: Int,
        snowSpeedPercent: Int
    ) {
        if (width <= 1 || height <= 1) return
        val count = snowCount.coerceIn(
            WallpaperSettings.MIN_SNOW_COUNT,
            WallpaperSettings.MAX_SNOW_COUNT
        )
        val cell = width.toFloat() / columns.coerceAtLeast(1)
        val baseRadius = cell * 0.42f *
            (snowSizePercent.coerceIn(
                WallpaperSettings.MIN_SNOW_SIZE_PERCENT,
                WallpaperSettings.MAX_SNOW_SIZE_PERCENT
            ) / 100f)
        val speedScale = snowSpeedPercent.coerceIn(
            WallpaperSettings.MIN_SNOW_SPEED_PERCENT,
            WallpaperSettings.MAX_SNOW_SPEED_PERCENT
        ) / 100f
        // At 100 % a flake crosses the viewport in roughly eighteen seconds.
        // Slower flakes meander, faster ones look like driven snow.
        val speed = height * 0.055f * speedScale
        val time = timeSeconds.coerceAtLeast(0f)
        val margin = max(baseRadius * 3f, height * 0.035f)
        val wrapHeight = height + margin * 2f
        val edgeFadeHeight = max(margin * 1.8f, 1f)

        for (index in 0 until count) {
            val phase = random01(index, 0x51ED)
            // Individual flakes fall at slightly different speeds; without
            // this the whole field moves like one rigid curtain.
            val speedFactor = 0.78f + random01(index, 0x51EE) * 0.52f
            val travel = time * speed * speedFactor + phase * wrapHeight
            val cycle = floor(travel / wrapHeight).toInt()
            val local = travel - cycle * wrapHeight
            val y = -margin + local

            // Fade at both edges so flakes do not pop into existence. The
            // middle of the fall stays at full brightness.
            val fadeIn = (local / edgeFadeHeight).coerceIn(0f, 1f)
            val fadeOut = ((wrapHeight - local) / edgeFadeHeight).coerceIn(0f, 1f)
            val edge = min(fadeIn, fadeOut)
            if (edge <= 0.001f) continue

            val cycleSalt = cycle * 7
            val sizeSeed = random01(index, cycleSalt + 1)
            val radius = max(
                0.65f,
                baseRadius * (0.62f + sizeSeed * 0.78f)
            )
            val baseX = (0.04f + random01(index, cycleSalt + 2) * 0.92f) * width
            val swayAmplitude = width * (0.006f + random01(index, cycleSalt + 3) * 0.012f)
            val swayPhase = phase * 6.2831855f
            val swayFrequency = 0.65f + random01(index, cycleSalt + 4) * 0.9f
            val x = (baseX + sin(time * swayFrequency + swayPhase) * swayAmplitude)
                .coerceIn(0f, width.toFloat())
            val twinkle = 0.82f + 0.18f *
                sin(time * (0.9f + sizeSeed * 0.7f) + swayPhase)
            val alpha = (255f * edge * twinkle)
                .toInt()
                .coerceIn(0, 255)
            if (alpha <= 2) continue

            glowPaint.alpha = (alpha * 0.13f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, radius * 2.3f, glowPaint)
            glowPaint.alpha = (alpha * 0.30f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, radius * 1.5f, glowPaint)
            corePaint.alpha = alpha
            canvas.drawCircle(x, y, radius, corePaint)

            if (radius >= cell * 0.48f) {
                armPaint.alpha = (alpha * 0.62f).toInt().coerceIn(0, 255)
                armPaint.strokeWidth = (radius * 0.48f).coerceAtLeast(0.7f)
                val arm = radius * 2.0f
                val rotation = swayPhase * 0.35f + time * 0.22f
                for (armIndex in 0..2) {
                    val angle = rotation + armIndex * (PI.toFloat() / 3f)
                    val dx = cos(angle) * arm
                    val dy = sin(angle) * arm
                    canvas.drawLine(x - dx, y - dy, x + dx, y + dy, armPaint)
                }
            }
        }
    }

    /** Small deterministic 32-bit hash mapped to [0, 1). */
    private fun random01(seed: Int, salt: Int): Float {
        var value = seed * 374761393 + salt * 668265263
        value = (value xor (value ushr 13)) * 1274126177
        value = value xor (value ushr 16)
        return (value and 0x7FFFFFFF) / 2147483647f
    }
}
