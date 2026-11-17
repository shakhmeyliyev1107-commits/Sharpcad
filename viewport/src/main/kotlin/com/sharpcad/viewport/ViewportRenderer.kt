package com.sharpcad.viewport

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Owns the Filament engine and draws the CAD scene (grid, axes, one model) into a
 * [SurfaceView].
 *
 * All methods must be called on the main thread (Filament's Android API is
 * single-threaded on the caller side; GPU work happens on Filament's own thread).
 * Frames are only drawn while something changed, so an idle viewport costs no GPU time.
 */
internal class ViewportRenderer(private val surfaceView: SurfaceView) {

    /** Called on the main thread a few times per second while frames are being drawn. */
    var onFrameStats: ((FrameStats) -> Unit)? = null

    val camera = OrbitCamera()
    var width = 0; private set
    var height = 0; private set

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val filamentCamera: Camera
    @Entity private val cameraEntity: Int
    private val skybox: Skybox
    private val colorGrading: ColorGrading

    private val vertexColorMaterial: Material
    private val shadedMaterial: Material
    private val flatMaterial: Material
    private val overlayMaterial: Material
    private val overlayInstance: MaterialInstance
    private val faceInstance: MaterialInstance
    private val edgeInstance: MaterialInstance

    private val grid: Lines
    private var model: Model? = null
    private var overlay: Lines? = null
    private var highlight: Lines? = null
    private val highlightInstance: MaterialInstance
    private var modelBounds: FloatArray? = null

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(surfaceView.context)
    private var swapChain: SwapChain? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var destroyed = false

    /** Frames still to draw; refilled by [requestRender]. */
    private var pendingFrames = 0
    private val stats = FrameStatsRecorder()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            val chain = swapChain
            if (pendingFrames <= 0 || chain == null || !uiHelper.isReadyToRender) {
                stats.endBurst()?.let { onFrameStats?.invoke(it) }
                return
            }
            applyCamera()
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
                pendingFrames--
                stats.onFrame(frameTimeNanos)?.let { onFrameStats?.invoke(it) }
            } else {
                stats.onSkipped()
            }
        }
    }

    init {
        Filament.init()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        filamentCamera = engine.createCamera(cameraEntity)

        val background = srgbToLinear(BACKGROUND_SRGB)
        skybox = Skybox.Builder().color(background[0], background[1], background[2], 1f).build(engine)
        scene.skybox = skybox
        // Colours are authored for display as-is: no filmic tone curve.
        colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)

        view.scene = scene
        view.camera = filamentCamera
        view.colorGrading = colorGrading
        view.isPostProcessingEnabled = true
        view.antiAliasing = View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
        // Drop internal resolution instead of frames when the GPU falls behind.
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = true
            minScale = 0.6f
            maxScale = 1.0f
            quality = View.QualityLevel.MEDIUM
        }

        vertexColorMaterial = loadMaterial("materials/vertex_color.filamat")
        shadedMaterial = loadMaterial("materials/cad_shaded.filamat")
        flatMaterial = loadMaterial("materials/flat_color.filamat")
        faceInstance = shadedMaterial.createInstance().apply {
            val c = srgbToLinear(FACE_SRGB)
            setParameter("baseColor", c[0], c[1], c[2])
            // Push faces back slightly so the edges drawn on top of them do not flicker.
            setPolygonOffset(1f, 1f)
        }
        edgeInstance = flatMaterial.createInstance().apply {
            val c = srgbToLinear(EDGE_SRGB)
            setParameter("color", c[0], c[1], c[2])
        }

        overlayMaterial = loadMaterial("materials/overlay_color.filamat")
        overlayInstance = overlayMaterial.createInstance().apply {
            val c = srgbToLinear(OVERLAY_SRGB)
            setParameter("color", c[0], c[1], c[2])
        }

        highlightInstance = flatMaterial.createInstance().apply {
            val c = srgbToLinear(HIGHLIGHT_SRGB)
            setParameter("color", c[0], c[1], c[2])
            // Same depth as the faces, which are pushed back: the highlight wins.
            setPolygonOffset(0f, 0f)
        }

        grid = createGrid()
        scene.addEntity(grid.entity)
        fitView()

        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    // ---- Scene ---------------------------------------------------------------------

    /** Replaces the displayed model; `null` clears it. Does not move the camera. */
    fun setModel(mesh: RenderMesh?) {
        if (destroyed) return
        model?.let { destroyModel(it) }
        model = null
        modelBounds = null
        if (mesh != null && mesh.vertexCount > 0 && mesh.indexCount > 0) {
            model = createModel(mesh, faceInstance, withEdges = true)
            modelBounds = mesh.bounds.copyOf()
        }
        updateSceneRadius()
        requestRender()
    }

    /**
     * Replaces the overlay lines (sketch geometry), drawn on top of everything.
     * [segments] holds xyz triples, two points per line segment; `null` or empty clears it.
     */
    fun setOverlayLines(segments: FloatArray?) {
        if (destroyed) return
        overlay?.let { destroyLines(it) }
        overlay = null
        if (segments != null && segments.size >= 6) {
            require(segments.size % 6 == 0) { "Overlay segments must be pairs of xyz points" }
            overlay = createOverlay(segments)
        }
        requestRender()
    }

    /** Replaces the highlighted triangles (selected face or sketch region); `null` clears. */
    fun setHighlight(mesh: RenderMesh?) {
        if (destroyed) return
        highlight?.let { destroyLines(it) }
        highlight = null
        if (mesh != null && mesh.vertexCount > 0 && mesh.indexCount > 0) {
            val part = createModel(mesh, highlightInstance, withEdges = false).faces
            highlight = part
        }
        requestRender()
    }

    /** Colour of the overlay lines, as 0xRRGGBB sRGB. */
    fun setOverlayColor(srgb: Int) {
        val c = srgbToLinear(srgb)
        overlayInstance.setParameter("color", c[0], c[1], c[2])
        requestRender()
    }

    /** Points the camera along a fixed direction (radians) and frames the scene. */
    fun setViewDirection(yaw: Double, pitch: Double) {
        camera.setView(yaw, pitch)
        fitView()
    }

    /** Frames the model, or the grid when there is no model. */
    fun fitView() {
        val aspect = if (width > 0 && height > 0) width.toDouble() / height else 1.0
        val b = modelBounds
        if (b == null) {
            camera.fit(0.0, 0.0, 0.0, GridGeometry.EXTENT * 0.6, aspect)
        } else {
            val dx = (b[3] - b[0]).toDouble()
            val dy = (b[4] - b[1]).toDouble()
            val dz = (b[5] - b[2]).toDouble()
            camera.fit(
                (b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0,
                sqrt(dx * dx + dy * dy + dz * dz) / 2.0, aspect,
            )
        }
        updateSceneRadius()
        requestRender()
    }

    /** Asks for the scene to be redrawn on the next display frames. */
    fun requestRender() {
        pendingFrames = FRAMES_PER_REQUEST
    }

    private fun updateSceneRadius() {
        var radius = GridGeometry.EXTENT * 1.5f
        modelBounds?.let { b ->
            val x = max(kotlin.math.abs(b[0]), kotlin.math.abs(b[3]))
            val y = max(kotlin.math.abs(b[1]), kotlin.math.abs(b[4]))
            val z = max(kotlin.math.abs(b[2]), kotlin.math.abs(b[5]))
            radius = max(radius, sqrt(x * x + y * y + z * z))
        }
        camera.sceneRadius = radius.toDouble()
    }

    private fun applyCamera() {
        if (width <= 0 || height <= 0) return
        filamentCamera.setProjection(
            Math.toDegrees(camera.verticalFov), width.toDouble() / height,
            camera.near, camera.far, Camera.Fov.VERTICAL,
        )
        filamentCamera.lookAt(
            camera.eyeX, camera.eyeY, camera.eyeZ,
            camera.targetX, camera.targetY, camera.targetZ,
            0.0, 0.0, 1.0,
        )
    }

    // ---- Lifecycle -----------------------------------------------------------------

    fun resume() {
        if (destroyed || running) return
        running = true
        requestRender()
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
        stats.endBurst()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        uiHelper.detach()
        model?.let { destroyModel(it) }
        model = null
        overlay?.let { destroyLines(it) }
        overlay = null
        highlight?.let { destroyLines(it) }
        highlight = null
        scene.remove(grid.entity)
        engine.destroyEntity(grid.entity)
        engine.destroyVertexBuffer(grid.vertices)
        engine.destroyIndexBuffer(grid.indices)
        EntityManager.get().destroy(grid.entity)
        engine.destroyMaterialInstance(faceInstance)
        engine.destroyMaterialInstance(edgeInstance)
        engine.destroyMaterialInstance(overlayInstance)
        engine.destroyMaterialInstance(highlightInstance)
        engine.destroyMaterial(overlayMaterial)
        engine.destroyMaterial(vertexColorMaterial)
        engine.destroyMaterial(shadedMaterial)
        engine.destroyMaterial(flatMaterial)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroySkybox(skybox)
        engine.destroyColorGrading(colorGrading)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(renderer, surfaceView.display)
            requestRender()
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                // Make sure the swap chain is gone before the surface is released.
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val first = this@ViewportRenderer.width == 0
            this@ViewportRenderer.width = width
            this@ViewportRenderer.height = height
            view.viewport = Viewport(0, 0, width, height)
            if (first) fitView() // the first fit ran before the aspect ratio was known
            requestRender()
        }
    }

    // ---- GPU resources -------------------------------------------------------------

    private class Lines(@Entity val entity: Int, val vertices: VertexBuffer, val indices: IndexBuffer)

    private class Model(val faces: Lines, val edges: Lines?)

    private fun createGrid(): Lines {
        val lines = GridGeometry.build()
        val vertices = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(lines.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, GridGeometry.STRIDE)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 12, GridGeometry.STRIDE)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
            .apply { setBufferAt(engine, 0, lines.vertices) }
        val indices = sequentialIndices(lines.vertexCount)
        val e = GridGeometry.EXTENT
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, e / 4, e, e, e / 4))
            .geometry(0, RenderableManager.PrimitiveType.LINES, vertices, indices, 0, lines.vertexCount)
            .material(0, vertexColorMaterial.defaultInstance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        return Lines(entity, vertices, indices)
    }

    private fun createModel(mesh: RenderMesh, material: MaterialInstance, withEdges: Boolean): Model {
        val b = mesh.bounds
        val box = Box(
            (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2,
            max((b[3] - b[0]) / 2, 1e-3f), max((b[4] - b[1]) / 2, 1e-3f), max((b[5] - b[2]) / 2, 1e-3f),
        )

        val faceVertices = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(mesh.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 1, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
            .apply {
                setBufferAt(engine, 0, mesh.positions.duplicate())
                setBufferAt(engine, 1, mesh.normals.duplicate())
            }
        val faceIndices = IndexBuffer.Builder()
            .indexCount(mesh.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
            .apply { setBuffer(engine, mesh.indices.duplicate()) }
        val faceEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(box)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, faceVertices, faceIndices, 0, mesh.indexCount)
            .material(0, material)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, faceEntity)
        scene.addEntity(faceEntity)
        val faces = Lines(faceEntity, faceVertices, faceIndices)

        if (!withEdges || mesh.edgeVertexCount == 0) return Model(faces, null)

        val edgeVertices = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(mesh.edgeVertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
            .apply { setBufferAt(engine, 0, mesh.edgeSegments.duplicate()) }
        val edgeIndices = sequentialIndices(mesh.edgeVertexCount)
        val edgeEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(box)
            .geometry(0, RenderableManager.PrimitiveType.LINES, edgeVertices, edgeIndices, 0, mesh.edgeVertexCount)
            .material(0, edgeInstance)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, edgeEntity)
        scene.addEntity(edgeEntity)
        return Model(faces, Lines(edgeEntity, edgeVertices, edgeIndices))
    }

    private fun createOverlay(segments: FloatArray): Lines {
        val count = segments.size / 3
        val data = ByteBuffer.allocateDirect(segments.size * 4).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(segments)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in segments.indices step 3) {
            minX = minOf(minX, segments[i]); maxX = maxOf(maxX, segments[i])
            minY = minOf(minY, segments[i + 1]); maxY = maxOf(maxY, segments[i + 1])
            minZ = minOf(minZ, segments[i + 2]); maxZ = maxOf(maxZ, segments[i + 2])
        }
        val vertices = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(count)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
            .apply { setBufferAt(engine, 0, data) }
        val indices = sequentialIndices(count)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(
                Box(
                    (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                    max((maxX - minX) / 2, 1e-3f), max((maxY - minY) / 2, 1e-3f), max((maxZ - minZ) / 2, 1e-3f),
                ),
            )
            .geometry(0, RenderableManager.PrimitiveType.LINES, vertices, indices, 0, count)
            .material(0, overlayInstance)
            .priority(7) // drawn last, on top of the model
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        return Lines(entity, vertices, indices)
    }

    private fun destroyModel(m: Model) {
        for (part in listOfNotNull(m.faces, m.edges)) destroyLines(part)
    }

    private fun destroyLines(part: Lines) {
        scene.remove(part.entity)
        engine.destroyEntity(part.entity)
        engine.destroyVertexBuffer(part.vertices)
        engine.destroyIndexBuffer(part.indices)
        EntityManager.get().destroy(part.entity)
    }

    /** Index buffer 0, 1, 2, … for non-indexed line lists (Filament always needs indices). */
    private fun sequentialIndices(count: Int): IndexBuffer {
        val data = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until count) data.putInt(i)
        data.flip()
        return IndexBuffer.Builder()
            .indexCount(count)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
            .apply { setBuffer(engine, data) }
    }

    private fun loadMaterial(assetPath: String): Material {
        // AssetManager.open works whether or not the asset is stored compressed in the APK;
        // openFd does not, and library modules cannot control the app's compression settings.
        val bytes = surfaceView.context.assets.open(assetPath).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    private companion object {
        const val BACKGROUND_SRGB = 0x1B1F26
        const val FACE_SRGB = 0xB9C4D6
        const val EDGE_SRGB = 0x11151B
        const val OVERLAY_SRGB = 0x5AA2FF
        const val HIGHLIGHT_SRGB = 0x3E8BFF

        /** A few extra frames after each request so the last state always reaches the screen. */
        const val FRAMES_PER_REQUEST = 3
    }
}

