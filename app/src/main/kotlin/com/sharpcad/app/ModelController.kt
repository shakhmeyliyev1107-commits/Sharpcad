package com.sharpcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.document.BooleanFeature
import com.sharpcad.document.ChamferFeature
import com.sharpcad.document.Document
import com.sharpcad.document.DocumentState
import com.sharpcad.document.ExtrudeFeature
import com.sharpcad.document.Feature
import com.sharpcad.document.FilletFeature
import com.sharpcad.document.ImportFeature
import com.sharpcad.document.MaterialLibrary
import com.sharpcad.document.Operation
import com.sharpcad.document.PlaneSpec
import com.sharpcad.document.Profile
import com.sharpcad.document.PushPullFeature
import com.sharpcad.document.RevolveAxis
import com.sharpcad.document.RevolveFeature
import com.sharpcad.document.formatMm
import com.sharpcad.kernel.BooleanOp
import com.sharpcad.kernel.KernelException
import com.sharpcad.sketch.SketchPlane
import com.sharpcad.ui.workspace.ChoiceField
import com.sharpcad.ui.workspace.DimensionField
import com.sharpcad.ui.workspace.HistoryItem
import com.sharpcad.ui.workspace.PanelAction
import com.sharpcad.viewport.DisplayMode
import com.sharpcad.viewport.ViewportController
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked face of a body. */
data class Selection(val bodyId: Long, val face: Int)

/** Picked closed regions of the sketch on one plane, ready to extrude or revolve. */
data class RegionSelection(val plane: SketchPlane, val regions: Set<Int>, val revision: Int)

/** Parameters and actions for the floating panel, derived from the current state. */
class PanelModel(
    val title: String,
    val fields: List<DimensionField>,
    val choices: List<ChoiceField>,
    val actions: List<PanelAction>,
    val message: String,
)

/**
 * Connects the document (feature history) to the viewport and the panels: shows the
 * rebuilt bodies, handles taps for selection, offers the tools that fit the selection
 * and edits feature parameters. Main thread only.
 */
