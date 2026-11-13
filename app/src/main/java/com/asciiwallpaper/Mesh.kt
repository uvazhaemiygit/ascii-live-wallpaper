package com.asciiwallpaper

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small, allocation-light triangle mesh. Face normals are stored next to the
 * vertices so the software projector only has to rotate them once per frame.
 */
class Mesh(
    val positions: FloatArray,
    val faceIndices: IntArray,
    val faceNormals: FloatArray,
    val edgeIndices: IntArray = IntArray(0)
) {
    val vertexCount: Int = positions.size / 3
    val faceCount: Int = faceIndices.size / 3
    val boundingRadius: Float = run {
        var maxSq = 0f
        var i = 0
        while (i < positions.size) {
            val x = positions[i]
            val y = positions[i + 1]
            val z = positions[i + 2]
            val d = x * x + y * y + z * z
            if (d > maxSq) maxSq = d
            i += 3
        }
        sqrt(maxSq)
    }
}

/** Immutable build-time vector helper (not used in the per-frame hot path). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun lengthSquared() = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val len = sqrt(lengthSquared())
        return if (len < 1e-6f) Vec3(0f, 0f, 0f) else Vec3(x / len, y / len, z / len)
    }
}

/**
 * Row-major 3x3 rotation matrix. It is reused by the renderer every frame;
 * mutating this object allocates nothing. R = Ry * Rx * Rz.
 */
class Mat3 {
    val values = FloatArray(9)

    fun setEulerDegrees(rxD: Float, ryD: Float, rzD: Float) {
        val rx = Math.toRadians(rxD.toDouble())
        val ry = Math.toRadians(ryD.toDouble())
        val rz = Math.toRadians(rzD.toDouble())
        val cx = cos(rx).toFloat()
        val sx = sin(rx).toFloat()
        val cy = cos(ry).toFloat()
        val sy = sin(ry).toFloat()
        val cz = cos(rz).toFloat()
        val sz = sin(rz).toFloat()

        values[0] = cy * cz + sy * sx * sz
        values[1] = -cy * sz + sy * sx * cz
        values[2] = sy * cx

        values[3] = cx * sz
        values[4] = cx * cz
        values[5] = -sx

        values[6] = -sy * cz + cy * sx * sz
        values[7] = sy * sz + cy * sx * cz
        values[8] = cy * cx
    }

    fun tx(x: Float, y: Float, z: Float): Float =
        values[0] * x + values[1] * y + values[2] * z

    fun ty(x: Float, y: Float, z: Float): Float =
        values[3] * x + values[4] * y + values[5] * z

    fun tz(x: Float, y: Float, z: Float): Float =
        values[6] * x + values[7] * y + values[8] * z

    fun tx(v: Vec3) = tx(v.x, v.y, v.z)
    fun ty(v: Vec3) = ty(v.x, v.y, v.z)
    fun tz(v: Vec3) = tz(v.x, v.y, v.z)
}