/** Turns vsync timestamps of drawn frames into [FrameStats] for bursts of activity. */
internal class FrameStatsRecorder(private val reportEvery: Int = 30) {
    private var lastNanos = 0L
    private var sumNanos = 0L
    private var worstNanos = 0L
    private var frames = 0
    private var skipped = 0

    /** Records a drawn frame; returns stats once [reportEvery] intervals were collected. */
    fun onFrame(frameTimeNanos: Long): FrameStats? {
        if (lastNanos != 0L) {
            val interval = frameTimeNanos - lastNanos
            if (interval > 0) {
                sumNanos += interval
                worstNanos = max(worstNanos, interval)
                frames++
            }
        }
        lastNanos = frameTimeNanos
        return if (frames >= reportEvery) flush() else null
    }

    fun onSkipped() {
        skipped++
    }

    /**
     * Ends the current run of consecutive frames. Returns its stats if it was long enough
     * to mean something; a handful of redraw frames is discarded.
     */
    fun endBurst(): FrameStats? {
        lastNanos = 0L
        if (frames >= MIN_BURST_FRAMES) return flush()
        if (frames > 0) flush()
        return null
    }

    private companion object {
        const val MIN_BURST_FRAMES = 10
    }

    private fun flush(): FrameStats {
        val result = FrameStats(
            averageMillis = sumNanos / frames / 1e6f,
            worstMillis = worstNanos / 1e6f,
            frames = frames,
            skipped = skipped,
        )
        sumNanos = 0L
        worstNanos = 0L
        frames = 0
        skipped = 0
        return result
    }
}
