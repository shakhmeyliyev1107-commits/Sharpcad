package com.sharpcad.app

import com.sharpcad.document.BodyMesh
import com.sharpcad.document.MaterialLibrary
import com.sharpcad.document.MaterialPreset
import com.sharpcad.kernel.Mesh
import com.sharpcad.viewport.RenderMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns kernel meshes into what the viewport draws. Pure buffer work, no kernel calls. */
internal object MeshTools {

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /**
     * All bodies in one mesh (one draw call), or `null` when there are none. Each body's
     * vertices carry its material from [materialOf] for the PBR shader.
     */
    fun merge(bodies: List<BodyMesh>, materialOf: (Long) -> MaterialPreset = { MaterialLibrary.default }): RenderMesh? {
        if (bodies.isEmpty()) return null
        val vertices = bodies.sumOf { it.mesh.vertexCount }
        val indices = bodies.sumOf { it.mesh.triangleCount * 3 }
        val edgeFloats = bodies.sumOf { it.mesh.edgeSegmentCount * 6 }
        if (vertices == 0 || indices == 0) return null

        val positions = direct(vertices * 12)
        val normals = direct(vertices * 12)
        val index = direct(indices * 4)
        val edges = direct(edgeFloats * 4)
        val appearance = direct(vertices * RenderMesh.APPEARANCE_STRIDE)
        val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var base = 0
        for (body in bodies) {
            val m = body.mesh
            positions.put(m.positions.duplicate().apply { rewind() })
            normals.put(m.normals.duplicate().apply { rewind() })
            edges.put(m.edgeSegments.duplicate().apply { rewind() })
            val src = m.indices.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
            // The int view starts at the byte buffer's current position: no extra offset.
            val out = index.asIntBuffer()
            for (i in 0 until src.capacity()) out.put(src.get(i) + base)
            index.position(index.position() + src.capacity() * 4)
            val mat = materialOf(body.bodyId)
            val r = linear((mat.color shr 16) and 0xFF)
            val g = linear((mat.color shr 8) and 0xFF)
            val b = linear(mat.color and 0xFF)
            repeat(m.vertexCount) {
                appearance.putFloat(r).putFloat(g).putFloat(b).putFloat(mat.roughness).putFloat(mat.metallic)
            }
            base += m.vertexCount
            for (k in 0 until 3) {
                bounds[k] = minOf(bounds[k], body.bounds[k].toFloat())
                bounds[k + 3] = maxOf(bounds[k + 3], body.bounds[k + 3].toFloat())
            }
        }
        listOf(positions, normals, index, edges, appearance).forEach { it.flip() }
        return RenderMesh(positions, normals, index, edges, bounds, appearance)
    }

    private fun linear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    /** The triangles of [mesh] whose face index is in [faces], as a separate mesh. */
    fun facesOf(mesh: Mesh, faces: Set<Int>): RenderMesh? {
        val triFaces = mesh.triangleFaces.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val idx = mesh.indices.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val pos = mesh.positions.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val nor = mesh.normals.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val picked = (0 until triFaces.capacity()).filter { triFaces.get(it) in faces }
        if (picked.isEmpty()) return null

        val count = picked.size * 3
        val positions = direct(count * 12)
        val normals = direct(count * 12)
        val index = direct(count * 4)
        val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var n = 0
        for (t in picked) {
            for (corner in 0 until 3) {
                val v = idx.get(t * 3 + corner)
                for (k in 0 until 3) {
                    val p = pos.get(v * 3 + k)
                    positions.putFloat(p)
                    normals.putFloat(nor.get(v * 3 + k))
                    bounds[k] = minOf(bounds[k], p)
                    bounds[k + 3] = maxOf(bounds[k + 3], p)
                }
                index.putInt(n++)
            }
        }
        listOf(positions, normals, index).forEach { it.flip() }
        return RenderMesh(positions, normals, index, direct(0), bounds)
    }
    /**
     * Nearest ray parameter t where origin + t·direction hits a triangle of [face], or
     * `null`. Möller–Trumbore on the face's triangles; runs on the caller's thread.
     */
    fun rayHitFace(mesh: Mesh, face: Int, origin: DoubleArray, direction: DoubleArray): Double? {
        val triFaces = mesh.triangleFaces.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val idx = mesh.indices.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val pos = mesh.positions.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        var best: Double? = null
        val p = Array(3) { DoubleArray(3) }
        for (t in 0 until triFaces.capacity()) {
            if (triFaces.get(t) != face) continue
            for (k in 0 until 3) {
                val v = idx.get(t * 3 + k)
                for (c in 0 until 3) p[k][c] = pos.get(v * 3 + c).toDouble()
            }
            val e1 = DoubleArray(3) { p[1][it] - p[0][it] }
            val e2 = DoubleArray(3) { p[2][it] - p[0][it] }
            val h = cross(direction, e2)
            val a = dot(e1, h)
            if (kotlin.math.abs(a) < 1e-12) continue
            val f = 1.0 / a
            val s = DoubleArray(3) { origin[it] - p[0][it] }
            val u = f * dot(s, h)
            if (u < 0 || u > 1) continue
            val q = cross(s, e1)
            val v = f * dot(direction, q)
            if (v < 0 || u + v > 1) continue
            val dist = f * dot(e2, q)
            if (dist > 0 && (best == null || dist < best)) best = dist
        }
        return best
    }

    /** Average vertex normal of [face], normalised; `null` for an unknown face. */
    fun faceNormal(mesh: Mesh, face: Int): DoubleArray? {
        val triFaces = mesh.triangleFaces.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val idx = mesh.indices.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        val nor = mesh.normals.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val sum = DoubleArray(3)
        for (t in 0 until triFaces.capacity()) {
            if (triFaces.get(t) != face) continue
            for (k in 0 until 3) {
                val v = idx.get(t * 3 + k)
                for (c in 0 until 3) sum[c] += nor.get(v * 3 + c)
            }
        }
        val len = kotlin.math.sqrt(dot(sum, sum))
        return if (len < 1e-9) null else DoubleArray(3) { sum[it] / len }
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
