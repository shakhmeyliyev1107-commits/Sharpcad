package com.sharpcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What trimming one entity does: [removed] goes away and the kept pieces are added back.
 *
 * @property lines kept line pieces as (x1, y1, x2, y2).
 * @property arcs kept arc pieces as (cx, cy, r, startAngle, endAngle), counter-clockwise.
 */
data class TrimResult(val removed: Int, val lines: List<DoubleArray>, val arcs: List<DoubleArray>)

/** Trim: cuts the tapped piece of an entity back to its nearest intersections. Pure geometry. */
object Trim {
    private const val TWO_PI = 2 * PI
    private const val EPS = 1e-9

    /**
     * Trims the entity nearest to ([x], [y]) within [tolerance]. The piece between the two
     * intersections around the tap is removed; an entity crossing nothing is removed whole.
     * Returns `null` when nothing is close enough.
     */
    fun trim(entities: List<SketchEntity>, x: Double, y: Double, tolerance: Double): TrimResult? {
        val target = entities
            .map { it to distance(it, x, y) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }?.first ?: return null
        val others = entities.filter { it.id != target.id }
        val hits = others.flatMap { intersections(target, it) }
        return when (target) {
            is SketchLine -> trimLine(target, hits, x, y)
            is SketchCircle -> trimCircle(target, hits, x, y)
            is SketchArc -> trimArc(target, hits, x, y)
        }
    }

