package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural starfield for the Cosmos style.
 *
 * The scene is intentionally stateless: every star has a random phase offset,
 * so some are already visible, some are fading in and some are fading out at
 * any given moment. When a star's lifetime expires it becomes fully
 * transparent, then respawns at a new pseudo-random position on the next
 * cycle. This is deterministic, has no per-star object churn and stays cheap
 * on a Snapdragon 425.
 */
class CosmosSceneRenderer {
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
    private val rayPaint = Paint().apply {
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
        starCount: Int,
        starSizePercent: Int,
        lifetimeSeconds: Int
    ) {
        if (width <= 1 || height <= 1) return
        val count = starCount.coerceIn(
            WallpaperSettings.MIN_STAR_COUNT,
            WallpaperSettings.MAX_STAR_COUNT
        )
        val life = lifetimeSeconds.coerceIn(
            WallpaperSettings.MIN_STAR_LIFETIME_SECONDS,
            WallpaperSettings.MAX_STAR_LIFETIME_SECONDS
        ).toFloat()
        val cell = width.toFloat() / columns.coerceAtLeast(1)
        val baseRadius = cell * 0.42f *
            (starSizePercent.coerceIn(
                WallpaperSettings.MIN_STAR_SIZE_PERCENT,
                WallpaperSettings.MAX_STAR_SIZE_PERCENT
            ) / 100f)
        val time = timeSeconds.coerceAtLeast(0f)
        val fadeSeconds = min(4f, life * 0.18f).coerceAtLeast(0.6f)

        for (index in 0 until count) {
            val phase = random01(index, 0x1357) * life
            val absolute = time + phase
            val cycle = floor(absolute / life).toInt()
            val local = absolute - cycle * life
            val fadeIn = (local / fadeSeconds).coerceIn(0f, 1f)
            val fadeOut = ((life - local) / fadeSeconds).coerceIn(0f, 1f)
            val envelope = min(fadeIn, fadeOut)
            if (envelope <= 0.001f) continue

            val sizeSeed = random01(index, cycle * 5 + 1)
            val radius = baseRadius * (0.62f + sizeSeed * 0.78f)
            val x = (0.04f + random01(index, cycle * 5 + 2) * 0.92f) * width
            val y = (0.04f + random01(index, cycle * 5 + 3) * 0.92f) * height
            val twinkle = 0.84f + 0.16f *
                sin(time * 1.7f + random01(index, cycle * 5 + 4) * 6.2831855f)
            val alpha = (255f * envelope * twinkle)
                .toInt()
                .coerceIn(0, 255)
            if (alpha <= 2) continue

            // A soft halo plus a solid core reads as a star after the scene
            // is downsampled into ASCII cells. The halo is deliberately dim:
            // a bright glow would turn several adjacent glyphs into a blob.
            glowPaint.alpha = (alpha * 0.14f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, radius * 2.25f, glowPaint)
            glowPaint.alpha = (alpha * 0.34f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, radius * 1.45f, glowPaint)
            corePaint.alpha = alpha
            canvas.drawCircle(x, y, radius, corePaint)

            if (radius >= cell * 0.55f) {
                rayPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
                rayPaint.strokeWidth = (radius * 0.55f).coerceAtLeast(0.7f)
                val ray = radius * 2.1f
                canvas.drawLine(x - ray, y, x + ray, y, rayPaint)
                canvas.drawLine(x, y - ray, x, y + ray, rayPaint)
            }
        }
    }

    /**
     * Small deterministic 32-bit hash mapped to [0, 1). Int overflow is
     * intentional and keeps the method allocation-free.
     */
    private fun random01(seed: Int, salt: Int): Float {
        var value = seed * 374761393 + salt * 668265263
        value = (value xor (value ushr 13)) * 1274126177
        value = value xor (value ushr 16)
        return (value and 0x7FFFFFFF) / 2147483647f
    }
}
