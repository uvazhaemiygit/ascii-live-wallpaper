package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 3D donut made from a depth-sorted wireframe torus with a handful of
 * sprinkles. It intentionally rotates around both the vertical and horizontal
 * axes, so the hole is visible from above, from the side and upside down.
 */
class DonutSceneRenderer {
    private companion object {
        private const val CAMERA_DISTANCE = 7f
        private const val FOCAL_HEIGHT = 1.05f
        private const val MAJOR_RADIUS = 1.18f
        private const val TUBE_RADIUS = 0.48f
        private const val MAJOR_STEPS = 28
        private const val TUBE_STEPS = 14
        private const val DEG_TO_RAD = PI.toFloat() / 180f
        private const val DEPTH_FLOOR = 0.52f
    }

    private class Segment(
        val ax: Float,
        val ay: Float,
        val az: Float,
        val bx: Float,
        val by: Float,
        val bz: Float,
        val brightness: Float
    )

    private val segments = ArrayList<Segment>(1000)
    private val startX: FloatArray
    private val startY: FloatArray
    private val endX: FloatArray
    private val endY: FloatArray
    private val depths: FloatArray
    private val order: IntArray
    private val visible: BooleanArray
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    init {
        buildTorus()
        addSprinkles()
        val count = segments.size
        startX = FloatArray(count)
        startY = FloatArray(count)
        endX = FloatArray(count)
        endY = FloatArray(count)
        depths = FloatArray(count)
        order = IntArray(count) { it }
        visible = BooleanArray(count)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        spinXDegrees: Float,
        spinYDegrees: Float,
        excitement: Float,
        lineWidthScale: Float
    ) {
        if (width <= 1 || height <= 1 || segments.isEmpty()) return

        val cell = width.toFloat() / columns.coerceAtLeast(1)
        // Leave a margin for the outer tube coming toward the camera during
        // the two-axis tumble, so the donut keeps its whole silhouette.
        val focal = minOf(width * 1.55f, height * FOCAL_HEIGHT)
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val yaw = spinYDegrees * DEG_TO_RAD
        val pitch = spinXDegrees * DEG_TO_RAD
        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val pitchCos = cos(pitch)
        val pitchSin = sin(pitch)
        val boost = 1f + excitement.coerceIn(0f, 1f) * 0.20f

        for (i in segments.indices) {
            val segment = segments[i]
            project(
                segment.ax, segment.ay, segment.az,
                centerX, centerY, focal,
                yawCos, yawSin, pitchCos, pitchSin
            )
            val ax = projectedX
            val ay = projectedY
            val ad = projectedDepth
            project(
                segment.bx, segment.by, segment.bz,
                centerX, centerY, focal,
                yawCos, yawSin, pitchCos, pitchSin
            )
            val bx = projectedX
            val by = projectedY
            val bd = projectedDepth
            if (ad < 0.35f || bd < 0.35f) {
                visible[i] = false
                depths[i] = Float.MAX_VALUE
                continue
            }
            startX[i] = ax
            startY[i] = ay
            endX[i] = bx
            endY[i] = by
            depths[i] = (ad + bd) * 0.5f
            visible[i] = true
        }

        sortByDepth(order, depths, 0, segments.lastIndex)

        val widthScale = lineWidthScale.coerceIn(
            WallpaperSettings.MIN_LINE_WIDTH,
            WallpaperSettings.MAX_LINE_WIDTH
        )
        for (orderIndex in order.indices) {
            val i = order[orderIndex]
            if (!visible[i]) continue
            val closeness = (1f - depths[i] / 8.6f).coerceIn(0f, 1f)
            val shade = segments[i].brightness *
                (DEPTH_FLOOR + (1f - DEPTH_FLOOR) * closeness) * boost
            paint.strokeWidth = (cell * (0.46f + 0.34f * closeness) *
                widthScale).coerceAtLeast(1.1f)
            paint.color = SceneColor.gray(shade.coerceIn(0f, 1f))
            canvas.drawLine(startX[i], startY[i], endX[i], endY[i], paint)
        }
    }

    private fun buildTorus() {
        fun point(u: Float, v: Float): Triple<Float, Float, Float> {
            val ring = MAJOR_RADIUS + TUBE_RADIUS * cos(v)
            return Triple(ring * cos(u), TUBE_RADIUS * sin(v), ring * sin(u))
        }

        for (i in 0 until MAJOR_STEPS) {
            val u0 = i.toFloat() / MAJOR_STEPS * PI.toFloat() * 2f
            val u1 = (i + 1).toFloat() / MAJOR_STEPS * PI.toFloat() * 2f
            for (j in 0 until TUBE_STEPS) {
                val v0 = j.toFloat() / TUBE_STEPS * PI.toFloat() * 2f
                val v1 = (j + 1).toFloat() / TUBE_STEPS * PI.toFloat() * 2f
                val a = point(u0, v0)
                val b = point(u1, v0)
                val c = point(u0, v1)
                val shade = 0.58f + 0.27f * sin(v0).coerceAtLeast(0f)
                segments += Segment(
                    a.first, a.second, a.third,
                    b.first, b.second, b.third,
                    shade
                )
                segments += Segment(
                    a.first, a.second, a.third,
                    c.first, c.second, c.third,
                    shade * 0.92f
                )
            }
        }
    }

    private fun addSprinkles() {
        for (i in 0 until 34) {
            val u = (i * 2.3999632f) % (PI.toFloat() * 2f)
            val v = 0.35f + 0.45f * sin(i * 1.71f)
            val ring = MAJOR_RADIUS + TUBE_RADIUS * cos(v)
            val x = ring * cos(u)
            val y = TUBE_RADIUS * sin(v)
            val z = ring * sin(u)
            val tangentLength = 0.10f + 0.05f * (i % 3)
            val tx = -ring * sin(u) * tangentLength
            val tz = ring * cos(u) * tangentLength
            segments += Segment(
                x, y, z,
                x + tx, y, z + tz,
                1.0f
            )
        }
    }

    private var projectedX = 0f
    private var projectedY = 0f
    private var projectedDepth = 0f

    private fun project(
        x: Float,
        y: Float,
        z: Float,
        centerX: Float,
        centerY: Float,
        focal: Float,
        yawCos: Float,
        yawSin: Float,
        pitchCos: Float,
        pitchSin: Float
    ) {
        val yawX = x * yawCos - z * yawSin
        val yawZ = x * yawSin + z * yawCos
        val pitchY = y * pitchCos - yawZ * pitchSin
        val pitchZ = y * pitchSin + yawZ * pitchCos
        val depth = CAMERA_DISTANCE - pitchZ
        val inverse = if (depth > 0.35f) focal / depth else 0f
        projectedX = centerX + yawX * inverse
        projectedY = centerY - pitchY * inverse
        projectedDepth = depth
    }

    private fun sortByDepth(order: IntArray, depth: FloatArray, lo: Int, hi: Int) {
        var left = lo
        var right = hi
        if (left >= right) return
        val pivot = depth[order[(left + right) ushr 1]]
        while (left <= right) {
            while (depth[order[left]] < pivot) left++
            while (depth[order[right]] > pivot) right--
            if (left <= right) {
                val tmp = order[left]
                order[left] = order[right]
                order[right] = tmp
                left++
                right--
            }
        }
        if (lo < right) sortByDepth(order, depth, lo, right)
        if (left < hi) sortByDepth(order, depth, left, hi)
    }
}
