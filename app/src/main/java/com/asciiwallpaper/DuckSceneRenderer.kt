package com.asciiwallpaper

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Ебало утиное" — a deliberately goofy 3D duck bust.
 *
 * The duck is built from several ellipsoids (head, neck, body, two bill
 * lobes) and two eyes. It is rendered as a depth-sorted wireframe. A slow
 * yaw sway keeps the bill readable while the whole bust somersaults vertically
 * around the horizontal X axis, so the character clearly tumbles in 3D.
 */
class DuckSceneRenderer {
    private companion object {
        private const val CAMERA_DISTANCE = 7f
        private const val FOCAL_HEIGHT = 1.55f
        private const val DEG_TO_RAD = PI.toFloat() / 180f
        private const val DEPTH_FLOOR = 0.56f
        private const val BILL_BRIGHTNESS = 1.0f
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

    private class Eye(
        val x: Float,
        val y: Float,
        val z: Float,
        val radius: Float
    )

    private val segments = ArrayList<Segment>(900)
    private val eyes = ArrayList<Eye>(2)
    private val startX: FloatArray
    private val startY: FloatArray
    private val endX: FloatArray
    private val endY: FloatArray
    private val depths: FloatArray
    private val order: IntArray
    private val visible: BooleanArray
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val eyePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private var projectedX = 0f
    private var projectedY = 0f
    private var projectedDepth = 0f

    init {
        buildDuck()
        val count = segments.size
        startX = FloatArray(count)
        startY = FloatArray(count)
        endX = FloatArray(count)
        endY = FloatArray(count)
        depths = FloatArray(count)
        order = IntArray(count) { it }
        visible = BooleanArray(count)
    }

    private fun buildDuck() {
        // Head.
        addEllipsoid(
            cx = 0f, cy = 0.55f, cz = 0f,
            rx = 0.92f, ry = 0.88f, rz = 0.78f,
            latitudeRings = 8, longitudeLines = 14,
            brightness = 0.86f
        )
        // Neck and body give the head a duck-shaped bust instead of leaving a
        // floating ball on the screen.
        addEllipsoid(
            cx = 0f, cy = -0.34f, cz = -0.02f,
            rx = 0.56f, ry = 0.58f, rz = 0.55f,
            latitudeRings = 5, longitudeLines = 10,
            brightness = 0.52f
        )
        addEllipsoid(
            cx = 0f, cy = -1.16f, cz = -0.04f,
            rx = 1.14f, ry = 0.92f, rz = 0.76f,
            latitudeRings = 6, longitudeLines = 12,
            brightness = 0.48f
        )
        // Upper and lower bill. Two flattened lobes make the beak read from
        // the side as well as from the front.
        addEllipsoid(
            cx = 0f, cy = 0.24f, cz = 0.75f,
            rx = 0.62f, ry = 0.14f, rz = 0.53f,
            latitudeRings = 4, longitudeLines = 14,
            brightness = BILL_BRIGHTNESS
        )
        addEllipsoid(
            cx = 0f, cy = 0.08f, cz = 0.69f,
            rx = 0.52f, ry = 0.09f, rz = 0.46f,
            latitudeRings = 3, longitudeLines = 12,
            brightness = BILL_BRIGHTNESS
        )

        addEye(-0.37f, 0.82f, 0.66f, 0.155f)
        addEye(0.37f, 0.82f, 0.66f, 0.155f)
    }

    private fun addEye(x: Float, y: Float, z: Float, radius: Float) {
        eyes += Eye(x, y, z, radius)
        val steps = 14
        var previousX = x + radius
        var previousY = y
        for (i in 1..steps) {
            val angle = i.toFloat() / steps * PI.toFloat() * 2f
            val nextX = x + radius * cos(angle)
            val nextY = y + radius * sin(angle)
            segments += Segment(
                previousX, previousY, z + 0.005f,
                nextX, nextY, z + 0.005f,
                0.98f
            )
            previousX = nextX
            previousY = nextY
        }
    }

    /**
     * Adds a latitude/longitude wire cage. The extra density at the silhouette
     * comes from the native Canvas antialiasing, not from duplicating points.
     */
    private fun addEllipsoid(
        cx: Float,
        cy: Float,
        cz: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        latitudeRings: Int,
        longitudeLines: Int,
        brightness: Float
    ) {
        // Latitude rings.
        for (ring in 1 until latitudeRings) {
            val latitude = -PI.toFloat() * 0.5f +
                PI.toFloat() * ring / latitudeRings
            val ringRadius = cos(latitude)
            val y = cy + ry * sin(latitude)
            var previousX = 0f
            var previousZ = 0f
            for (step in 0..longitudeLines) {
                val longitude = step.toFloat() / longitudeLines * PI.toFloat() * 2f
                val x = cx + rx * ringRadius * cos(longitude)
                val z = cz + rz * ringRadius * sin(longitude)
                if (step > 0) {
                    segments += Segment(
                        previousX, y, previousZ,
                        x, y, z,
                        brightness
                    )
                }
                previousX = x
                previousZ = z
            }
        }

        // Longitude meridians.
        for (line in 0 until longitudeLines) {
            val longitude = line.toFloat() / longitudeLines * PI.toFloat() * 2f
            val cosLon = cos(longitude)
            val sinLon = sin(longitude)
            val latitudeSteps = 10
            var previousX = 0f
            var previousY = 0f
            var previousZ = 0f
            for (step in 0..latitudeSteps) {
                val latitude = -PI.toFloat() * 0.44f +
                    PI.toFloat() * 0.88f * step / latitudeSteps
                val ringRadius = cos(latitude)
                val x = cx + rx * ringRadius * cosLon
                val y = cy + ry * sin(latitude)
                val z = cz + rz * ringRadius * sinLon
                if (step > 0) {
                    segments += Segment(
                        previousX, previousY, previousZ,
                        x, y, z,
                        brightness
                    )
                }
                previousX = x
                previousY = y
                previousZ = z
            }
        }
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
        if (width <= 1 || height <= 1 || segments.isEmpty()) return

        val cell = width.toFloat() / columns.coerceAtLeast(1)
        // Fit the widest part of the body horizontally as well as the bust
        // vertically, with enough margin that the body does not lose its
        // sides while the somersault brings it closer to the camera.
        val focal = minOf(width * 2.35f, height * FOCAL_HEIGHT)
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val spin = spinDegrees * DEG_TO_RAD
        // "По вертикали": the duck somersaults around the horizontal X axis.
        // A slow yaw sway keeps the bill from being edge-on for too long.
        val yaw = 0.16f + 0.22f * sin(spin * 0.55f)
        val pitch = spin
        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val tiltCos = cos(pitch)
        val tiltSin = sin(pitch)

        val widthBoost = (1f + excitement.coerceIn(0f, 1f) * 0.22f) *
            lineWidthScale.coerceIn(
                WallpaperSettings.MIN_LINE_WIDTH,
                WallpaperSettings.MAX_LINE_WIDTH
            )

        val count = segments.size

        for (i in 0 until count) {
            val segment = segments[i]
            project(
                segment.ax, segment.ay, segment.az,
                centerX, centerY, focal, yawCos, yawSin, tiltCos, tiltSin
            )
            val ax = projectedX
            val ay = projectedY
            val ad = projectedDepth
            project(
                segment.bx, segment.by, segment.bz,
                centerX, centerY, focal, yawCos, yawSin, tiltCos, tiltSin
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

        sortByDepth(order, depths, 0, count - 1)

        for (orderIndex in 0 until count) {
            val i = order[orderIndex]
            if (!visible[i]) continue
            val closeness = (1f - depths[i] / 8.6f).coerceIn(0f, 1f)
            val shade = segments[i].brightness *
                (DEPTH_FLOOR + (1f - DEPTH_FLOOR) * closeness) *
                (1f + excitement.coerceIn(0f, 1f) * 0.12f)
            linePaint.strokeWidth = (cell * (0.48f + 0.34f * closeness) *
                widthBoost).coerceAtLeast(1.1f)
            linePaint.color = SceneColor.gray(shade.coerceIn(0f, 1f))
            canvas.drawLine(startX[i], startY[i], endX[i], endY[i], linePaint)
        }

        // Eyes are painted after the cage so the pupils read as holes in the
        // face. When a vertical somersault turns the face away from the
        // camera, hide the eyes instead of letting them shine through the
        // back of the head wireframe.
        for (eye in eyes) {
            val yawZ = eye.x * yawSin + eye.z * yawCos
            val facingZ = eye.y * tiltSin + yawZ * tiltCos
            if (facingZ < 0f) continue
            project(
                eye.x, eye.y, eye.z,
                centerX, centerY, focal, yawCos, yawSin, tiltCos, tiltSin
            )
            if (projectedDepth <= 0.35f) continue
            val radius = (eye.radius * focal / projectedDepth)
                .coerceAtLeast(2f)
            eyePaint.color = SceneColor.gray(0.025f)
            canvas.drawCircle(projectedX, projectedY, radius, eyePaint)
            eyePaint.color = SceneColor.gray(0.96f)
            canvas.drawCircle(
                projectedX - radius * 0.28f,
                projectedY - radius * 0.28f,
                (radius * 0.23f).coerceAtLeast(0.8f),
                eyePaint
            )
        }
    }

    private fun project(
        x: Float,
        y: Float,
        z: Float,
        centerX: Float,
        centerY: Float,
        focal: Float,
        yawCos: Float,
        yawSin: Float,
        tiltCos: Float,
        tiltSin: Float
    ) {
        val rotatedX = x * yawCos - z * yawSin
        val rotatedZ = x * yawSin + z * yawCos
        val tiltedY = y * tiltCos - rotatedZ * tiltSin
        val tiltedZ = y * tiltSin + rotatedZ * tiltCos
        val depth = CAMERA_DISTANCE - tiltedZ
        val inverse = if (depth > 0.35f) focal / depth else 0f
        projectedX = centerX + rotatedX * inverse
        projectedY = centerY - tiltedY * inverse
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
