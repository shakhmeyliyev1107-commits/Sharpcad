package com.sharpcad.app

import com.sharpcad.kernel.Kernel
import com.sharpcad.kernel.Mesh
import com.sharpcad.kernel.Shape
import com.sharpcad.viewport.RenderMesh
import java.io.File

/** A model ready for display, with the figures reported in the status readout. */
class LoadedModel(
    val mesh: RenderMesh,
    val triangles: Int,
    val importMillis: Long,
    val tessellateMillis: Long,
)

internal object ModelLoader {
    /** Chord error as a fraction of the model's diagonal, clamped to sensible millimetres. */
    private const val RELATIVE_DEFLECTION = 0.001
    private const val MIN_DEFLECTION_MM = 0.005
    private const val MAX_DEFLECTION_MM = 2.0

    /**
     * Reads a STEP file and tessellates it for display. Blocking and CPU-heavy: call on a
     * worker thread. The B-rep is released afterwards; Phase 1 only views models.
     *
     * @throws com.sharpcad.kernel.KernelException if the file is not readable STEP.
     */
    fun loadStep(file: File): LoadedModel {
        val importStart = System.nanoTime()
        val shape: Shape = Kernel.importStep(file.path)
        val importMillis = (System.nanoTime() - importStart) / 1_000_000

        shape.use {
            val box = it.boundingBox()
            val deflection = (box.diagonal * RELATIVE_DEFLECTION)
                .coerceIn(MIN_DEFLECTION_MM, MAX_DEFLECTION_MM)
            val tessellateStart = System.nanoTime()
            val mesh: Mesh = it.tessellate(deflection)
            val tessellateMillis = (System.nanoTime() - tessellateStart) / 1_000_000

            val bounds = floatArrayOf(
                box.minX.toFloat(), box.minY.toFloat(), box.minZ.toFloat(),
                box.maxX.toFloat(), box.maxY.toFloat(), box.maxZ.toFloat(),
            )
            return LoadedModel(
                mesh = RenderMesh(mesh.positions, mesh.normals, mesh.indices, mesh.edgeSegments, bounds),
                triangles = mesh.triangleCount,
                importMillis = importMillis,
                tessellateMillis = tessellateMillis,
            )
        }
    }
}
