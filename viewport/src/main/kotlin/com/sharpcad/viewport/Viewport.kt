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

    private var renderer: ViewportRenderer? = null
    private var model: RenderMesh? = null

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

    internal fun attach(r: ViewportRenderer) {
        renderer = r
        r.onFrameStats = { stats -> onFrameStats?.invoke(stats) }
        r.setModel(model)
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
                    renderer.camera.orbit(dx, dy, renderer.height)
                    renderer.requestRender()
                }

                override fun onZoom(factor: Float) {
                    renderer.camera.zoom(factor)
                    renderer.requestRender()
                }

                override fun onFit() = renderer.fitView()
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
