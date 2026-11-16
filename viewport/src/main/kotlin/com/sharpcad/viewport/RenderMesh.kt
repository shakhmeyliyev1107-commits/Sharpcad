package com.sharpcad.viewport

import java.nio.ByteBuffer

/**
 * Geometry handed to the viewport for display. All buffers are direct, in native byte
 * order, and must not be modified after being passed in.
 *
 * @property positions xyz floats per vertex.
 * @property normals unit xyz floats per vertex (same vertex count as [positions]).
 * @property indices unsigned 32-bit ints, three per triangle.
 * @property edgeSegments xyz floats, two points per line segment; may be empty.
 * @property bounds minX, minY, minZ, maxX, maxY, maxZ in millimetres.
 */
class RenderMesh(
    val positions: ByteBuffer,
    val normals: ByteBuffer,
    val indices: ByteBuffer,
    val edgeSegments: ByteBuffer,
    val bounds: FloatArray,
) {
    init {
        require(positions.isDirect && normals.isDirect && indices.isDirect && edgeSegments.isDirect) {
            "RenderMesh buffers must be direct"
        }
        require(positions.capacity() == normals.capacity()) { "One normal per vertex is required" }
        require(positions.capacity() % 12 == 0 && indices.capacity() % 12 == 0) {
            "Positions must be float triples and indices int triples"
        }
        require(edgeSegments.capacity() % 24 == 0) { "Edge segments must be pairs of float triples" }
        require(bounds.size == 6) { "Bounds must hold six values" }
    }

    val vertexCount: Int get() = positions.capacity() / 12
    val indexCount: Int get() = indices.capacity() / 4
    val edgeVertexCount: Int get() = edgeSegments.capacity() / 12
}

/**
 * Frame pacing over the most recent interaction, measured from display vsync timestamps.
 *
 * @property averageMillis mean time between presented frames.
 * @property worstMillis longest time between two consecutive frames.
 * @property frames number of frame intervals measured.
 * @property skipped frames Filament asked to drop because the GPU was behind.
 */
data class FrameStats(
    val averageMillis: Float,
    val worstMillis: Float,
    val frames: Int,
    val skipped: Int,
)
