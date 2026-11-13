package com.asciiwallpaper

/**
 * A plain unit cube. Every quad is split into two triangles; the renderer
 * additionally paints all 12 edges so the result reads as a wireframe cube
 * even after the ASCII downsampling.
 */
object CubeMesh {
    fun create(): Mesh {
        val vertices = floatArrayOf(
            -1f, -1f, 1f,   // 0 front bottom left
            1f, -1f, 1f,    // 1 front bottom right
            1f, 1f, 1f,     // 2 front top right
            -1f, 1f, 1f,    // 3 front top left
            -1f, -1f, -1f,  // 4 back bottom left
            1f, -1f, -1f,   // 5 back bottom right
            1f, 1f, -1f,    // 6 back top right
            -1f, 1f, -1f    // 7 back top left
        )

        // Vertex order inside each quad does not matter for correctness: the
        // helper below orients every triangle outwards using its face hint.
        val quads = arrayOf(
            intArrayOf(0, 1, 2, 3) to Vec3(0f, 0f, 1f),
            intArrayOf(5, 4, 7, 6) to Vec3(0f, 0f, -1f),
            intArrayOf(1, 5, 6, 2) to Vec3(1f, 0f, 0f),
            intArrayOf(4, 0, 3, 7) to Vec3(-1f, 0f, 0f),
            intArrayOf(3, 2, 6, 7) to Vec3(0f, 1f, 0f),
            intArrayOf(4, 5, 1, 0) to Vec3(0f, -1f, 0f)
        )

        val indices = ArrayList<Int>(36)
        val normals = ArrayList<Float>(36)
        for ((quad, outward) in quads) {
            // (a,b,c)
            addTriangle(
                a = quad[0], b = quad[1], c = quad[2],
                vertices = vertices, outward = outward,
                indices = indices, normals = normals
            )
            // (a,c,d)
            addTriangle(
                a = quad[0], b = quad[2], c = quad[3],
                vertices = vertices, outward = outward,
                indices = indices, normals = normals
            )
        }

        val edges = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 0, // front
            4, 5, 5, 6, 6, 7, 7, 4, // back
            0, 4, 1, 5, 2, 6, 3, 7  // connecting struts
        )

        return Mesh(
            positions = vertices,
            faceIndices = indices.toIntArray(),
            faceNormals = normals.toFloatArray(),
            edgeIndices = edges
        )
    }

    private fun addTriangle(
        a: Int,
        b: Int,
        c: Int,
        vertices: FloatArray,
        outward: Vec3,
        indices: MutableList<Int>,
        normals: MutableList<Float>
    ) {
        val pa = Vec3(
            vertices[a * 3], vertices[a * 3 + 1], vertices[a * 3 + 2]
        )
        val pb = Vec3(
            vertices[b * 3], vertices[b * 3 + 1], vertices[b * 3 + 2]
        )
        val pc = Vec3(
            vertices[c * 3], vertices[c * 3 + 1], vertices[c * 3 + 2]
        )
        var normal = (pb - pa).cross(pc - pa).normalized()
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
