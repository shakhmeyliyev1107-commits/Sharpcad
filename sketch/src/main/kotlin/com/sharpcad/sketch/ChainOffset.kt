package com.sharpcad.sketch

import kotlin.math.abs
import kotlin.math.hypot

/** Offsets a chain of connected lines as one outline, with mitred corners. Pure geometry. */
object ChainOffset {
    private const val TOL = 1e-6

    /** A chain as ordered vertices (u, v pairs) and the ids of the lines in it. */
    data class Chain(val points: List<Double>, val closed: Boolean, val lines: List<Int>)

    /** The chain of lines connected end to end through [start], ordered. */
    fun chain(entities: List<SketchEntity>, start: SketchLine): Chain {
        val lines = entities.filterIsInstance<SketchLine>()
        fun same(ax: Double, ay: Double, bx: Double, by: Double) = abs(ax - bx) < TOL && abs(ay - by) < TOL
        fun degree(x: Double, y: Double) = lines.count { same(it.x1, it.y1, x, y) || same(it.x2, it.y2, x, y) }
        val used = mutableSetOf(start.id)
        val ids = mutableListOf(start.id)
        val pts = mutableListOf(start.x1, start.y1, start.x2, start.y2)
        // Walk forward from the end, then backward from the start; stop at branches.
        fun walk(forward: Boolean) {
            while (true) {
                val x = if (forward) pts[pts.size - 2] else pts[0]
                val y = if (forward) pts[pts.size - 1] else pts[1]
                if (degree(x, y) != 2) return
                val next = lines.firstOrNull { it.id !in used && (same(it.x1, it.y1, x, y) || same(it.x2, it.y2, x, y)) } ?: return
                used += next.id
                val (fx, fy) = if (same(next.x1, next.y1, x, y)) next.x2 to next.y2 else next.x1 to next.y1
                if (forward) { pts += fx; pts += fy; ids += next.id } else { pts.add(0, fy); pts.add(0, fx); ids.add(0, next.id) }
            }
        }
        walk(true)
        val closed = pts.size > 4 && same(pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
        if (!closed) walk(false)
        return Chain(pts, closed, ids)
    }

    /** Signed distance of ([x], [y]) from the chain segment nearest to it (left side positive). */
    fun side(c: Chain, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        var signed = 0.0
        for (i in 0 until c.points.size / 2 - 1) {
            val ax = c.points[2 * i]; val ay = c.points[2 * i + 1]
            val bx = c.points[2 * i + 2]; val by = c.points[2 * i + 3]
            val len = hypot(bx - ax, by - ay)
            if (len < TOL) continue
            val t = (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (len * len)).coerceIn(0.0, 1.0)
            val d = hypot(x - (ax + t * (bx - ax)), y - (ay + t * (by - ay)))
            if (d < best) {
                best = d
                signed = ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / len
            }
        }
        return signed
    }

    /** Vertices of the chain offset by [d] to the left of its direction. */
    fun offset(c: Chain, d: Double): List<Double> {
        val n = c.points.size / 2
        val segs = (0 until n - 1).map { i ->
            val ax = c.points[2 * i]; val ay = c.points[2 * i + 1]
            val bx = c.points[2 * i + 2]; val by = c.points[2 * i + 3]
            val len = hypot(bx - ax, by - ay).coerceAtLeast(TOL)
            val nx = -(by - ay) / len * d
            val ny = (bx - ax) / len * d
            doubleArrayOf(ax + nx, ay + ny, bx + nx, by + ny)
        }
        fun join(p: DoubleArray, q: DoubleArray): Pair<Double, Double> {
            val rx = p[2] - p[0]; val ry = p[3] - p[1]
            val sx = q[2] - q[0]; val sy = q[3] - q[1]
            val den = rx * sy - ry * sx
            if (abs(den) < TOL) return p[2] to p[3]
            val t = ((q[0] - p[0]) * sy - (q[1] - p[1]) * sx) / den
            return p[0] + t * rx to p[1] + t * ry
        }
        val out = mutableListOf<Double>()
        if (c.closed) {
            val first = join(segs.last(), segs.first())
            out += first.first; out += first.second
        } else {
            out += segs.first()[0]; out += segs.first()[1]
        }
        for (i in 0 until segs.size - 1) {
            val p = join(segs[i], segs[i + 1])
            out += p.first; out += p.second
        }
        if (c.closed) { out += out[0]; out += out[1] } else { out += segs.last()[2]; out += segs.last()[3] }
        return out
    }
}
