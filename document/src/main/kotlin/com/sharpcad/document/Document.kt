package com.sharpcad.document

import com.sharpcad.kernel.Mesh
import java.util.concurrent.Executors
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A tessellated body ready for display. Bounds are minX, minY, minZ, maxX, maxY, maxZ. */
class BodyMesh(val bodyId: Long, val label: String, val mesh: Mesh, val bounds: DoubleArray)

/** What the UI shows after each rebuild. */
data class DocumentState(
    val features: List<Feature> = emptyList(),
    val bodies: List<BodyMesh> = emptyList(),
    val errors: Map<Long, String> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val rebuildMillis: Long = 0,
)

/** A face under a screen ray. */
data class FaceHit(val bodyId: Long, val face: Int, val distance: Double)

/**
 * The feature history of one model: the source of truth for its geometry.
 *
 * Every change replaces the history and rebuilds all bodies on a single kernel thread
 * ([KernelDispatcher]); results arrive through [state]. Edits are serialised, so calls
 * may come from any coroutine. Undo and redo step through whole histories.
 */
class Document(private val dispatcher: CoroutineDispatcher = KernelDispatcher) : AutoCloseable {

    private val _state = MutableStateFlow(DocumentState())
    val state: StateFlow<DocumentState> = _state.asStateFlow()

    private val lock = Mutex()
    private var features: List<Feature> = emptyList()
    private val undoStack = ArrayDeque<List<Feature>>()
    private val redoStack = ArrayDeque<List<Feature>>()
    private var built: BuildResult? = null
    private var nextId = 1L

    /** A fresh, unused feature id. */
    @Synchronized
    fun newId(): Long = nextId++

    /** Appends a feature. */
    suspend fun add(feature: Feature) = change { it + feature }

    /** Replaces the feature with the same id (parameter edits). */
    suspend fun update(feature: Feature) = change { list -> list.map { if (it.id == feature.id) feature else it } }

    /** Removes a feature. Later features that depended on it report errors. */
    suspend fun remove(id: Long) = change { list -> list.filterNot { it.id == id } }

    /** Starts a new model with the given history; clears undo and redo. */
    suspend fun reset(initial: List<Feature>) = lock.withLock {
        undoStack.clear()
        redoStack.clear()
        initial.forEach { reserveId(it.id) }
        features = initial
        rebuild()
    }

    suspend fun undo() = lock.withLock {
        val previous = undoStack.removeLastOrNull() ?: return@withLock
        redoStack.addLast(features)
        features = previous
        rebuild()
    }

    suspend fun redo() = lock.withLock {
        val next = redoStack.removeLastOrNull() ?: return@withLock
        undoStack.addLast(features)
        features = next
        rebuild()
    }

    /** Nearest face of any body under the ray, or `null`. */
    suspend fun pick(origin: DoubleArray, direction: DoubleArray): FaceHit? = lock.withLock {
        withContext(dispatcher) {
            built?.bodies?.mapNotNull { (id, shape) -> shape.pick(origin, direction)?.let { FaceHit(id, it.face, it.distance) } }
                ?.minByOrNull { it.distance }
        }
    }

    /**
     * Profile regions of a sketch: tessellated planar faces, used to show and pick
     * regions before extruding. Returns `null` when the sketch encloses nothing.
     */
    suspend fun profileRegions(plane: PlaneSpec, lines: List<Double>, circles: List<Double>): ProfileRegions? =
        withContext(dispatcher) {
            try {
                com.sharpcad.kernel.Kernel.profileFromSketch(plane.frame(), lines.toDoubleArray(), circles.toDoubleArray())
                    .use { ProfileRegions(it.faceCount, it.tessellate(REGION_DEFLECTION)) }
            } catch (e: com.sharpcad.kernel.KernelException) {
                null
            }
        }

    /** Region index at plane point ([u], [v]), or -1. */
    suspend fun regionAt(plane: PlaneSpec, lines: List<Double>, circles: List<Double>, u: Double, v: Double): Int =
        withContext(dispatcher) {
            try {
                com.sharpcad.kernel.Kernel.profileFromSketch(plane.frame(), lines.toDoubleArray(), circles.toDoubleArray())
                    .use { it.regionAt(plane.frame(), u, v) }
            } catch (e: com.sharpcad.kernel.KernelException) {
                -1
            }
        }

    /** Measurements of a body and one of its faces: (volume mm³, face area mm²), or null. */
    suspend fun measure(bodyId: Long, face: Int): Pair<Double, Double>? = lock.withLock {
        withContext(dispatcher) {
            val shape = built?.bodies?.get(bodyId) ?: return@withContext null
            try { shape.volume to shape.faceArea(face) } catch (e: com.sharpcad.kernel.KernelException) { null }
        }
    }

    /** Writes all bodies to one STEP file. Returns false when there is nothing to export. */
    suspend fun exportStep(path: String): Boolean = exportExact(path, iges = false)

    /** Writes all bodies to one IGES file. Returns false when there is nothing to export. */
    suspend fun exportIges(path: String): Boolean = exportExact(path, iges = true)

