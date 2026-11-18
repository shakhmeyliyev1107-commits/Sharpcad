package com.sharpcad.viewport

import android.annotation.SuppressLint
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Handle through which the rest of the app drives a [Viewport]. Main thread only.
 *
 * Can be created before the viewport exists: the model set here is shown as soon as the
 * renderer starts, and survives the renderer being recreated.
 */
class ViewportController {
    /** Receives frame pacing a few times per second while the view is animating. */
    var onFrameStats: ((FrameStats) -> Unit)? = null

    /**
     * When set, receives one-finger touches before navigation does. Returning `true` from
     * [TouchPhase.DOWN] claims that gesture for drawing; otherwise it pans as usual.
     */
    var onSingleTouch: ((phase: TouchPhase, x: Float, y: Float) -> Boolean)? = null

    /** Called for a single tap on the view (selection), in pixels. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    private var renderer: ViewportRenderer? = null
    private var model: RenderMesh? = null
    private var overlay: FloatArray? = null
    private var highlight: RenderMesh? = null
    private var overlayColor: Int = 0x5AA2FF
    private var displayMode = DisplayMode.SHADED_EDGES
    private var background = 0x1B1F26
    private var section = floatArrayOf(0f, 0f, 0f, 0f)

    /** Shows [mesh] (or clears the model when `null`) and, if [fit], frames it. */
    fun setModel(mesh: RenderMesh?, fit: Boolean = true) {
        model = mesh
        renderer?.let {
            it.setModel(mesh)
            if (fit) it.fitView()
        }
    }

    /** Frames the model, or the grid when there is none. */
    fun fitView() {
        renderer?.fitView()
    }

    /**
     * Shows line segments on top of the scene (sketch geometry): xyz triples in model
     * millimetres, two points per segment. `null` clears them.
     */
    fun setOverlayLines(segments: FloatArray?) {
        overlay = segments
        renderer?.setOverlayLines(segments)
    }

    /** Cuts the model with a plane (normal, offset); a zero normal shows everything. */
    fun setSection(nx: Float, ny: Float, nz: Float, offset: Float) {
        section = floatArrayOf(nx, ny, nz, offset)
        renderer?.setSection(nx, ny, nz, offset)
    }

    /** Background colour as 0xRRGGBB (follows the app theme). */
    fun setBackground(srgb: Int) {
        background = srgb
        renderer?.setBackground(srgb)
    }

    /** Benchmark: spins the camera once over [frames] frames, then reports frame pacing. */
    fun runAutoOrbit(frames: Int, onDone: (FrameStats?) -> Unit) {
        val r = renderer
        if (r == null) onDone(null) else r.runAutoOrbit(frames, onDone)
    }

    /** Shows faces, edges or both. */
    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
        renderer?.setDisplayMode(mode)
    }

    /** Colour of the overlay lines as 0xRRGGBB (sketch state: blue under-defined, green fully defined). */
    fun setOverlayColor(srgb: Int) {
        overlayColor = srgb
        renderer?.setOverlayColor(srgb)
    }

    /** Shows [mesh] in the accent colour on top of the model (selection); `null` clears it. */
    fun setHighlight(mesh: RenderMesh?) {
        highlight = mesh
        renderer?.setHighlight(mesh)
    }

    /** Looks along a fixed direction (radians: [yaw] around +Z, [pitch] above XY) and fits. */
    /** The current frame as a bitmap, delivered on the main thread; null before layout or on failure. */
    fun capture(done: (android.graphics.Bitmap?) -> Unit) {
        renderer?.capture(done) ?: done(null)
    }

    fun setViewDirection(yaw: Double, pitch: Double) {
        renderer?.setViewDirection(yaw, pitch)
    }

    /**
     * The view ray through a screen pixel: origin xyz then direction xyz, in model space.
     * `null` until the viewport has a size.
     */
    fun screenRay(x: Float, y: Float): DoubleArray? =
        renderer?.let { it.camera.ray(x, y, it.width, it.height) }

    /** Model-space size of one pixel at the camera target's depth; 0 before layout. */
    fun millimetresPerPixel(): Double =
        renderer?.let { it.camera.millimetresPerPixel(it.height) } ?: 0.0

    internal fun attach(r: ViewportRenderer) {
        renderer = r
        r.onFrameStats = { stats -> onFrameStats?.invoke(stats) }
        r.setModel(model)
        r.setOverlayLines(overlay)
        r.setHighlight(highlight)
        r.setOverlayColor(overlayColor)
        r.setDisplayMode(displayMode)
        r.setBackground(background)
        r.setSection(section[0], section[1], section[2], section[3])
        r.fitView()
    }

    internal fun detach() {
        renderer?.onFrameStats = null
        renderer = null
    }
}

/**
 * Full-bleed 3D viewport with touch navigation.
 *
 * Hosts a [SurfaceView] rendered by Filament. Frames are driven by Choreographer inside
 * [ViewportRenderer]; recomposition of this function never triggers or blocks rendering.
 * If the renderer cannot start, [onError] is called once and the viewport stays blank.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun Viewport(
    controller: ViewportController,
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val surfaceView = remember { SurfaceView(context) }
    // A renderer that fails to start must not take the whole app down: report it instead.
    val renderer = remember {
        runCatching { ViewportRenderer(surfaceView) }.onFailure(onError).getOrNull()
    }

    DisposableEffect(lifecycle, renderer, controller) {
        if (renderer == null) return@DisposableEffect onDispose { }
        controller.attach(renderer)

        val gestures = NavigationGestures(
            context,
            object : NavigationGestures.Listener {
                override fun onPan(dx: Float, dy: Float) {
                    renderer.camera.pan(dx, dy, renderer.height)
                    renderer.requestRender()
                }

                override fun onOrbit(dx: Float, dy: Float) {
                    renderer.camera.orbit(dx, dy, minOf(renderer.width, renderer.height))
                    renderer.requestRender()
                }

                override fun onZoom(factor: Float) {
                    renderer.camera.zoom(factor)
                    renderer.requestRender()
                }

                override fun onFit() = renderer.fitView()

                override fun onTap(x: Float, y: Float) {
                    controller.onTap?.invoke(x, y)
                }

                override fun onSingleTouch(phase: TouchPhase, x: Float, y: Float): Boolean =
                    controller.onSingleTouch?.invoke(phase, x, y) ?: false
            },
        )
        surfaceView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.resume()
                Lifecycle.Event.ON_PAUSE -> renderer.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) renderer.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            surfaceView.setOnTouchListener(null)
            controller.detach()
            renderer.destroy()
        }
    }

    AndroidView(factory = { surfaceView }, modifier = modifier)
}
