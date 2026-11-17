package com.sharpcad.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.document.ImportFeature
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
 * File handling and the status readout of the workspace. Main thread only; file work
 * moves to worker threads internally. Geometry lives in [model].
 */
class WorkspaceState(
    context: Context,
    private val scope: CoroutineScope,
    val viewport: ViewportController,
    val model: ModelController,
) {
    private val appContext = context.applicationContext

    var projectName by mutableStateOf("Untitled")
        private set
    var busy by mutableStateOf(false)
        private set
    private var frameLine by mutableStateOf("")
    private var errorLine by mutableStateOf("")

    /** Readout for the bottom of the workspace. */
    val status: String
        get() {
            val importError = model.state.features.filterIsInstance<ImportFeature>()
                .firstNotNullOfOrNull { model.state.errors[it.id] }
                ?.let { "Could not read the file as STEP: $it" }
            return listOfNotNull(errorLine.ifEmpty { null }, importError, model.statusLine, frameLine.ifEmpty { null })
                .joinToString("\n")
        }

    init {
        viewport.onFrameStats = ::onFrameStats
    }

    fun newModel() {
        projectName = "Untitled"
        errorLine = ""
        model.reset(emptyList())
    }

    fun openSample() = load(SAMPLE_NAME) { appContext.assets.open("samples/$SAMPLE_NAME") }

    fun importStep(uri: Uri) = load(displayName(uri)) {
        appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    }

    /** Writes every body to one STEP file at [uri] (from the system "create document" picker). */
    fun exportStep(uri: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val staged = File(appContext.cacheDir, "export.step")
                if (!model.document.exportStep(staged.path)) {
                    errorLine = "Nothing to export yet"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out -> staged.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Cannot write $uri")
                    staged.delete()
                }
                errorLine = ""
            } catch (e: IOException) {
                errorLine = "Export failed: ${e.message}"
            } catch (e: com.sharpcad.kernel.KernelException) {
                errorLine = "Export failed (kernel error ${e.code})"
            } finally {
                busy = false
            }
        }
    }

    fun fitView() = viewport.fitView()

    fun onViewportError(error: Throwable) {
        errorLine = "Viewport error: $error"
    }

    /**
     * Copies the file into app storage (the import feature replays from there on every
     * rebuild) and starts a new model with it.
     */
    private fun load(name: String, open: () -> InputStream) {
        if (busy) return
        busy = true
        errorLine = ""
        scope.launch {
            try {
                val stored = withContext(Dispatchers.IO) {
                    val dir = File(appContext.filesDir, "imports").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(dir, "${System.currentTimeMillis()}-$safe")
                    open().use { input -> file.outputStream().use { input.copyTo(it) } }
                    file
                }
                projectName = name.substringBeforeLast('.')
                frameLine = ""
                model.reset(listOf(ImportFeature(1, stored.path, name.substringBeforeLast('.'))))
            } catch (e: IOException) {
                errorLine = "Could not open $name: ${e.message}"
            } finally {
                busy = false
            }
        }
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
