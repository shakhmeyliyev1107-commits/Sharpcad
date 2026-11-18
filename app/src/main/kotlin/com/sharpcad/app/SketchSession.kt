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
import com.sharpcad.sketch.Trim
import com.sharpcad.sketch.Spline
import com.sharpcad.sketch.SolveStatus
import com.sharpcad.ui.workspace.DimensionField
import com.sharpcad.viewport.TouchPhase
import com.sharpcad.viewport.ViewportController
import kotlin.math.abs
import kotlin.math.hypot

/** Sketch geometry as flat arrays for the kernel (see Profile). */
/** [splines]: per spline a point count n then n (x, y) points, as the kernel takes them. */
data class SketchGeometry(val lines: List<Double>, val circles: List<Double>, val arcs: List<Double>, val splines: List<Double> = emptyList())

enum class SketchTool(val label: String) {
    LINE("Line"),
    RECTANGLE("Rectangle"),
    CENTER_RECTANGLE("Center rect"),
    CIRCLE("Circle"),
    POLYGON("Hexagon"),
    ARC("Arc"),
    TRIM("Trim"),
    OFFSET("Offset"),
    PATTERN("Pattern"),
    CIRCULAR("Circular pattern"),
    SPLINE("Spline"),
    TEXT("Text"),
    HORIZONTAL("Horizontal"),
    VERTICAL("Vertical"),
    EQUAL("Equal"),
    PARALLEL("Parallel"),
    PERPENDICULAR("Perpendicular"),
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
        get() = PLANES.filter { p ->
            sketches[p]?.entities()?.isNotEmpty() == true || splines[p]?.isNotEmpty() == true || texts[p]?.isNotEmpty() == true
        }

    /** Splines per plane as control points (u, v pairs); not part of the constraint solver. */
    private val splines = mutableMapOf<SketchPlane, MutableList<List<Double>>>()

    /** Text outlines per plane: closed polylines (x,y …), outside the solver like splines. */
    private val texts = mutableMapOf<SketchPlane, MutableList<List<Double>>>()

    /** What the Text tool writes. */
    var textValue by mutableStateOf("SharpCAD")
    private var splineDraft: MutableList<Double>? = null

    /**
     * Lines (x1, y1, x2, y2…) and circles (cx, cy, r…) of the sketch on [plane], in plane
     * coordinates; used to build profiles for extrude and revolve.
     */
    fun geometry(plane: SketchPlane): SketchGeometry {
        val lines = mutableListOf<Double>()
        val circles = mutableListOf<Double>()
        val arcs = mutableListOf<Double>()
        sketches[plane]?.entities()?.forEach { e ->
            when (e) {
                is SketchLine -> lines += listOf(e.x1, e.y1, e.x2, e.y2)
                is SketchCircle -> circles += listOf(e.cx, e.cy, e.radius)
                is com.sharpcad.sketch.SketchArc -> arcs += listOf(e.cx, e.cy, e.radius, e.startAngle, e.endAngle)
            }
        }
        texts[plane]?.forEach { lines += TextOutline.segments(it) }
        val curves = mutableListOf<Double>()
        splines[plane]?.forEach { pts -> curves += (pts.size / 2).toDouble(); curves += pts }
        return SketchGeometry(lines, circles, arcs, curves)
    }

    /** Bumped whenever sketch geometry changes, so cached profiles can be dropped. */
    var revision by mutableStateOf(0)
        private set

    fun toggle() = if (active) exit() else enter()

    fun enter() {
        if (active) return
        active = true
        lookAtPlane()
        refresh()
    }

    fun exit() {
        if (!active) return
        cancelDraft()
        active = false
        tool = null
        clearDimensions()
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
        pendingPair = null
        val picked = SketchTool.entries.firstOrNull { it.label == label }
        tool = if (picked == tool) null else picked
        clearDimensions()
        refresh()
    }

    /** Clears the sketch on the selected plane only. */
    fun clear() {
        cancelDraft()
        sketches.remove(plane)?.close()
        splines.remove(plane)
        texts.remove(plane)
        clearDimensions()
        refresh()
    }

