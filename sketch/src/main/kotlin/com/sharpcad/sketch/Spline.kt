package com.sharpcad.sketch

import kotlin.math.hypot

/** Smooth curves through points (uniform Catmull-Rom), sampled into line segments. */
object Spline {
    /** Segments per span between two control points. */
    const val SAMPLES = 16

    /**
     * Points (u0, v0, u1, v1…) of a curve passing through every control point of
     * [points] (flat u, v pairs), [SAMPLES] steps per span.
     */
    fun sample(points: List<Double>): List<Double> {
        val n = points.size / 2
        if (n < 2) return points
        fun px(i: Int) = points[2 * i.coerceIn(0, n - 1)]
        fun py(i: Int) = points[2 * i.coerceIn(0, n - 1) + 1]
        val out = mutableListOf(px(0), py(0))
        for (i in 0 until n - 1) {
            for (k in 1..SAMPLES) {
                val t = k.toDouble() / SAMPLES
                val t2 = t * t
                val t3 = t2 * t
                fun cr(p0: Double, p1: Double, p2: Double, p3: Double) =
                    0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                out += cr(px(i - 1), px(i), px(i + 1), px(i + 2))
                out += cr(py(i - 1), py(i), py(i + 1), py(i + 2))
            }
        }
        return out
    }

    /** Line segments (x1, y1, x2, y2…) of the sampled curve. */
    fun segments(points: List<Double>): List<Double> {
        val p = sample(points)
        val out = mutableListOf<Double>()
        for (i in 0 until p.size / 2 - 1) out += listOf(p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3])
        return out
    }

    /** Keeps the first point and every point at least [spacing] from the last kept one, plus the last. */
    fun thin(points: List<Double>, spacing: Double): List<Double> {
        if (points.size < 4) return points
        val out = mutableListOf(points[0], points[1])
        for (i in 1 until points.size / 2 - 1) {
            if (hypot(points[2 * i] - out[out.size - 2], points[2 * i + 1] - out[out.size - 1]) >= spacing) {
                out += points[2 * i]; out += points[2 * i + 1]
            }
        }
        out += points[points.size - 2]; out += points[points.size - 1]
        return out
    }
}
