package com.asciiwallpaper

/**
 * A deliberately blocky Android robot assembled from ten 3D boxes: head,
 * torso, two arms, two legs, two antennae and two bright eyes. It is rendered
 * through the same projector as the cube/fusilli scenes, so a horizontal spin
 * has real depth and face shading instead of a flat width squash.
 */
object RobotMesh {
    /**
     * The antennae and legs make the raw mesh taller above the origin than
     * below it. Shifting every box down by the middle of that extent keeps
     * the whole robot optically centered on the wallpaper; rotating around
     * the origin no longer leaves a large empty band at the bottom.
     */
    private const val Y_CENTER_OFFSET = -0.245f

    fun create(): Mesh {
        val vertices = ArrayList<Float>(10 * 8 * 3)
        val indices = ArrayList<Int>(10 * 12 * 3)
        val normals = ArrayList<Float>(10 * 12 * 3)
        val edges = ArrayList<Int>(10 * 24)

        fun addBox(
            cx: Float,
            cy: Float,
            cz: Float,
            hx: Float,
            hy: Float,
            hz: Float
        ) {
            val base = vertices.size / 3
            val centeredY = cy + Y_CENTER_OFFSET
            //  0..3 front, 4..7 back
            val points = floatArrayOf(
                cx - hx, centeredY - hy, cz + hz,
                cx + hx, centeredY - hy, cz + hz,
                cx + hx, centeredY + hy, cz + hz,
                cx - hx, centeredY + hy, cz + hz,
                cx - hx, centeredY - hy, cz - hz,
                cx + hx, centeredY - hy, cz - hz,
                cx + hx, centeredY + hy, cz - hz,
                cx - hx, centeredY + hy, cz - hz
            )
            for (value in points) vertices.add(value)

            val quads = arrayOf(
                intArrayOf(0, 1, 2, 3) to Vec3(0f, 0f, 1f),
                intArrayOf(5, 4, 7, 6) to Vec3(0f, 0f, -1f),
                intArrayOf(1, 5, 6, 2) to Vec3(1f, 0f, 0f),
                intArrayOf(4, 0, 3, 7) to Vec3(-1f, 0f, 0f),
                intArrayOf(3, 2, 6, 7) to Vec3(0f, 1f, 0f),
                intArrayOf(4, 5, 1, 0) to Vec3(0f, -1f, 0f)
            )
            for ((quad, outward) in quads) {
                addTriangle(
                    base + quad[0], base + quad[1], base + quad[2],
                    vertices, outward, indices, normals
                )
                addTriangle(
                    base + quad[0], base + quad[2], base + quad[3],
                    vertices, outward, indices, normals
                )
            }
            val boxEdges = intArrayOf(
                0, 1, 1, 2, 2, 3, 3, 0,
                4, 5, 5, 6, 6, 7, 7, 4,
                0, 4, 1, 5, 2, 6, 3, 7
            )
            for (edge in boxEdges) edges.add(base + edge)
        }

        // Head, torso, arms and legs. The proportions are intentionally close
        // to the classic Android robot mark, just extruded into 3D.
        addBox(0f, 0.82f, 0f, 0.31f, 0.22f, 0.20f)
        addBox(0f, 0.22f, 0f, 0.28f, 0.36f, 0.17f)
        addBox(-0.47f, 0.22f, 0f, 0.10f, 0.36f, 0.10f)
        addBox(0.47f, 0.22f, 0f, 0.10f, 0.36f, 0.10f)
        addBox(-0.17f, -0.42f, 0f, 0.10f, 0.29f, 0.11f)
        addBox(0.17f, -0.42f, 0f, 0.10f, 0.29f, 0.11f)
        addBox(-0.18f, 1.10f, 0f, 0.022f, 0.10f, 0.022f)
        addBox(0.18f, 1.10f, 0f, 0.022f, 0.10f, 0.022f)
        addBox(-0.13f, 0.86f, 0.205f, 0.038f, 0.038f, 0.018f)
        addBox(0.13f, 0.86f, 0.205f, 0.038f, 0.038f, 0.018f)

        return Mesh(
            positions = vertices.toFloatArray(),
            faceIndices = indices.toIntArray(),
            faceNormals = normals.toFloatArray(),
            edgeIndices = edges.toIntArray()
        )
    }

    private fun addTriangle(
        a: Int,
        b: Int,
        c: Int,
        vertices: List<Float>,
        outward: Vec3,
        indices: MutableList<Int>,
        normals: MutableList<Float>
    ) {
        val pa = Vec3(vertices[a * 3], vertices[a * 3 + 1], vertices[a * 3 + 2])
        val pb = Vec3(vertices[b * 3], vertices[b * 3 + 1], vertices[b * 3 + 2])
        val pc = Vec3(vertices[c * 3], vertices[c * 3 + 1], vertices[c * 3 + 2])
        var normal = (pb - pa).cross(pc - pa).normalized()
        if (normal.lengthSquared() < 0.000001f) {
            normal = outward
        }
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
}
