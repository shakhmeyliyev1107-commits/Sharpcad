package com.sharpcad.app

import com.sharpcad.document.BodyMesh
import com.sharpcad.kernel.Mesh
import com.sharpcad.viewport.RenderMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns kernel meshes into what the viewport draws. Pure buffer work, no kernel calls. */
internal object MeshTools {

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /** All bodies in one mesh (one draw call), or `null` when there are none. */
    fun merge(bodies: List<BodyMesh>): RenderMesh? {
        if (bodies.isEmpty()) return null
        val vertices = bodies.sumOf { it.mesh.vertexCount }
        val indices = bodies.sumOf { it.mesh.triangleCount * 3 }
        val edgeFloats = bodies.sumOf { it.mesh.edgeSegmentCount * 6 }
        if (vertices == 0 || indices == 0) return null

        val positions = direct(vertices * 12)
        val normals = direct(vertices * 12)
        val index = direct(indices * 4)
        val edges = direct(edgeFloats * 4)
        val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var base = 0
        for (body in bodies) {
            val m = body.mesh
            positions.put(m.positions.duplicate().apply { rewind() })
            normals.put(m.normals.duplicate().apply { rewind() })
            edges.put(m.edgeSegments.duplicate().apply { rewind() })
            val src = m.indices.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
            val out = index.asIntBuffer()
            out.position(index.position() / 4)
            for (i in 0 until src.capacity()) out.put(src.get(i) + base)
            index.position(index.position() + src.capacity() * 4)
            base += m.vertexCount
            for (k in 0 until 3) {
                bounds[k] = minOf(bounds[k], body.bounds[k].toFloat())
                bounds[k + 3] = maxOf(bounds[k + 3], body.bounds[k + 3].toFloat())
            }
        }
        listOf(positions, normals, index, edges).forEach { it.flip() }
        return RenderMesh(positions, normals, index, edges, bounds)
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
}
