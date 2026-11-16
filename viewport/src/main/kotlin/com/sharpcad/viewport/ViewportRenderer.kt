package com.sharpcad.viewport

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels

/**
 * Owns the Filament engine and draws the scene into a [SurfaceView].
 *
 * All methods must be called on the main thread (Filament's Android API is
 * single-threaded on the caller side; the GPU work happens on Filament's own thread).
 * Phase 0 content is one hard-coded triangle.
 */
internal class ViewportRenderer(private val surfaceView: SurfaceView) {

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    @Entity private val cameraEntity: Int

    private val material: Material
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    @Entity private val triangle: Int

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(surfaceView.context)
    private var swapChain: SwapChain? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var destroyed = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            val chain = swapChain ?: return
            if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
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
        camera = engine.createCamera(cameraEntity)

        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = BACKGROUND
        }
        view.scene = scene
        view.camera = camera
        view.isPostProcessingEnabled = false

        material = loadMaterial("materials/vertex_color.filamat")
        vertexBuffer = createTriangleVertices()
        indexBuffer = createTriangleIndices()
        triangle = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(com.google.android.filament.Box(0f, 0f, 0f, 1f, 1f, 0.01f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 3)
            .material(0, material.defaultInstance)
            .culling(false)
            .build(engine, triangle)
        scene.addEntity(triangle)

        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    fun resume() {
        if (destroyed || running) return
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        uiHelper.detach()
        engine.destroyEntity(triangle)
        engine.destroyRenderer(renderer)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyMaterial(material)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(triangle)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(renderer, surfaceView.display)
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
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(
                Camera.Projection.ORTHO,
                -aspect * HALF_HEIGHT, aspect * HALF_HEIGHT, -HALF_HEIGHT, HALF_HEIGHT,
                0.0, 10.0,
            )
            view.viewport = Viewport(0, 0, width, height)
        }
    }

    private fun loadMaterial(assetPath: String): Material {
        val buffer = surfaceView.context.assets.openFd(assetPath).use { fd ->
            fd.createInputStream().use { input ->
                val bytes = ByteBuffer.allocateDirect(fd.length.toInt())
                val channel = Channels.newChannel(input)
                while (bytes.hasRemaining() && channel.read(bytes) >= 0) Unit
                bytes.flip() as ByteBuffer
            }
        }
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    private fun createTriangleVertices(): VertexBuffer {
        // x, y, z (float) + ABGR colour (4 normalised bytes) = 16 bytes per vertex.
        val stride = 16
        val data = ByteBuffer.allocateDirect(3 * stride).order(ByteOrder.nativeOrder())
        fun vertex(x: Float, y: Float, argb: Int) {
            data.putFloat(x).putFloat(y).putFloat(0f)
            data.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte())
                .put((argb ushr 24).toByte())
        }
        vertex(0.0f, 0.9f, 0xFF4C9BFF.toInt())
        vertex(-0.8f, -0.6f, 0xFF2ED3B7.toInt())
        vertex(0.8f, -0.6f, 0xFFFFB454.toInt())
        data.flip()

        return VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 12, stride)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
            .apply { setBufferAt(engine, 0, data) }
    }

    private fun createTriangleIndices(): IndexBuffer {
        val data = ByteBuffer.allocateDirect(3 * 2).order(ByteOrder.nativeOrder())
        data.putShort(0).putShort(1).putShort(2).flip()
        return IndexBuffer.Builder()
            .indexCount(3)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .apply { setBuffer(engine, data) }
    }

    private companion object {
        const val HALF_HEIGHT = 1.0
        val BACKGROUND = doubleArrayOf(0.106, 0.122, 0.149, 1.0) // #1B1F26
    }
}
