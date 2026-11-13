package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A full-screen double helix made from depth-sorted 3D line segments.
 *
 * There are two helical backbones and a dense ladder of base-pair rungs. The
 * helix is deliberately broad, its axis runs from the bottom-left to the
 * top-right, while the strand spins around that axis. The segment chain is
 * longer than the screen diagonal and has no end caps, so the DNA appears to
 * continue beyond the viewport.
 */
class DnaSceneRenderer {
    companion object {
        private const val CAMERA_DISTANCE = 7f
        private const val DNA_HEIGHT = 5.05f
        // The first wide revision used 1.45f. The user asked to narrow the
        // figure itself twice, so the final radius is 0.725f (1.25x the
        // original 0.58f prototype). Width is independent of helix length,
        // so the diagonal still runs off both edges.
        private const val DNA_RADIUS = 0.725f
        private const val DNA_TURNS = 4.6f
        private const val BACKBONE_SEGMENTS = 190
        private const val RUNG_COUNT = 42
        // Negative angle: screen axis goes bottom-left -> top-right.
        private const val DIAGONAL_DEGREES = -33f
        private const val DIAGONAL_ZOOM = 1.22f
        private const val DEPTH_FLOOR = 0.78f
        private const val BACKBONE_CORE_WIDTH = 0.78f
        private const val BACKBONE_GLOW_WIDTH = 1.75f
        private const val RUNG_CORE_WIDTH = 0.48f
        private const val RUNG_GLOW_WIDTH = 1.15f
        private const val RUNG_BRIGHTNESS = 0.66f
        private const val DEG_TO_RAD = PI.toFloat() / 180f
    }

    private class Geometry(
        val starts: FloatArray,
        val ends: FloatArray,
        val rung: BooleanArray,
        val count: Int
    )

    private val geometry = buildGeometry()
    private val segmentCount = geometry.count

