package com.sharpcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.sketch.PointRef
import com.sharpcad.sketch.PointRole
import com.sharpcad.sketch.Sketch
import com.sharpcad.sketch.SketchCircle
import com.sharpcad.sketch.SketchException
import com.sharpcad.sketch.SketchLine
import com.sharpcad.sketch.SketchLines
import com.sharpcad.sketch.SketchPlane
import com.sharpcad.sketch.Snap
import com.sharpcad.sketch.SnapKind
import com.sharpcad.sketch.Snapper
import com.sharpcad.sketch.SolveStatus
import com.sharpcad.ui.workspace.DimensionField
import com.sharpcad.viewport.TouchPhase
import com.sharpcad.viewport.ViewportController
import kotlin.math.abs
import kotlin.math.hypot

enum class SketchTool(val label: String) {
    LINE("Line"),
    RECTANGLE("Rectangle"),
    CENTER_RECTANGLE("Center rect"),
    CIRCLE("Circle"),
    POLYGON("Hexagon"),
}

/**
 * Sketch mode: draws lines, rectangles and circles on a base plane with one finger,
 * snaps them to existing points and the grid, and lets the user type exact sizes.
 *
 * Main thread only. Owns a native [Sketch]; call [dispose] when the screen goes away.
 */
class SketchSession(private val viewport: ViewportController) {

    var active by mutableStateOf(false)
        private set
    var plane by mutableStateOf(SketchPlane.XY)
        private set
    var tool by mutableStateOf<SketchTool?>(null)
        private set

    /** Size fields for the entity just drawn; empty when there is nothing to dimension. */
    var dimensions by mutableStateOf<List<DimensionField>>(emptyList())
        private set
    var message by mutableStateOf("")
        private set
    var isEmpty by mutableStateOf(true)
        private set

    /** One sketch per base plane; drawing goes into the one for the selected [plane]. */
    private val sketches = mutableMapOf<SketchPlane, Sketch>()
    private val sketch: Sketch? get() = sketches[plane]
    private var draft: Draft? = null
    private var snapMarker: Snap? = null

    /** What each entry of [dimensions] constrains, in the same order. */
    private var dimensionTargets: List<DimensionTarget> = emptyList()

    /** Planes that currently hold sketch geometry. */
    val sketchedPlanes: List<SketchPlane>
        get() = sketches.filterValues { it.entities().isNotEmpty() }.keys.toList()

    /**
     * Lines (x1, y1, x2, y2…) and circles (cx, cy, r…) of the sketch on [plane], in plane
     * coordinates; used to build profiles for extrude and revolve.
     */
    fun geometry(plane: SketchPlane): Pair<List<Double>, List<Double>> {
        val lines = mutableListOf<Double>()
        val circles = mutableListOf<Double>()
        sketches[plane]?.entities()?.forEach { e ->
            when (e) {
                is SketchLine -> lines += listOf(e.x1, e.y1, e.x2, e.y2)
                is SketchCircle -> circles += listOf(e.cx, e.cy, e.radius)
            }
        }
        return lines to circles
    }

    /** Bumped whenever sketch geometry changes, so cached profiles can be dropped. */
    var revision by mutableStateOf(0)
        private set

    fun toggle() = if (active) exit() else enter()

    fun enter() {
        if (active) return
        active = true
        viewport.onSingleTouch = ::onTouch
        lookAtPlane()
        refresh()
    }

    fun exit() {
        if (!active) return
        cancelDraft()
        active = false
        tool = null
        clearDimensions()
        viewport.onSingleTouch = null
        refresh()
    }

    /**
     * Switches to the sketch on another plane. Sketches on the other planes are kept and
     * stay visible; drawing continues on the selected one.
     */
    fun selectPlane(name: String) {
        val picked = PLANES.firstOrNull { it.name == name } ?: return
        if (picked == plane) return
        cancelDraft()
        clearDimensions()
        plane = picked
        lookAtPlane()
        refresh()
    }

    /** Activates a tool, or returns to navigation when the active tool is tapped again. */
    fun selectTool(label: String) {
        cancelDraft()
        val picked = SketchTool.entries.firstOrNull { it.label == label }
        tool = if (picked == tool) null else picked
        clearDimensions()
        refresh()
    }

    /** Clears the sketch on the selected plane only. */
    fun clear() {
        cancelDraft()
        sketches.remove(plane)?.close()
        clearDimensions()
        refresh()
    }

