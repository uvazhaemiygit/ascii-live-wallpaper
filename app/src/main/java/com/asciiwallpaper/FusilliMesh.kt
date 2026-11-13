package com.asciiwallpaper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Процедурная макаронина-спираль (фузилли).
 *
 * Центральная линия — открытый винт:
 *   x = R·cos(2π·turns·t)
 *   y = -H/2 + H·t
 *   z = R·sin(2π·turns·t)
 * Вокруг неё строится шестигранная трубка с небольшими кольцевыми рёбрами.
 * Торцы закрыты, поэтому на вращении не видно «полой» трубы. Геометрия
 * создаётся один раз вместе с рендерером.
 */
object FusilliMesh {
    const val SEGMENTS = 96
    const val SIDES = 6

    private const val TURNS = 2.5f
    private const val HELIX_RADIUS = 0.68f
    private const val HEIGHT = 1.35f
    private const val TUBE_RADIUS = 0.092f

    fun create(segments: Int = SEGMENTS, sides: Int = SIDES): Mesh {
        require(segments >= 12 && sides >= 3)

        val ringCount = segments + 1
        val startCap = ringCount * sides
        val endCap = startCap + 1
        val positions = FloatArray((ringCount * sides + 2) * 3)
        val centers = Array(ringCount) { i ->
            centerline(i.toFloat() / segments)
        }

        for (i in 0 until ringCount) {
            val t = i.toFloat() / segments
            val u = TURNS * 2f * PI.toFloat() * t
            val tangent = tangentAt(t)
            val radial = Vec3(cos(u), 0f, sin(u))
            val normal = (
                radial - tangent * radial.dot(tangent)
                ).normalized()
            val binormal = tangent.cross(normal).normalized()

            for (j in 0 until sides) {
                val angle = j * (2f * PI.toFloat()) / sides
                val radialDir = normal * cos(angle) + binormal * sin(angle)
                // Лёгкая гофрированность вдоль трубки — характерный рельеф
                // фузилли; на ASCII-сетке он даёт живые блики.
                val radius = TUBE_RADIUS * (
                    1f + 0.085f * cos(3f * angle + 2f * u)
                    )
                writeVec(positions, i * sides + j, centers[i] + radialDir * radius)
            }
        }
        writeVec(positions, startCap, centers.first())
        writeVec(positions, endCap, centers.last())

        val indices = ArrayList<Int>(segments * sides * 6 + sides * 6)
        val normals = ArrayList<Float>(indices.size)

        for (i in 0 until segments) {
            val hint = (centers[i] + centers[i + 1]) * 0.5f
            for (j in 0 until sides) {
                val j2 = (j + 1) % sides
                val a = i * sides + j
                val b = i * sides + j2
                val c = (i + 1) * sides + j2
                val d = (i + 1) * sides + j

                addTriangle(a, b, c, positions, hint, indices, normals)
                addTriangle(a, c, d, positions, hint, indices, normals)
            }
        }

        // Плоские торцевые заглушки.
        val startOut = tangentAt(0f) * -1f
        val endOut = tangentAt(1f)
        for (j in 0 until sides) {
            val j2 = (j + 1) % sides
            addTriangleDirected(startCap, j, j2, positions, startOut, indices, normals)
            addTriangleDirected(
                endCap,
                segments * sides + j,
                segments * sides + j2,
                positions,
                endOut,
                indices,
                normals
            )
        }

        return Mesh(
            positions = positions,
            faceIndices = indices.toIntArray(),
            faceNormals = normals.toFloatArray()
        )
    }

    private fun centerline(t: Float): Vec3 {
        val u = TURNS * 2f * PI.toFloat() * t
        return Vec3(
            HELIX_RADIUS * cos(u),
            -HEIGHT * 0.5f + HEIGHT * t,
            HELIX_RADIUS * sin(u)
        )
    }

    /** Аналитическая производная центральной линии. */
    private fun tangentAt(t: Float): Vec3 {
        val u = TURNS * 2f * PI.toFloat() * t
        val du = TURNS * 2f * PI.toFloat()
        return Vec3(
            -HELIX_RADIUS * sin(u) * du,
            HEIGHT,
            HELIX_RADIUS * cos(u) * du
        ).normalized()
    }

    /**
     * Строит треугольник с внешней нормалью. Hint — точка на центральной
     * линии, от которой трубка растёт наружу.
     */
    private fun addTriangle(
        a: Int,
        b: Int,
        c: Int,
        positions: FloatArray,
        hint: Vec3,
        indices: MutableList<Int>,
        normals: MutableList<Float>
    ) {
        val pa = vecAt(positions, a)
        val pb = vecAt(positions, b)
        val pc = vecAt(positions, c)
        val centroid = (pa + pb + pc) * (1f / 3f)
        addTriangleDirected(a, b, c, positions, centroid - hint, indices, normals)
    }

    private fun addTriangleDirected(
        a: Int,
        b: Int,
        c: Int,
        positions: FloatArray,
        outward: Vec3,
        indices: MutableList<Int>,
        normals: MutableList<Float>
    ) {
        val pa = vecAt(positions, a)
        val pb = vecAt(positions, b)
        val pc = vecAt(positions, c)

        var normal = (pb - pa).cross(pc - pa)
        if (normal.lengthSquared() < 1e-10f) return
        normal = normal.normalized()

        if (normal.dot(outward) < 0f) {
            normal = normal * -1f
            indices.add(a); indices.add(c); indices.add(b)
        } else {
            indices.add(a); indices.add(b); indices.add(c)
        }
        normals.add(normal.x)
        normals.add(normal.y)
        normals.add(normal.z)
    }

    private fun writeVec(target: FloatArray, index: Int, value: Vec3) {
        val offset = index * 3
        target[offset] = value.x
        target[offset + 1] = value.y
        target[offset + 2] = value.z
    }

    private fun vecAt(positions: FloatArray, index: Int): Vec3 = Vec3(
        positions[index * 3],
        positions[index * 3 + 1],
        positions[index * 3 + 2]
    )
}
