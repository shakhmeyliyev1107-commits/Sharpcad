package com.sharpcad.viewport

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
 * Full-bleed 3D viewport.
 *
 * Hosts a [SurfaceView] rendered by Filament. Frames are driven by Choreographer inside
 * [ViewportRenderer]; recomposition of this function never triggers or blocks rendering.
 * If the renderer cannot start, [onError] is called once and the viewport stays blank.
 */
@Composable
fun Viewport(modifier: Modifier = Modifier, onError: (Throwable) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val surfaceView = remember { SurfaceView(context) }
    // A renderer that fails to start must not take the whole app down: report it instead.
    val renderer = remember {
        runCatching { ViewportRenderer(surfaceView) }.onFailure(onError).getOrNull()
    }

    DisposableEffect(lifecycle, renderer) {
        if (renderer == null) return@DisposableEffect onDispose { }
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
            renderer.destroy()
        }
    }

    AndroidView(factory = { surfaceView }, modifier = modifier)
}