    /** Applies a typed size to the entity just drawn. */
    fun setDimension(index: Int, millimetres: Double) {
        val s = sketch ?: return
        val target = dimensionTargets.getOrNull(index) ?: return
        try {
            val existing = target.constraint
            if (existing != null) {
                s.setDimension(existing, millimetres)
            } else {
                target.constraint = when (target) {
                    is DimensionTarget.Distance -> s.addDistance(target.a, target.b, millimetres)
                    is DimensionTarget.Radius -> s.addRadius(target.circle, millimetres)
                }
            }
            val result = s.solve()
            if (result.status != SolveStatus.SOLVED) {
                // The size contradicts what is already there: take it back out.
                target.constraint?.let { s.removeConstraint(it) }
                target.constraint = null
                s.solve()
                refresh("That size conflicts with the other constraints")
                return
            }
        } catch (e: SketchException) {
            refresh("Could not apply the size (error ${e.code})")
            return
        }
        refresh()
    }

    fun dispose() {
        viewport.onSingleTouch = null
        sketches.values.forEach { it.close() }
        sketches.clear()
    }

    // ---- Drawing ------------------------------------------------------------------

    private fun onTouch(phase: TouchPhase, x: Float, y: Float): Boolean {
        val activeTool = tool ?: return false
        return try {
            when (phase) {
                TouchPhase.DOWN -> begin(activeTool, x, y)
                TouchPhase.MOVE -> { drag(x, y); true }
                TouchPhase.UP -> { drag(x, y); finish(); true }
                TouchPhase.CANCEL -> { cancelDraft(); refresh(); true }
            }
        } catch (e: SketchException) {
            cancelDraft()
            refresh("Sketch error ${e.code}: ${e.message}")
            false
        }
    }

    private fun begin(activeTool: SketchTool, x: Float, y: Float): Boolean {
        val point = planePoint(x, y) ?: return false
        val s = sketches.getOrPut(plane) { Sketch() }
        // The size fields of the previous entity stay until a new entity is finished: a
        // two-finger orbit also starts with one finger down and must not wipe them.
        val start = snap(point, exclude = emptySet())
        val ids = when (activeTool) {
            SketchTool.LINE -> listOf(s.addLine(start.x, start.y, start.x, start.y))
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> List(4) { s.addLine(start.x, start.y, start.x, start.y) }
            SketchTool.POLYGON -> List(POLYGON_SIDES) { s.addLine(start.x, start.y, start.x, start.y) }
            SketchTool.CIRCLE -> listOf(s.addCircle(start.x, start.y, MIN_SIZE))
        }
        draft = Draft(activeTool, ids, start, end = start)
        snapMarker = start.takeIf { it.kind != SnapKind.NONE }
        refresh()
        return true
    }

    private fun drag(x: Float, y: Float) {
        val d = draft ?: return
        val s = sketch ?: return
        val point = planePoint(x, y) ?: return
        var end = snap(point, exclude = d.ids.toSet())
        when (d.tool) {
            SketchTool.LINE -> {
                // Straighten nearly horizontal or vertical lines, unless a point was snapped.
                d.horizontal = false
                d.vertical = false
                if (end.point == null) {
                    val dx = abs(end.x - d.start.x)
                    val dy = abs(end.y - d.start.y)
                    if (dy <= dx * AXIS_TANGENT && dx > 0.0) {
                        end = end.copy(y = d.start.y); d.horizontal = true
                    } else if (dx <= dy * AXIS_TANGENT && dy > 0.0) {
                        end = end.copy(x = d.start.x); d.vertical = true
                    }
                }
                s.setPoint(PointRef(d.ids[0], PointRole.END), end.x, end.y)
            }
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> {
                val (bottom, right, top, left) = d.ids
                // Diagonal: dragged corner to corner. Centre: start is the middle, end a corner.
                val centered = d.tool == SketchTool.CENTER_RECTANGLE
                val ax = if (centered) 2 * d.start.x - end.x else d.start.x
                val ay = if (centered) 2 * d.start.y - end.y else d.start.y
                s.setPoint(PointRef(bottom, PointRole.START), ax, ay)
                s.setPoint(PointRef(bottom, PointRole.END), end.x, ay)
                s.setPoint(PointRef(right, PointRole.START), end.x, ay)
                s.setPoint(PointRef(right, PointRole.END), end.x, end.y)
                s.setPoint(PointRef(top, PointRole.START), end.x, end.y)
                s.setPoint(PointRef(top, PointRole.END), ax, end.y)
                s.setPoint(PointRef(left, PointRole.START), ax, end.y)
                s.setPoint(PointRef(left, PointRole.END), ax, ay)
            }
            SketchTool.CIRCLE ->
                s.setCircleRadius(d.ids[0], hypot(end.x - d.start.x, end.y - d.start.y).coerceAtLeast(MIN_SIZE))
            SketchTool.POLYGON -> {
                // Regular polygon: start is the centre, end one vertex.
                val r = hypot(end.x - d.start.x, end.y - d.start.y)
                val a0 = kotlin.math.atan2(end.y - d.start.y, end.x - d.start.x)
                fun vx(k: Int) = d.start.x + r * kotlin.math.cos(a0 + 2 * Math.PI * k / POLYGON_SIDES)
                fun vy(k: Int) = d.start.y + r * kotlin.math.sin(a0 + 2 * Math.PI * k / POLYGON_SIDES)
                d.ids.forEachIndexed { k, line ->
                    s.setPoint(PointRef(line, PointRole.START), vx(k), vy(k))
                    s.setPoint(PointRef(line, PointRole.END), vx(k + 1), vy(k + 1))
                }
            }
        }
        d.end = end
        snapMarker = end.takeIf { it.kind != SnapKind.NONE }
        refresh()
    }

