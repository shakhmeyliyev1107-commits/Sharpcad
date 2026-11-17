package com.sharpcad.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.document.ImportFeature
import com.sharpcad.document.MeshFormat
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
    private val store = com.sharpcad.document.ProjectStore(File(appContext.filesDir, "projects"))
    private var projectId: String? = null
    private var saveJob: kotlinx.coroutines.Job? = null

    /** Saved projects, newest first: (id, name). */
    var recentProjects by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

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
        model.onChanged = ::scheduleSave
    }

    /** Opens the most recent project, or the sample part on first launch. */
    fun start() {
        scope.launch {
            val recent = withContext(Dispatchers.IO) { store.list() }
            recentProjects = recent.map { it.id to it.name }
            val last = recent.firstOrNull()
            if (last != null) openProject(last.id) else openSample()
        }
    }

    fun openProject(id: String) {
        if (busy) return
        scope.launch {
            val data = withContext(Dispatchers.IO) { store.load(id) }
            if (data == null) {
                errorLine = "Could not open the project"
                return@launch
            }
            saveJob?.cancel()
            projectId = id
            projectName = data.name
            errorLine = ""
            model.restoreMaterials(data.materials)
            model.restoreItems(data.bodyNames, data.hidden)
            model.reset(data.features)
        }
    }

    fun newModel() {
        saveJob?.cancel()
        projectId = store.newId()
        projectName = "Untitled"
        errorLine = ""
        model.restoreMaterials(emptyMap())
        model.reset(emptyList())
    }

    /** Saves shortly after the last change, so a burst of edits is written once. */
    private fun scheduleSave() {
        val id = projectId ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(SAVE_DELAY_MS)
            val data = com.sharpcad.document.ProjectData(
                name = projectName, features = model.state.features, materials = model.materialsSnapshot(),
                bodyNames = model.namesSnapshot(), hidden = model.hiddenSnapshot(),
            )
            try {
                withContext(Dispatchers.IO) { store.save(id, data) }
                recentProjects = withContext(Dispatchers.IO) { store.list() }.map { it.id to it.name }
            } catch (e: IOException) {
                errorLine = "Autosave failed: ${e.message}"
            } catch (e: IllegalStateException) {
                errorLine = "Autosave failed: ${e.message}"
            }
        }
    }

    fun openSample() = load(SAMPLE_NAME) { appContext.assets.open("samples/$SAMPLE_NAME") }

    fun importStep(uri: Uri) = load(displayName(uri)) {
        appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    }

    /**
     * Writes every body to [uri] (from the system "create document" picker) in the format
     * named by [extension]: step, igs, stl, obj or 3mf.
     */
    fun export(uri: Uri, extension: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val sheet = model.drawing
                if (sheet != null && extension in setOf("svg", "dxf", "pdf")) {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            when (extension) {
                                "pdf" -> SheetPainter.writePdf(sheet, out)
                                "svg" -> out.write(com.sharpcad.document.DrawingExport.svg(sheet).toByteArray())
                                else -> out.write(com.sharpcad.document.DrawingExport.dxf(sheet).toByteArray())
                            }
                        } ?: throw IOException("Cannot write $uri")
                    }
                    errorLine = ""
                    return@launch
                }
                val staged = File(appContext.cacheDir, "export.$extension")
                val doc = model.document
                val written = when (extension) {
                    "igs" -> doc.exportIges(staged.path)
                    "stl" -> doc.exportMesh(MeshFormat.STL, staged.path)
                    "obj" -> doc.exportMesh(MeshFormat.OBJ, staged.path)
                    "3mf" -> doc.exportMesh(MeshFormat.THREE_MF, staged.path)
                    "glb" -> doc.exportGlb(staged.path, model::materialOf)
                    else -> doc.exportStep(staged.path)
                }
                if (!written) {
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

    /** Last benchmark report, shown until dismissed. */
    var benchmarkReport by mutableStateOf<String?>(null)

    /** Runs the benchmark in a new, unsaved model and keeps a copy of the report on disk. */
    fun runBenchmark() {
        if (busy) return
        busy = true
        saveJob?.cancel()
        projectId = null // the benchmark model is not saved
        projectName = "Benchmark"
        scope.launch {
            try {
                val report = Benchmark(model, viewport, appContext.cacheDir).run()
                benchmarkReport = report
                withContext(Dispatchers.IO) { File(appContext.filesDir, "benchmark-${System.currentTimeMillis()}.txt").writeText(report) }
            } catch (e: com.sharpcad.kernel.KernelException) {
                errorLine = "Benchmark failed (kernel error ${e.code})"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Shows the model in augmented reality: writes a GLB and hands it to Google's AR
     * viewer (part of Google Play Services for AR), or to any installed 3D viewer.
     */
    fun viewInAr(activity: android.app.Activity) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "model.glb")
                if (!model.document.exportGlb(file.path, model::materialOf)) {
                    errorLine = "Nothing to show in AR yet"
                    return@launch
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
                errorLine = if (ArLauncher.launch(activity, uri, projectName)) "" else
                    "No AR viewer found: install Google Play Services for AR, or export GLB from the File menu"
            } catch (e: IOException) {
                errorLine = "AR export failed: ${e.message}"
            } catch (e: com.sharpcad.kernel.KernelException) {
                errorLine = "AR export failed (kernel error ${e.code})"
            } finally {
                busy = false
            }
        }
    }

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
                saveJob?.cancel()
                projectId = store.newId()
                projectName = name.substringBeforeLast('.')
                frameLine = ""
                model.restoreMaterials(emptyMap())
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
        const val SAVE_DELAY_MS = 800L
        const val SAMPLE_NAME = "bracket.step"
    }
}
