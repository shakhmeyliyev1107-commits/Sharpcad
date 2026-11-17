package com.sharpcad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sharpcad.document.Document
import com.sharpcad.ui.designsystem.SharpCadTheme
import com.sharpcad.document.MaterialLibrary
import com.sharpcad.ui.workspace.HistoryPanel
import com.sharpcad.ui.workspace.MaterialPanel
import com.sharpcad.ui.workspace.MaterialSwatch
import com.sharpcad.ui.workspace.ParameterPanel
import com.sharpcad.ui.workspace.SketchControls
import com.sharpcad.ui.workspace.Workspace
import com.sharpcad.ui.workspace.WorkspaceActions
import com.sharpcad.viewport.Viewport
import com.sharpcad.viewport.ViewportController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SharpCadTheme {
                WorkspaceScreen()
            }
        }
    }
}

@Composable
private fun WorkspaceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewport = remember { ViewportController() }
    val sketch = remember { SketchSession(viewport) }
    val document = remember { Document() }
    val model = remember { ModelController(scope, viewport, document, sketch) }
    val state = remember { WorkspaceState(context, scope, viewport, model) }
    DisposableEffect(Unit) {
        // Taps select sketch regions and faces; drawing tools claim one-finger drags first.
        viewport.onTap = { x, y -> if (sketch.tool == null) model.onTap(x, y) }
        onDispose {
            viewport.onTap = null
            sketch.dispose()
            document.close()
        }
    }
    LaunchedEffect(sketch.revision) { model.onSketchChanged(sketch.revision) }

    // STEP files have no reliable MIME type on Android, so the pickers accept all files.
    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importStep(uri)
    }
    // The format chosen in the menu, remembered until the picker returns.
    val exportFormat = remember { androidx.compose.runtime.mutableStateOf("step") }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) state.export(uri, exportFormat.value)
    }
    val actions = remember(state, openPicker, exportPicker) {
        WorkspaceActions(
            onImportStep = { openPicker.launch(arrayOf("*/*")) },
            onOpenSample = state::openSample,
            onFitView = state::fitView,
            onNew = state::newModel,
            onExport = { ext ->
                exportFormat.value = ext
                exportPicker.launch("${state.projectName}.$ext")
            },
            onUndo = model::undo,
            onRedo = model::redo,
        )
    }

    // Start with something to look at.
    LaunchedEffect(Unit) { state.openSample() }

    Box(Modifier.fillMaxSize()) {
    Workspace(
        projectName = state.projectName,
        actions = actions,
        status = state.status,
        busy = state.busy,
        sketchActive = sketch.active,
        onSketchToggle = sketch::toggle,
        canUndo = model.state.canUndo,
        canRedo = model.state.canRedo,
        historyActive = model.historyOpen,
        onHistoryToggle = model::toggleHistory,
        materialsActive = model.materialsOpen,
        onMaterialsToggle = model::toggleMaterials,
        onDisplayMode = model::cycleDisplayMode,
        onDrawings = { model.openDrawing(state.projectName) },
        overlay = {
            // Between the rails, clear of both.
            Column(
                Modifier.align(Alignment.TopCenter).padding(horizontal = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (sketch.active) {
                    SketchControls(
                        planes = SketchSession.PLANES.map { it.name },
                        selectedPlane = sketch.plane.name,
                        planesEnabled = true,
                        onPlane = sketch::selectPlane,
                        tools = SketchTool.entries.map { it.label },
                        selectedTool = sketch.tool?.label,
                        onTool = sketch::selectTool,
                        onClear = sketch::clear,
                        onDone = sketch::exit,
                        dimensions = sketch.dimensions,
                        onDimension = sketch::setDimension,
                        message = sketch.message,
                    )
                }
                model.panel?.let { panel ->
                    ParameterPanel(
                        title = panel.title,
                        fields = panel.fields,
                        onField = model::setField,
                        choices = panel.choices,
                        onChoice = model::setChoice,
                        actions = panel.actions,
                        message = panel.message,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
                    )
                }
            }
            if (model.materialsOpen) {
                MaterialPanel(
                    categories = MaterialLibrary.categories,
                    selectedCategory = model.materialCategory,
                    onCategory = { model.materialCategory = it },
                    swatches = MaterialLibrary.presets.filter { it.category == model.materialCategory }
                        .map { MaterialSwatch(it.id, it.name, it.color, it.metallic, it.roughness) },
                    currentId = model.currentMaterialId,
                    onPick = model::applyMaterial,
                    subtitle = model.materialTarget,
                    // Right of the left rail.
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp),
                )
            }
            if (model.historyOpen) {
                HistoryPanel(
                    items = model.historyItems,
                    selectedId = model.editingId,
                    onSelect = model::edit,
                    // Left of the right rail.
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 64.dp),
                )
            }
        },
    ) {
        Viewport(viewport, Modifier.fillMaxSize(), onError = state::onViewportError)
    }
    model.drawing?.let { sheet ->
        DrawingScreen(sheet, onExport = actions.onExport, onClose = model::closeDrawing)
    }
    }
}
