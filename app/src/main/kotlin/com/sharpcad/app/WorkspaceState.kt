package com.sharpcad.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.kernel.KernelException
import com.sharpcad.viewport.FrameStats
import com.sharpcad.viewport.ViewportController
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state of the workspace and the actions that change it. Main thread only;
 * file and kernel work is moved to worker threads internally.
 */
class WorkspaceState(
    context: Context,
    private val scope: CoroutineScope,
    val viewport: ViewportController,
) {
    private val appContext = context.applicationContext

    var projectName by mutableStateOf("Untitled")
        private set
    var busy by mutableStateOf(false)
        private set
    private var modelLine by mutableStateOf("")
    private var frameLine by mutableStateOf("")
    private var errorLine by mutableStateOf("")

    /** Readout for the bottom of the workspace. */
    val status: String
        get() = listOf(errorLine, modelLine, frameLine).filter { it.isNotEmpty() }.joinToString("\n")

    init {
        viewport.onFrameStats = ::onFrameStats
    }

    fun openSample() = load(SAMPLE_NAME) { appContext.assets.open("samples/$SAMPLE_NAME") }

    fun importStep(uri: Uri) = load(displayName(uri)) {
        appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    }

    fun fitView() = viewport.fitView()

    fun onViewportError(error: Throwable) {
        errorLine = "Viewport error: $error"
    }

    private fun load(name: String, open: () -> InputStream) {
        if (busy) return
        busy = true
        errorLine = ""
        modelLine = "Loading $name…"
        scope.launch {
            try {
                val model = withContext(Dispatchers.Default) {
                    // The kernel reads from a path, so stage the stream in the cache first.
                    val staged = File(appContext.cacheDir, "import.step")
                    try {
                        open().use { input -> staged.outputStream().use { input.copyTo(it) } }
                        ModelLoader.loadStep(staged)
                    } finally {
                        staged.delete()
                    }
                }
                viewport.setModel(model.mesh)
                projectName = name.substringBeforeLast('.')
                frameLine = ""
                modelLine = String.format(
                    Locale.US, "%s · %,d triangles · import %d ms · mesh %d ms",
                    name, model.triangles, model.importMillis, model.tessellateMillis,
                )
            } catch (e: KernelException) {
                fail("Could not read $name as STEP (error ${e.code})")
            } catch (e: IOException) {
                fail("Could not open $name: ${e.message}")
            } catch (e: LinkageError) {
                fail("Geometry kernel failed to load: ${e.message}")
            } catch (e: OutOfMemoryError) {
                fail("$name is too large for this device")
            } finally {
                busy = false
            }
        }
    }

    private fun fail(message: String) {
        errorLine = message
        modelLine = ""
    }

    private fun onFrameStats(stats: FrameStats) {
        frameLine = String.format(
            Locale.US, "frame %.1f ms avg · %.1f ms worst · %d frames%s",
            stats.averageMillis, stats.worstMillis, stats.frames,
            if (stats.skipped > 0) " · ${stats.skipped} skipped" else "",
        )
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "model.step"
    }

    private companion object {
        const val SAMPLE_NAME = "bracket.step"
    }
}
