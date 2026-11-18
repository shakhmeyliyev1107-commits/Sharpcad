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
import com.sharpcad.ui.workspace.ItemsPanel
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
    val settings = remember { AppSettings(context, viewport) }
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
    val actions = remember(state, openPicker, exportPicker, state.recentProjects) {
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
            onViewInAr = { (context as? android.app.Activity)?.let(state::viewInAr) },
            recentProjects = state.recentProjects,
            onOpenProject = state::openProject,
            onBenchmark = state::runBenchmark,
        )
    }

    // Reopen the last project; the sample part on first launch.
    LaunchedEffect(Unit) { state.start() }

    // Side panels share the screen: opening one closes the others so they never stack.
    fun closePanels() {
        if (model.materialsOpen) model.toggleMaterials()
        if (model.itemsOpen) model.toggleItems()
        if (model.historyOpen) model.toggleHistory()
        if (model.sectionOn) model.toggleSection()
        if (settings.open) settings.toggle()
    }
    fun exclusive(isOpen: Boolean, toggle: () -> Unit): () -> Unit = { if (!isOpen) closePanels(); toggle() }
    val anyPanel = model.materialsOpen || model.itemsOpen || model.historyOpen || model.sectionOn || settings.open
    Box(Modifier.fillMaxSize()) {
    Workspace(
        projectName = state.projectName,
        actions = actions,
        status = if (anyPanel) "" else state.status,
        busy = state.busy,
        sketchActive = sketch.active,
        onSketchToggle = sketch::toggle,
        canUndo = model.state.canUndo,
        canRedo = model.state.canRedo,
        historyActive = model.historyOpen,
        onHistoryToggle = exclusive(model.historyOpen, model::toggleHistory),
        materialsActive = model.materialsOpen,
        onMaterialsToggle = exclusive(model.materialsOpen, model::toggleMaterials),
        onDisplayMode = model::cycleDisplayMode,
        onDrawings = { model.openDrawing(state.projectName) },
        onAppearance = exclusive(settings.open, settings::toggle),
        onSection = exclusive(model.sectionOn, model::toggleSection),
        onItems = exclusive(model.itemsOpen, model::toggleItems),
        itemsActive = model.itemsOpen,
        sectionActive = model.sectionOn,
        appearanceActive = settings.open,
        toolsOnRight = settings.toolsOnRight,
        showLabels = settings.showLabels,
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
                model.sectionPanel?.let { panel ->
                    ParameterPanel(
                        title = panel.title, fields = panel.fields, onField = { _, v -> model.changeSectionOffset(v) },
                        choices = panel.choices, onChoice = { _, option -> model.changeSectionPlane(option) },
                        actions = panel.actions, message = panel.message,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
                    )
                }
                if (settings.open) {
                    ParameterPanel(
                        title = "Appearance", fields = emptyList(), onField = { _, _ -> },
                        choices = settings.choices, onChoice = settings::choose,
                        actions = settings.actions, message = "",
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
                    )
                }
                state.benchmarkReport?.let { report ->
                    ParameterPanel(
                        title = "Benchmark", fields = emptyList(), onField = { _, _ -> }, choices = emptyList(), onChoice = { _, _ -> },
                        actions = listOf(com.sharpcad.ui.workspace.PanelAction("Close", accent = true) { state.benchmarkReport = null }),
                        message = report,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
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
            if (model.itemsOpen) {
                ItemsPanel(
                    items = model.itemRows,
                    onSelect = model::selectBody,
                    onRename = model::renameBody,
                    onToggleVisible = model::toggleVisible,
                    onDelete = model::deleteBody,
                    modifier = if (settings.toolsOnRight) Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 64.dp)
                    else Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 64.dp),
                )
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
                    // Beside the tool rail.
                    modifier = if (settings.toolsOnRight) Modifier.align(Alignment.TopEnd).padding(end = 64.dp)
                    else Modifier.align(Alignment.TopStart).padding(start = 64.dp),
                )
            }
            if (model.historyOpen) {
                HistoryPanel(
                    items = model.historyItems,
                    selectedId = model.editingId,
                    onSelect = model::edit,
                    // Beside the settings rail.
                    modifier = if (settings.toolsOnRight) Modifier.align(Alignment.TopStart).padding(start = 64.dp)
                    else Modifier.align(Alignment.TopEnd).padding(end = 64.dp),
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
