package com.sharpcad.app

import android.os.Build
import com.sharpcad.document.ExtrudeFeature
import com.sharpcad.document.KernelDispatcher
import com.sharpcad.document.Operation
import com.sharpcad.document.PlaneSpec
import com.sharpcad.document.Profile
import com.sharpcad.kernel.BooleanOp
import com.sharpcad.kernel.Kernel
import com.sharpcad.viewport.FrameStats
import com.sharpcad.viewport.ViewportController
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reproducible performance run on the current device: builds a model of [BODIES]
 * separate bodies and measures rebuild, boolean, STEP export/import and orbit frame
 * pacing. The report is plain text so it can be shared as-is.
 */
internal class Benchmark(private val model: ModelController, private val viewport: ViewportController, private val cacheDir: File) {

    suspend fun run(): String {
        val lines = mutableListOf<String>()
        lines += "SharpCAD benchmark · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

        // 1. Rebuild a 10 × 10 grid of filleted-free blocks: 100 bodies.
        val features = (0 until BODIES).map { i ->
            val x = (i % 10) * 15.0
            val y = (i / 10) * 15.0
            val rect = listOf(x, y, x + 10, y, x + 10, y, x + 10, y + 10, x + 10, y + 10, x, y + 10, x, y + 10, x, y)
            ExtrudeFeature(i + 1L, Profile(PlaneSpec.XY, rect, listOf(x + 5, y + 5, 2.0)), 8.0 + (i % 7), Operation.NEW_BODY)
        }
        val rebuildStart = System.nanoTime()
        model.document.reset(features)
        val rebuildMs = ms(rebuildStart)
        val state = model.document.state.value
        lines += String.format(Locale.US, "Rebuild %d bodies: %d ms (%,d triangles)", state.bodies.size, rebuildMs, state.bodies.sumOf { it.mesh.triangleCount })

        // 2. Boolean latency on the kernel thread, median of 5.
        val booleanMs = withContext(KernelDispatcher) {
            Kernel.makeBox(40.0, 40.0, 40.0).use { a ->
                Kernel.profileFromSketch(PlaneSpec.XY.frame(), DoubleArray(0), doubleArrayOf(20.0, 20.0, 12.0)).use { p ->
                    p.extrude(0.0, 0.0, 60.0).use { tool ->
                        List(5) {
                            val t = System.nanoTime()
                            a.booleanWith(tool, BooleanOp.SUBTRACT).close()
                            (System.nanoTime() - t) / 1e6
                        }.sorted()[2]
                    }
                }
            }
        }
        lines += String.format(Locale.US, "Boolean subtract (box − cylinder), median of 5: %.1f ms", booleanMs)

        // 3. STEP export and re-import of the whole model.
        val file = File(cacheDir, "benchmark.step")
        val exportStart = System.nanoTime()
        model.document.exportStep(file.path)
        val exportMs = ms(exportStart)
        val importMs = withContext(KernelDispatcher) {
            val t = System.nanoTime()
            Kernel.importStep(file.path).close()
            (System.nanoTime() - t) / 1_000_000
        }
        lines += String.format(Locale.US, "STEP export: %d ms · import: %d ms (%,d KB)", exportMs, importMs, file.length() / 1024)
        file.delete()

        // 4. Orbit frame pacing over one full turn.
        viewport.fitView()
        val stats: FrameStats? = suspendCancellableCoroutine { cont -> viewport.runAutoOrbit(ORBIT_FRAMES) { cont.resume(it) } }
        lines += if (stats == null || stats.frames == 0) {
            "Orbit: not measured (viewport not ready)"
        } else {
            String.format(
                Locale.US, "Orbit %d frames: %.2f ms average, %.2f ms worst, %d skipped (target ≤ 16.7 ms)",
                stats.frames, stats.averageMillis, stats.worstMillis, stats.skipped,
            )
        }
        val runtime = Runtime.getRuntime()
        lines += String.format(
            Locale.US, "Memory: Java heap %d MB used · native heap %d MB",
            (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576, android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576,
        )
        return lines.joinToString("\n")
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    companion object {
        const val BODIES = 100
        const val ORBIT_FRAMES = 240
    }
}
