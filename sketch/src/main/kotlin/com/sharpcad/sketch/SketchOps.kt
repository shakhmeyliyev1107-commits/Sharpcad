package com.sharpcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Pure geometry for the sketch Mirror and Fillet tools. */
object SketchOps {
    /** What to add: lines (x1,y1,x2,y2), circles (cx,cy,r), arcs (cx,cy,r,start,end ccw). */
    data class Shapes(val lines: List<DoubleArray> = emptyList(), val circles: List<DoubleArray> = emptyList(), val arcs: List<DoubleArray> = emptyList())

    /** [entity] reflected across the infinite line through [axis]. */
    fun mirror(entity: SketchEntity, axis: SketchLine): Shapes {
        val dx = axis.x2 - axis.x1
        val dy = axis.y2 - axis.y1
        val len2 = dx * dx + dy * dy
        require(len2 > 1e-18) { "Mirror line has no length" }
        fun reflect(x: Double, y: Double): Pair<Double, Double> {
            val t = ((x - axis.x1) * dx + (y - axis.y1) * dy) / len2
            val px = axis.x1 + t * dx
            val py = axis.y1 + t * dy
            return (2 * px - x) to (2 * py - y)
        }
        // Reflection flips orientation: angle a maps to 2φ − a, so start and end swap.
        val phi = atan2(dy, dx)
        return when (entity) {
            is SketchLine -> {
                val (ax, ay) = reflect(entity.x1, entity.y1)
                val (bx, by) = reflect(entity.x2, entity.y2)
                Shapes(lines = listOf(doubleArrayOf(ax, ay, bx, by)))
            }
            is SketchCircle -> {
                val (cx, cy) = reflect(entity.cx, entity.cy)
                Shapes(circles = listOf(doubleArrayOf(cx, cy, entity.radius)))
            }
            is SketchArc -> {
                val (cx, cy) = reflect(entity.cx, entity.cy)
                Shapes(arcs = listOf(doubleArrayOf(cx, cy, entity.radius, 2 * phi - entity.endAngle, 2 * phi - entity.startAngle)))
            }
        }
    }

    /**
     * Rounds the corner where two lines meet at ([px], [py]) with radius [r].
     * [a] and [b] are the far ends of the two lines. Returns the new tangent points
     * (t1x, t1y, t2x, t2y) and the arc (cx, cy, r, start, end ccw), or `null` when the
     * lines are (anti)parallel or too short for that radius.
     */
    fun fillet(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, r: Double): Pair<DoubleArray, DoubleArray>? {
        val la = hypot(ax - px, ay - py)
        val lb = hypot(bx - px, by - py)
        if (la < 1e-9 || lb < 1e-9 || r <= 0) return null
        val u1x = (ax - px) / la; val u1y = (ay - py) / la
        val u2x = (bx - px) / lb; val u2y = (by - py) / lb
        val theta = kotlin.math.acos((u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0))
        if (theta < 1e-6 || abs(theta - PI) < 1e-6) return null
        val t = r / tan(theta / 2)
        if (t >= la || t >= lb) return null
        val bxv = u1x + u2x; val byv = u1y + u2y
        val bl = hypot(bxv, byv)
        val d = r / sin(theta / 2)
        val cx = px + bxv / bl * d
        val cy = py + byv / bl * d
        val t1x = px + u1x * t; val t1y = py + u1y * t
        val t2x = px + u2x * t; val t2y = py + u2y * t
        var s = atan2(t1y - cy, t1x - cx)
        var e = atan2(t2y - cy, t2x - cx)
        // The short way round, counter-clockwise.
        var sweep = (e - s) % (2 * PI); if (sweep < 0) sweep += 2 * PI
        if (sweep > PI) { val tmp = s; s = e; e = tmp }
        return doubleArrayOf(t1x, t1y, t2x, t2y) to doubleArrayOf(cx, cy, r, s, e)
    }
}