    private suspend fun exportExact(path: String, iges: Boolean): Boolean = lock.withLock {
        withContext(dispatcher) {
            val shapes = built?.bodies?.values?.toList().orEmpty()
            if (shapes.isEmpty()) return@withContext false
            com.sharpcad.kernel.Kernel.compound(shapes).use { if (iges) it.exportIges(path) else it.exportStep(path) }
            true
        }
    }

    /**
     * Writes all bodies as a mesh file, tessellated finer than the display mesh
     * ([deflectionMm] chord error). Returns false when there is nothing to export.
     */
    suspend fun exportMesh(format: MeshFormat, path: String, deflectionMm: Double = 0.01): Boolean = lock.withLock {
        val names = _state.value.bodies.associate { it.bodyId to it.label }
        val parts = withContext(dispatcher) {
            built?.bodies?.map { (id, shape) -> meshPart(names[id] ?: "Body $id", shape.tessellate(deflectionMm)) }.orEmpty()
        }
        if (parts.isEmpty()) return@withLock false
        withContext(kotlinx.coroutines.Dispatchers.IO) { MeshExport.write(format, parts, java.io.File(path)) }
        true
    }

    /**
     * Writes all bodies as binary glTF for AR viewers, each with its material from
     * [materialOf]. Returns false when there is nothing to export.
     */
    suspend fun exportGlb(path: String, materialOf: (Long) -> MaterialPreset, deflectionMm: Double = 0.05): Boolean = lock.withLock {
        val names = _state.value.bodies.associate { it.bodyId to it.label }
        val parts = withContext(dispatcher) {
            built?.bodies?.map { (id, shape) ->
                val mesh = shape.tessellate(deflectionMm)
                val part = meshPart(names[id] ?: "Body $id", mesh)
                val normals = FloatArray(mesh.vertexCount * 3).also { mesh.normals.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
                GlbPart(part.name, part.positions, normals, part.indices, materialOf(id))
            }.orEmpty()
        }
        if (parts.isEmpty()) return@withLock false
        withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(path).outputStream().buffered().use { GlbExport.write(parts, it) } }
        true
    }

    private fun meshPart(name: String, mesh: Mesh): MeshPart {
        val positions = FloatArray(mesh.vertexCount * 3).also { mesh.positions.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
        val indices = IntArray(mesh.triangleCount * 3).also { mesh.indices.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(it) }
        return MeshPart(name, positions, indices)
    }

    /** Hidden-line views of all bodies for a drawing, or empty when there are none. */
    suspend fun drawingViews(views: List<DrawingView> = DrawingView.entries): List<ViewLines> = lock.withLock {
        withContext(dispatcher) {
            val shapes = built?.bodies?.values?.toList().orEmpty()
            if (shapes.isEmpty()) return@withContext emptyList()
            com.sharpcad.kernel.Kernel.compound(shapes).use { all ->
                val deflection = (all.boundingBox().diagonal * 0.0005).coerceIn(0.005, 1.0)
                views.map { v -> all.project(v.toViewer, v.up, deflection).let { ViewLines(v, it.visible, it.hidden) } }
            }
        }
    }

    override fun close() {
        built?.close()
        built = null
    }

    private suspend fun change(transform: (List<Feature>) -> List<Feature>) = lock.withLock {
        val updated = transform(features)
        if (updated == features) return@withLock
        undoStack.addLast(features)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updated.forEach { reserveId(it.id) }
        features = updated
        rebuild()
    }

    @Synchronized
    private fun reserveId(id: Long) {
        nextId = max(nextId, id + 1)
    }

    private suspend fun rebuild() {
        val snapshot = features
        val (result, meshes, millis) = withContext(dispatcher) {
            val start = System.nanoTime()
            val result = ModelBuilder.build(snapshot)
            val meshes = result.bodies.map { (id, shape) ->
                val box = shape.boundingBox()
                val diagonal = box.diagonal
                val mesh = shape.tessellate((diagonal * 0.001).coerceIn(0.005, 2.0))
                val label = snapshot.firstOrNull { it.id == id }?.let { bodyLabel(it) } ?: "Body"
                BodyMesh(id, label, mesh, doubleArrayOf(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
            }
            Triple(result, meshes, (System.nanoTime() - start) / 1_000_000)
        }
        built?.close()
        built = result
        _state.value = DocumentState(
            features = snapshot,
            bodies = meshes,
            errors = result.errors,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            rebuildMillis = millis,
        )
    }

    private fun bodyLabel(feature: Feature): String = when (feature) {
        is ImportFeature -> feature.name
        is ExtrudeFeature -> "Body ${feature.id}"
        is RevolveFeature -> "Body ${feature.id}"
        else -> "Body"
    }

    companion object {
        const val MAX_UNDO = 100
        private const val REGION_DEFLECTION = 0.05
    }
}

/** Tessellated sketch regions; [mesh]'s triangle faces are region indices. */
class ProfileRegions(val count: Int, val mesh: Mesh)

/**
 * The single thread every kernel call runs on. OCCT objects are not thread-safe, and
 * one ordered queue keeps rebuilds, picks and exports from overlapping.
 */
val KernelDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "kernel").apply { isDaemon = true } }.asCoroutineDispatcher()