    /** Applies a typed size to the entity just drawn. */
    fun setDimension(index: Int, millimetres: Double) {
        val s = sketch ?: return
        val target = dimensionTargets.getOrNull(index) ?: return
        if (target is DimensionTarget.Count) {
            val count = millimetres.toInt().coerceIn(2, MAX_PATTERN)
            target.ids.forEach { runCatching { s.removeEntity(it) } }
            target.ids.clear()
            for (k in 1 until count) {
                target.ids += addShifted(s, placed(target.source, k * target.dx, k * target.dy, target.center, 2 * Math.PI * k / count), 0.0, 0.0)
            }
            refresh()
            return
        }
        try {
            val existing = target.constraint
            if (existing != null) {
                s.setDimension(existing, millimetres)
            } else {
                target.constraint = when (target) {
                    is DimensionTarget.Count -> return
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

    fun onTouch(phase: TouchPhase, x: Float, y: Float): Boolean {
        val activeTool = tool ?: return false
        if (activeTool == SketchTool.SPLINE) return splineTouch(phase, x, y)
        if (activeTool == SketchTool.TEXT) return textTouch(phase, x, y)
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
        when (activeTool) {
            SketchTool.TRIM -> return trimAt(point)
            SketchTool.OFFSET -> return beginOffset(point)
            SketchTool.PATTERN, SketchTool.CIRCULAR -> return beginPattern(activeTool, point)
            SketchTool.HORIZONTAL, SketchTool.VERTICAL, SketchTool.EQUAL,
            SketchTool.PARALLEL, SketchTool.PERPENDICULAR -> return constrainAt(activeTool, point)
            else -> Unit
        }
        val s = sketches.getOrPut(plane) { Sketch() }
        // The size fields of the previous entity stay until a new entity is finished: a
        // two-finger orbit also starts with one finger down and must not wipe them.
        val start = snap(point, exclude = emptySet())
        val ids = when (activeTool) {
            SketchTool.LINE -> listOf(s.addLine(start.x, start.y, start.x, start.y))
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> List(4) { s.addLine(start.x, start.y, start.x, start.y) }
            SketchTool.POLYGON -> List(POLYGON_SIDES) { s.addLine(start.x, start.y, start.x, start.y) }
            SketchTool.CIRCLE -> listOf(s.addCircle(start.x, start.y, MIN_SIZE))
            SketchTool.ARC -> listOf(s.addArc(start.x, start.y, MIN_SIZE, 0.0, Math.PI))
            else -> return false
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
            SketchTool.ARC -> {
                // Half circle over the dragged chord, counter-clockwise from start to end.
                val cx = (d.start.x + end.x) / 2
                val cy = (d.start.y + end.y) / 2
                val r = (hypot(end.x - d.start.x, end.y - d.start.y) / 2).coerceAtLeast(MIN_SIZE)
                val a0 = kotlin.math.atan2(d.start.y - cy, d.start.x - cx)
                s.setArc(d.ids[0], cx, cy, r, a0, a0 + Math.PI)
            }
            SketchTool.OFFSET -> offsetTo(d, end)
            SketchTool.PATTERN, SketchTool.CIRCULAR -> patternTo(d, end)
            SketchTool.TRIM, SketchTool.SPLINE, SketchTool.TEXT, SketchTool.HORIZONTAL, SketchTool.VERTICAL, SketchTool.EQUAL,
            SketchTool.PARALLEL, SketchTool.PERPENDICULAR -> Unit
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
            SketchTool.RECTANGLE, SketchTool.CENTER_RECTANGLE -> width < MIN_SIZE * 10 || height < MIN_SIZE * 10
            else -> hypot(width, height) < MIN_SIZE * 10
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
            SketchTool.OFFSET -> d.chain?.let { chain ->
                // Keep the offset outline joined at its corners.
                for (i in 0 until d.ids.size - 1) {
                    added += s.addCoincident(PointRef(d.ids[i], PointRole.END), PointRef(d.ids[i + 1], PointRole.START))
                }
                if (chain.closed) added += s.addCoincident(PointRef(d.ids.last(), PointRole.END), PointRef(d.ids[0], PointRole.START))
            }
            SketchTool.PATTERN -> {
                val n = d.ids.size.toDouble()
                dimensionTargets = listOf(
                    DimensionTarget.Count("Count", d.source!!, (d.end.x - d.start.x) / n, (d.end.y - d.start.y) / n, d.ids.toMutableList()),
                )
            }
            SketchTool.CIRCULAR -> dimensionTargets = listOf(
                DimensionTarget.Count("Count", d.source!!, 0.0, 0.0, d.ids.toMutableList(), center = d.end.x to d.end.y),
            )
            SketchTool.ARC -> {
                val arc = d.ids[0]
                d.start.point?.let { added += s.addCoincident(PointRef(arc, PointRole.START), it) }
                d.end.point?.let { added += s.addCoincident(PointRef(arc, PointRole.END), it) }
                dimensionTargets = listOf(DimensionTarget.Radius("Radius", arc))
            }
            else -> Unit
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

    /** Offset tool: press on an entity and drag away; a parallel copy follows the finger. */
    private fun beginOffset(point: DoubleArray): Boolean {
        val s = sketch ?: return true
        val tolerance = viewport.millimetresPerPixel() * SNAP_PIXELS
        val target = s.entities().filter { Trim.distance(it, point[0], point[1]) <= tolerance }
            .minByOrNull { Trim.distance(it, point[0], point[1]) }
        if (target == null) {
            refresh("Press on a line, arc or circle and drag to offset it")
            return true
        }
        if (target is SketchLine) {
            val chain = com.sharpcad.sketch.ChainOffset.chain(s.entities(), target)
            if (chain.lines.size > 1) {
                val p = chain.points
                val ids = List(p.size / 2 - 1) { i -> s.addLine(p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]) }
                val start = Snap(point[0], point[1], SnapKind.NONE)
                draft = Draft(SketchTool.OFFSET, ids, start, start).also { it.source = target; it.chain = chain }
                refresh()
                return true
            }
        }
        val copy = when (target) {
            is SketchLine -> s.addLine(target.x1, target.y1, target.x2, target.y2)
            is SketchCircle -> s.addCircle(target.cx, target.cy, target.radius)
            is com.sharpcad.sketch.SketchArc ->
                s.addArc(target.cx, target.cy, target.radius, target.startAngle, target.endAngle)
        }
        val start = Snap(point[0], point[1], SnapKind.NONE)
        draft = Draft(SketchTool.OFFSET, listOf(copy), start, start).also { it.source = target }
        refresh()
        return true
    }

    /** Spline tool: freehand stroke, thinned to control points of a smooth curve. */
    private fun splineTouch(phase: TouchPhase, x: Float, y: Float): Boolean {
        val point = planePoint(x, y)
        when (phase) {
            TouchPhase.DOWN -> {
                point ?: return false
                val start = snap(point, exclude = emptySet())
                splineDraft = mutableListOf(start.x, start.y)
            }
            TouchPhase.MOVE -> point?.let { splineDraft?.addAll(listOf(it[0], it[1])) }
            TouchPhase.UP -> {
                val raw = splineDraft ?: return true
                point?.let { val end = snap(it, exclude = emptySet()); raw += listOf(end.x, end.y) }
                splineDraft = null
                val spacing = viewport.millimetresPerPixel() * SPLINE_SPACING_PIXELS
                val points = Spline.thin(raw, spacing)
                if (points.size >= 4 && kotlin.math.hypot(points[2] - points[0], points[3] - points[1]) > MIN_SIZE * 10) {
                    splines.getOrPut(plane) { mutableListOf() } += points
                }
            }
            TouchPhase.CANCEL -> splineDraft = null
        }
        refresh()
        return true
    }

    private var textStart: DoubleArray? = null

    /**
     * Text tool: press sets the baseline start; dragging up sets the height (a plain tap
     * uses [DEFAULT_TEXT_MM]). The outlines land on the plane as closed polylines.
     */
    private fun textTouch(phase: TouchPhase, x: Float, y: Float): Boolean {
        val point = planePoint(x, y)
        when (phase) {
            TouchPhase.DOWN -> textStart = point ?: return false
            TouchPhase.MOVE -> Unit
            TouchPhase.UP -> {
                val start = textStart ?: return true
                textStart = null
                val dragged = point?.let { kotlin.math.abs(it[1] - start[1]) } ?: 0.0
                val height = if (dragged > viewport.millimetresPerPixel() * SNAP_PIXELS) dragged else DEFAULT_TEXT_MM
                val contours = TextOutline.contours(textValue, start[0], start[1], height)
                if (contours.isNotEmpty()) texts.getOrPut(plane) { mutableListOf() } += contours
            }
            TouchPhase.CANCEL -> textStart = null
        }
        refresh()
        return true
    }

    /** Pattern tool: press on an entity and drag; copies spread evenly up to the finger. */
    private fun beginPattern(activeTool: SketchTool, point: DoubleArray): Boolean {
        val s = sketch ?: return true
        val tolerance = viewport.millimetresPerPixel() * SNAP_PIXELS
        val target = s.entities().filter { Trim.distance(it, point[0], point[1]) <= tolerance }
            .minByOrNull { Trim.distance(it, point[0], point[1]) }
        if (target == null) {
            refresh("Press on a line, arc or circle and drag to pattern it")
            return true
        }
        val copies = if (activeTool == SketchTool.CIRCULAR) DEFAULT_CIRCULAR - 1 else DEFAULT_PATTERN - 1
        val ids = List(copies) { addShifted(s, target, 0.0, 0.0) }
        val start = Snap(point[0], point[1], SnapKind.NONE)
        draft = Draft(activeTool, ids, start, start).also { it.source = target }
        refresh()
        return true
    }

    private fun patternTo(d: Draft, end: Snap) {
        val s = sketch ?: return
        val src = d.source ?: return
        val n = d.ids.size.toDouble()
        d.ids.forEachIndexed { i, id ->
            val f = (i + 1) / n
            val ox = (end.x - d.start.x) * f
            val oy = (end.y - d.start.y) * f
            // Circular: the finger is the centre; copies go evenly round 360°.
            val placedSrc = if (d.tool == SketchTool.CIRCULAR) {
                placed(src, 0.0, 0.0, end.x to end.y, 2 * Math.PI * (i + 1) / (n + 1))
            } else {
                placed(src, ox, oy, null, 0.0)
            }
            when (val e = placedSrc) {
                is SketchLine -> {
                    s.setPoint(PointRef(id, PointRole.START), e.x1, e.y1)
                    s.setPoint(PointRef(id, PointRole.END), e.x2, e.y2)
                }
                is SketchCircle -> s.setPoint(PointRef(id, PointRole.CENTER), e.cx, e.cy)
                is com.sharpcad.sketch.SketchArc -> s.setArc(id, e.cx, e.cy, e.radius, e.startAngle, e.endAngle)
            }
        }
    }

    /** [e] rotated by [angle] about [center] (if any), then moved by ([ox], [oy]). */
    private fun placed(
        e: com.sharpcad.sketch.SketchEntity, ox: Double, oy: Double, center: Pair<Double, Double>?, angle: Double,
    ): com.sharpcad.sketch.SketchEntity {
        val c = kotlin.math.cos(angle)
        val sn = kotlin.math.sin(angle)
        fun px(x: Double, y: Double) = if (center == null) x + ox else center.first + (x - center.first) * c - (y - center.second) * sn + ox
        fun py(x: Double, y: Double) = if (center == null) y + oy else center.second + (x - center.first) * sn + (y - center.second) * c + oy
        val turn = if (center == null) 0.0 else angle
        return when (e) {
            is SketchLine -> SketchLine(e.id, px(e.x1, e.y1), py(e.x1, e.y1), px(e.x2, e.y2), py(e.x2, e.y2))
            is SketchCircle -> SketchCircle(e.id, px(e.cx, e.cy), py(e.cx, e.cy), e.radius)
            is com.sharpcad.sketch.SketchArc ->
                com.sharpcad.sketch.SketchArc(e.id, px(e.cx, e.cy), py(e.cx, e.cy), e.radius, e.startAngle + turn, e.endAngle + turn)
        }
    }

    private fun addShifted(s: Sketch, e: com.sharpcad.sketch.SketchEntity, ox: Double, oy: Double): Int = when (e) {
        is SketchLine -> s.addLine(e.x1 + ox, e.y1 + oy, e.x2 + ox, e.y2 + oy)
        is SketchCircle -> s.addCircle(e.cx + ox, e.cy + oy, e.radius)
        is com.sharpcad.sketch.SketchArc -> s.addArc(e.cx + ox, e.cy + oy, e.radius, e.startAngle, e.endAngle)
    }

    private fun offsetTo(d: Draft, end: Snap) {
        val s = sketch ?: return
        d.chain?.let { chain ->
            val dist = com.sharpcad.sketch.ChainOffset.side(chain, end.x, end.y)
            val p = com.sharpcad.sketch.ChainOffset.offset(chain, dist)
            d.ids.forEachIndexed { i, line ->
                s.setPoint(PointRef(line, PointRole.START), p[2 * i], p[2 * i + 1])
                s.setPoint(PointRef(line, PointRole.END), p[2 * i + 2], p[2 * i + 3])
            }
            d.offset = abs(dist)
            return
        }
        val id = d.ids[0]
        when (val src = d.source) {
            is SketchLine -> {
                val len = hypot(src.x2 - src.x1, src.y2 - src.y1).coerceAtLeast(MIN_SIZE)
                val nx = -(src.y2 - src.y1) / len
                val ny = (src.x2 - src.x1) / len
                val dist = (end.x - src.x1) * nx + (end.y - src.y1) * ny
                s.setPoint(PointRef(id, PointRole.START), src.x1 + nx * dist, src.y1 + ny * dist)
                s.setPoint(PointRef(id, PointRole.END), src.x2 + nx * dist, src.y2 + ny * dist)
                d.offset = abs(dist)
            }
            is SketchCircle -> {
                val r = hypot(end.x - src.cx, end.y - src.cy).coerceAtLeast(MIN_SIZE)
                s.setCircleRadius(id, r)
                d.offset = abs(r - src.radius)
            }
            is com.sharpcad.sketch.SketchArc -> {
                val r = hypot(end.x - src.cx, end.y - src.cy).coerceAtLeast(MIN_SIZE)
                s.setArc(id, src.cx, src.cy, r, src.startAngle, src.endAngle)
                d.offset = abs(r - src.radius)
            }
            null -> Unit
        }
    }

    private var pendingPair: Int? = null

    /** Constraint tools: tap a line (or two lines for the pair constraints). */
    private fun constrainAt(tool: SketchTool, point: DoubleArray): Boolean {
        val s = sketch ?: return true
        val tolerance = viewport.millimetresPerPixel() * SNAP_PIXELS
        val line = s.entities().filterIsInstance<SketchLine>()
            .filter { Trim.distance(it, point[0], point[1]) <= tolerance }
            .minByOrNull { Trim.distance(it, point[0], point[1]) }
        if (line == null) {
            refresh("Tap a line")
            return true
        }
        val first = pendingPair
        val constraint = when (tool) {
            SketchTool.HORIZONTAL -> s.addHorizontal(line.id)
            SketchTool.VERTICAL -> s.addVertical(line.id)
            else -> {
                if (first == null || first == line.id) {
                    pendingPair = line.id
                    refresh("Now tap the second line")
                    return true
                }
                pendingPair = null
                when (tool) {
                    SketchTool.EQUAL -> s.addEqualLength(first, line.id)
                    SketchTool.PARALLEL -> s.addParallel(first, line.id)
                    else -> s.addPerpendicular(first, line.id)
                }
            }
        }
        if (s.solve().status != SolveStatus.SOLVED) {
            s.removeConstraint(constraint)
            s.solve()
            refresh("That constraint conflicts with the others")
        } else {
            refresh()
        }
        return true
    }

    /** Trim tool: cuts the tapped piece back to its nearest intersections. */
    private fun trimAt(point: DoubleArray): Boolean {
        val s = sketch ?: return true
        val tolerance = viewport.millimetresPerPixel() * SNAP_PIXELS
        val result = Trim.trim(s.entities(), point[0], point[1], tolerance)
        if (result == null) {
            refresh("Tap a line, arc or circle to trim it")
            return true
        }
        s.removeEntity(result.removed)
        result.lines.forEach { s.addLine(it[0], it[1], it[2], it[3]) }
        result.arcs.forEach { s.addArc(it[0], it[1], it[2], it[3], it[4]) }
        clearDimensions()
        refresh()
        return true
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
        when (plane.name.take(2)) {
            "XY" -> viewport.setViewDirection(Math.toRadians(-90.0), Math.toRadians(90.0))
            "XZ" -> viewport.setViewDirection(Math.toRadians(-90.0), 0.0)
            else -> viewport.setViewDirection(0.0, 0.0)
        }
    }

    private fun clearDimensions() {
        dimensionTargets = emptyList()
        dimensions = emptyList()
    }

    /** Pushes the current sketch to the viewport and updates the readouts. */
    /** Extra model-space line segments drawn with the sketch overlay (a picked edge). */
    private var highlightLines = FloatArray(0)

    fun setHighlightLines(segments: FloatArray?) {
        highlightLines = segments ?: FloatArray(0)
        refresh()
    }

    private fun refresh(note: String? = null) {
        revision++
        val entities = sketch?.entities().orEmpty()
        isEmpty = entities.isEmpty() && splines[plane].isNullOrEmpty() && texts[plane].isNullOrEmpty()

        // Every plane's sketch is shown, not just the one being edited.
        var segments = sketches.entries.fold(FloatArray(0)) { acc, (p, sk) ->
            acc + SketchLines.segments(if (p == plane) entities else sk.entities(), p)
        }
        fun polyline(flat: List<Double>, p: SketchPlane): FloatArray {
            val out = FloatArray(flat.size / 4 * 6)
            var o = 0
            for (i in flat.indices step 2) {
                val w = p.toWorld(flat[i], flat[i + 1])
                out[o++] = w[0].toFloat(); out[o++] = w[1].toFloat(); out[o++] = w[2].toFloat()
            }
            return out
        }
        splines.forEach { (p, list) -> list.forEach { segments += polyline(Spline.segments(it), p) } }
        texts.forEach { (p, list) -> list.forEach { segments += polyline(TextOutline.segments(it), p) } }
        splineDraft?.let { raw ->
            val segs = (0 until raw.size / 2 - 1).flatMap { listOf(raw[2 * it], raw[2 * it + 1], raw[2 * it + 2], raw[2 * it + 3]) }
            segments += polyline(segs, plane)
        }
        snapMarker?.let { marker ->
            val size = viewport.millimetresPerPixel() * MARKER_PIXELS
            segments += SketchLines.marker(marker.x, marker.y, size, plane)
        }
        segments += highlightLines
        viewport.setOverlayLines(segments.takeIf { it.isNotEmpty() })

        dimensions = dimensionTargets.mapNotNull { target ->
            when (target) {
                is DimensionTarget.Distance -> {
                    val line = entities.firstOrNull { it.id == target.a.entity } as? SketchLine
                    line?.let { DimensionField(target.label, hypot(it.x2 - it.x1, it.y2 - it.y1)) }
                }
                is DimensionTarget.Count -> DimensionField(target.label, target.ids.size + 1.0, unit = "")
                is DimensionTarget.Radius -> {
                    when (val e = entities.firstOrNull { it.id == target.circle }) {
                        is SketchCircle -> DimensionField(target.label, e.radius)
                        is com.sharpcad.sketch.SketchArc -> DimensionField(target.label, e.radius)
                        else -> null
                    }
                }
            }
        }
        if (dimensions.size != dimensionTargets.size) clearDimensions()

        message = note ?: when {
            !active -> ""
            tool == SketchTool.TEXT -> "Tap to place the text; drag up to set its height"
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
        var source: com.sharpcad.sketch.SketchEntity? = null
        var offset = 0.0
        var chain: com.sharpcad.sketch.ChainOffset.Chain? = null
    }

    private sealed class DimensionTarget(val label: String) {
        /** Id of the constraint once the user has typed a value. */
        var constraint: Int? = null

        class Distance(label: String, val a: PointRef, val b: PointRef) : DimensionTarget(label)
        class Radius(label: String, val circle: Int) : DimensionTarget(label)

        /** Linear sketch pattern: copies of [source] at k·([dx], [dy]). */
        class Count(
            label: String,
            val source: com.sharpcad.sketch.SketchEntity,
            val dx: Double,
            val dy: Double,
            val ids: MutableList<Int>,
            /** Centre of a circular pattern; `null` for a linear one. */
            val center: Pair<Double, Double>? = null,
        ) : DimensionTarget(label)
    }

    companion object {
        val PLANES = listOf(
            SketchPlane.XY, SketchPlane.XZ, SketchPlane.YZ,
            offset(SketchPlane.XY, 50.0), offset(SketchPlane.XY, 100.0), offset(SketchPlane.XZ, 50.0), offset(SketchPlane.YZ, 50.0),
        )

        /** Construction plane parallel to [base] at [mm] along its normal (used by Loft). */
        private fun offset(base: SketchPlane, mm: Double) = SketchPlane(
            "${base.name}+${mm.toInt()}",
            DoubleArray(3) { base.origin[it] + base.normal[it] * mm }, base.xAxis, base.yAxis,
        )

        private const val POLYGON_SIDES = 6
        private const val DEFAULT_PATTERN = 3
        private const val DEFAULT_CIRCULAR = 6
        const val DEFAULT_TEXT_MM = 10.0
        private const val SPLINE_SPACING_PIXELS = 40.0
        private const val MAX_PATTERN = 50
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