    /** Distance from ([x], [y]) to the entity's curve. */
    fun distance(e: SketchEntity, x: Double, y: Double): Double = when (e) {
        is SketchLine -> {
            val dx = e.x2 - e.x1
            val dy = e.y2 - e.y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 < EPS) 0.0 else (((x - e.x1) * dx + (y - e.y1) * dy) / len2).coerceIn(0.0, 1.0)
            hypot(x - (e.x1 + t * dx), y - (e.y1 + t * dy))
        }
        is SketchCircle -> abs(hypot(x - e.cx, y - e.cy) - e.radius)
        is SketchArc -> {
            val a = atan2(y - e.cy, x - e.cx)
            if (onArc(e, a)) abs(hypot(x - e.cx, y - e.cy) - e.radius)
            else minOf(hypot(x - e.startX, y - e.startY), hypot(x - e.endX, y - e.endY))
        }
    }

    private fun span(a: SketchArc): Double {
        val s = norm(a.endAngle - a.startAngle)
        return if (s < EPS) TWO_PI else s
    }

    private fun norm(a: Double): Double {
        var v = a % TWO_PI
        if (v < 0) v += TWO_PI
        return v
    }

    private fun onArc(a: SketchArc, angle: Double) = norm(angle - a.startAngle) <= span(a) + EPS

    /** Intersection points of the curves of [a] and [b], restricted to both entities. */
    private fun intersections(a: SketchEntity, b: SketchEntity): List<Pair<Double, Double>> {
        val raw = when {
            a is SketchLine && b is SketchLine -> lineLine(a, b)
            a is SketchLine -> lineCircle(a, circleOf(b))
            b is SketchLine -> lineCircle(b, circleOf(a))
            else -> circleCircle(circleOf(a), circleOf(b))
        }
        return raw.filter { (px, py) -> within(a, px, py) && within(b, px, py) }
    }

    private fun circleOf(e: SketchEntity): DoubleArray = when (e) {
        is SketchCircle -> doubleArrayOf(e.cx, e.cy, e.radius)
        is SketchArc -> doubleArrayOf(e.cx, e.cy, e.radius)
        is SketchLine -> error("not a circle")
    }

    private fun within(e: SketchEntity, px: Double, py: Double): Boolean = when (e) {
        is SketchLine -> distance(e, px, py) < 1e-6
        is SketchCircle -> true
        is SketchArc -> onArc(e, atan2(py - e.cy, px - e.cx))
    }

    private fun lineLine(a: SketchLine, b: SketchLine): List<Pair<Double, Double>> {
        val rx = a.x2 - a.x1; val ry = a.y2 - a.y1
        val sx = b.x2 - b.x1; val sy = b.y2 - b.y1
        val den = rx * sy - ry * sx
        if (abs(den) < EPS) return emptyList()
        val t = ((b.x1 - a.x1) * sy - (b.y1 - a.y1) * sx) / den
        return listOf(a.x1 + t * rx to a.y1 + t * ry)
    }

    private fun lineCircle(l: SketchLine, c: DoubleArray): List<Pair<Double, Double>> {
        val dx = l.x2 - l.x1; val dy = l.y2 - l.y1
        val fx = l.x1 - c[0]; val fy = l.y1 - c[1]
        val qa = dx * dx + dy * dy
        if (qa < EPS) return emptyList()
        val qb = 2 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - c[2] * c[2]
        val disc = qb * qb - 4 * qa * qc
        if (disc < 0) return emptyList()
        val root = sqrt(disc)
        return listOf((-qb - root) / (2 * qa), (-qb + root) / (2 * qa)).distinct()
            .map { t -> l.x1 + t * dx to l.y1 + t * dy }
    }

    private fun circleCircle(a: DoubleArray, b: DoubleArray): List<Pair<Double, Double>> {
        val d = hypot(b[0] - a[0], b[1] - a[1])
        if (d < EPS || d > a[2] + b[2] || d < abs(a[2] - b[2])) return emptyList()
        val along = (a[2] * a[2] - b[2] * b[2] + d * d) / (2 * d)
        val h = sqrt((a[2] * a[2] - along * along).coerceAtLeast(0.0))
        val mx = a[0] + along * (b[0] - a[0]) / d
        val my = a[1] + along * (b[1] - a[1]) / d
        val ox = h * (b[1] - a[1]) / d
        val oy = h * (b[0] - a[0]) / d
        return listOf(mx + ox to my - oy, mx - ox to my + oy).distinct()
    }

    private fun trimLine(l: SketchLine, hits: List<Pair<Double, Double>>, x: Double, y: Double): TrimResult {
        val dx = l.x2 - l.x1; val dy = l.y2 - l.y1
        val len2 = dx * dx + dy * dy
        fun param(px: Double, py: Double) = ((px - l.x1) * dx + (py - l.y1) * dy) / len2
        val tap = param(x, y).coerceIn(0.0, 1.0)
        val ts = hits.map { param(it.first, it.second) }.filter { it > EPS && it < 1 - EPS }
        val lo = ts.filter { it < tap }.maxOrNull() ?: 0.0
        val hi = ts.filter { it > tap }.minOrNull() ?: 1.0
        fun at(t: Double) = doubleArrayOf(l.x1 + t * dx, l.y1 + t * dy)
        val lines = buildList {
            if (lo > EPS) add(doubleArrayOf(l.x1, l.y1) + at(lo))
            if (hi < 1 - EPS) add(at(hi) + doubleArrayOf(l.x2, l.y2))
        }
        return TrimResult(l.id, lines, emptyList())
    }

    private fun trimCircle(c: SketchCircle, hits: List<Pair<Double, Double>>, x: Double, y: Double): TrimResult {
        val angles = hits.map { norm(atan2(it.second - c.cy, it.first - c.cx)) }.sorted()
            .fold(mutableListOf<Double>()) { acc, a -> if (acc.isEmpty() || a - acc.last() > EPS) acc += a; acc }
        if (angles.size < 2) return TrimResult(c.id, emptyList(), emptyList())
        val tap = norm(atan2(y - c.cy, x - c.cx))
        // The removed piece runs counter-clockwise from the last cut before the tap to the first after.
        val lo = angles.lastOrNull { it <= tap } ?: angles.last()
        val hi = angles.firstOrNull { it > tap } ?: angles.first()
        val end = if (lo > hi) lo else lo + TWO_PI
        return TrimResult(c.id, emptyList(), listOf(doubleArrayOf(c.cx, c.cy, c.radius, hi, end)))
    }

    private fun trimArc(a: SketchArc, hits: List<Pair<Double, Double>>, x: Double, y: Double): TrimResult {
        val total = span(a)
        fun param(px: Double, py: Double) = norm(atan2(py - a.cy, px - a.cx) - a.startAngle)
        val tap = param(x, y).let { if (it > total) (if (it - total < TWO_PI - it) total else 0.0) else it }
        val us = hits.map { param(it.first, it.second) }.filter { it > EPS && it < total - EPS }
        val lo = us.filter { it < tap }.maxOrNull() ?: 0.0
        val hi = us.filter { it > tap }.minOrNull() ?: total
        val arcs = buildList {
            if (lo > EPS) add(doubleArrayOf(a.cx, a.cy, a.radius, a.startAngle, a.startAngle + lo))
            if (hi < total - EPS) add(doubleArrayOf(a.cx, a.cy, a.radius, a.startAngle + hi, a.startAngle + total))
        }
        return TrimResult(a.id, emptyList(), arcs)
    }

}