    private fun finish() {
        val d = draft ?: return
        val s = sketch ?: return
        draft = null
        snapMarker = null
        val width = abs(d.end.x - d.start.x)
        val height = abs(d.end.y - d.start.y)
        val tooSmall = when (d.tool) {
            SketchTool.LINE, SketchTool.CIRCLE, SketchTool.POLYGON -> hypot(width, height) < MIN_SIZE * 10
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> width < MIN_SIZE * 10 || height < MIN_SIZE * 10
        }
        if (tooSmall) {
            d.ids.forEach { s.removeEntity(it) }
            refresh()
            return
        }

        val added = mutableListOf<Int>()
        when (d.tool) {
            SketchTool.LINE -> {
                val line = d.ids[0]
                d.start.point?.let { added += s.addCoincident(PointRef(line, PointRole.START), it) }
                d.end.point?.let { added += s.addCoincident(PointRef(line, PointRole.END), it) }
                if (d.horizontal) added += s.addHorizontal(line)
                if (d.vertical) added += s.addVertical(line)
                dimensionTargets = listOf(
                    DimensionTarget.Distance("Length", PointRef(line, PointRole.START), PointRef(line, PointRole.END)),
                )
            }
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> {
                val (bottom, right, top, left) = d.ids
                added += s.addCoincident(PointRef(bottom, PointRole.END), PointRef(right, PointRole.START))
                added += s.addCoincident(PointRef(right, PointRole.END), PointRef(top, PointRole.START))
                added += s.addCoincident(PointRef(top, PointRole.END), PointRef(left, PointRole.START))
                added += s.addCoincident(PointRef(left, PointRole.END), PointRef(bottom, PointRole.START))
                added += s.addHorizontal(bottom)
                added += s.addHorizontal(top)
                added += s.addVertical(right)
                added += s.addVertical(left)
                if (d.tool == SketchTool.RECTANGLE) {
                    d.start.point?.let { added += s.addCoincident(PointRef(bottom, PointRole.START), it) }
                }
                d.end.point?.let { added += s.addCoincident(PointRef(right, PointRole.END), it) }
                dimensionTargets = listOf(
                    DimensionTarget.Distance("Width", PointRef(bottom, PointRole.START), PointRef(bottom, PointRole.END)),
                    DimensionTarget.Distance("Height", PointRef(right, PointRole.START), PointRef(right, PointRole.END)),
                )
            }
            SketchTool.POLYGON -> {
                d.ids.forEachIndexed { k, line ->
                    val next = d.ids[(k + 1) % d.ids.size]
                    added += s.addCoincident(PointRef(line, PointRole.END), PointRef(next, PointRole.START))
                    if (k > 0) added += s.addEqualLength(d.ids[0], line) // regular: one Side value sets all
                }
                dimensionTargets = listOf(
                    DimensionTarget.Distance("Side", PointRef(d.ids[0], PointRole.START), PointRef(d.ids[0], PointRole.END)),
                )
            }
            SketchTool.CIRCLE -> {
                val circle = d.ids[0]
                d.start.point?.let { added += s.addCoincident(PointRef(circle, PointRole.CENTER), it) }
                dimensionTargets = listOf(DimensionTarget.Radius("Radius", circle))
            }
        }

        if (s.solve().status != SolveStatus.SOLVED) {
            // The automatic constraints clash with existing ones: keep the shape, drop them.
            added.forEach { s.removeConstraint(it) }
            s.solve()
            refresh("Drawn without automatic constraints (they conflicted)")
        } else {
            refresh()
        }
    }

    private fun cancelDraft() {
        val d = draft ?: return
        draft = null
        snapMarker = null
        runCatching { d.ids.forEach { sketch?.removeEntity(it) } }
    }

    // ---- Helpers ------------------------------------------------------------------

