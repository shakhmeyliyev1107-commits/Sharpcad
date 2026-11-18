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
import com.sharpcad.ui.designsystem.Units as U
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
    /** A picked edge of a body, with its points for highlighting. */
    var edgeSelection by mutableStateOf<Document.EdgeHit?>(null)
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
            if (measuring) {
                val hit = try { document.pick(origin, direction) } catch (e: KernelException) { null } ?: return@launch
                addMeasurePoint(DoubleArray(3) { origin[it] + hit.distance * direction[it] })
                return@launch
            }
            if (pendingBoolean == null) {
                val tolerance = viewport.millimetresPerPixel() * EDGE_PICK_PX
                val edge = if (tolerance > 0) {
                    try { document.pickEdge(origin, direction, tolerance) } catch (e: KernelException) { null }
                } else null
                if (edge != null) {
                    selectEdge(edge)
                    return@launch
                }
            }
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

    // ---- Add, transform, measure --------------------------------------------------

    private enum class Primitive { BOX, CYLINDER, SPHERE }

    var addOpen by mutableStateOf(false)
        private set

    fun toggleAdd() {
        addOpen = !addOpen
        if (addOpen) { measuring = false; clearSelection(); editingId = null }
    }

    private fun addPrimitive(kind: Primitive) {
        addOpen = false
        val h = PRIMITIVE_SIZE / 2
        val xy = PlaneSpec.XY
        val feature: Feature = when (kind) {
            Primitive.BOX -> ExtrudeFeature(
                document.newId(),
                Profile(xy, listOf(-h, -h, h, -h, h, -h, h, h, h, h, -h, h, -h, h, -h, -h), emptyList()),
                PRIMITIVE_SIZE, Operation.NEW_BODY,
            )
            Primitive.CYLINDER -> ExtrudeFeature(document.newId(), Profile(xy, emptyList(), listOf(0.0, 0.0, h)), PRIMITIVE_SIZE, Operation.NEW_BODY)
            // A half disc above the X axis, revolved a full turn about it.
            Primitive.SPHERE -> RevolveFeature(
                document.newId(),
                Profile(xy, listOf(-h, 0.0, h, 0.0), emptyList(), arcs = listOf(0.0, 0.0, h, 0.0, Math.PI)),
                RevolveAxis.U, 360.0, Operation.NEW_BODY,
            )
        }
        addAndEdit(feature)
    }

    /** Centre of a body's displayed mesh bounds, or null. */
    private fun bodyCentre(bodyId: Long): DoubleArray? =
        state.bodies.firstOrNull { it.bodyId == bodyId }?.bounds?.let { b -> DoubleArray(3) { (b[it] + b[it + 3]) / 2 } }

    /** A split across the body's middle, on the XY plane (normal Z). */
    private fun splitThroughCentre(bodyId: Long) = com.sharpcad.document.SplitFeature(
        document.newId(), bodyId, com.sharpcad.document.WorldAxis.Z, bodyCentre(bodyId)?.get(2) ?: 0.0,
    )

    /** Transform rail button: moves the selected body (opens the Move panel). */
    fun transformSelected() {
        val body = selection?.bodyId ?: edgeSelection?.bodyId
        if (body == null) {
            note = "Select a body first, then tap Transform"
            return
        }
        addAndEdit(com.sharpcad.document.MoveFeature(document.newId(), body))
    }

    var measuring by mutableStateOf(false)
        private set
    private val measurePoints = mutableListOf<DoubleArray>()
    private var measureReadout by mutableStateOf("")

    fun toggleMeasure() {
        measuring = !measuring
        measurePoints.clear()
        measureReadout = ""
        sketches.setHighlightLines(null)
        if (measuring) { addOpen = false; clearSelection(); editingId = null }
    }

    private fun addMeasurePoint(p: DoubleArray) {
        if (measurePoints.size >= 2) measurePoints.clear()
        measurePoints += p
        if (measurePoints.size == 2) {
            val (a, b) = measurePoints
            val d = DoubleArray(3) { b[it] - a[it] }
            val dist = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            measureReadout = "Distance ${U.format(dist)} · dx ${U.number(d[0])} · dy ${U.number(d[1])} · dz ${U.number(d[2])}"
            sketches.setHighlightLines(floatArrayOf(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), b[0].toFloat(), b[1].toFloat(), b[2].toFloat()))
        } else {
            measureReadout = "Tap the second point"
            sketches.setHighlightLines(null)
        }
    }

    // ---- Direct manipulation ------------------------------------------------------

    /** A face drag in progress: the face's axis line and the feature it drives. */
    private class FaceDrag(val bodyId: Long, val face: Int, val anchor: DoubleArray, val normal: DoubleArray, val start: Double, val downX: Float, val downY: Float) {
        var featureId: Long? = null
        var pending: Double? = null
        var running = false
        var last = 0.0
    }

    private var faceDrag: FaceDrag? = null

    /** Distance shown while a face is dragged, or `null`. */
    var dragReadout by mutableStateOf<String?>(null)
        private set

    /**
     * One-finger input outside sketching. A drag that starts on the selected face pushes or
     * pulls it along its normal, live; lifting opens the Push/Pull panel for an exact value.
     * While the Move panel is open, dragging the moved body slides it on the world plane
     * facing the camera. Returns false (orbit as usual) when the touch hits neither.
     */
    fun onDrag(phase: com.sharpcad.viewport.TouchPhase, x: Float, y: Float): Boolean {
        when (phase) {
            com.sharpcad.viewport.TouchPhase.DOWN -> {
                faceDrag = null
                moveDrag = null
                edgeDrag = null
                beginMoveDrag(x, y)?.let { moveDrag = it; return true }
                beginEdgeDrag(x, y)?.let { edgeDrag = it; return true }
                val s = selection ?: return false
                if (pendingBoolean != null) return false
                val body = state.bodies.firstOrNull { it.bodyId == s.bodyId } ?: return false
                val ray = viewport.screenRay(x, y) ?: return false
                val o = doubleArrayOf(ray[0], ray[1], ray[2])
                val d = doubleArrayOf(ray[3], ray[4], ray[5])
                val t = MeshTools.rayHitFace(body.mesh, s.face, o, d) ?: return false
                val n = MeshTools.faceNormal(body.mesh, s.face) ?: return false
                val anchor = DoubleArray(3) { o[it] + t * d[it] }
                val start = com.sharpcad.viewport.AxisDrag.along(anchor, n, o, d) ?: return false
                faceDrag = FaceDrag(s.bodyId, s.face, anchor, n, start, x, y)
                return true
            }
            com.sharpcad.viewport.TouchPhase.MOVE -> edgeDrag?.let { edgeTo(it, x, y) } ?: moveDrag?.let { moveTo(it, x, y) } ?: faceDrag?.let { dragTo(it, x, y) }
            com.sharpcad.viewport.TouchPhase.UP -> edgeDrag?.let { g ->
                edgeTo(g, x, y)
                edgeDrag = null
                dragReadout = null
                g.featureId?.let { id -> editingId = id }
            } ?: moveDrag?.let { m ->
                moveTo(m, x, y)
                moveDrag = null
                dragReadout = null
            } ?: faceDrag?.let { g ->
                dragTo(g, x, y)
                faceDrag = null
                dragReadout = null
                g.featureId?.let { id -> editingId = id }
            }
            com.sharpcad.viewport.TouchPhase.CANCEL -> edgeDrag?.let { g ->
                edgeDrag = null
                dragReadout = null
                g.featureId?.let { id -> launchEdit { document.remove(id) } }
            } ?: moveDrag?.let { m ->
                moveDrag = null
                dragReadout = null
                launchEdit { document.amend(m.original) }
            } ?: faceDrag?.let { g ->
                faceDrag = null
                dragReadout = null
                g.featureId?.let { id -> launchEdit { document.remove(id) } }
            }
        }
        return true
    }

    /** Dragging the selected edge: the radius grows with the drag length on screen. */
    private class EdgeDrag(val edge: Document.EdgeHit, val downX: Float, val downY: Float) {
        var featureId: Long? = null
        var pending: Double? = null
        var running = false
    }

    private var edgeDrag: EdgeDrag? = null

    private fun beginEdgeDrag(x: Float, y: Float): EdgeDrag? {
        val e = edgeSelection ?: return null
        val ray = viewport.screenRay(x, y) ?: return null
        val tolerance = viewport.millimetresPerPixel() * EDGE_PICK_PX * 2
        val p = e.points
        val near = (0 until p.size / 3).any { i ->
            // Distance from the edge point to the ray line.
            val v = DoubleArray(3) { p[i * 3 + it] - ray[it] }
            val t = v[0] * ray[3] + v[1] * ray[4] + v[2] * ray[5]
            val len2 = ray[3] * ray[3] + ray[4] * ray[4] + ray[5] * ray[5]
            val dx = v[0] - t / len2 * ray[3]; val dy = v[1] - t / len2 * ray[4]; val dz = v[2] - t / len2 * ray[5]
            dx * dx + dy * dy + dz * dz <= tolerance * tolerance
        }
        return if (near) EdgeDrag(e, x, y) else null
    }

    private fun edgeTo(g: EdgeDrag, x: Float, y: Float) {
        val px = kotlin.math.hypot(x - g.downX, y - g.downY)
        if (g.featureId == null && px < DRAG_SLOP_PX) return
        val step = if (viewport.millimetresPerPixel() > 0.2) 0.5 else 0.1
        val radius = com.sharpcad.viewport.AxisDrag.snap((px - DRAG_SLOP_PX) * viewport.millimetresPerPixel(), step).coerceAtLeast(step)
        dragReadout = "Fillet ${U.format(radius)}"
        g.pending = radius
        if (g.running) return
        g.running = true
        launchEdit {
            try {
                while (true) {
                    val next = g.pending ?: break
                    g.pending = null
                    val id = g.featureId
                    if (id == null) {
                        val f = FilletFeature(document.newId(), g.edge.bodyId, next, edge = g.edge.edge)
                        clearSelection()
                        document.add(f)
                        g.featureId = f.id
                    } else {
                        document.amend(FilletFeature(id, g.edge.bodyId, next, edge = g.edge.edge))
                    }
                }
            } finally {
                g.running = false
            }
        }
    }

    /** A body drag while the Move panel is open: on a world plane facing the camera. */
    private class MoveDrag(val original: com.sharpcad.document.MoveFeature, val normal: Int, val start: DoubleArray, val downX: Float) {
        var pending: com.sharpcad.document.MoveFeature? = null
        var running = false
    }

    private var moveDrag: MoveDrag? = null

    /** Move panel: one-finger drag on the body rotates about the chosen axis instead. */
    private var dragRotates by mutableStateOf(false)

    private fun beginMoveDrag(x: Float, y: Float): MoveDrag? {
        val f = editingFeature() as? com.sharpcad.document.MoveFeature ?: return null
        val shownId = if (f.copy) f.id else f.body
        val body = state.bodies.firstOrNull { it.bodyId == shownId } ?: return null
        val ray = viewport.screenRay(x, y) ?: return null
        val o = doubleArrayOf(ray[0], ray[1], ray[2])
        val d = doubleArrayOf(ray[3], ray[4], ray[5])
        val t = MeshTools.rayHitFace(body.mesh, -1, o, d) ?: return null
        val hit = DoubleArray(3) { o[it] + t * d[it] }
        // Slide on the world plane most square to the view, through the touched point.
        val normal = (0..2).maxBy { kotlin.math.abs(d[it]) }
        return MoveDrag(f, normal, hit, x)
    }

    private fun moveTo(m: MoveDrag, x: Float, y: Float) {
        if (dragRotates) {
            val o = m.original
            val degrees = com.sharpcad.viewport.AxisDrag.snap((x - m.downX) * ROTATE_DEG_PER_PX, ROTATE_SNAP_DEG)
            queueMove(m, o.copy(angleDegrees = o.angleDegrees + degrees))
            dragReadout = "Rotate ${formatMm(o.angleDegrees + degrees)}°"
            return
        }
        val ray = viewport.screenRay(x, y) ?: return
        val k = m.normal
        if (kotlin.math.abs(ray[3 + k]) < 1e-9) return
        val t = (m.start[k] - ray[k]) / ray[3 + k]
        val step = if (viewport.millimetresPerPixel() > 0.2) 1.0 else 0.1
        val delta = DoubleArray(3) { if (it == k) 0.0 else com.sharpcad.viewport.AxisDrag.snap(ray[it] + t * ray[3 + it] - m.start[it], step) }
        val o = m.original
        val f = o.copy(dx = o.dx + delta[0], dy = o.dy + delta[1], dz = o.dz + delta[2])
        dragReadout = "Move ${U.number(f.dx)}, ${U.number(f.dy)}, ${U.number(f.dz)} ${U.current.label}"
        queueMove(m, f)
    }

    private fun queueMove(m: MoveDrag, f: com.sharpcad.document.MoveFeature) {
        m.pending = f
        if (m.running) return
        m.running = true
        launchEdit {
            try {
                while (true) {
                    val next = m.pending ?: break
                    m.pending = null
                    document.amend(next)
                }
            } finally {
                m.running = false
            }
        }
    }

    private fun dragTo(g: FaceDrag, x: Float, y: Float) {
        if (g.featureId == null && kotlin.math.hypot(x - g.downX, y - g.downY) < DRAG_SLOP_PX) return
        val ray = viewport.screenRay(x, y) ?: return
        val s = com.sharpcad.viewport.AxisDrag.along(
            g.anchor, g.normal, doubleArrayOf(ray[0], ray[1], ray[2]), doubleArrayOf(ray[3], ray[4], ray[5]),
        ) ?: return
        val step = if (viewport.millimetresPerPixel() > 0.2) 1.0 else 0.1
        val distance = com.sharpcad.viewport.AxisDrag.snap(s - g.start, step)
        dragReadout = "Push/Pull ${U.format(distance)}"
        if (kotlin.math.abs(distance) < 1e-6 || distance == g.last) return
        g.pending = distance
        if (g.running) return
        g.running = true
        launchEdit {
            try {
                while (true) {
                    val next = g.pending ?: break
                    g.pending = null
                    g.last = next
                    val id = g.featureId
                    if (id == null) {
                        val f = PushPullFeature(document.newId(), g.bodyId, g.face, next)
                        clearSelection()
                        document.add(f)
                        g.featureId = f.id
                    } else {
                        document.amend(PushPullFeature(id, g.bodyId, g.face, next))
                    }
                }
            } finally {
                g.running = false
            }
        }
    }

    private fun selectEdge(edge: Document.EdgeHit?) {
        if (edge != null) {
            select(null)
            regionSelection = null
            editingId = null
        }
        edgeSelection = edge
        sketches.setHighlightLines(edge?.let { e ->
            val p = e.points
            val n = p.size / 3
            FloatArray(maxOf(0, n - 1) * 6) { i ->
                val seg = i / 6
                val k = i % 6
                p[(seg + k / 3) * 3 + k % 3].toFloat()
            }
        })
    }

    fun clearSelection() {
        if (edgeSelection != null) selectEdge(null)
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
            val (lines, circles, arcs, splines) = sketches.geometry(plane)
            val index = document.regionAt(planeSpec(plane), lines, circles, uv[0], uv[1], arcs, splines)
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
        val (lines, circles, arcs, splines) = sketches.geometry(plane)
        val mesh = document.profileRegions(planeSpec(plane), lines, circles, arcs, splines)?.mesh
        viewport.setHighlight(mesh?.let { MeshTools.facesOf(it, regions) })
    }

    /** Extrudes or revolves the selected regions. */
    /**
     * Loft from the selected profile through the first profile of every other sketched
     * plane parallel to it, ordered along the normal.
     */
    private fun loftFromRegions() {
        val r = regionSelection ?: return
        val n = r.plane.normal
        fun along(p: SketchPlane) = p.origin[0] * n[0] + p.origin[1] * n[1] + p.origin[2] * n[2]
        val planes = sketches.sketchedPlanes.filter { p ->
            val m = p.normal
            kotlin.math.abs(m[0] * n[0] + m[1] * n[1] + m[2] * n[2]) > 0.999
        }.sortedBy { along(it) }
        if (planes.size < 2) {
            note = "Loft needs sketches on two parallel planes (e.g. XY and XY+50)"
            return
        }
        val profiles = planes.map { p ->
            val (lines, circles, arcs, splines) = sketches.geometry(p)
            Profile(planeSpec(p), lines, circles, if (p == r.plane) r.regions.sorted().take(1) else null, arcs, splines)
        }
        regionSelection = null
        viewport.setHighlight(null)
        launchEdit { document.add(com.sharpcad.document.LoftFeature(document.newId(), profiles)) }
    }

    /** Sweep the selected profile along the lines/arcs sketched on a plane that crosses it. */
    private fun sweepFromRegion() {
        val r = regionSelection ?: return
        val n = r.plane.normal
        val pathPlane = sketches.sketchedPlanes.firstOrNull { p ->
            val m = p.normal
            kotlin.math.abs(m[0] * n[0] + m[1] * n[1] + m[2] * n[2]) < 0.999
        }
        val path = pathPlane?.let { sketches.geometry(it) }
        if (pathPlane == null || path == null || (path.lines.isEmpty() && path.arcs.isEmpty())) {
            note = "Sweep needs a path: draw lines/arcs on a crossing plane (e.g. XZ for an XY profile)"
            return
        }
        val (lines, circles, arcs, splines) = sketches.geometry(r.plane)
        regionSelection = null
        viewport.setHighlight(null)
        launchEdit {
            document.add(
                com.sharpcad.document.SweepFeature(
                    document.newId(), Profile(planeSpec(r.plane), lines, circles, r.regions.sorted().take(1), arcs, splines),
                    planeSpec(pathPlane), path.lines, path.arcs,
                ),
            )
        }
    }

    private fun solidFromRegions(revolve: Boolean) {
        val r = regionSelection ?: return
        val (lines, circles, arcs, splines) = sketches.geometry(r.plane)
        regionSelection = null
        viewport.setHighlight(null)
        addFromProfile(Profile(planeSpec(r.plane), lines, circles, r.regions.sorted(), arcs, splines), revolve)
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
            if (edgeSelection != null) selectEdge(null)
        }
        val mesh = value?.let { s -> state.bodies.firstOrNull { it.bodyId == s.bodyId } }
        viewport.setHighlight(mesh?.let { MeshTools.facesOf(it.mesh, setOf(value.face)) })
    }

    // ---- Panel ------------------------------------------------------------------

    /** The panel to show: the feature being edited, else tools for the selection. */
    val panel: PanelModel?
        get() {
            if (measuring) {
                return PanelModel(
                    "Measure", emptyList(), emptyList(),
                    listOf(PanelAction("Clear") { measurePoints.clear(); measureReadout = ""; sketches.setHighlightLines(null) }, PanelAction("Done", accent = true) { toggleMeasure() }),
                    measureReadout.ifEmpty { "Tap two points on the model" },
                )
            }
            if (addOpen) {
                return PanelModel(
                    "Add", emptyList(), emptyList(),
                    listOf(
                        PanelAction("Box", accent = true) { addPrimitive(Primitive.BOX) },
                        PanelAction("Cylinder") { addPrimitive(Primitive.CYLINDER) },
                        PanelAction("Sphere") { addPrimitive(Primitive.SPHERE) },
                        PanelAction("Close") { addOpen = false },
                    ),
                    "New solid at the origin; edit its size afterwards",
                )
            }
            val editing = editingId?.let { id -> state.features.firstOrNull { it.id == id } }
            if (editing != null) return editorFor(editing)
            regionSelection?.let { r ->
                val count = r.regions.size
                return PanelModel(
                    "Sketch profile", emptyList(), emptyList(),
                    listOf(
                        PanelAction("Extrude", accent = true) { solidFromRegions(revolve = false) },
                        PanelAction("Revolve") { solidFromRegions(revolve = true) },
                        PanelAction("Loft") { loftFromRegions() },
                        PanelAction("Sweep") { sweepFromRegion() },
                        PanelAction("Deselect") { clearSelection(); viewport.setHighlight(null) },
                    ),
                    "${r.plane.name} · $count region${if (count == 1) "" else "s"} · tap more regions to add",
                )
            }
            edgeSelection?.let { e ->
                return PanelModel(
                    "Edge", emptyList(), emptyList(),
                    listOf(
                        PanelAction("Fillet", accent = true) { addAndEdit(FilletFeature(document.newId(), e.bodyId, 1.0, edge = e.edge)) },
                        PanelAction("Chamfer") { addAndEdit(ChamferFeature(document.newId(), e.bodyId, 1.0, edge = e.edge)) },
                        PanelAction("Deselect") { clearSelection() },
                    ),
                    "${bodyName(e.bodyId)} · edge ${e.edge + 1} · drag the edge to round it",
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
            PanelAction("Split") { addAndEdit(splitThroughCentre(s.bodyId)) },
            PanelAction("Scale") { addAndEdit(com.sharpcad.document.ScaleFeature(document.newId(), s.bodyId)) },
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
                choices += ChoiceField("Drag", DRAG_MODES, if (dragRotates) DRAG_MODES[1] else DRAG_MODES[0])
            }
            is com.sharpcad.document.ScaleFeature -> fields += DimensionField("Factor", f.factor, unit = "×")
            is com.sharpcad.document.SplitFeature -> {
                fields += DimensionField("Offset", f.offset, allowNegative = true)
                choices += ChoiceField("Plane", MIRROR_PLANES, mirrorLabel(f.plane))
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
            is ImportFeature, is com.sharpcad.document.DeleteBodyFeature, is com.sharpcad.document.LoftFeature, is com.sharpcad.document.SweepFeature -> Unit
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
            is com.sharpcad.document.ScaleFeature -> f.copy(factor = value)
            is com.sharpcad.document.SplitFeature -> f.copy(offset = value)
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
            is com.sharpcad.document.MoveFeature -> if (index == 1) {
                dragRotates = option == DRAG_MODES[1]
                null
            } else axisOf(option)?.let { f.copy(axis = it) }
            is com.sharpcad.document.SplitFeature -> MIRROR_PLANES.indexOf(option).takeIf { it >= 0 }?.let { i ->
                val axis = com.sharpcad.document.WorldAxis.entries[i]
                f.copy(plane = axis, offset = bodyCentre(f.body)?.get(i) ?: f.offset)
            }
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
        private const val PRIMITIVE_SIZE = 20.0
        private const val DRAG_SLOP_PX = 12f
        private const val ROTATE_DEG_PER_PX = 0.5
        private const val ROTATE_SNAP_DEG = 5.0
        private val DRAG_MODES = listOf("Move", "Rotate")
        /** A tap this close to an edge (in pixels) picks the edge rather than the face. */
        private const val EDGE_PICK_PX = 10.0

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

        fun planeSpec(plane: SketchPlane): PlaneSpec =
            PlaneSpec(plane.name, plane.origin.toList(), plane.xAxis.toList(), plane.yAxis.toList())

        fun formatValue(v: Double) = formatMm(v)
    }
}