class ModelController(
    private val scope: CoroutineScope,
    private val viewport: ViewportController,
    val document: Document,
    private val sketches: SketchSession,
) {
    var state by mutableStateOf(DocumentState())
        private set
    var selection by mutableStateOf<Selection?>(null)
        private set
    var regionSelection by mutableStateOf<RegionSelection?>(null)
        private set
    var editingId by mutableStateOf<Long?>(null)
        private set
    var historyOpen by mutableStateOf(false)
        private set
    var note by mutableStateOf("")
        private set

    /** Material preset id per body; bodies without an entry use the default material. */
    private val materials = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    var materialsOpen by mutableStateOf(false)
        private set
    var materialCategory by mutableStateOf("Metal")
    var displayMode by mutableStateOf(DisplayMode.SHADED_EDGES)
        private set

    /** Waiting for the second body of a boolean. */
    private var pendingBoolean by mutableStateOf<BooleanOp?>(null)
    private var fitOnNextModel = true

    init {
        scope.launch { document.state.collect { show(it) } }
    }

    // ---- Document ---------------------------------------------------------------

    fun reset(features: List<Feature>, fit: Boolean = true) = launchEdit {
        clearSelection()
        editingId = null
        fitOnNextModel = fit
        document.reset(features)
    }

    fun undo() = launchEdit { clearSelection(); document.undo() }

    fun redo() = launchEdit { clearSelection(); document.redo() }

    /** Adds a solid made from a sketch profile and opens it for editing. */
    fun addFromProfile(profile: Profile, revolve: Boolean) = launchEdit {
        val id = document.newId()
        val hasBody = state.bodies.isNotEmpty()
        val feature = if (revolve) {
            RevolveFeature(id, profile, RevolveAxis.V, 360.0)
        } else {
            ExtrudeFeature(id, profile, DEFAULT_DISTANCE)
        }
        document.add(feature)
        editingId = id
        note = if (hasBody) "Auto joins when touching a body and cuts when inside one" else ""
    }

    fun toggleHistory() {
        historyOpen = !historyOpen
    }

    /** The drawing being previewed, or `null`. */
    var drawing by mutableStateOf<com.sharpcad.document.DrawingSheet?>(null)
        private set

    /** Projects all bodies into a drawing sheet (front, top, right, isometric). */
    fun openDrawing(title: String) = launchEdit {
        val views = document.drawingViews()
        if (views.isEmpty()) {
            note = "Nothing to draw yet"
            return@launchEdit
        }
        drawing = com.sharpcad.document.DrawingLayout.layout(title, views)
    }

    fun closeDrawing() {
        drawing = null
    }

    // ---- Section view -------------------------------------------------------------

    var sectionOn by mutableStateOf(false)
        private set
    private var sectionPlane by mutableStateOf("XY")
    private var sectionOffset by mutableStateOf(0.0)
    private var sectionFlip by mutableStateOf(false)

    fun toggleSection() {
        sectionOn = !sectionOn
        if (sectionOn) {
            // Start through the middle of the model.
            val b = state.bodies.map { it.bounds }
            if (b.isNotEmpty()) sectionOffset = (b.minOf { it[2] } + b.maxOf { it[5] }) / 2
        }
        applySection()
    }

    val sectionPanel: PanelModel?
        get() = if (!sectionOn) null else PanelModel(
            "Section view",
            listOf(DimensionField("Offset", sectionOffset, allowNegative = true)),
            listOf(ChoiceField("Plane", listOf("XY", "XZ", "YZ"), sectionPlane)),
            listOf(
                PanelAction("Flip side") { sectionFlip = !sectionFlip; applySection() },
                PanelAction("Close", accent = true) { toggleSection() },
            ),
            "Cuts away everything on one side of the plane",
        )

    fun changeSectionOffset(value: Double) {
        sectionOffset = value
        applySection()
    }

    fun changeSectionPlane(plane: String) {
        sectionPlane = plane
        val b = state.bodies.map { it.bounds }
        val axis = when (plane) { "YZ" -> 0; "XZ" -> 1; else -> 2 }
        if (b.isNotEmpty()) sectionOffset = (b.minOf { it[axis] } + b.maxOf { it[axis + 3] }) / 2
        applySection()
    }

    private fun applySection() {
        if (!sectionOn) {
            viewport.setSection(0f, 0f, 0f, 0f)
            return
        }
        val sign = if (sectionFlip) -1f else 1f
        val (x, y, z) = when (sectionPlane) { "YZ" -> Triple(1f, 0f, 0f); "XZ" -> Triple(0f, 1f, 0f); else -> Triple(0f, 0f, 1f) }
        viewport.setSection(x * sign, y * sign, z * sign, (sectionOffset * sign).toFloat())
    }

    fun toggleMaterials() {
        materialsOpen = !materialsOpen
    }

    fun cycleDisplayMode() {
        displayMode = DisplayMode.entries[(displayMode.ordinal + 1) % DisplayMode.entries.size]
        viewport.setDisplayMode(displayMode)
        note = displayMode.label
    }

    /** Material of the selected body, or of the first body when nothing is selected. */
    val currentMaterialId: String?
        get() = (selection?.bodyId ?: state.bodies.firstOrNull()?.bodyId)?.let { materials[it] ?: MaterialLibrary.DEFAULT_ID }

    val materialTarget: String
        get() = selection?.let { s -> "Applies to ${state.bodies.firstOrNull { it.bodyId == s.bodyId }?.label ?: "the selected body"}" }
            ?: "Nothing selected: applies to every body"

    // ---- Items ----------------------------------------------------------------------

    private val bodyNames = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    private val hidden = androidx.compose.runtime.mutableStateListOf<Long>()
    var itemsOpen by mutableStateOf(false)
        private set

    fun toggleItems() {
        itemsOpen = !itemsOpen
    }

    fun bodyName(id: Long): String = bodyNames[id] ?: state.bodies.firstOrNull { it.bodyId == id }?.label ?: "Body"

    val itemRows: List<com.sharpcad.ui.workspace.ItemRow>
        get() = state.bodies.map { com.sharpcad.ui.workspace.ItemRow(it.bodyId, bodyName(it.bodyId), it.bodyId !in hidden, selection?.bodyId == it.bodyId) }

    fun selectBody(id: Long) = select(Selection(id, 0))

    fun renameBody(id: Long, name: String) {
        bodyNames[id] = name
        onChanged?.invoke()
    }

    fun toggleVisible(id: Long) {
        if (id in hidden) hidden.remove(id) else hidden.add(id)
        if (selection?.bodyId == id) clearSelection()
        scope.launch { redraw(fit = false) }
        onChanged?.invoke()
    }

    fun deleteBody(id: Long) = launchEdit {
        clearSelection()
        document.add(com.sharpcad.document.DeleteBodyFeature(document.newId(), id))
    }

    fun namesSnapshot(): Map<Long, String> = bodyNames.toMap()

    fun hiddenSnapshot(): Set<Long> = hidden.toSet()

    fun restoreItems(names: Map<Long, String>, hiddenIds: Set<Long>) {
        bodyNames.clear(); bodyNames.putAll(names)
        hidden.clear(); hidden.addAll(hiddenIds)
    }

    /** Called after every rebuild or appearance change (autosave). */
    var onChanged: (() -> Unit)? = null

    fun materialsSnapshot(): Map<Long, String> = materials.toMap()

    /** Replaces all material assignments (opening a project). */
    fun restoreMaterials(saved: Map<Long, String>) {
        materials.clear()
        materials.putAll(saved)
        scope.launch { redraw(fit = false) }
    }

    /** Material of a body for exports and rendering. */
    fun materialOf(bodyId: Long) = MaterialLibrary.find(materials[bodyId])

    /** Applies a preset to the selected body, or to every body when nothing is selected. */
    fun applyMaterial(id: String) {
        val targets = selection?.let { listOf(it.bodyId) } ?: state.bodies.map { it.bodyId }
        targets.forEach { materials[it] = id }
        scope.launch { redraw(fit = false) }
        onChanged?.invoke()
    }

    fun edit(id: Long) {
        editingId = if (editingId == id) null else id
    }

    // ---- Selection --------------------------------------------------------------

    /**
     * Picks what is under a tap: a closed sketch region or a body face, whichever is
     * nearer. Completes a pending boolean when one is waiting. A tap on empty space
     * clears the selection.
     */
    fun onTap(x: Float, y: Float) {
        val ray = viewport.screenRay(x, y) ?: return
        val origin = doubleArrayOf(ray[0], ray[1], ray[2])
        val direction = doubleArrayOf(ray[3], ray[4], ray[5])
        scope.launch {
            val hit = try {
                document.pick(origin, direction)
            } catch (e: KernelException) {
                null
            }
            val region = if (pendingBoolean == null) regionUnder(origin, direction) else null
            if (region != null && (hit == null || region.second <= hit.distance + REGION_PRIORITY_MM)) {
                selectRegion(region.first)
                return@launch
            }
            val op = pendingBoolean
            val target = selection
            if (op != null && target != null && hit != null && hit.bodyId != target.bodyId) {
                pendingBoolean = null
                addAndEdit(BooleanFeature(document.newId(), target.bodyId, hit.bodyId, op))
                clearSelection()
                return@launch
            }
            pendingBoolean = null
            select(hit?.let { Selection(it.bodyId, it.face) })
        }
    }

    fun clearSelection() {
        select(null)
        regionSelection = null
    }

    /** Drops a region selection made before the sketch changed. */
    fun onSketchChanged(revision: Int) {
        val r = regionSelection ?: return
        if (r.revision != revision) {
            regionSelection = null
            viewport.setHighlight(null)
        }
    }

    /** Nearest sketch region along the ray: (plane, region index) and its distance. */
    private suspend fun regionUnder(origin: DoubleArray, direction: DoubleArray): Pair<Pair<SketchPlane, Int>, Double>? {
        var best: Pair<Pair<SketchPlane, Int>, Double>? = null
        for (plane in sketches.sketchedPlanes) {
            val uv = plane.intersect(origin, direction) ?: continue
            val world = plane.toWorld(uv[0], uv[1])
            val distance = kotlin.math.sqrt((0 until 3).sumOf { (world[it] - origin[it]).let { d -> d * d } })
            val (lines, circles) = sketches.geometry(plane)
            val index = document.regionAt(planeSpec(plane), lines, circles, uv[0], uv[1])
            if (index >= 0 && (best == null || distance < best.second)) best = (plane to index) to distance
        }
        return best
    }

    /** Toggles a region; tapping a region on another plane starts a new selection. */
    private suspend fun selectRegion(picked: Pair<SketchPlane, Int>) {
        val (plane, index) = picked
        select(null)
        val current = regionSelection?.takeIf { it.plane == plane && it.revision == sketches.revision }
        val regions = current?.regions?.let { if (index in it) it - index else it + index } ?: setOf(index)
        if (regions.isEmpty()) {
            regionSelection = null
            viewport.setHighlight(null)
            return
        }
        regionSelection = RegionSelection(plane, regions, sketches.revision)
        val (lines, circles) = sketches.geometry(plane)
        val mesh = document.profileRegions(planeSpec(plane), lines, circles)?.mesh
        viewport.setHighlight(mesh?.let { MeshTools.facesOf(it, regions) })
    }

    /** Extrudes or revolves the selected regions. */
    private fun solidFromRegions(revolve: Boolean) {
        val r = regionSelection ?: return
        val (lines, circles) = sketches.geometry(r.plane)
        regionSelection = null
        viewport.setHighlight(null)
        addFromProfile(Profile(planeSpec(r.plane), lines, circles, r.regions.sorted()), revolve)
    }

    /** Volume and face area of the selection, filled in asynchronously. */
    private var measureText by mutableStateOf<String?>(null)

    private fun select(value: Selection?) {
        if (value != selection) measureText = null
        if (value != null && value != selection) {
            scope.launch {
                document.measure(value.bodyId, value.face)?.let { (volume, area) ->
                    if (selection == value) {
                        measureText = String.format(java.util.Locale.US, "area %,.2f mm² · volume %,.1f mm³", area, volume)
                    }
                }
            }
        }
        selection = value
        if (value != null) {
            editingId = null
            regionSelection = null
        }
        val mesh = value?.let { s -> state.bodies.firstOrNull { it.bodyId == s.bodyId } }
        viewport.setHighlight(mesh?.let { MeshTools.facesOf(it.mesh, setOf(value.face)) })
    }

    // ---- Panel ------------------------------------------------------------------

    /** The panel to show: the feature being edited, else tools for the selection. */
    val panel: PanelModel?
        get() {
            val editing = editingId?.let { id -> state.features.firstOrNull { it.id == id } }
            if (editing != null) return editorFor(editing)
            regionSelection?.let { r ->
                val count = r.regions.size
                return PanelModel(
                    "Sketch profile", emptyList(), emptyList(),
                    listOf(
                        PanelAction("Extrude", accent = true) { solidFromRegions(revolve = false) },
                        PanelAction("Revolve") { solidFromRegions(revolve = true) },
                        PanelAction("Deselect") { clearSelection(); viewport.setHighlight(null) },
                    ),
                    "${r.plane.name} · $count region${if (count == 1) "" else "s"} · tap more regions to add",
                )
            }
            val s = selection ?: return null
            return selectionTools(s)
        }

    private fun selectionTools(s: Selection): PanelModel {
        val body = state.bodies.firstOrNull { it.bodyId == s.bodyId }?.label ?: "Body"
        val waiting = pendingBoolean
        val actions = listOf(
            PanelAction("Push/Pull", accent = true) { addAndEdit(PushPullFeature(document.newId(), s.bodyId, s.face, DEFAULT_DISTANCE / 2)) },
            PanelAction("Move") { addAndEdit(com.sharpcad.document.MoveFeature(document.newId(), s.bodyId, dx = 10.0)) },
            PanelAction("Copy") { addAndEdit(com.sharpcad.document.MoveFeature(document.newId(), s.bodyId, dx = 10.0, copy = true)) },
            PanelAction("Mirror") { addAndEdit(com.sharpcad.document.MirrorFeature(document.newId(), s.bodyId, com.sharpcad.document.WorldAxis.X)) },
            PanelAction("Pattern") { addAndEdit(com.sharpcad.document.PatternFeature(document.newId(), s.bodyId)) },
            PanelAction("Shell (open face)") { addAndEdit(com.sharpcad.document.ShellFeature(document.newId(), s.bodyId, s.face, 1.0)) },
            PanelAction("Fillet face") { addAndEdit(FilletFeature(document.newId(), s.bodyId, 1.0, s.face)) },
            PanelAction("Chamfer face") { addAndEdit(ChamferFeature(document.newId(), s.bodyId, 1.0, s.face)) },
            PanelAction("Fillet all") { addAndEdit(FilletFeature(document.newId(), s.bodyId, 1.0)) },
            PanelAction("Chamfer all") { addAndEdit(ChamferFeature(document.newId(), s.bodyId, 1.0)) },
            PanelAction("Union…") { pendingBoolean = BooleanOp.UNION },
            PanelAction("Subtract…") { pendingBoolean = BooleanOp.SUBTRACT },
            PanelAction("Intersect…") { pendingBoolean = BooleanOp.INTERSECT },
            PanelAction("Deselect") { pendingBoolean = null; clearSelection() },
        )
        val message = if (waiting != null) {
            "Tap the other body to ${waiting.name.lowercase()} it"
        } else {
            "$body · face ${s.face + 1}" + (measureText?.let { " · $it" } ?: "")
        }
        return PanelModel("Selection", emptyList(), emptyList(), actions, message)
    }

    private fun editorFor(f: Feature): PanelModel {
        val error = state.errors[f.id]
        val fields = mutableListOf<DimensionField>()
        val choices = mutableListOf<ChoiceField>()
        when (f) {
            is ExtrudeFeature -> {
                fields += DimensionField("Distance", kotlin.math.abs(f.distance))
                choices += ChoiceField("Result", Operation.entries.map { it.label }, f.operation.label)
                choices += ChoiceField("Sides", SIDES, if (f.symmetric) SIDES[1] else SIDES[0])
            }
            is RevolveFeature -> {
                fields += DimensionField("Angle", f.angleDegrees, unit = "°")
                choices += ChoiceField("Axis", RevolveAxis.entries.map { it.label }, f.axis.label)
                choices += ChoiceField("Result", Operation.entries.map { it.label }, f.operation.label)
            }
            is FilletFeature -> fields += DimensionField("Radius", f.radius)
            is ChamferFeature -> fields += DimensionField("Distance", f.distance)
            is PushPullFeature -> fields += DimensionField("Distance", kotlin.math.abs(f.distance))
            is com.sharpcad.document.ShellFeature -> fields += DimensionField("Thickness", f.thickness)
            is BooleanFeature -> choices += ChoiceField("Operation", BooleanOp.entries.map { it.name.lowercase() }, f.op.name.lowercase())
            is com.sharpcad.document.MoveFeature -> {
                fields += DimensionField("X", f.dx, allowNegative = true)
                fields += DimensionField("Y", f.dy, allowNegative = true)
                fields += DimensionField("Z", f.dz, allowNegative = true)
                fields += DimensionField("Rotate", f.angleDegrees, unit = "°", allowNegative = true)
                choices += ChoiceField("Axis", AXES, f.axis.label)
            }
            is com.sharpcad.document.MirrorFeature -> {
                choices += ChoiceField("Across", MIRROR_PLANES, mirrorLabel(f.plane))
                choices += ChoiceField("Original", KEEP, if (f.keepOriginal) KEEP[0] else KEEP[1])
            }
            is com.sharpcad.document.PatternFeature -> {
                fields += DimensionField("Count", f.count.toDouble(), unit = "")
                fields += if (f.circular) DimensionField("Angle", f.angleDegrees, unit = "°") else DimensionField("Spacing", f.spacing, allowNegative = true)
                choices += ChoiceField("Type", PATTERN_TYPES, if (f.circular) PATTERN_TYPES[1] else PATTERN_TYPES[0])
                choices += ChoiceField("Axis", AXES, f.axis.label)
            }
            is ImportFeature, is com.sharpcad.document.DeleteBodyFeature -> Unit
        }
        val flip = when (f) {
            is ExtrudeFeature -> f.copy(distance = -f.distance)
            is PushPullFeature -> f.copy(distance = -f.distance)
            else -> null
        }
        val actions = listOfNotNull(
            flip?.let { PanelAction("Flip direction") { note = ""; launchEdit { document.update(it) } } },
            PanelAction("Delete") { launchEdit { editingId = null; document.remove(f.id) } },
            PanelAction("Done", accent = true) { editingId = null },
        )
        val message = error?.let { "Failed: $it" } ?: note
        return PanelModel(f.label, fields, choices, actions, message)
    }

    /** A typed value for field [index] of the feature being edited. */
    fun setField(index: Int, value: Double) {
        val f = editingFeature() ?: return
        val multi: Feature? = when (f) {
            is com.sharpcad.document.MoveFeature -> when (index) {
                0 -> f.copy(dx = value); 1 -> f.copy(dy = value); 2 -> f.copy(dz = value); else -> f.copy(angleDegrees = value)
            }
            is com.sharpcad.document.PatternFeature -> when (index) {
                0 -> f.copy(count = value.toInt().coerceIn(2, 200))
                else -> if (f.circular) f.copy(angleDegrees = value.coerceIn(1.0, 360.0)) else f.copy(spacing = value)
            }
            else -> null
        }
        if (multi != null) {
            note = ""
            launchEdit { document.update(multi) }
            return
        }
        if (index != 0) return
        val updated = when (f) {
            is ExtrudeFeature -> f.copy(distance = if (f.distance < 0) -value else value)
            is RevolveFeature -> f.copy(angleDegrees = value.coerceIn(0.1, 360.0))
            is FilletFeature -> f.copy(radius = value)
            is ChamferFeature -> f.copy(distance = value)
            is PushPullFeature -> f.copy(distance = if (f.distance < 0) -value else value)
            is com.sharpcad.document.ShellFeature -> f.copy(thickness = value)
            else -> return
        }
        note = ""
        launchEdit { document.update(updated) }
    }

    /** A chosen option for choice row [index] of the feature being edited. */
    fun setChoice(index: Int, option: String) {
        val f = editingFeature() ?: return
        val operation = Operation.entries.firstOrNull { it.label == option }
        val updated = when (f) {
            is ExtrudeFeature -> when (index) {
                0 -> operation?.let { f.copy(operation = it) }
                else -> f.copy(symmetric = option == SIDES[1])
            }
            is RevolveFeature -> when (index) {
                0 -> RevolveAxis.entries.firstOrNull { it.label == option }?.let { f.copy(axis = it) }
                else -> operation?.let { f.copy(operation = it) }
            }
            is BooleanFeature -> BooleanOp.entries.firstOrNull { it.name.lowercase() == option }?.let { f.copy(op = it) }
            is com.sharpcad.document.MoveFeature -> axisOf(option)?.let { f.copy(axis = it) }
            is com.sharpcad.document.MirrorFeature -> when (index) {
                0 -> MIRROR_PLANES.indexOf(option).takeIf { it >= 0 }?.let { f.copy(plane = com.sharpcad.document.WorldAxis.entries[it]) }
                else -> f.copy(keepOriginal = option == KEEP[0])
            }
            is com.sharpcad.document.PatternFeature -> when (index) {
                0 -> f.copy(circular = option == PATTERN_TYPES[1], axis = if (option == PATTERN_TYPES[1]) com.sharpcad.document.WorldAxis.Z else f.axis)
                else -> axisOf(option)?.let { f.copy(axis = it) }
            }
            else -> null
        } ?: return
        note = ""
        launchEdit { document.update(updated) }
    }

    private fun editingFeature(): Feature? = editingId?.let { id -> state.features.firstOrNull { it.id == id } }

    private fun addAndEdit(feature: Feature) = launchEdit {
        clearSelection()
        document.add(feature)
        editingId = feature.id
    }

    // ---- Readouts ---------------------------------------------------------------

    val historyItems: List<HistoryItem>
        get() = state.features.map { HistoryItem(it.id, it.label, state.errors[it.id]) }

    val statusLine: String
        get() {
            val triangles = state.bodies.sumOf { it.mesh.triangleCount }
            val bodies = state.bodies.size
            if (state.features.isEmpty()) return "Empty model · open Sketch to start drawing"
            return String.format(
                Locale.US, "%d bodies · %,d triangles · %d features · rebuild %d ms",
                bodies, triangles, state.features.size, state.rebuildMillis,
            )
        }

    // ---- Internals --------------------------------------------------------------

    private fun launchEdit(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: KernelException) {
                note = "Kernel error ${e.code}: ${e.message}"
            }
        }
    }

    private suspend fun show(newState: DocumentState) {
        state = newState
        val drawn = redraw(fit = fitOnNextModel)
        if (drawn) fitOnNextModel = false
        onChanged?.invoke()
        // Face indices may have changed: drop a selection whose body is gone.
        selection?.let { s -> if (newState.bodies.none { it.bodyId == s.bodyId }) clearSelection() else select(s) }
    }

    /** Re-merges the bodies with their materials and hands the mesh to the viewport. */
    private suspend fun redraw(fit: Boolean): Boolean {
        val hiddenNow = hidden.toSet()
        val bodies = state.bodies.filter { it.bodyId !in hiddenNow }
        val chosen = materials.toMap()
        val merged = withContext(Dispatchers.Default) {
            MeshTools.merge(bodies) { id -> MaterialLibrary.find(chosen[id]) }
        }
        viewport.setModel(merged, fit = fit && merged != null)
        return merged != null
    }

    companion object {
        const val DEFAULT_DISTANCE = 10.0

        /** A region this close behind a face still wins: sketches drawn on a face lie on it. */
        private const val REGION_PRIORITY_MM = 1e-3
        private val SIDES = listOf("One side", "Symmetric")
        private val AXES = listOf("X", "Y", "Z")
        /** Mirror planes, in WorldAxis order (normal X → YZ plane …). */
        private val MIRROR_PLANES = listOf("YZ plane", "XZ plane", "XY plane")
        private val KEEP = listOf("Keep", "Remove")
        private val PATTERN_TYPES = listOf("Linear", "Circular")

        private fun axisOf(label: String) = com.sharpcad.document.WorldAxis.entries.firstOrNull { it.label == label }
        private fun mirrorLabel(axis: com.sharpcad.document.WorldAxis) = MIRROR_PLANES[axis.ordinal]

        fun planeSpec(plane: SketchPlane): PlaneSpec = when (plane.name) {
            "XZ" -> PlaneSpec.XZ
            "YZ" -> PlaneSpec.YZ
            else -> PlaneSpec.XY
        }

        fun formatValue(v: Double) = formatMm(v)
    }
}
