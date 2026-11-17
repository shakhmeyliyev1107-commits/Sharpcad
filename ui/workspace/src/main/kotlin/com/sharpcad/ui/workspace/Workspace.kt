package com.sharpcad.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.SharpCadDimens

/** What the workspace chrome can ask the app to do. */
class WorkspaceActions(
    val onImportStep: () -> Unit,
    val onOpenSample: () -> Unit,
    val onFitView: () -> Unit,
    val onNew: () -> Unit = {},
    /** Export in a format named by its file extension: step, igs, stl, obj or 3mf. */
    val onExport: (String) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onViewInAr: () -> Unit = {},
    /** Saved projects (id to name), newest first, and how to open one. */
    val recentProjects: List<Pair<String, String>> = emptyList(),
    val onOpenProject: (String) -> Unit = {},
    val onBenchmark: () -> Unit = {},
)

/**
 * The modeling workspace: top bar, tool rails and status readout laid over a full-bleed
 * [viewport].
 *
 * @param projectName shown in the middle of the top bar.
 * @param status short multi-line readout shown at the bottom (model and frame figures).
 * @param busy while true the file actions are disabled.
 * @param sketchActive highlights the Sketch rail button.
 * @param onSketchToggle called when the Sketch rail button is tapped.
 * @param overlay extra controls drawn over the viewport, below the top bar (sketch tools).
 * @param historyActive highlights the History rail button.
 * @param onHistoryToggle called when the History rail button is tapped.
 */
@Composable
fun Workspace(
    projectName: String,
    actions: WorkspaceActions,
    status: String,
    busy: Boolean,
    sketchActive: Boolean = false,
    onSketchToggle: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    historyActive: Boolean = false,
    onHistoryToggle: () -> Unit = {},
    materialsActive: Boolean = false,
    onMaterialsToggle: () -> Unit = {},
    onDisplayMode: () -> Unit = {},
    onDrawings: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onSection: () -> Unit = {},
    onItems: () -> Unit = {},
    itemsActive: Boolean = false,
    sectionActive: Boolean = false,
    appearanceActive: Boolean = false,
    /** Left-handed layout: tools on the right, settings on the left. */
    toolsOnRight: Boolean = false,
    showLabels: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    viewport: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SharpCadColors.Background)) {
        viewport()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(projectName, actions, busy, canUndo, canRedo)
            Box(Modifier.fillMaxSize().padding(SharpCadDimens.Gutter)) {
                ToolRail(
                    LeftRailGroups,
                    Modifier.align(if (toolsOnRight) Alignment.TopEnd else Alignment.TopStart),
                    showLabels = showLabels,
                    actions = mapOf(
                        SKETCH_ITEM to onSketchToggle, VISUALIZATION_ITEM to onMaterialsToggle,
                        DRAWINGS_ITEM to onDrawings, SECTION_ITEM to onSection, ITEMS_ITEM to onItems,
                    ),
                    active = setOfNotNull(
                        SKETCH_ITEM.takeIf { sketchActive }, VISUALIZATION_ITEM.takeIf { materialsActive },
                        SECTION_ITEM.takeIf { sectionActive }, ITEMS_ITEM.takeIf { itemsActive },
                    ),
                )
                ToolRail(
                    RightRailGroups,
                    Modifier.align(if (toolsOnRight) Alignment.TopStart else Alignment.TopEnd),
                    showLabels = showLabels,
                    actions = mapOf(HISTORY_ITEM to onHistoryToggle, DISPLAY_ITEM to onDisplayMode, APPEARANCE_ITEM to onAppearance),
                    active = setOfNotNull(HISTORY_ITEM.takeIf { historyActive }, APPEARANCE_ITEM.takeIf { appearanceActive }),
                )
                overlay()
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        color = SharpCadColors.ContentMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(SharpCadDimens.ButtonRadius))
                            .background(SharpCadColors.Panel.copy(alpha = 0.88f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(projectName: String, actions: WorkspaceActions, busy: Boolean, canUndo: Boolean, canRedo: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(SharpCadDimens.TopBarHeight)
            .background(SharpCadColors.Panel.copy(alpha = 0.94f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopMenu("File") { close ->
            MenuItem("New", enabled = !busy) { close(); actions.onNew() }
            actions.recentProjects.take(6).forEach { (id, name) ->
                MenuItem("Open: $name", enabled = !busy) { close(); actions.onOpenProject(id) }
            }
            MenuItem("Import STEP / IGES…", enabled = !busy) { close(); actions.onImportStep() }
            for ((label, ext) in EXPORTS) MenuItem("Export $label…", enabled = !busy) { close(); actions.onExport(ext) }
            MenuItem("Open sample part", enabled = !busy) { close(); actions.onOpenSample() }
        }
        TopMenu("Edit") { close ->
            MenuItem("Undo", enabled = canUndo) { close(); actions.onUndo() }
            MenuItem("Redo", enabled = canRedo) { close(); actions.onRedo() }
        }
        TopMenu("Item") { _ -> MenuItem("No item selected", enabled = false) {} }
        TopMenu("View") { close ->
            MenuItem("Fit to screen") { close(); actions.onFitView() }
            MenuItem("View in AR", enabled = !busy) { close(); actions.onViewInAr() }
        }
        TopMenu("Help") { close ->
            MenuItem("Run benchmark", enabled = !busy) { close(); actions.onBenchmark() }
            MenuItem("Double-tap the view to fit", enabled = false) {}
        }
        Text(
            text = projectName,
            color = SharpCadColors.Content,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = actions.onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = if (canUndo) SharpCadColors.Content else SharpCadColors.ContentDisabled)
        }
        IconButton(onClick = actions.onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Outlined.Redo, "Redo", tint = if (canRedo) SharpCadColors.Content else SharpCadColors.ContentDisabled)
        }
    }
}

private val EXPORTS = listOf("STEP" to "step", "IGES" to "igs", "STL" to "stl", "OBJ" to "obj", "3MF" to "3mf", "GLB" to "glb")

@Composable
private fun TopMenu(label: String, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(label, color = SharpCadColors.Content, fontSize = 13.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SharpCadColors.PanelRaised,
        ) {
            content { open = false }
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp) },
        enabled = enabled,
        onClick = onClick,
    )
}