    private val startX = FloatArray(segmentCount)
    private val startY = FloatArray(segmentCount)
    private val endX = FloatArray(segmentCount)
    private val endY = FloatArray(segmentCount)
    private val depths = FloatArray(segmentCount)
    private val visible = BooleanArray(segmentCount)
    private val order = IntArray(segmentCount) { it }

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        columns: Int,
        spinDegrees: Float,
        excitement: Float,
        lineWidthScale: Float
    ) {
        if (width <= 1 || height <= 1 || segmentCount == 0) return

        val cell = width.toFloat() / columns.coerceAtLeast(1)
        val diagonal = sqrt(width.toFloat() * width + height.toFloat() * height)
        val focal = DIAGONAL_ZOOM * diagonal * CAMERA_DISTANCE / DNA_HEIGHT
        val centerX = width * 0.5f
        val centerY = height * 0.5f

        val spin = spinDegrees * DEG_TO_RAD
        val spinCos = cos(spin)
        val spinSin = sin(spin)
        val tilt = DIAGONAL_DEGREES * DEG_TO_RAD
        val tiltCos = cos(tilt)
        val tiltSin = sin(tilt)

        val starts = geometry.starts
        val ends = geometry.ends
        var p = 0
        for (i in 0 until segmentCount) {
            val ax = starts[p]
            val ay = starts[p + 1]
            val az = starts[p + 2]
            val bx = ends[p]
            val by = ends[p + 1]
            val bz = ends[p + 2]
            p += 3

            // Spin around the helix' local Y axis, then tilt the whole strand
            // until its axis lies on the screen diagonal.
            val arx = ax * spinCos - az * spinSin
            val arz = ax * spinSin + az * spinCos
            val brx = bx * spinCos - bz * spinSin
            val brz = bx * spinSin + bz * spinCos

            val adx = arx * tiltCos - ay * tiltSin
            val ady = arx * tiltSin + ay * tiltCos
            val bdx = brx * tiltCos - by * tiltSin
            val bdy = brx * tiltSin + by * tiltCos

            val depthA = CAMERA_DISTANCE - arz
            val depthB = CAMERA_DISTANCE - brz
            if (depthA < 0.35f || depthB < 0.35f) {
                visible[i] = false
                depths[i] = Float.MAX_VALUE
                continue
            }

            val invA = focal / depthA
            val invB = focal / depthB
            startX[i] = centerX + adx * invA
            startY[i] = centerY - ady * invA
            endX[i] = centerX + bdx * invB
            endY[i] = centerY - bdy * invB
            depths[i] = (depthA + depthB) * 0.5f
            visible[i] = true
        }

        sortByDepth(order, depths, 0, segmentCount - 1)

        val glowAlpha = (58 + 32 * excitement.coerceIn(0f, 1f)).toInt()
        val brightnessBoost = 1f + 0.12f * excitement.coerceIn(0f, 1f)
        val widthBoost = (1f + 0.16f * excitement.coerceIn(0f, 1f)) *
            lineWidthScale.coerceIn(
                WallpaperSettings.MIN_LINE_WIDTH,
                WallpaperSettings.MAX_LINE_WIDTH
            )

        // Far to near. A few hundred short strokes are cheap, and the sort
        // makes the two backbones cross with a proper front/back relationship.
        for (orderIndex in 0 until segmentCount) {
            val i = order[orderIndex]
            if (!visible[i]) continue

            val isRung = geometry.rung[i]
            val depthShade = (
                DEPTH_FLOOR + (1f - DEPTH_FLOOR) *
                    ((7.58f - depths[i]) / 1.35f).coerceIn(0f, 1f)
                )
            val shade = if (isRung) depthShade * RUNG_BRIGHTNESS else depthShade
            val coreWidth = cell * if (isRung) RUNG_CORE_WIDTH else BACKBONE_CORE_WIDTH
            val glowWidth = cell * if (isRung) RUNG_GLOW_WIDTH else BACKBONE_GLOW_WIDTH

            paint.strokeWidth = glowWidth * widthBoost
            paint.color = SceneColor.gray(shade * 0.34f * brightnessBoost, glowAlpha)
            canvas.drawLine(startX[i], startY[i], endX[i], endY[i], paint)

            paint.strokeWidth = coreWidth * widthBoost
            paint.color = SceneColor.gray(shade * brightnessBoost)
            canvas.drawLine(startX[i], startY[i], endX[i], endY[i], paint)
        }
    }

    private fun buildGeometry(): Geometry {
        val count = BACKBONE_SEGMENTS * 2 + RUNG_COUNT
        val starts = FloatArray(count * 3)
        val ends = FloatArray(count * 3)
        val rung = BooleanArray(count)
        var segment = 0

        for (strand in 0..1) {
            val strandPhase = strand * PI.toFloat()
            var prevX = 0f
            var prevY = 0f
            var prevZ = 0f
            for (i in 0..BACKBONE_SEGMENTS) {
                val t = i.toFloat() / BACKBONE_SEGMENTS
                val angle = DNA_TURNS * 2f * PI.toFloat() * t + strandPhase
                val x = DNA_RADIUS * cos(angle)
                val y = -DNA_HEIGHT * 0.5f + DNA_HEIGHT * t
                val z = DNA_RADIUS * sin(angle)
                if (i > 0) {
                    val offset = segment * 3
                    starts[offset] = prevX
                    starts[offset + 1] = prevY
                    starts[offset + 2] = prevZ
                    ends[offset] = x
                    ends[offset + 1] = y
                    ends[offset + 2] = z
                    segment++
                }
                prevX = x
                prevY = y
                prevZ = z
            }
        }

        for (r in 0 until RUNG_COUNT) {
            val t = (r + 0.5f) / RUNG_COUNT
            val angle = DNA_TURNS * 2f * PI.toFloat() * t
            val y = -DNA_HEIGHT * 0.5f + DNA_HEIGHT * t
            val offset = segment * 3
            starts[offset] = DNA_RADIUS * cos(angle)
            starts[offset + 1] = y
            starts[offset + 2] = DNA_RADIUS * sin(angle)
            ends[offset] = DNA_RADIUS * cos(angle + PI.toFloat())
            ends[offset + 1] = y
            ends[offset + 2] = DNA_RADIUS * sin(angle + PI.toFloat())
            rung[segment] = true
            segment++
        }

        return Geometry(starts, ends, rung, segment)
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