    private fun planePoint(x: Float, y: Float): DoubleArray? {
        val ray = viewport.screenRay(x, y) ?: return null
        return plane.intersect(
            doubleArrayOf(ray[0], ray[1], ray[2]),
            doubleArrayOf(ray[3], ray[4], ray[5]),
        )
    }

    private fun snap(point: DoubleArray, exclude: Set<Int>): Snap {
        val tolerance = viewport.millimetresPerPixel() * SNAP_PIXELS
        return Snapper.snap(
            point[0], point[1], sketch?.entities().orEmpty(), tolerance, GRID_SPACING_MM, exclude,
        )
    }

    private fun lookAtPlane() {
        when (plane) {
            SketchPlane.XY -> viewport.setViewDirection(Math.toRadians(-90.0), Math.toRadians(90.0))
            SketchPlane.XZ -> viewport.setViewDirection(Math.toRadians(-90.0), 0.0)
            else -> viewport.setViewDirection(0.0, 0.0)
        }
    }

    private fun clearDimensions() {
        dimensionTargets = emptyList()
        dimensions = emptyList()
    }

    /** Pushes the current sketch to the viewport and updates the readouts. */
    private fun refresh(note: String? = null) {
        revision++
        val entities = sketch?.entities().orEmpty()
        isEmpty = entities.isEmpty()

        // Every plane's sketch is shown, not just the one being edited.
        var segments = sketches.entries.fold(FloatArray(0)) { acc, (p, sk) ->
            acc + SketchLines.segments(if (p == plane) entities else sk.entities(), p)
        }
        snapMarker?.let { marker ->
            val size = viewport.millimetresPerPixel() * MARKER_PIXELS
            segments += SketchLines.marker(marker.x, marker.y, size, plane)
        }
        viewport.setOverlayLines(segments.takeIf { it.isNotEmpty() })

        dimensions = dimensionTargets.mapNotNull { target ->
            when (target) {
                is DimensionTarget.Distance -> {
                    val line = entities.firstOrNull { it.id == target.a.entity } as? SketchLine
                    line?.let { DimensionField(target.label, hypot(it.x2 - it.x1, it.y2 - it.y1)) }
                }
                is DimensionTarget.Radius -> {
                    val circle = entities.firstOrNull { it.id == target.circle } as? SketchCircle
                    circle?.let { DimensionField(target.label, it.radius) }
                }
            }
        }
        if (dimensions.size != dimensionTargets.size) clearDimensions()

        message = note ?: when {
            !active -> ""
            draft != null -> snapMarker?.let { "snap: ${it.kind.name.lowercase()}" } ?: ""
            isEmpty -> if (tool == null) "Pick a tool, then drag with one finger" else "Drag with one finger to draw"
            else -> {
                val dof = runCatching { sketch?.solve()?.degreesOfFreedom }.getOrNull()
                val state = when (dof) {
                    null, -1 -> ""
                    0 -> " · fully constrained"
                    else -> " · $dof degrees of freedom"
                }
                "${entities.size} entities$state"
            }
        }
        // Sketch state colour: green when fully defined, blue while it can still move.
        val fullyDefined = !isEmpty && runCatching { sketch?.solve()?.degreesOfFreedom }.getOrNull() == 0
        viewport.setOverlayColor(if (fullyDefined) FULLY_DEFINED_SRGB else UNDER_DEFINED_SRGB)
    }

    private class Draft(val tool: SketchTool, val ids: List<Int>, val start: Snap, var end: Snap) {
        var horizontal = false
        var vertical = false
    }

    private sealed class DimensionTarget(val label: String) {
        /** Id of the constraint once the user has typed a value. */
        var constraint: Int? = null

        class Distance(label: String, val a: PointRef, val b: PointRef) : DimensionTarget(label)
        class Radius(label: String, val circle: Int) : DimensionTarget(label)
    }

    companion object {
        val PLANES = listOf(SketchPlane.XY, SketchPlane.XZ, SketchPlane.YZ)

        private const val POLYGON_SIDES = 6
        private const val UNDER_DEFINED_SRGB = 0x5AA2FF
        private const val FULLY_DEFINED_SRGB = 0x46C46E

        /** Matches the viewport grid pitch. */
        private const val GRID_SPACING_MM = 10.0

        /** Finger-sized snapping radius on screen. */
        private const val SNAP_PIXELS = 28.0
        private const val MARKER_PIXELS = 14.0

        /** Smallest size an entity may have, in millimetres. */
        private const val MIN_SIZE = 1e-3

        /** tan(4°): lines closer than this to an axis are straightened. */
        private const val AXIS_TANGENT = 0.07
    }
}
