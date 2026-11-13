package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * ASCII black hole: a star field, a tilted particle accretion disk, a black
 * event horizon and a bright photon ring with a lensed upper arc.
 *
 * The disk is split into a far half and a near half so the horizon can occlude
 * the matter behind it while the near side sweeps over the black sphere. On
 * unlock a gravitational wave expands from the horizon while the disk briefly
 * spins faster; the renderer drives that boost through the shared spin angle.
 */
class BlackHoleSceneRenderer {
    companion object {
        private const val EVENT_RADIUS_FRACTION = 0.22f
        private const val DISK_INNER_FRACTION = 0.30f
        private const val DISK_OUTER_FRACTION = 0.64f
        private const val DISK_TILT = 0.38f
        private const val STAR_COUNT = 160
        private const val STAR_HALO_COUNT = 18
        private const val PARTICLE_COUNT = 400
        private const val TWO_PI = PI.toFloat() * 2f
        private const val DEG_TO_RAD = PI.toFloat() / 180f
    }

    private val random = Random(0xB1AC4017L)

    private val starU = FloatArray(STAR_COUNT)
    private val starV = FloatArray(STAR_COUNT)
    private val starBase = FloatArray(STAR_COUNT)
    private val starRadiusFactor = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)
    private val starTwinkle = FloatArray(STAR_COUNT)

    private val particleRadius = FloatArray(PARTICLE_COUNT)
    private val particleAngle = FloatArray(PARTICLE_COUNT)
    private val particleOrbit = FloatArray(PARTICLE_COUNT)
    private val particleStreak = FloatArray(PARTICLE_COUNT)
    private val particleBrightness = FloatArray(PARTICLE_COUNT)
    private val particleSize = FloatArray(PARTICLE_COUNT)
    private val particleYOffset = FloatArray(PARTICLE_COUNT)

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val arcPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    init {
        for (i in 0 until STAR_COUNT) {
            starU[i] = random.nextFloat() - 0.5f
            starV[i] = random.nextFloat() - 0.5f
            starBase[i] = 0.32f + random.nextFloat() * 0.68f
            starRadiusFactor[i] = 0.0020f + random.nextFloat() * 0.0062f
            starPhase[i] = random.nextFloat() * TWO_PI
            starTwinkle[i] = 0.30f + random.nextFloat() * 1.10f
        }

        val inner = DISK_INNER_FRACTION
        val span = DISK_OUTER_FRACTION - inner
        for (i in 0 until PARTICLE_COUNT) {
            val radius = inner + span * random.nextFloat().pow(0.62f)
            val angle = if (i % 6 == 0) {
                random.nextFloat() * TWO_PI
            } else {
                val arm = (i % 3) * (TWO_PI / 3f)
                arm + 2.05f * ln(radius / inner) + random.nextGaussian().toFloat() * 0.24f
            }
            particleRadius[i] = radius
            particleAngle[i] = angle
            particleOrbit[i] = 1f - 0.60f * ((radius - inner) / span)
            particleStreak[i] = 0.015f + random.nextFloat() * 0.035f
            particleBrightness[i] = 0.60f + random.nextFloat() * 0.40f
            particleSize[i] = 0.0017f + random.nextFloat() * 0.0030f
            particleYOffset[i] = (random.nextFloat() - 0.5f) * 0.014f
        }

        // Make a small handful of background stars noticeably brighter.
        for (i in 0 until STAR_HALO_COUNT) {
            val index = (random.nextFloat() * STAR_COUNT).toInt().coerceIn(0, STAR_COUNT - 1)
            starBase[index] = 1f
            starRadiusFactor[index] = 0.0075f
        }
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        spinDegrees: Float,
        timeSeconds: Float,
        unlockAgeSeconds: Float,
        lineWidthScale: Float
    ) {
        if (width <= 1 || height <= 1) return

        val minDim = minOf(width, height).toFloat()
        val strokeScale = lineWidthScale.coerceIn(
            WallpaperSettings.MIN_LINE_WIDTH,
            WallpaperSettings.MAX_LINE_WIDTH
        )
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val eventRadius = minDim * EVENT_RADIUS_FRACTION
        val cell = width.toFloat() / columns.coerceAtLeast(1)

        val age = unlockAgeSeconds
        val activePulse = age >= 0f && age < 1.5f
        val flash = if (activePulse) 1f + 0.60f * exp(-age * 2.7f) else 1f
        val spinRadians = spinDegrees * DEG_TO_RAD

        drawStars(canvas, width, height, centerX, centerY, eventRadius, timeSeconds)
        drawParticles(
            canvas = canvas,
            centerX = centerX,
            centerY = centerY,
            minDim = minDim,
            spin = spinRadians,
            flash = flash,
            front = false,
            strokeScale = strokeScale
        )

        // Event horizon: opaque black, then a soft halo around its edge.
        fillPaint.color = 0xFF000000.toInt()
        canvas.drawCircle(centerX, centerY, eventRadius, fillPaint)
        linePaint.color = SceneColor.gray(0.65f * flash, 40)
        linePaint.strokeWidth = minDim * 0.022f * strokeScale
        canvas.drawCircle(centerX, centerY, eventRadius * 1.10f, linePaint)

        drawParticles(
            canvas = canvas,
            centerX = centerX,
            centerY = centerY,
            minDim = minDim,
            spin = spinRadians,
            flash = flash,
            front = true,
            strokeScale = strokeScale
        )

        // Bright inner rim of the accretion disk.
        drawArc(
            canvas, centerX, centerY,
            DISK_INNER_FRACTION * minDim,
            DISK_INNER_FRACTION * minDim * 0.50f,
            startAngle = 185f, sweepAngle = 170f,
            width = cell * 0.9f * strokeScale,
            color = SceneColor.gray(1f * flash, 145)
        )
        drawArc(
            canvas, centerX, centerY,
            DISK_INNER_FRACTION * minDim,
            DISK_INNER_FRACTION * minDim * 0.50f,
            startAngle = 5f, sweepAngle = 170f,
            width = cell * 0.9f * strokeScale,
            color = SceneColor.gray(1f * flash, 175)
        )

        // Lensed far side of the disk, visible as a thin eyebrow above the hole.
        val eyebrowX = eventRadius * 1.65f
        val eyebrowY = eventRadius * 0.53f
        arcPaint.color = SceneColor.gray(0.88f * flash, 160)
        arcPaint.strokeWidth = cell * 0.65f * strokeScale
        canvas.drawArc(
            centerX - eyebrowX,
            centerY - eyebrowY,
            centerX + eyebrowX,
            centerY + eyebrowY,
            194f,
            152f,
            false,
            arcPaint
        )

        // Photon ring, painted last so it remains continuous over the near disk.
        linePaint.color = SceneColor.gray(0.65f * flash, 48)
        linePaint.strokeWidth = maxOf(cell * 2.4f, minDim * 0.026f) * strokeScale
        canvas.drawCircle(centerX, centerY, eventRadius * 1.03f, linePaint)

        linePaint.color = SceneColor.gray(0.98f * flash, 190)
        linePaint.strokeWidth = maxOf(cell * 1.10f, minDim * 0.0115f) * strokeScale
        canvas.drawCircle(centerX, centerY, eventRadius * 1.055f, linePaint)

        linePaint.color = SceneColor.gray(0.78f * flash, 112)
        linePaint.strokeWidth = maxOf(cell * 0.55f, minDim * 0.006f) * strokeScale
        canvas.drawCircle(centerX, centerY, eventRadius * 1.12f, linePaint)

        if (activePulse) {
            drawShockwave(canvas, centerX, centerY, eventRadius, age, strokeScale)
        }
    }

    private fun drawStars(
        canvas: Canvas,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        eventRadius: Float,
        timeSeconds: Float
    ) {
        val minDim = minOf(width, height).toFloat()
        val eventRadiusSq = eventRadius * eventRadius * 1.05f
        for (i in 0 until STAR_COUNT) {
            val x = centerX + starU[i] * width
            val y = centerY + starV[i] * height
            val dx = x - centerX
            val dy = y - centerY
            if (dx * dx + dy * dy < eventRadiusSq) continue

            val twinkle = 0.60f + 0.40f *
                sin(timeSeconds * starTwinkle[i] + starPhase[i])
            val brightness = starBase[i] * (0.52f + 0.48f * twinkle)
            val radius = maxOf(0.65f, starRadiusFactor[i] * minDim)

            fillPaint.color = SceneColor.gray(0.52f + 0.48f * brightness, 235)
            canvas.drawCircle(x, y, radius, fillPaint)
        }
    }

    private fun drawParticles(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        minDim: Float,
        spin: Float,
        flash: Float,
        front: Boolean,
        strokeScale: Float
    ) {
        val inner = DISK_INNER_FRACTION
        val span = DISK_OUTER_FRACTION - inner
        for (i in 0 until PARTICLE_COUNT) {
            val angle = particleAngle[i] + spin * particleOrbit[i]
            val sinAngle = sin(angle)
            val isFront = sinAngle >= 0f
            if (isFront != front) continue

            val radius = particleRadius[i] * minDim
            val z = sinAngle * radius
            val cosAngle = cos(angle)
            val x0 = centerX + cosAngle * radius
            val y0 = centerY + z * DISK_TILT + particleYOffset[i] * minDim

            val tail = angle - particleStreak[i]
            val tailZ = sin(tail) * radius
            val x1 = centerX + cos(tail) * radius
            val y1 = centerY + tailZ * DISK_TILT + particleYOffset[i] * minDim

            val innerHeat = (
                1f - (particleRadius[i] - inner) / span
                ).coerceIn(0f, 1f)
            val brightness = (
                particleBrightness[i] * (0.48f + 0.65f * innerHeat) * flash
                ).coerceIn(0f, 1f)
            val size = particleSize[i] * minDim

            linePaint.color = SceneColor.gray(brightness * 0.50f, 55)
            linePaint.strokeWidth = size * 2.55f * strokeScale
            canvas.drawLine(x0, y0, x1, y1, linePaint)

            linePaint.color = SceneColor.gray(brightness, 250)
            linePaint.strokeWidth = maxOf(0.8f, size * 0.95f) * strokeScale
            canvas.drawLine(x0, y0, x1, y1, linePaint)
        }
    }

    private fun drawArc(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        startAngle: Float,
        sweepAngle: Float,
        width: Float,
        color: Int
    ) {
        arcPaint.color = color
        arcPaint.strokeWidth = width
        canvas.drawArc(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY,
            startAngle,
            sweepAngle,
            false,
            arcPaint
        )
    }

    private fun drawShockwave(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        eventRadius: Float,
        age: Float,
        strokeScale: Float
    ) {
        val delays = floatArrayOf(0f, 0.10f)
        val strengths = floatArrayOf(1f, 0.7f)
        for (i in delays.indices) {
            val progress = ((age - delays[i]) / 1.2f).coerceIn(0f, 1f)
            if (progress <= 0f) continue
            val radius = eventRadius * (
                1.04f + 3.1f * (1f - (1f - progress) * (1f - progress) * (1f - progress))
                )
            val alpha = (200f * (1f - progress) * (1f - progress) * strengths[i]).toInt()
            if (alpha <= 2) continue
            linePaint.color = SceneColor.gray(0.92f, alpha)
            linePaint.strokeWidth = (2f + 4f * (1f - progress)) * strokeScale
            canvas.drawCircle(centerX, centerY, radius, linePaint)
        }
    }
}
